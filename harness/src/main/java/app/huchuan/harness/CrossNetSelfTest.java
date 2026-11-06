package app.huchuan.harness;

import app.huchuan.core.m1.BtSession;
import app.huchuan.core.m1.Candidate;
import app.huchuan.core.m1.DeviceIdentity;
import app.huchuan.core.m1.Domains;
import app.huchuan.core.m1.GroupRuntime;
import app.huchuan.core.m1.IpAllowlist;
import app.huchuan.core.m1.Json;
import app.huchuan.core.m1.KernelEvents;
import app.huchuan.core.m1.M1Kernel;
import app.huchuan.core.m1.PeerAnnounceMsg;
import app.huchuan.core.m1.PublicMagnetResult;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * 跨网 BT 可达性（AGENTS §9）的自检：ip_filter 动态白名单 + peer.announce.pubBtPort。
 *
 * 四组断言，每一组都有一条现实缺陷能让它当场变红：
 *
 *   1. 纯函数口径（[policy]）：私网 / 公网 / 主机名的分类、方括号与 zone 归一、去重。
 *      打红方式：把 isPublicLiteral 改成「非 10/8 都算公网」或者收下主机名。
 *   2. 私有会话的 filter 真值（[sessionFilter]）：真起一个 BtSession，读
 *      `session::get_ip_filter().access()` —— 断言的是 libtorrent 真正看到的东西。
 *      打红方式：只放行私网（原缺陷，公网候选永远 false）、或者一次 set 之后不清旧地址。
 *   3. 群知识 → filter（[groupAllowlist]）：真内核 + 一条签名合法的 peer.announce（公网 btHost）
 *      + tracker 候选 + 手动 IP:port，四类来源分别断言进了白名单；换端点后旧地址撤销；
 *      退群（M1 唯一的成员集合缩小事件）后全部撤销，私网段仍然放行。
 *      打红方式：GroupRuntime 不把 btEndpoints 交给 BtSession、或者移除时只删账本不重建 filter。
 *   4. pubBtPort（[announceFields] / [twoKernels]）：JSON 往返 / 缺省 / 非法值；两个真内核建链后
 *      **广播**里带的就是公开会话端口；公开条目对成员拨号走公开会话（sess=pub，端口是 pubBtPort），
 *      不再被「公开条目整轮跳过」的短路吃掉。
 *      打红方式：去掉 pubBtPort 字段解析、或者把 connectSeeders 里公开条目的早退加回来。
 */
final class CrossNetSelfTest {

    private CrossNetSelfTest() {
    }

    private static final int[] COUNTS = new int[2];

    static void run(BiConsumer<String, Boolean> rawCheck) {
        COUNTS[0] = 0;
        COUNTS[1] = 0;
        BiConsumer<String, Boolean> check = (name, ok) -> {
            COUNTS[0]++;
            if (!ok) {
                COUNTS[1]++;
            }
            rawCheck.accept(name, ok);
        };
        policy(check);
        announceFields(check);
        sessionFilter(check);
        groupAllowlist(check);
        twoKernels(check);
        System.out.println("selftest.crossnet checks=" + COUNTS[0] + " failures=" + COUNTS[1]);
    }

    // ---------------------------------------------------------------- 1. 纯函数口径

