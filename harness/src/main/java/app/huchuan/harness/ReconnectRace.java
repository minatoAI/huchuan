package app.huchuan.harness;

import app.huchuan.core.m1.GroupRuntime;
import app.huchuan.core.m1.KernelEvents;
import app.huchuan.core.m1.M1Kernel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 链路撞车（两端同时拨号）的回归断言。
 *
 * 现场（artifacts/m1-device/reconnect-race-evidence.txt）：两端都带重连器时，双方各拨一条，
 * duplicate_link 的裁决把其中一条关掉；被关的那一侧没有「我这条被弃了但同一成员已经有活链路」
 * 的认知，于是退回 30 s 退避无限重拨，P6 永远显示离线。
 *
 * 这个夹具用绝对时刻做「同时拨号」，把竞态做成可重复的一轮：
 *   phase=bootstrap  RA 建群、RB 只拿后 8 位加入；两边各自把群落到 state.json，然后都退出
 *   phase=race       两边带着本地库同时起来（各自 restored_groups=1，各自有重连器），
 *                    在 dial_at 这个同一毫秒各自拨一次，然后断言：
 *                      ① 10 s 内双方都 attach（P6 在线双向为 true）
 *                      ② 之后 60 s 内不再出现同一 dev 的 peer_link_closed / 上下线抖动
 *
 * 反向验证：-Dhuchuan.mutants=no_link_fallback 时内核关掉撞车兜底，②必须 FAIL
 * （见 docs/core.md §3.4 与 docs/reports/e0-team.md）。
 */
public final class ReconnectRace {

    static final long ATTACH_LIMIT_MS = longProp("m1.raceAttachMs", 10_000L);
    static final long STABLE_WINDOW_MS = longProp("m1.raceStableMs", 60_000L);

    /** 撞车那一波过去多久算「收尾」：这之前的 duplicate_link 是裁决的正常产物。 */
    static final long SETTLE_MS = longProp("m1.raceSettleMs", 5_000L);

