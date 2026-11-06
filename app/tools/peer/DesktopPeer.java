import app.huchuan.core.m1.FileEntry;
import app.huchuan.core.m1.GroupRuntime;
import app.huchuan.core.m1.KernelEvents;
import app.huchuan.core.m1.M1Kernel;
import app.huchuan.core.m1.OpRecord;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 桌面 JVM 对端：真机验收里的「另一台设备」。
 *
 *   host：建群 → 打印邀请码后 8 位 → 等对端加入 → 等对端共享文件 → 下载并比对 SHA-256
 *         → 发评论 → 等对端回评论 →（可选）延迟补一条评论 → 共享压力文件做种 → 服务到脚本收工
 *   join：只拿后 8 位加入（+ 可选手动 IP:port）→ 共享文件 → 下载对端的文件 → 评论 → 等回复
 *
 * 输出是结构化行（"PEER <event> k=v ..."），脚本只 grep 文本。
 * 它不碰 UI，只证明「Android 内核 ↔ Windows 内核」在真局域网里能互通。
 */
public final class DesktopPeer {

    static final long JOIN_TIMEOUT_MS = 240_000L;
    static final long FILE_TIMEOUT_MS = 600_000L;
    static final long DOWNLOAD_TIMEOUT_MS = 900_000L;
    static final long OP_TIMEOUT_MS = 300_000L;