    private static void policy(BiConsumer<String, Boolean> check) {
        check.accept("crossnet.public_literal_v4", IpAllowlist.INSTANCE.isPublicLiteral("203.0.113.9"));
        check.accept("crossnet.public_literal_v6", IpAllowlist.INSTANCE.isPublicLiteral("2001:db8::1"));
        check.accept("crossnet.private_v4_rejected",
                !IpAllowlist.INSTANCE.isPublicLiteral("10.1.2.3")
                        && !IpAllowlist.INSTANCE.isPublicLiteral("172.16.0.1")
                        && !IpAllowlist.INSTANCE.isPublicLiteral("192.168.1.1")
                        && !IpAllowlist.INSTANCE.isPublicLiteral("127.0.0.1")
                        && !IpAllowlist.INSTANCE.isPublicLiteral("169.254.1.1"));
        check.accept("crossnet.private_v6_rejected",
                !IpAllowlist.INSTANCE.isPublicLiteral("::1")
                        && !IpAllowlist.INSTANCE.isPublicLiteral("fd00::1")
                        && !IpAllowlist.INSTANCE.isPublicLiteral("fe80::1"));
        check.accept("crossnet.cgnat_and_reserved_rejected",
                !IpAllowlist.INSTANCE.isPublicLiteral("100.64.0.1")
                        && !IpAllowlist.INSTANCE.isPublicLiteral("224.0.0.1")
                        && !IpAllowlist.INSTANCE.isPublicLiteral("0.0.0.0"));
        check.accept("crossnet.hostname_rejected",
                !IpAllowlist.INSTANCE.isPublicLiteral("nas.local")
                        && !IpAllowlist.INSTANCE.isPublicLiteral("example.com"));
        check.accept("crossnet.empty_rejected", !IpAllowlist.INSTANCE.isPublicLiteral(""));

        // 手动 IP:port 的方括号 / zone 写法归一成裸字面量（IPv6 的规范写法由 JDK 给，
        // 这里只断言「带方括号与不带的归一结果一致、且都判成公网」）。
        String v6 = IpAllowlist.INSTANCE.normalizeLiteral("[2001:db8::1]");
        check.accept("crossnet.normalize_bracket (got " + v6 + ")",
                v6 != null
                        && v6.equals(IpAllowlist.INSTANCE.normalizeLiteral("2001:db8::1"))
                        && IpAllowlist.INSTANCE.isPublicLiteral(v6));
        check.accept("crossnet.normalize_zone",
                IpAllowlist.INSTANCE.isPrivateLiteral("[fe80::1%wlan0]"));
        check.accept("crossnet.normalize_endpoint_is_not_host",
                IpAllowlist.INSTANCE.normalizeLiteral("[2001:db8::1]:47100") == null);

        Set<String> collected = IpAllowlist.INSTANCE.collect(java.util.Arrays.asList(
                "203.0.113.9", "203.0.113.9", "10.0.0.1", "nas.local", null, "198.51.100.7"));
        check.accept("crossnet.collect_filters_and_dedupes (got " + collected + ")",
                collected.size() == 2
                        && collected.contains("203.0.113.9")
                        && collected.contains("198.51.100.7"));
    }

    // ---------------------------------------------------------------- 2. pubBtPort 字段

    private static void announceFields(BiConsumer<String, Boolean> check) {
        PeerAnnounceMsg withPort = PeerAnnounceMsg.Companion.fromJson(Json.INSTANCE.parse(
                "{\"gid\":\"7Q2F9C4M1XZ8KD3V\",\"dev\":\"7Q2F9C4M1XZ8KD3V\",\"btPort\":51413,"
                        + "\"pubBtPort\":6882,\"ts\":1762381200500,\"sig\":\"1c2d\"}"));
        check.accept("crossnet.announce_pub_bt_port_roundtrip", withPort.getPubBtPort() == 6882);
        check.accept("crossnet.announce_endpoint_unchanged",
                withPort.getBtPort() == 51413 && withPort.getGid().equals("7Q2F9C4M1XZ8KD3V"));

        PeerAnnounceMsg bare = PeerAnnounceMsg.Companion.fromJson(Json.INSTANCE.parse(
                "{\"gid\":\"7Q2F9C4M1XZ8KD3V\",\"dev\":\"7Q2F9C4M1XZ8KD3V\",\"btPort\":51413,"
                        + "\"ts\":1762381200500,\"sig\":\"1c2d\"}"));
        check.accept("crossnet.announce_missing_pub_bt_port_is_zero", bare.getPubBtPort() == 0);

        check.accept("crossnet.announce_negative_pub_bt_port_is_zero",
                PeerAnnounceMsg.Companion.fromJson(Json.INSTANCE.parse(
                        "{\"gid\":\"7Q2F9C4M1XZ8KD3V\",\"dev\":\"7Q2F9C4M1XZ8KD3V\",\"btPort\":51413,"
                                + "\"pubBtPort\":-5,\"ts\":1762381200500,\"sig\":\"1c2d\"}")).getPubBtPort() == 0);
        check.accept("crossnet.announce_overflow_pub_bt_port_is_zero",
                PeerAnnounceMsg.Companion.fromJson(Json.INSTANCE.parse(
                        "{\"gid\":\"7Q2F9C4M1XZ8KD3V\",\"dev\":\"7Q2F9C4M1XZ8KD3V\",\"btPort\":51413,"
                                + "\"pubBtPort\":70000,\"ts\":1762381200500,\"sig\":\"1c2d\"}")).getPubBtPort() == 0);

        // 签名域固定 [dev, gid, btPort, ts]：pubBtPort 改了，signBytes 必须一模一样。
        PeerAnnounceMsg a = new PeerAnnounceMsg("7Q2F9C4M1XZ8KD3V", "7Q2F9C4M1XZ8KD3V", 51413, "",
                1762381200500L, "", "windows", 2, 6882);
        PeerAnnounceMsg b = new PeerAnnounceMsg("7Q2F9C4M1XZ8KD3V", "7Q2F9C4M1XZ8KD3V", 51413, "",
                1762381200500L, "", "windows", 2, 6999);
        boolean sameFields = a.signFields().size() == 4;
        for (int i = 0; sameFields && i < a.signFields().size(); i++) {
            sameFields = java.util.Arrays.equals(a.signFields().get(i), b.signFields().get(i));
        }
        check.accept("crossnet.announce_pub_bt_port_outside_signature", sameFields);
    }