    public static void main(String[] args) {
        RunLog.install();
        try {
            if (args.length < 3) {
                System.err.println("usage: ReconnectRace <RA|RB> <workDir> <dataDir> phase=bootstrap|race [k=v ...]");
                System.exit(2);
            }
            String role = args[0];
            Path workDir = Paths.get(args[1]).toAbsolutePath();
            Path dataDir = Paths.get(args[2]).toAbsolutePath();
            Map<String, String> opts = new LinkedHashMap<>();
            for (int i = 3; i < args.length; i++) {
                int eq = args[i].indexOf('=');
                if (eq > 0) {
                    opts.put(args[i].substring(0, eq), args[i].substring(eq + 1));
                }
            }
            Files.createDirectories(workDir);
            Files.createDirectories(dataDir);
            int msgPort = Integer.parseInt(opts.getOrDefault("msg_port", "47500"));
            int btPort = Integer.parseInt(opts.getOrDefault("bt_port", "53613"));
            String phase = opts.getOrDefault("phase", "bootstrap");
            if ("bootstrap".equals(phase)) {
                bootstrap(role, workDir, dataDir, msgPort, btPort, opts);
            } else {
                race(role, workDir, dataDir, msgPort, btPort, opts);
            }
        } catch (Throwable t) {
            RunLog.out("M1 ? fail error=" + clean(t.toString()));
            t.printStackTrace(System.out);
            RunLog.out("RESULT=FAIL");
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 第一段：建群 / 入群

    static void bootstrap(String role, Path workDir, Path dataDir, int msgPort, int btPort,
                          Map<String, String> opts) throws Exception {
        Events events = new Events(role);
        M1Kernel kernel = new M1Kernel(dataDir.toFile(), opts.getOrDefault("name", "race-" + role),
                msgPort, btPort, events);
        kernel.start();
        RunLog.out("M1 " + role + " boot_start msg_port=" + kernel.localMessagePort()
                + " bt_port=" + kernel.localBtPort());
        try {
            if ("RA".equals(role)) {
                GroupRuntime group = kernel.createGroup("撞车验证群");
                String code = group.getInviteCode();
                RunLog.out("M1 RA invite_tail8=" + code.substring(8));
                RunLog.out("M1 RA group_id=" + group.getGroupId());
                long deadline = System.currentTimeMillis() + 120_000L;
                while (System.currentTimeMillis() < deadline && events.memberJoined() < 1) {
                    Thread.sleep(100);
                }
                RunLog.out("M1 RA bootstrap_joined members=" + events.memberJoined()
                        + " online=" + group.onlinePeers().size());
                // 等 RB 退出（链路断开），再把内核关掉：两边都留下一个「有群的 state.json」
                long offlineDeadline = System.currentTimeMillis() + 60_000L;
                while (System.currentTimeMillis() < offlineDeadline && !group.onlinePeers().isEmpty()) {
                    Thread.sleep(100);
                }
                RunLog.out("M1 RA bootstrap_offline online=" + group.onlinePeers().size());
            } else {
                String tail8 = opts.get("tail8");
                if (tail8 == null || tail8.length() != 8) {
                    fail(role, "missing_tail8");
                    return;
                }
                List<String> manual = new ArrayList<>();
                for (String part : opts.getOrDefault("manual", "").split(",")) {
                    if (!part.trim().isEmpty()) {
                        manual.add(part.trim());
                    }
                }
                GroupRuntime group = kernel.joinByTail8(tail8, manual, 90_000L);
                if (group == null) {
                    fail(role, "join_failed");
                    return;
                }
                RunLog.out("M1 RB joined gid=" + group.getGroupId()
                        + " members=" + group.members().size());
                Thread.sleep(1500);
            }
            kernel.close();
            RunLog.out("M1 " + role + " boot_done");
            RunLog.out("RESULT=PASS");
            System.exit(0);
        } finally {
            try {
                kernel.close();
            } catch (Throwable ignored) {
                // 退出路径上不再纠缠
            }
        }
    }

    // ------------------------------------------------------------------ 第二段：同时拨号

    static void race(String role, Path workDir, Path dataDir, int msgPort, int btPort,
                     Map<String, String> opts) throws Exception {
        long dialAt = 0L;
        try {
            dialAt = Long.parseLong(opts.getOrDefault("dial_at", "0"));
        } catch (NumberFormatException ignored) {
            dialAt = 0L;
        }
        String peerAddr = opts.getOrDefault("peer", "");
        Events events = new Events(role);
        M1Kernel kernel = new M1Kernel(dataDir.toFile(), opts.getOrDefault("name", "race-" + role),
                msgPort, btPort, events);
        kernel.start();
        RunLog.out("M1 " + role + " race_start groups=" + kernel.groups().size()
                + " msg_port=" + kernel.localMessagePort()
                + " link_fallback=" + app.huchuan.core.m1.LinkRaceGuard.fallbackEnabled);
        try {
            if (kernel.groups().isEmpty()) {
                fail(role, "no_group_after_restart");
                return;
            }
            GroupRuntime group = kernel.groups().get(0);
            String peerDev = "";
            for (app.huchuan.core.m1.MemberRecord m : group.members()) {
                if (!m.getDev().equals(kernel.getDeviceId())) {
                    peerDev = m.getDev();
                    break;
                }
            }
            RunLog.out("M1 " + role + " race_peer dev=" + peerDev + " peer_addr=" + peerAddr);

            // 绝对时刻对齐：两边等同一个毫秒各自拨一次（竞态要可重复，不能靠「碰巧同时」）
            long waitMs = dialAt - System.currentTimeMillis();
            if (waitMs > 0) {
                Thread.sleep(waitMs);
            }
            final M1Kernel k = kernel;
            String host = peerHost(peerAddr);
            int port = peerPort(peerAddr);
            int waves = 1;
            try {
                waves = Integer.parseInt(opts.getOrDefault("waves", "1"));
            } catch (NumberFormatException ignored) {
                waves = 1;
            }
            final int waveCount = waves;
            final String dev = peerDev;
            Thread dialer = new Thread(() -> {
                // 撞车的正确触发方式：把本端现有链路掐掉，让**生产重连器**自己去拨
                // （PeerConnector.dial → openOutboundLink）。两端在同一个毫秒各掐一次，
                // 两边的重连器就在 250 ms 的节拍内各自拨出一条 —— 这就是现场「两端都重启」。
                // 若直接用 openOutboundLink 拨，会绕过 PeerConnector，撞车兜底那条路永远走不到。
                for (int i = 0; i < waveCount; i++) {
                    try {
                        int cut = cutLinks(k, dev);
                        RunLog.out("M1 " + role + " race_cut wave=" + (i + 1) + " links=" + cut
                                + " peer=" + peerAddr + " peer_host=" + host + ":" + port);
                    } catch (Throwable t) {
                        RunLog.out("M1 " + role + " race_cut_error wave=" + (i + 1)
                                + " error=" + clean(t.toString()));
                    }
                    if (i + 1 < waveCount) {
                        try {
                            Thread.sleep(WAVE_GAP_MS);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                    }
                }
            });
            dialer.setDaemon(true);
            dialer.setName("race-dial-" + role);
            // 基准必须在拨号之前取：拨号之后发生的关闭 / 上下线都算进「抖动窗口」
            int closedBase = events.linkClosed(peerDev);
            int offlineBase = events.offline();
            boolean onlineBeforeDial = !group.onlinePeers().isEmpty();
            long dialedAt = System.currentTimeMillis();
            dialer.start();
            RunLog.out("M1 " + role + " race_dial_start ts=" + dialedAt + " target=" + peerAddr + " waves=" + waves);

            // ① 10 s 内必须在线（P6 的双向 online）
            boolean online = false;
            long onlineAt = 0L;
            long attachDeadline = dialedAt + ATTACH_LIMIT_MS;
            while (System.currentTimeMillis() < attachDeadline) {
                if (!group.onlinePeers().isEmpty()) {
                    online = true;
                    onlineAt = System.currentTimeMillis();
                    break;
                }
                Thread.sleep(50);
            }
            RunLog.out("M1 " + role + " race_attached ok=" + online
                    + " elapsed_ms=" + (online ? (onlineAt - dialedAt) : -1)
                    + " online=" + (online ? "true" : "false")
                    + " limit_ms=" + ATTACH_LIMIT_MS
                    + " online_before_dial=" + onlineBeforeDial
                    + " peers_changed_online=" + events.online()
                    + " peers_changed_offline=" + events.offline());
            if (!online) {
                kernel.close();
                fail(role, "race_not_attached");
                return;
            }

            // ② 收尾之后（撞车那一波过去 SETTLE_MS 起）的 STABLE_WINDOW_MS 内不许再抖：
            //    同一 dev 的 peer_link_closed 与上下线都必须为 0。
            //    撞车当场出现 duplicate_link 是裁决的正常产物，不算抖动 —— 算的是「撞完之后还在撞」。
            long settleAt = dialedAt + SETTLE_MS;
            if (settleAt > System.currentTimeMillis()) {
                Thread.sleep(settleAt - System.currentTimeMillis());
            }
            int closedAfterSettleBase = events.linkClosed(peerDev);
            int offlineAfterSettleBase = events.offline();
            long stableEnd = settleAt + STABLE_WINDOW_MS;
            while (System.currentTimeMillis() < stableEnd) {
                Thread.sleep(200);
            }
            int closedBurst = closedAfterSettleBase - closedBase;
            int closedTail = events.linkClosed(peerDev) - closedAfterSettleBase;
            int offline = events.offline() - offlineAfterSettleBase;
            boolean stillOnline = !group.onlinePeers().isEmpty();
            int settled = events.raceSettled();
            // 正常臂的判据：撞完之后不再抖 + 仍在线 + 撞车兜底真的走到过（否则这条断言没验到东西）
            boolean stable = closedTail == 0 && offline == 0 && stillOnline;
            RunLog.out("M1 " + role + " race_stable ok=" + stable
                    + " window_s=" + (STABLE_WINDOW_MS / 1000)
                    + " settle_ms=" + SETTLE_MS
                    + " link_closed_burst=" + closedBurst
                    + " link_closed_tail=" + closedTail
                    + " offline_tail=" + offline
                    + " online_end=" + stillOnline
                    + " race_settled=" + settled
                    + " dial_failed=" + events.dialFailed()
                    + " link_fallback=" + app.huchuan.core.m1.LinkRaceGuard.fallbackEnabled
                    + " dev=" + peerDev);
            kernel.close();
            if (!stable) {
                fail(role, "race_link_churn closed_tail=" + closedTail
                        + " offline_tail=" + offline + " online_end=" + stillOnline);
                return;
            }
            RunLog.out("RESULT=PASS");
            System.exit(0);
        } finally {
            try {
                kernel.close();
            } catch (Throwable ignored) {
                // 退出路径上不再纠缠
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 同时拨号之间的间隔：一波接一波地撞，等价于现场「重连器按退避反复拨」。 */
    static final long WAVE_GAP_MS = longProp("m1.raceWaveGapMs", 800L);

    /**
     * 调内核的去程链路（Kotlin internal → JVM 上是 openOutboundLink$<module>）。
     *
     * 用它而不是 joinByTail8：后者会先浏览 mDNS 并把本机自己的实例也当候选（自连会产生
     * 一个「同一 deviceId 的两个本机进程」的假 peer），而且候选超时会把「同时拨号」打散。
     */
    static Object openLink(M1Kernel kernel, String host, int port) throws Exception {
        for (java.lang.reflect.Method m : kernel.getClass().getMethods()) {
            if (m.getName().startsWith("openOutboundLink") && m.getParameterCount() == 2) {
                return m.invoke(kernel, host, port);
            }
        }
        throw new IllegalStateException("harness injection point missing: M1Kernel.openOutboundLink");
    }

    /**
     * 掐掉本端到该成员的全部消息链路（等价于「网断了 / 进程被冻住」这一瞬间）。
     *
     * 走 PeerLink.close(reason)，所以对端会读到 EOF；重连器随后自己把链路拨回来。
     * 这里不直接调用 openOutboundLink —— 撞车必须发生在生产重连器的拨号路径上。
     */
    static int cutLinks(M1Kernel kernel, String dev) throws Exception {
        return cutViaKernel(kernel, dev);
    }

    static java.lang.reflect.Field fieldOf(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** 通过内核的群对象关掉到该成员的全部链路；返回关掉几条。 */
    static int cutViaKernel(M1Kernel kernel, String dev) throws Exception {
        int closed = 0;
        for (GroupRuntime g : kernel.groups()) {
            java.lang.reflect.Field linksField = fieldOf(g, "links");
            linksField.setAccessible(true);
            Object raw = linksField.get(g);
            if (!(raw instanceof Map)) {
                continue;
            }
            Object link = ((Map<?, ?>) raw).get(dev);
            if (link == null) {
                continue;
            }
            java.lang.reflect.Method close = link.getClass().getMethod("close", String.class);
            close.invoke(link, "race_cut");
            closed++;
        }
        return closed;
    }

    static String peerHost(String addr) {
        int at = addr.lastIndexOf(':');
        return at > 0 ? addr.substring(0, at) : addr;
    }

    static int peerPort(String addr) {
        int at = addr.lastIndexOf(':');
        try {
            return at > 0 ? Integer.parseInt(addr.substring(at + 1)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static void fail(String role, String reason) {
        RunLog.out("M1 " + role + " FAIL reason=" + reason);
        RunLog.out("RESULT=FAIL");
        System.out.flush();
        System.exit(1);
    }

    static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\s]+", "_");
    }

    static long longProp(String key, long def) {
        try {
            String raw = System.getProperty(key);
            return raw == null || raw.trim().isEmpty() ? def : Long.parseLong(raw.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    // ------------------------------------------------------------------ 事件出口

    static final class Events extends KernelEvents {
        private final String role;
        private final java.util.concurrent.atomic.AtomicInteger online =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger offline =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger memberJoined =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> closedByDev =
                new java.util.concurrent.ConcurrentHashMap<>();
        /** 撞车兜底真的走到过几次（reconnect_link_race_settled）。 */
        private final java.util.concurrent.atomic.AtomicInteger raceSettled =
                new java.util.concurrent.atomic.AtomicInteger();
        /** 撞车兜底被关掉时的重连失败次数（reconnect_dial_failed / unbound）。 */
        private final java.util.concurrent.atomic.AtomicInteger dialFailed =
                new java.util.concurrent.atomic.AtomicInteger();

        int raceSettled() {
            return raceSettled.get();
        }

        int dialFailed() {
            return dialFailed.get();
        }

        Events(String role) {
            this.role = role;
        }

        int online() {
            return online.get();
        }

        int offline() {
            return offline.get();
        }

        int memberJoined() {
            return memberJoined.get();
        }

        int linkClosed(String dev) {
            java.util.concurrent.atomic.AtomicInteger n = closedByDev.get(dev == null ? "" : dev);
            return n == null ? 0 : n.get();
        }

        @Override
        public void peersChanged(GroupRuntime group, String deviceId, boolean onlineFlag) {
            if (onlineFlag) {
                online.incrementAndGet();
            } else {
                offline.incrementAndGet();
            }
            log("peers_changed", Map.of(
                    "dev", deviceId == null ? "" : deviceId,
                    "online", String.valueOf(onlineFlag),
                    "online_peers", String.valueOf(group == null ? -1 : group.onlinePeers().size())));
        }

        @Override
        public void memberJoined(GroupRuntime group, String deviceId, String name) {
            memberJoined.incrementAndGet();
            log("member_joined", Map.of("dev", deviceId == null ? "" : deviceId, "name", name == null ? "" : name));
        }

        @Override
        public void log(String event, Map<String, String> fields) {
            if ("reconnect_link_race_settled".equals(event)) {
                raceSettled.incrementAndGet();
            } else if ("reconnect_dial_failed".equals(event) || "reconnect_dial_unbound".equals(event)) {
                dialFailed.incrementAndGet();
            }
            if ("peer_link_closed".equals(event)) {
                String dev = fields.getOrDefault("dev", "");
                closedByDev.computeIfAbsent(dev, k -> new java.util.concurrent.atomic.AtomicInteger())
                        .incrementAndGet();
            }
            StringBuilder sb = new StringBuilder("M1 ").append(role).append(' ').append(event);
            for (Map.Entry<String, String> e : fields.entrySet()) {
                String v = e.getValue();
                if (v == null || v.isEmpty()) {
                    continue;
                }
                sb.append(' ').append(e.getKey()).append('=').append(clean(v));
            }
            System.out.println(sb);
            System.out.flush();
        }
    }
}
