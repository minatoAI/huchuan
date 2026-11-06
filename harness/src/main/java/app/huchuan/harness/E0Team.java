package app.huchuan.harness;

import app.huchuan.core.m1.FileEntry;
import app.huchuan.core.m1.GroupRuntime;
import app.huchuan.core.m1.KernelEvents;
import app.huchuan.core.m1.M1Kernel;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * E0 组队基线实验：三个进程（S 做种者 + C1/C2 两个下载者）量 D = S.uploaded / F。
 *
 * 装置口径（docs/design/team-acceleration.md §9.1）：
 *   F = 512 MiB，S 单文件上行限速 2 MiB/s，C1/C2 同时下载同一文件、靠内核的「每成员一次拨号」互连。
 *
 * 判定：D < 1.25 → 只做通告、不做分片；D ≥ 1.25 → rarest-first 没有去重，要重估分片。
 * 每一轮还必须满足「每个成员的 numPeers ≥ 2」（做种者 + 队友），否则该轮作废。
 *
 * 进程间的文件约定（都是 harness 自己的协调文件，不是协议字段）：
 *   <markers>/done-C1.txt、done-C2.txt   下载者完成时写，内容是 key=value 行
 *   <markers>/stop.txt                   S 读完读数后写，下载者看到就退出
 *
 * 用法见 harness/e0-team.sh；单跑：
 *   java ... app.huchuan.harness.E0Team S <workDir> <dataDir> msg_port=47300 bt_port=53413 markers=<dir>
 *   java ... app.huchuan.harness.E0Team C1 <workDir> <dataDir> tail8=<8> manual=127.0.0.1:47300 ...
 */
public final class E0Team {

    /** 装置口径：文件大小固定 512 MiB（片大小由 BtSession.pieceSizeFor 定成 256 KiB）。 */
    static final long FILE_BYTES = 512L * 1024 * 1024;

    /** 做种者上行限速 2 MiB/s（setUploadLimit 的单位是 KB/s）。 */
    static final int UPLOAD_KBPS = 2048;

    /** 判定阈值（docs/design/team-acceleration.md §9.4）。 */
    static final double D_THRESHOLD = 1.25;

    static final long WAIT_MS = longProp("m1.waitMs", 900_000L);