    // ---------------------------------------------------------------- 3. 私有会话的 filter 真值

    private static void sessionFilter(BiConsumer<String, Boolean> check) {
        File root = new File(System.getProperty("java.io.tmpdir"),
                "huchuan-selftest-allowlist-" + System.nanoTime());
        root.mkdirs();
        BtSession bt = null;
        try {
            bt = new BtSession(0, new File(root, "bt"), true);
            bt.start();
            check.accept("crossnet.filter_private_allowed",
                    bt.ipFilterAllows("127.0.0.1")
                            && bt.ipFilterAllows("10.1.2.3")
                            && bt.ipFilterAllows("172.16.0.1")
                            && bt.ipFilterAllows("192.168.1.1")
                            && bt.ipFilterAllows("169.254.1.1")
                            && bt.ipFilterAllows("::1")
                            && bt.ipFilterAllows("fd00::1")
                            && bt.ipFilterAllows("fe80::1"));
            check.accept("crossnet.filter_public_denied_before_register",
                    !bt.ipFilterAllows("203.0.113.9") && !bt.ipFilterAllows("198.51.100.7"));

            check.accept("crossnet.filter_allowlist_update_reports_change",
                    bt.updatePublicAllowlist(List.of("203.0.113.9")));
            check.accept("crossnet.filter_registered_public_allowed",
                    bt.ipFilterAllows("203.0.113.9"));
            check.accept("crossnet.filter_unregistered_public_still_denied",
                    !bt.ipFilterAllows("198.51.100.7"));
            check.accept("crossnet.filter_private_still_allowed_after_update",
                    bt.ipFilterAllows("10.1.2.3") && bt.ipFilterAllows("192.168.1.1"));

            // 整份替换：旧地址必须撤掉（防「只加不减」的白名单泄漏）
            check.accept("crossnet.filter_replace_reports_change",
                    bt.updatePublicAllowlist(List.of("198.51.100.7")));
            check.accept("crossnet.filter_replaced_old_revoked", !bt.ipFilterAllows("203.0.113.9"));
            check.accept("crossnet.filter_replaced_new_allowed", bt.ipFilterAllows("198.51.100.7"));

            check.accept("crossnet.filter_clear_reports_change", bt.updatePublicAllowlist(List.of()));
            check.accept("crossnet.filter_clear_revokes_all",
                    !bt.ipFilterAllows("198.51.100.7") && !bt.ipFilterAllows("203.0.113.9"));
            check.accept("crossnet.filter_clear_keeps_private", bt.ipFilterAllows("192.168.1.1"));
            check.accept("crossnet.filter_same_set_is_noop",
                    !bt.updatePublicAllowlist(List.of()) && bt.getIpFilterError().isEmpty());
        } catch (Throwable t) {
            check.accept("crossnet.filter_exception (" + t + ")", false);
            t.printStackTrace(System.out);
        } finally {
            if (bt != null) {
                try {
                    bt.stop();
                } catch (Throwable ignored) {
                    // 停不掉不影响断言结论
                }
            }
            deleteTree(root);
        }
    }

    // ---------------------------------------------------------------- 4. 群知识 → filter