    static final Events EVENTS = new Events();

    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                System.err.println("usage: DesktopPeer <host|join> data=<dir> work=<dir> [name=..] [tail8=..] [manual=ip:port] "
                        + "[msg_port=..] [bt_port=..] [share=path1,path2] [comment=..] [expect_comment=..]"
                        + " [echo=..] [echo_delay_ms=..] [download=0|1] [hold=seconds] [control=<file>]");
                System.exit(2);
            }
            String role = args[0];
            Map<String, String> opts = new LinkedHashMap<>();
            for (int i = 1; i < args.length; i++) {
                int eq = args[i].indexOf('=');
                if (eq > 0) {
                    opts.put(args[i].substring(0, eq), args[i].substring(eq + 1));
                }
            }
            run(role, opts);
        } catch (Throwable t) {
            System.out.println("PEER fail error=" + clean(t.toString()));
            t.printStackTrace(System.out);
            System.out.println("RESULT=FAIL");
            System.out.flush();
            System.exit(1);
        }
    }

    static void run(String role, Map<String, String> opts) throws Exception {
        Path work = Paths.get(opts.getOrDefault("work", ".")).toAbsolutePath();
        Path data = Paths.get(opts.getOrDefault("data", work.resolve("data").toString())).toAbsolutePath();
        String name = opts.getOrDefault("name", "桌面电脑");
        String groupName = opts.getOrDefault("group_name", "真机验收群");
        int msgPort = Integer.parseInt(opts.getOrDefault("msg_port", "47100"));
        int btPort = Integer.parseInt(opts.getOrDefault("bt_port", "51413"));
        long holdSeconds = Long.parseLong(opts.getOrDefault("hold", "1800"));
        boolean doDownload = !"0".equals(opts.getOrDefault("download", "1"));

        Files.createDirectories(work);
        Files.createDirectories(data);

        M1Kernel kernel = new M1Kernel(data.toFile(), name, msgPort, btPort, EVENTS);
        kernel.start();
        GroupRuntime group;
        if ("host".equals(role)) {
            group = kernel.createGroup(groupName);
            String code = group.getInviteCode();
            System.out.println("PEER ready role=host tail8=" + code.substring(8)
                    + " invite=" + code
                    + " gid=" + group.getGroupId()
                    + " fp=" + group.getFingerprint()
                    + " msg_port=" + kernel.localMessagePort()
                    + " bt_port=" + kernel.localBtPort()
                    + " device_id=" + kernel.getDeviceId()
                    + " dht_nodes=" + kernel.dhtNodes());
        } else {
            String tail8 = opts.get("tail8");
            if (tail8 == null || tail8.length() != 8) {
                fail("missing_tail8");
                return;
            }
            List<String> manual = new ArrayList<>();
            for (String part : opts.getOrDefault("manual", "").split(",")) {
                if (!part.trim().isEmpty()) {
                    manual.add(part.trim());
                }
            }
            group = kernel.joinByTail8(tail8, manual, 120_000L);
            if (group == null) {
                fail("join_failed");
                return;
            }
            System.out.println("PEER ready role=join tail8=" + tail8
                    + " gid=" + group.getGroupId()
                    + " fp=" + group.getFingerprint()
                    + " members=" + group.members().size()
                    + " msg_port=" + kernel.localMessagePort()
                    + " bt_port=" + kernel.localBtPort()
                    + " device_id=" + kernel.getDeviceId()
                    + " dht_nodes=" + kernel.dhtNodes());
        }
        System.out.flush();

        // ---------------------------------------------------------- 1. 共享（可选，先于等待）
        // throttle=<索引>:<KB/s>：给指定序号的共享文件限速（0 起），
        // 让真机上的「中途取消」有一个够宽的窗口，顺带验证协议 §4 的单文件限速。
        int throttleIndex = -1;
        int throttleKbps = 0;
        String throttleOpt = opts.getOrDefault("throttle", "");
        if (throttleOpt.contains(":")) {
            String[] parts = throttleOpt.split(":");
            throttleIndex = Integer.parseInt(parts[0].trim());
            throttleKbps = Integer.parseInt(parts[1].trim());
        }
        List<String> shared = new ArrayList<>();
        for (String raw : opts.getOrDefault("share", "").split(",")) {
            String text = raw.trim();
            if (text.isEmpty()) {
                continue;
            }
            File file = new File(text);
            if (!file.isFile()) {
                System.out.println("PEER share_missing path=" + text);
                continue;
            }
            FileEntry entry = group.addFile(file);
            System.out.println("PEER share file_id=" + entry.getFileId()
                    + " name=" + clean(entry.getName())
                    + " size=" + entry.getSize()
                    + " piece_size=" + entry.getPieceSize()
                    + " sha256=" + sha256(file));
            System.out.flush();
            if (shared.size() == throttleIndex && throttleKbps > 0) {
                group.setUploadLimitKbps(entry.getFileId(), throttleKbps);
                System.out.println("PEER throttle file_id=" + entry.getFileId()
                        + " index=" + shared.size() + " kbps=" + throttleKbps);
                System.out.flush();
            }
            shared.add(entry.getFileId());
        }
        if (!shared.isEmpty()) {
            System.out.println("PEER shared_count n=" + shared.size());
            System.out.flush();
        }

        // ---------------------------------------------------------- 2. 等对端加入 / 等对端的文件
        if ("host".equals(role)) {
            long deadline = System.currentTimeMillis() + JOIN_TIMEOUT_MS;
            boolean joined = false;
            while (System.currentTimeMillis() < deadline) {
                if (EVENTS.memberJoined.await(200, TimeUnit.MILLISECONDS)) {
                    joined = true;
                    break;
                }
                if (group.onlinePeers().size() >= 2) {
                    joined = true;
                    break;
                }
            }
            if (!joined) {
                fail("peer_join_timeout");
                return;
            }
            System.out.println("PEER peer_joined members=" + group.members().size()
                    + " online=" + group.onlinePeers().size());
            System.out.flush();
        }

        String remoteFileId = null;
        if (doDownload) {
            FileEntry remote = waitRemoteFile(kernel, group, FILE_TIMEOUT_MS);
            if (remote == null) {
                fail("file_not_seen");
                return;
            }
            remoteFileId = remote.getFileId();
            System.out.println("PEER file_seen file_id=" + remote.getFileId()
                    + " name=" + clean(remote.getName())
                    + " size=" + remote.getSize()
                    + " added_by=" + remote.getAddedBy());
            System.out.flush();

            boolean started = group.startDownload(remote.getFileId());
            System.out.println("PEER download_start ok=" + started + " file_id=" + remote.getFileId());
            System.out.flush();
            if (!started) {
                fail("download_not_started");
                return;
            }
            long downloadDeadline = System.currentTimeMillis() + DOWNLOAD_TIMEOUT_MS;
            int lastPct = -1;
            while (System.currentTimeMillis() < downloadDeadline) {
                if (group.isComplete(remote.getFileId())) {
                    break;
                }
                int pct = (int) (group.progress(remote.getFileId()) * 100.0);
                if (pct != lastPct) {
                    System.out.println("PEER progress pct=" + pct);
                    System.out.flush();
                    lastPct = pct;
                }
                Thread.sleep(200);
            }
            if (!group.isComplete(remote.getFileId())) {
                fail("download_timeout");
                return;
            }
            File saved = group.savedFile(remote.getFileId());
            if (saved == null || !saved.isFile()) {
                fail("download_file_missing");
                return;
            }
            String sha = sha256(saved);
            System.out.println("PEER download_done file_id=" + remote.getFileId()
                    + " bytes=" + saved.length()
                    + " sha256=" + sha
                    + " path=" + saved.getAbsolutePath());
            System.out.flush();

            // ------------------------------------------------------ 3. 发评论
            String body = opts.getOrDefault("comment", "from-pc-1");
            OpRecord op = group.comment(remote.getFileId(), body, null);
            System.out.println("PEER comment_sent op_id=" + op.getOpId() + " body=" + clean(body));
            System.out.flush();
        }

        // ---------------------------------------------------------- 4. 等对端的评论
        // control=<文件>：脚本按需往里写 download= / comment= 命令（见 §6）。这种对端是按
        // 脚本节奏工作的，不该在"等对端评论"上空等 5 分钟 —— 否则手机侧切完档、写进控制文件
        // 的命令要等 OP_TIMEOUT_MS 之后才被读到（实测：通知三档的第二步会卡死在这里）。
        String controlPath = opts.getOrDefault("control", "");
        String reply = opts.getOrDefault("expect_comment", "");
        long opWaitMs = (reply.isEmpty() && !controlPath.isEmpty()) ? 0L : OP_TIMEOUT_MS;
        long opDeadline = System.currentTimeMillis() + opWaitMs;
        boolean gotReply = false;
        while (System.currentTimeMillis() < opDeadline) {
            Events.Received rec = EVENTS.ops.poll(200, TimeUnit.MILLISECONDS);
            if (rec == null) {
                continue;
            }
            OpRecord received = rec.op;
            if (received.getAuthor().equals(kernel.getDeviceId())) {
                continue;
            }
            // 时延按内核口径算：op.ts 是对端墙钟，直接拿本机墙钟去减，两台机器时钟不同步时
            // 会得到负值（真机日志里出现过 -2144 ms）。latencyMillis 先把对端发送时刻按
            // observePeerClock 估出的偏移换算到本机时钟再相减，减法两边都在本机时钟上。
            // ts 只用于展示，评论顺序固定按 (lamport, author, seq)。
            long latency = kernel.latencyMillis(received.getAuthor(), received.getTs(), rec.recvAt);
            System.out.println("PEER op_received op_id=" + received.getOpId()
                    + " author=" + received.getAuthor()
                    + " body=" + clean(received.getBody())
                    + " latency_ms=" + latency
                    + " sent_at=" + received.getTs()
                    + " recv_at=" + rec.recvAt);
            System.out.flush();
            if (received.getBody().contains(reply)) {
                gotReply = true;
                break;
            }
        }
        System.out.println("PEER reply_seen ok=" + gotReply);
        System.out.flush();

        // ---------------------------------------------------------- 5. 延迟补一条评论
        // 真机侧要验「P2 群行未读角标」：手机回完评论后会退回 P2，它不在群组页上的时候
        // 必须有人发一条新评论，角标才有机会出现。delay 从「收到手机评论」开始算，
        // 留出手机返回 P2 的时间。echo=<正文> 不传就跳过。
        String echo = opts.getOrDefault("echo", "");
        long echoDelayMs = Long.parseLong(opts.getOrDefault("echo_delay_ms", "20000"));
        if (!echo.isEmpty() && remoteFileId != null) {
            long echoAt = System.currentTimeMillis() + echoDelayMs;
            while (System.currentTimeMillis() < echoAt) {
                Thread.sleep(100);
            }
            OpRecord echoed = group.comment(remoteFileId, echo, null);
            System.out.println("PEER echo_comment_sent op_id=" + echoed.getOpId()
                    + " body=" + clean(echo)
                    + " file_id=" + remoteFileId
                    + " delay_ms=" + echoDelayMs);
            System.out.flush();
        }

        // ---------------------------------------------------------- 6. 一直做种（+ 按需下载 / 按需评论）
        // control=<文件>：脚本往里写一行命令，这里每 400 ms 读一次：
        //   download=<文件名>   把对端的同名文件拉一份（何时开始传由脚本决定，速率在这里量，
        //                       不靠日志猜；真机验收用它验「P7 全局限速 / 清掉限制后恢复」）。
        //   comment=<正文>      对 remoteFileId 发一条评论。通知三档的验收要在**同一轮连接**里
        //                       按脚本节奏一条条发评论（切档 → 发 → 断言 → 再切档），
        //                       每发一条就重起一个对端既慢又容易被别的 agent 的对端清理打断。
        // 每条命令行只执行一次（controlDone 按整行去重）。
        java.util.Set<String> controlDone = new java.util.HashSet<>();
        long holdDeadline = System.currentTimeMillis() + holdSeconds * 1000L;
        while (System.currentTimeMillis() < holdDeadline) {
            if (!controlPath.isEmpty()) {
                for (String command : readControl(controlPath)) {
                    String line = command.trim();
                    if (line.isEmpty() || !controlDone.add(line)) {
                        continue;
                    }
                    if (line.startsWith("download=")) {
                        String wanted = line.substring("download=".length()).trim();
                        if (wanted.isEmpty()) {
                            continue;
                        }
                        autoDownload(kernel, group, wanted);
                        continue;
                    }
                    if (line.startsWith("share=")) {
                        // 运行中再共享一个文件（"群内出现新文件"这条通知触发的验收要用：
                        // 启动参数里的 share= 发生在手机入群之前，会被入群追赶窗口正确压掉）。
                        String path = line.substring("share=".length()).trim();
                        File file = new File(path);
                        if (!file.isFile()) {
                            System.out.println("PEER control_share_missing path=" + clean(path));
                        } else {
                            try {
                                FileEntry added = group.addFile(file);
                                System.out.println("PEER control_share file_id=" + added.getFileId()
                                        + " name=" + clean(added.getName()) + " size=" + added.getSize());
                            } catch (Throwable t) {
                                System.out.println("PEER control_share_failed path=" + clean(path)
                                        + " error=" + clean(t.toString()));
                            }
                        }
                        System.out.flush();
                        continue;
                    }
                    if (line.startsWith("comment=")) {
                        // comment=<正文>              → 发到下载来的那个文件（remoteFileId）
                        // comment=<文件名>|<正文>      → 按文件名找目标（含自己共享的文件）
                        String spec = line.substring("comment=".length()).trim();
                        String fileName = null;
                        String body = spec;
                        int bar = spec.indexOf('|');
                        if (bar >= 0) {
                            fileName = spec.substring(0, bar).trim();
                            body = spec.substring(bar + 1).trim();
                        }
                        String target = null;
                        if (fileName != null) {
                            for (FileEntry candidate : group.files()) {
                                if (candidate.getName().equals(fileName)) {
                                    target = candidate.getFileId();
                                    break;
                                }
                            }
                        } else {
                            target = remoteFileId;
                        }
                        if (body.isEmpty() || target == null) {
                            System.out.println("PEER control_comment_skipped body=" + clean(body)
                                    + " reason=" + (target == null
                                            ? (fileName == null ? "no_remote_file" : "file_not_found:" + fileName)
                                            : "empty_body"));
                            System.out.flush();
                            continue;
                        }
                        try {
                            OpRecord op = group.comment(target, body, null);
                            System.out.println("PEER control_comment_sent op_id=" + op.getOpId()
                                    + " body=" + clean(body) + " file_id=" + target);
                        } catch (Throwable t) {
                            System.out.println("PEER control_comment_failed body=" + clean(body)
                                    + " error=" + clean(t.toString()));
                        }
                        System.out.flush();
                        continue;
                    }
                    System.out.println("PEER control_ignored line=" + clean(line));
                    System.out.flush();
                }
            }
            Thread.sleep(400);
        }
        System.out.println("PEER hold_done shared=" + shared.size());
        System.out.println("RESULT=PASS");
        System.out.flush();
        kernel.close();
        System.exit(0);
    }

    // ------------------------------------------------------------------ 工具

    /** 控制文件全文（一行一条命令）；不存在/读不到就是空表。 */
    static List<String> readControl(String path) {
        try {
            Path file = Paths.get(path);
            if (!Files.isRegularFile(file)) {
                return java.util.Collections.emptyList();
            }
            return Files.readAllLines(file);
        } catch (Throwable ignored) {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * 按需下载一个对端文件，并把实测速率打出来。
     *
     * kbps 的分母是 startDownload 到 isComplete 的墙钟时间：稳态限速下会贴着标称值，
     * 不限速时远高于它 —— 这就是「限速生效 / 已清掉」的判据。
     */
    static void autoDownload(M1Kernel kernel, GroupRuntime group, String name) throws Exception {
        FileEntry entry = null;
        long seenDeadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < seenDeadline) {
            for (FileEntry candidate : group.files()) {
                if (candidate.getName().equals(name) && !candidate.getAddedBy().equals(kernel.getDeviceId())) {
                    entry = candidate;
                    break;
                }
            }
            if (entry != null) {
                break;
            }
            Thread.sleep(200);
        }
        if (entry == null) {
            System.out.println("PEER auto_done name=" + clean(name) + " complete=false reason=file_not_seen");
            System.out.flush();
            return;
        }
        long startedAt = System.currentTimeMillis();
        boolean started = group.startDownload(entry.getFileId());
        System.out.println("PEER auto_download name=" + clean(name)
                + " file_id=" + entry.getFileId()
                + " size=" + entry.getSize()
                + " started=" + started);
        System.out.flush();
        if (!started) {
            return;
        }
        long deadline = System.currentTimeMillis() + DOWNLOAD_TIMEOUT_MS;
        long lastSample = 0L;
        while (System.currentTimeMillis() < deadline && !group.isComplete(entry.getFileId())) {
            long now = System.currentTimeMillis();
            // 每 2 s 打一行瞬时速率与 BT 连接数：均速会把「连接慢」和「限速低」混在一起，
            // 这两行能直接分开看（peers 是 libtorrent 看到的该 torrent 连接数）。
            if (now - lastSample >= 2000L) {
                lastSample = now;
                System.out.println("PEER auto_progress name=" + clean(name)
                        + " pct=" + (int) (group.progress(entry.getFileId()) * 100.0)
                        + " peers=" + group.peerCount(entry.getFileId())
                        + " rate_kbps=" + (group.downloadRateBps(entry.getFileId()) / 1024L));
                System.out.flush();
            }
            Thread.sleep(100);
        }
        long elapsedMs = System.currentTimeMillis() - startedAt;
        boolean complete = group.isComplete(entry.getFileId());
        File saved = group.savedFile(entry.getFileId());
        long bytes = (saved != null && saved.isFile()) ? saved.length() : 0L;
        long kbps = elapsedMs > 0 ? (bytes / 1024L) * 1000L / elapsedMs : 0L;
        System.out.println("PEER auto_done name=" + clean(name)
                + " complete=" + complete
                + " bytes=" + bytes
                + " elapsed_ms=" + elapsedMs
                + " kbps=" + kbps
                + " sha256=" + (saved != null && saved.isFile() ? sha256(saved) : ""));
        System.out.flush();
    }

    static FileEntry waitRemoteFile(M1Kernel kernel, GroupRuntime group, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (FileEntry entry : group.files()) {
                if (!entry.getAddedBy().equals(kernel.getDeviceId())) {
                    return entry;
                }
            }
            Thread.sleep(200);
        }
        return null;
    }

    static void fail(String reason) {
        System.out.println("PEER fail reason=" + reason);
        System.out.println("RESULT=FAIL");
        System.out.flush();
        System.exit(1);
    }

    static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file.toPath()), 1 << 20)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    static String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\s]+", "_");
    }

    // ------------------------------------------------------------------ 事件出口

    static final class Events extends KernelEvents {
        /** 收到时刻与本机内核同一时刻取，时延按 latencyMillis 算（见 op_received 处注释）。 */
        static final class Received {
            final OpRecord op;
            final long recvAt;

            Received(OpRecord op, long recvAt) {
                this.op = op;
                this.recvAt = recvAt;
            }
        }

        final BlockingQueue<Received> ops = new LinkedBlockingQueue<>();
        final CountDownLatch memberJoined = new CountDownLatch(1);

        @Override
        public void log(String event, Map<String, String> fields) {
            StringBuilder sb = new StringBuilder("PEER ");
            sb.append(new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)
                    .format(new java.util.Date())).append(' ');
            sb.append(event);
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                String value = entry.getValue();
                if (value == null || value.isEmpty()) {
                    continue;
                }
                sb.append(' ').append(entry.getKey()).append('=').append(clean(value));
            }
            System.out.println(sb);
            System.out.flush();
        }

        @Override
        public void memberJoined(GroupRuntime group, String deviceId, String name) {
            System.out.println("PEER member_joined dev=" + deviceId + " name=" + clean(name));
            System.out.flush();
            memberJoined.countDown();
        }

        @Override
        public void fileAdded(GroupRuntime group, FileEntry entry) {
            System.out.println("PEER file_added file_id=" + entry.getFileId()
                    + " name=" + clean(entry.getName())
                    + " size=" + entry.getSize()
                    + " added_by=" + entry.getAddedBy());
            System.out.flush();
        }

        @Override
        public void fileCompleted(GroupRuntime group, FileEntry entry) {
            System.out.println("PEER file_completed file_id=" + entry.getFileId());
            System.out.flush();
        }

        @Override
        public void opReceived(GroupRuntime group, OpRecord op) {
            // 落队时就记下本机收到时刻：poll 之前空等的那 200 ms 不能算进时延。
            ops.offer(new Received(op, System.currentTimeMillis()));
        }
    }
}