    public static void main(String[] args) {
        RunLog.install();
        try {
            if (args.length < 3) {
                System.err.println("usage: E0Team <S|C1|C2> <workDir> <dataDir> [key=value ...]");
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
            int msgPort = Integer.parseInt(opts.getOrDefault("msg_port", "47300"));
            int btPort = Integer.parseInt(opts.getOrDefault("bt_port", "53413"));
            if ("S".equals(role)) {
                runSeeder(workDir, dataDir, msgPort, btPort, opts);
            } else if ("C1".equals(role) || "C2".equals(role)) {
                runDownloader(role, workDir, dataDir, msgPort, btPort, opts);
            } else {
                System.err.println("bad role: " + role);
                System.exit(2);
            }
        } catch (Throwable t) {
            RunLog.out("M1 ? fail error=" + clean(t.toString()));
            t.printStackTrace(System.out);
            RunLog.out("RESULT=FAIL");
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ S：做种者

    static void runSeeder(Path workDir, Path dataDir, int msgPort, int btPort, Map<String, String> opts)
            throws Exception {
        String name = opts.getOrDefault("name", "E0_S");
        // 装置口径 512 MiB；file_mb 只给快速调试用（判定口径不变，D 与 F 无关）
        long fileBytes = Long.parseLong(opts.getOrDefault("file_mb", "512")) * 1024L * 1024L;
        // BEP 16 开关：superseed=1 时对做种者打开 super-seeding（其余装置口径完全不变）
        boolean superSeed = "1".equals(opts.getOrDefault("superseed", "0").trim());
        Path markers = Paths.get(opts.getOrDefault("markers", workDir.resolve("markers").toString())).toAbsolutePath();
        Files.createDirectories(markers);
        RunLog.out("M1 S run_begin role=S run_id=" + RunLog.runId()
                + " F=" + FILE_BYTES
                + " upload_kbps=" + UPLOAD_KBPS
                + " markers=" + clean(markers.toString()));

        Events events = new Events("S");
        M1Kernel kernel = new M1Kernel(dataDir.toFile(), name, msgPort, btPort, events);
        events.attach(kernel);
        kernel.start();
        Probe probe = Probe.of(kernel);
        RunLog.out("M1 S net_ports msg_port=" + kernel.localMessagePort()
                + " bt_port=" + kernel.localBtPort());

        try {
            GroupRuntime group = kernel.createGroup("E0组队");
            String code = group.getInviteCode();
            RunLog.out("M1 S invite_code=" + code);
            RunLog.out("M1 S invite_tail8=" + code.substring(8));
            RunLog.out("M1 S group_id=" + group.getGroupId());

            File payload = workDir.resolve("share").resolve("payload-" + (fileBytes >> 20) + "m.bin").toFile();
            createRepeatingPayload(payload, fileBytes);
            FileEntry entry = group.addFile(payload, superSeed);
            group.setUploadLimitKbps(entry.getFileId(), UPLOAD_KBPS);
            RunLog.out("M1 S superseed requested=" + (superSeed ? "1" : "0")
                    + " readback_after_add=" + group.superSeeding(entry.getFileId()));
            RunLog.out("M1 S file_shared file_id=" + entry.getFileId()
                    + " name=" + clean(entry.getName())
                    + " size=" + entry.getSize()
                    + " piece_size=" + entry.getPieceSize()
                    + " upload_limit_kbps=" + UPLOAD_KBPS);

            // 等 libtorrent 把 512 MiB 校验完：没校验完就供片，会把「校验时间」算进 T_all。
            boolean ready = false;
            long readyDeadline = System.currentTimeMillis() + 180_000L;
            String last = "";
            while (System.currentTimeMillis() < readyDeadline) {
                last = probe.torrentLine(entry.getFileId());
                if (last.contains("finished=true")) {
                    ready = true;
                    break;
                }
                Thread.sleep(200);
            }
            RunLog.out("M1 S seeding_ready ok=" + ready + " " + clean(last));
            if (!ready) {
                fail("S", "seed_not_ready");
                return;
            }

            // 校验完再读一次真值：libtorrent 在 files_checked() 里有一条「不是 seed 就把
            // super-seeding 关掉」的兜底（torrent.cpp），所以必须在校验之后确认它还在。
            boolean ssAfterReady = group.superSeeding(entry.getFileId());
            String reassert = "n/a";
            if (superSeed && !ssAfterReady) {
                group.setSuperSeeding(entry.getFileId(), true);
                ssAfterReady = group.superSeeding(entry.getFileId());
                reassert = "1";
            }
            RunLog.out("M1 S superseed_readback requested=" + (superSeed ? "1" : "0")
                    + " value=" + ssAfterReady + " reassert=" + reassert);

            // 两个下载者完成时会各写一份 marker；S 只认这两个文件，不靠协议（不新造消息）
            Path c1 = markers.resolve("done-C1.txt");
            Path c2 = markers.resolve("done-C2.txt");
            long waitStart = System.currentTimeMillis();
            while (System.currentTimeMillis() - waitStart < WAIT_MS) {
                if (Files.isRegularFile(c1) && Files.isRegularFile(c2)) {
                    break;
                }
                Thread.sleep(200);
            }
            if (!Files.isRegularFile(c1) || !Files.isRegularFile(c2)) {
                RunLog.out("M1 S wait_timeout c1=" + Files.isRegularFile(c1) + " c2=" + Files.isRegularFile(c2));
                fail("S", "downloaders_not_done");
                return;
            }
            Map<String, String> m1 = readMarker(c1);
            Map<String, String> m2 = readMarker(c2);
            // 尾巴上还有几帧在飞：等 2 s 让 payload_uploaded 收敛
            Thread.sleep(2000);

            String stats = probe.torrentLine(entry.getFileId());
            RunLog.out("M1 S transfer_stats " + clean(stats));
            String peersNow = probe.peerProgressLine(entry.getFileId());
            RunLog.out("M1 S peer_progress " + clean(peersNow));

            long uploaded = fieldLong(stats, "payload_uploaded", -1);
            long fBytes = entry.getSize();
            long startMs = Math.min(fieldLong(m1, "start_ms", 0), fieldLong(m2, "start_ms", 0));
            long doneMs = Math.max(fieldLong(m1, "done_ms", 0), fieldLong(m2, "done_ms", 0));
            int p1 = (int) fieldLong(m1, "peers_max", 0);
            int p2 = (int) fieldLong(m2, "peers_max", 0);
            double tAllS = (startMs > 0 && doneMs > startMs) ? (doneMs - startMs) / 1000.0 : -1.0;
            double d = uploaded > 0 ? (double) uploaded / (double) fBytes : -1.0;

            boolean peersOk = p1 >= 2 && p2 >= 2;
            boolean ok = uploaded > 0 && tAllS > 0 && peersOk;
            RunLog.out("M1 S team_metric D=" + fmt(d, 4)
                    + " T_all=" + fmt(tAllS, 1) + "s"
                    + " peers=" + p1 + "/" + p2
                    + " uploaded=" + uploaded
                    + " F=" + fBytes
                    + " valid=" + ok
                    + " superseed=" + (superSeed ? "1" : "0")
                    + " super_readback=" + group.superSeeding(entry.getFileId())
                    + " verdict=" + (ok ? (d >= D_THRESHOLD ? "REASSESS_SHARDING" : "ANNOUNCE_ONLY") : "VOID"));

            // 收工：让两个下载者退出
            Files.write(markers.resolve("stop.txt"),
                    ("stop\n").getBytes(StandardCharsets.UTF_8));
            Thread.sleep(500);
            kernel.close();
            if (!ok) {
                fail("S", "round_void peers=" + p1 + "/" + p2 + " uploaded=" + uploaded);
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

    // ------------------------------------------------------------------ C1 / C2：下载者

    static void runDownloader(String role, Path workDir, Path dataDir, int msgPort, int btPort,
                              Map<String, String> opts) throws Exception {
        String tail8 = opts.get("tail8");
        if (tail8 == null || tail8.length() != 8) {
            fail(role, "missing_tail8");
            return;
        }
        String name = opts.getOrDefault("name", "E0_" + role);
        Path markers = Paths.get(opts.getOrDefault("markers", workDir.resolve("markers").toString())).toAbsolutePath();
        Files.createDirectories(markers);
        List<String> manual = new ArrayList<>();
        for (String part : opts.getOrDefault("manual", "").split(",")) {
            if (!part.trim().isEmpty()) {
                manual.add(part.trim());
            }
        }
        RunLog.out("M1 " + role + " run_begin role=" + role + " run_id=" + RunLog.runId()
                + " tail8=" + tail8
                + " manual=" + String.join(",", manual));

        Events events = new Events(role);
        M1Kernel kernel = new M1Kernel(dataDir.toFile(), name, msgPort, btPort, events);
        events.attach(kernel);
        kernel.start();
        Probe probe = Probe.of(kernel);
        RunLog.out("M1 " + role + " net_ports msg_port=" + kernel.localMessagePort()
                + " bt_port=" + kernel.localBtPort());

        try {
            GroupRuntime group = kernel.joinByTail8(tail8, manual, Math.min(90_000L, WAIT_MS));
            if (group == null) {
                fail(role, "join_failed");
                return;
            }
            RunLog.out("M1 " + role + " joined gid=" + group.getGroupId()
                    + " members=" + group.members().size());
            FileEntry entry = waitForFile(group, WAIT_MS);
            if (entry == null) {
                fail(role, "file_not_seen");
                return;
            }
            RunLog.out("M1 " + role + " file_seen file_id=" + entry.getFileId()
                    + " size=" + entry.getSize());

            long startMs = System.currentTimeMillis();
            boolean started = group.startDownload(entry.getFileId());
            RunLog.out("M1 " + role + " download_start ok=" + started + " start_ms=" + startMs);
            if (!started) {
                fail(role, "download_not_started");
                return;
            }

            long deadline = startMs + WAIT_MS;
            int maxPeers = 0;
            int maxTeammates = 0;
            int lastPct = -1;
            long doneMs = 0L;
            while (System.currentTimeMillis() < deadline) {
                int peers = group.peerCount(entry.getFileId());
                if (peers > maxPeers) {
                    maxPeers = peers;
                }
                int team = kernel.teammates(group.getGroupId(), entry.getFileId());
                if (team > maxTeammates) {
                    maxTeammates = team;
                }
                int pct = (int) (group.progress(entry.getFileId()) * 100.0);
                if (pct / 10 != lastPct / 10) {
                    lastPct = pct;
                    RunLog.out("M1 " + role + " progress pct=" + pct
                            + " peers=" + peers + " teammates=" + team
                            + " endpoints=" + clean(endpointsOf(group))
                            + " bt_peers=" + clean(probe.peerProgressLine(entry.getFileId())));
                }
                if (group.isComplete(entry.getFileId())) {
                    doneMs = System.currentTimeMillis();
                    break;
                }
                Thread.sleep(200);
            }
            if (doneMs == 0L) {
                RunLog.out("M1 " + role + " transfer_stats " + clean(probe.torrentLine(entry.getFileId())));
                fail(role, "download_timeout max_peers=" + maxPeers);
                return;
            }
            // 完成那一刻的读数：队友数在 100% 之后就该归零（文件到 100% 即退出组队）
            int teamAtDone = kernel.teammates(group.getGroupId(), entry.getFileId());
            File saved = group.savedFile(entry.getFileId());
            RunLog.out("M1 " + role + " download_done file_id=" + entry.getFileId()
                    + " bytes=" + (saved == null ? -1 : saved.length())
                    + " peers_max=" + maxPeers
                    + " teammates_max=" + maxTeammates
                    + " teammates_at_done=" + teamAtDone
                    + " elapsed_ms=" + (doneMs - startMs));
            RunLog.out("M1 " + role + " transfer_stats " + clean(probe.torrentLine(entry.getFileId())));

            String marker = "role=" + role + "\n"
                    + "start_ms=" + startMs + "\n"
                    + "done_ms=" + doneMs + "\n"
                    + "bytes=" + (saved == null ? -1 : saved.length()) + "\n"
                    + "peers_max=" + maxPeers + "\n"
                    + "teammates_max=" + maxTeammates + "\n"
                    + "teammates_at_done=" + teamAtDone + "\n";
            Files.write(markers.resolve("done-" + role + ".txt"), marker.getBytes(StandardCharsets.UTF_8));
            RunLog.out("M1 " + role + " marker_written path=" + clean(markers.resolve("done-" + role + ".txt").toString()));

            // 走完 100% 之后不要立刻退出：S 还要读它那一侧的 payload_uploaded / peer 列表。
            Path stop = markers.resolve("stop.txt");
            long holdDeadline = System.currentTimeMillis() + WAIT_MS;
            int closedCount = events.peerOfflineCount();
            while (System.currentTimeMillis() < holdDeadline) {
                if (Files.isRegularFile(stop)) {
                    break;
                }
                Thread.sleep(200);
            }
            RunLog.out("M1 " + role + " hold_done peer_offline_events=" + (events.peerOfflineCount() - closedCount));
            kernel.close();
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

    /**
     * 用「重复块」造 512 MiB：一块 256 KiB 的模板 + 每片在头 8 字节打上片号。
     *
     * 不写 512 MiB 随机数（慢），也不留成一份全零文件（所有片哈希相同，不像真实内容）。
     * 磁盘上确实占 F 字节 —— 两个下载者也会各占 F，脚本负责清。
     */
    static void createRepeatingPayload(File file, long bytes) throws Exception {
        file.getParentFile().mkdirs();
        if (file.isFile() && file.length() == bytes) {
            return;
        }
        int piece = 256 * 1024;
        byte[] template = new byte[piece];
        new Random(20261106L).nextBytes(template);
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(0);
            long written = 0;
            long index = 0;
            while (written < bytes) {
                long tag = index++;
                template[0] = (byte) (tag >>> 56);
                template[1] = (byte) (tag >>> 48);
                template[2] = (byte) (tag >>> 40);
                template[3] = (byte) (tag >>> 32);
                template[4] = (byte) (tag >>> 24);
                template[5] = (byte) (tag >>> 16);
                template[6] = (byte) (tag >>> 8);
                template[7] = (byte) tag;
                int n = (int) Math.min(template.length, bytes - written);
                raf.write(template, 0, n);
                written += n;
            }
        }
    }

    /** 本机看到的成员 BT 端点（dev@host:port，逗号分隔）：诊断「队友数为什么是 0」用。 */
    static String endpointsOf(GroupRuntime group) {
        try {
            java.lang.reflect.Field f = null;
            Class<?> c = group.getClass();
            while (c != null && c != Object.class && f == null) {
                try {
                    f = c.getDeclaredField("btEndpoints");
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) {
                return "no_field";
            }
            f.setAccessible(true);
            Object raw = f.get(group);
            if (!(raw instanceof Map)) {
                return "not_map";
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(e.getKey()).append('@').append(e.getValue());
            }
            return sb.toString();
        } catch (Throwable t) {
            return "error:" + t.getClass().getSimpleName();
        }
    }

    static Map<String, String> readMarker(Path p) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                out.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return out;
    }

    /** 从 key=value 行里取一个整数；取不到返回 def。 */
    static long fieldLong(String line, String key, long def) {
        if (line == null) {
            return def;
        }
        String marker = key + "=";
        int at = line.indexOf(marker);
        if (at < 0) {
            return def;
        }
        int from = at + marker.length();
        int to = from;
        while (to < line.length() && "-0123456789".indexOf(line.charAt(to)) >= 0) {
            to++;
        }
        if (to == from) {
            return def;
        }
        try {
            return Long.parseLong(line.substring(from, to));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static long fieldLong(Map<String, String> map, String key, long def) {
        String raw = map.get(key);
        if (raw == null || raw.trim().isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static String fmt(double value, int digits) {
        if (value < 0) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%." + digits + "f", value);
    }

    static FileEntry waitForFile(GroupRuntime group, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<FileEntry> files = group.files();
            if (!files.isEmpty()) {
                return files.get(0);
            }
            Thread.sleep(200);
        }
        return null;
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
        private final java.util.concurrent.atomic.AtomicInteger peerOffline =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger peerOnline =
                new java.util.concurrent.atomic.AtomicInteger();

        Events(String role) {
            this.role = role;
        }

        void attach(M1Kernel kernel) {
        }

        int peerOfflineCount() {
            return peerOffline.get();
        }

        int peerOnlineCount() {
            return peerOnline.get();
        }

        @Override
        public void peersChanged(GroupRuntime group, String deviceId, boolean online) {
            if (online) {
                peerOnline.incrementAndGet();
            } else {
                peerOffline.incrementAndGet();
            }
            log("peers_changed", Map.of(
                    "dev", deviceId == null ? "" : deviceId,
                    "online", String.valueOf(online),
                    "online_peers", String.valueOf(group == null ? -1 : group.onlinePeers().size())));
        }

        @Override
        public void log(String event, Map<String, String> fields) {
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