    private static void groupAllowlist(BiConsumer<String, Boolean> check) {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "huchuan-selftest-allowlist-kernel-" + System.nanoTime());
        dir.mkdirs();
        M1Kernel kernel = null;
        try {
            Recorder events = new Recorder();
            kernel = new M1Kernel(dir, "allowlist-selftest", 47810, 0, events);
            kernel.start();
            GroupRuntime group = kernel.createGroup("跨网白名单自检群");

            check.accept("crossnet.kernel_private_baseline", kernel.btIpFilterAllows("192.168.1.55"));
            check.accept("crossnet.kernel_public_baseline_denied", !kernel.btIpFilterAllows("203.0.113.9"));

            DeviceIdentity peer1 = DeviceIdentity.Companion.generate();
            DeviceIdentity peer2 = DeviceIdentity.Companion.generate();
            registerPub(kernel, peer1);
            registerPub(kernel, peer2);
            addMember(group, peer1, "公网成员一");
            addMember(group, peer2, "公网成员二");

            // ① peer.announce 的 btHost（签名合法、公钥已登记）
            announce(group, peer1, "203.0.113.9", 51413, 0);
            check.accept("crossnet.announce_host_in_allowlist (hosts=" + kernel.btAllowlistHosts() + ")",
                    kernel.btAllowlistHosts().contains("203.0.113.9"));
            check.accept("crossnet.announce_host_allowed_by_filter",
                    kernel.btIpFilterAllows("203.0.113.9"));
            check.accept("crossnet.announce_other_public_denied",
                    !kernel.btIpFilterAllows("198.51.100.7"));
            check.accept("crossnet.announce_private_still_allowed",
                    kernel.btIpFilterAllows("192.168.1.55"));
            check.accept("crossnet.allowlist_logged", events.has("bt_ip_allowlist"));

            announce(group, peer2, "198.51.100.7", 51513, 0);
            check.accept("crossnet.two_members_union",
                    kernel.btAllowlistHosts().contains("203.0.113.9")
                            && kernel.btAllowlistHosts().contains("198.51.100.7"));

            // ② 同一成员换主机：旧地址立即撤销（端点替换 = 移除策略的一部分）
            announce(group, peer1, "203.0.113.10", 51413, 0);
            check.accept("crossnet.endpoint_replace_revokes_old",
                    !kernel.btAllowlistHosts().contains("203.0.113.9")
                            && !kernel.btIpFilterAllows("203.0.113.9"));
            check.accept("crossnet.endpoint_replace_allows_new",
                    kernel.btIpFilterAllows("203.0.113.10"));
            check.accept("crossnet.endpoint_replace_keeps_other_member",
                    kernel.btIpFilterAllows("198.51.100.7"));

            // ③ tracker 候选与手动 IP:port 也是白名单来源
            setTrackerCandidates(group, List.of(new Candidate("198.51.100.20", 47100, "tracker")));
            check.accept("crossnet.tracker_candidate_allowed",
                    kernel.btIpFilterAllows("198.51.100.20"));
            // tracker 名单是整体替换的：新名单里没有的 host 立刻撤销（否则一份过期名单会永久放行）
            setTrackerCandidates(group, List.of(new Candidate("198.51.100.22", 47100, "tracker")));
            check.accept("crossnet.tracker_list_replace_revokes_old",
                    !kernel.btIpFilterAllows("198.51.100.20"));
            check.accept("crossnet.tracker_list_replace_allows_new",
                    kernel.btIpFilterAllows("198.51.100.22"));
            rememberManualAddrs(group, List.of("198.51.100.21:47100", "nas.local:47100"));
            check.accept("crossnet.manual_literal_allowed",
                    kernel.btIpFilterAllows("198.51.100.21"));
            check.accept("crossnet.manual_hostname_not_in_allowlist",
                    !kernel.btAllowlistHosts().contains("nas.local"));

            // ④ 退群 = M1 唯一的「成员集合缩小」事件：所有动态地址撤销，私网段保留。
            //    （成员离线不移除，理由见 docs/core.md §3.7：BT 连接可能还在传、离线是暂时的。）
            check.accept("crossnet.group_removed", kernel.removeGroup(group.getGroupId()));
            check.accept("crossnet.group_removed_revokes_all (hosts=" + kernel.btAllowlistHosts() + ")",
                    kernel.btAllowlistHosts().isEmpty());
            check.accept("crossnet.group_removed_filter_denies",
                    !kernel.btIpFilterAllows("203.0.113.10")
                            && !kernel.btIpFilterAllows("198.51.100.7")
                            && !kernel.btIpFilterAllows("198.51.100.20")
                            && !kernel.btIpFilterAllows("198.51.100.22")
                            && !kernel.btIpFilterAllows("198.51.100.21"));
            check.accept("crossnet.group_removed_private_kept", kernel.btIpFilterAllows("10.1.2.3"));
        } catch (Throwable t) {
            check.accept("crossnet.group_exception (" + t + ")", false);
            t.printStackTrace(System.out);
        } finally {
            if (kernel != null) {
                try {
                    kernel.close();
                } catch (Throwable ignored) {
                    // 同上
                }
            }
            deleteTree(dir);
        }
    }

    // ---------------------------------------------------------------- 5. 两个内核：pubBtPort 广播 + 公开拨号

    private static void twoKernels(BiConsumer<String, Boolean> check) {
        File root = new File(System.getProperty("java.io.tmpdir"),
                "huchuan-selftest-crossnet-" + System.nanoTime());
        root.mkdirs();
        M1Kernel a = null;
        M1Kernel b = null;
        try {
            Recorder eventsA = new Recorder();
            Recorder eventsB = new Recorder();
            a = new M1Kernel(new File(root, "a"), "crossnet-A", 47820, 0, eventsA);
            a.start();
            GroupRuntime ga = a.createGroup("跨网广播群");
            String hash = fakeInfoHash(71);
            String magnet = "magnet:?xt=urn:btih:" + hash + "&dn=crossnet-selftest.bin&xl=4096";
            // 公开会话按需起来才有端口可报；timeout=0 不等元数据（本机没有源）。
            PublicMagnetResult pub = a.addPublicMagnet(ga.getGroupId(), magnet, "跨网公开条目", 0);
            int aPubPort = a.publicSessionPort();
            check.accept("crossnet.public_session_started (port=" + aPubPort + ")", aPubPort >= 6881);
            check.accept("crossnet.public_entry_created", pub.getEntry() != null);

            b = new M1Kernel(new File(root, "b"), "crossnet-B", 47821, 0, eventsB);
            b.start();
            GroupRuntime gb = b.joinByTail8(ga.getInviteCode().substring(8),
                    List.of("127.0.0.1:" + a.localMessagePort()), 30_000L);
            check.accept("crossnet.join_ok", gb != null);

            // A 的 peer.announce 在链路绑定时发出，必须带公开会话端口；B 解析后写进事件字段。
            boolean portBroadcast = waitUntil(() -> eventsB.contains("pub_bt_port=" + aPubPort), 15_000L);
            check.accept("crossnet.pub_bt_port_broadcast (want " + aPubPort + ")", portBroadcast);

            boolean entryArrived = waitUntil(() -> gb != null && gb.file(hash) != null, 15_000L);
            check.accept("crossnet.public_entry_received", entryArrived);

            boolean activated = gb != null && gb.startDownload(hash);
            check.accept("crossnet.public_entry_activated", activated);

            // 公开条目对成员拨号：目标必须是 A 的**公开会话**端口，日志 sess=pub。
            String wantPrefix = "bt_connect_peer ";
            String wantAddr = "addr=127.0.0.1:" + aPubPort;
            boolean dialed = waitUntil(() -> eventsB.lines().stream().anyMatch(line ->
                    line.startsWith(wantPrefix)
                            && line.contains(" file_id=" + hash + " ")
                            && line.contains(" sess=pub ")
                            && line.contains(" " + wantAddr + " ")), 20_000L);
            check.accept("crossnet.public_dial_uses_pub_bt_port (" + wantAddr + ")", dialed);
            check.accept("crossnet.public_dial_not_private_session",
                    eventsB.lines().stream().noneMatch(line ->
                            line.startsWith(wantPrefix)
                                    && line.contains(" file_id=" + hash + " ")
                                    && line.contains(" sess=priv ")));
        } catch (Throwable t) {
            check.accept("crossnet.two_kernels_exception (" + t + ")", false);
            t.printStackTrace(System.out);
        } finally {
            if (b != null) {
                try {
                    b.close();
                } catch (Throwable ignored) {
                    // 同上
                }
            }
            if (a != null) {
                try {
                    a.close();
                } catch (Throwable ignored) {
                    // 同上
                }
            }
            deleteTree(root);
        }
    }

    // ---------------------------------------------------------------- 注入（Kotlin internal 在 JVM 上是 public + 模块后缀）

    private static void registerPub(M1Kernel kernel, DeviceIdentity identity) {
        invokeInternal(kernel.getClass(), kernel, "registerPub",
                new Class<?>[]{String.class, byte[].class},
                identity.getDeviceId(), identity.getPub64());
    }

    private static void addMember(GroupRuntime group, DeviceIdentity identity, String name) {
        invokeInternal(group.getClass(), group, "addMember",
                new Class<?>[]{String.class, String.class, long.class, String.class},
                identity.getDeviceId(), name, 1L, hex(identity.getPub64()));
    }

    /** 一条签名合法的 peer.announce：与线上路径完全同一个入口 [GroupRuntime.onPeerAnnounce]。 */
    private static void announce(GroupRuntime group, DeviceIdentity identity,
                                 String host, int btPort, int pubBtPort) {
        long ts = System.currentTimeMillis();
        PeerAnnounceMsg unsigned = new PeerAnnounceMsg(group.getGroupId(), identity.getDeviceId(),
                btPort, host, ts, "", "unknown", 0, pubBtPort);
        String sig = identity.sign(Domains.PEER, unsigned.signFields());
        PeerAnnounceMsg signed = new PeerAnnounceMsg(group.getGroupId(), identity.getDeviceId(),
                btPort, host, ts, sig, "unknown", 0, pubBtPort);
        invokeInternal(group.getClass(), group, "onPeerAnnounce",
                new Class<?>[]{PeerAnnounceMsg.class, String.class, String.class},
                signed, host, null);
    }

    @SuppressWarnings("unchecked")
    private static void setTrackerCandidates(GroupRuntime group, List<Candidate> candidates) {
        invokeInternal(group.getClass(), group, "setTrackerCandidates",
                new Class<?>[]{List.class}, candidates);
    }

    @SuppressWarnings("unchecked")
    private static void rememberManualAddrs(GroupRuntime group, List<String> addrs) {
        invokeInternal(group.getClass(), group, "rememberManualAddrs",
                new Class<?>[]{List.class}, addrs);
    }

    /**
     * Kotlin 的 internal 函数在 JVM 上是 public + `$` + 模块名后缀（本项目是 `$core`）。
     * 按「前缀 + 参数个数」找，不写死模块名，避免改构建模块名时测试先碎。
     */
    private static void invokeInternal(Class<?> type, Object target, String base,
                                       Class<?>[] parameterTypes, Object... args) {
        Method found = null;
        for (Method method : type.getMethods()) {
            if (method.getName().equals(base) || method.getName().startsWith(base + "$")) {
                if (method.getParameterCount() == parameterTypes.length) {
                    found = method;
                    break;
                }
            }
        }
        if (found == null) {
            throw new IllegalStateException("harness injection point missing: "
                    + type.getName() + "." + base + "$<module>");
        }
        try {
            found.setAccessible(true);
            found.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("injection failed: " + base + ": " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("injection failed: " + base + ": " + e, e);
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 事件出口：把内核日志拼成一行，供「广播里有没有 pubBtPort / 拨的是哪个会话」这类断言读。 */
    static final class Recorder extends KernelEvents {

        private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void log(String event, Map<String, String> fields) {
            StringBuilder sb = new StringBuilder(event);
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                sb.append(' ').append(entry.getKey()).append('=').append(entry.getValue());
            }
            lines.add(sb.toString());
        }

        List<String> lines() {
            synchronized (lines) {
                return new ArrayList<>(lines);
            }
        }

        boolean has(String event) {
            return lines().stream().anyMatch(line -> line.equals(event) || line.startsWith(event + " "));
        }

        boolean contains(String needle) {
            return lines().stream().anyMatch(line -> line.contains(needle));
        }
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return condition.getAsBoolean();
            }
        }
        return condition.getAsBoolean();
    }

    private static String fakeInfoHash(int seed) {
        byte[] raw = new byte[20];
        new java.util.Random(seed).nextBytes(raw);
        StringBuilder sb = new StringBuilder(40);
        for (byte value : raw) {
            sb.append(String.format("%02x", value));
        }
        return sb.toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            sb.append(String.format("%02x", value));
        }
        return sb.toString();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        // Windows 上刚停掉的会话可能还占着句柄：删不掉就算了，临时目录不参与断言。
        file.delete();
    }
}
