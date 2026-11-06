package app.huchuan.harness;

import app.huchuan.core.FileScope;
import app.huchuan.core.m1.FileEntry;
import app.huchuan.core.m1.GroupRuntime;
import app.huchuan.core.m1.KernelEvents;
import app.huchuan.core.m1.M1Kernel;
import app.huchuan.core.m1.OpRecord;
import app.huchuan.core.m1.PublicMagnetResult;
import app.huchuan.core.m1.PublicPublishResult;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M1 无头夹具：两个独立 JVM 进程（A / B）跑完整流程。
 *
 *   A：建群 → 打印 16 位邀请码 → 等 B 加入 → 共享 32 MiB 文件做种 → 等 B 的评论
 *   B：只拿后 8 位加入 → 收文件 → 下载 → 比对 SHA-256 → 发评论
 *
 * 输出是结构化日志（每行 "run=<runId> M1 <role> <event> k=v ..."），scripts/m1-headless.sh 直接 grep。
 * 每一行都带本轮 runId：断言只认本轮，历史日志留在同一文件里也不会被采信。
 * 退出码 0 = 该角色全部步骤完成；RESULT=PASS 只在成功路径打印。
 *
 * 反向测试用的变异注入点（-Dm1.mutant=...，默认关闭，见 docs/reviews/m1-harness-hardening.md）：
 *   no_seed  A 共享后立刻停止做种（B 应当下载失败）
 *   precopy  B 不走网络，直接把 A 的载荷拷进 downloads（provenance 断言应当抓住）
 *   dht_on   会话 settings_pack 打开 enable_dht（dht 断言应当抓住）
 *   replay   本轮不产出任何证据，只剩上一轮日志（runId 锚定应当抓住）
 *
 * 另一组角色跑「错过广播 → 重连补差」（scripts/m1-headless.sh 第 6.5 组，file.list 那条线上路径）：
 *   A2  建群 → 先共享 N 条（默认 33，>32）→ 注入一条签名对不上的假条目 → 等 B2 加入 →
 *       等 B2 离线 → 在没人收广播的时刻共享第 N+1 条 → 等 B2 重连收工
 *   B2  只拿后 8 位加入（phase=1）或带着本地库重连（phase=2），把「广播来的」与「file.list
 *       补来的」分开计数：内核在广播路径打 file_add、在补差路径打 file_list_entry。
 *
 * 第三组角色跑公开种子的端到端验收（scripts/e2e-public.sh）：
 *   A3  建群 → 共享 32 MiB 私有文件 → publishPublic → 等 B3 的 SHA 评论
 *   B3  只用后 8 位加入 → 拿 scope=public 的磁力条目 → 激活 → 元数据从 A3 的公开会话过来 →
 *       下载 → SHA-256 → 评论回 A3
 */
public final class M1Harness {

    static final long FILE_SIZE = 32L * 1024 * 1024;

    /** 各阶段等待上限；-Dm1.waitMs 可缩短（只用于加速反向测试，只会让失败更早发生）。 */
    static final long WAIT_MS = longProp("m1.waitMs", 180_000L);
    static final long JOIN_WAIT_MS = Math.min(150_000L, WAIT_MS);
    static final long SAMPLE_INTERVAL_MS = longProp("m1.sampleMs", 750L);
    /** 重连拨号的总时限：这条路径上等不到 join.ok，拨号只负责把链路建起来。 */
    static final long RECONNECT_DIAL_MS = longProp("m1.redialMs", 60_000L);

    static final String MUTANT = System.getProperty("m1.mutant", "").trim();

    /**
     * -Dm1.shareFirst=1：A 先共享、再等成员加入。
     * 这样新成员是从 join.ok 的 files 快照拿到清单（而不是 file.add 广播），
     * 是「补同步」那条路径（join.ok 快照 / file.list，都走同一条条目验签）唯一的在线覆盖。
     * 默认关闭，验收脚本的流程不变。
     */
    static final boolean SHARE_FIRST = truthy(System.getProperty("m1.shareFirst"));

    /** -D 属性用 true/1 都算开（Boolean.getBoolean 只认 "true"，容易踩空）。 */
    static boolean truthy(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.trim();
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }
    static final String MUTANT_COPY_SRC = System.getProperty("m1.mutant.copySrc", "").trim();

    static final CountDownLatch MEMBER_JOINED = new CountDownLatch(1);
    static final CountDownLatch FILE_SEEN = new CountDownLatch(1);
    static final CountDownLatch COMMENT_RECEIVED = new CountDownLatch(1);
    /** 本机下载完成事件（KernelEvents.fileCompleted）到达的那一刻。 */
    static final CountDownLatch FILE_COMPLETED = new CountDownLatch(1);

    /** 缺口一：B 冷启动后必须在这个时间内重新连上 A（A 只负责取证，判据在 B 侧）。 */
    static final long RECONNECT_LIMIT_MS = longProp("m1.reconnectLimitMs", 10_000L);

    /** 缺口一：A 等 B 冷启动后自己连回来的上限（宽松一些，失败原因由上面的 10 s 说清）。 */
    static final long REBIND_WAIT_MS = longProp("m1.rebindMs", 40_000L);

    /** 缺口二：下载完成到事件回调的上限。 */
    static final long COMPLETION_EVENT_LIMIT_MS = longProp("m1.completionLimitMs", 500L);

    static volatile FileEntry lastFile;
    static volatile OpRecord lastOp;
    static volatile Sampler sampler;

    public static void main(String[] args) {
        RunLog.install();
        try {
            if (args.length < 3) {
                System.err.println("usage: M1Harness <A|B> <workDir> <dataDir> [key=value ...]");
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
            if ("replay".equals(MUTANT)) {
                // 变异：本轮不产出任何证据行（等价于进程早退 / 有人拿上一轮日志来顶）。
                RunLog.out("M1 " + role + " run_begin role=" + role + " mutant=replay note=no_evidence_this_round");
                RunLog.out("RESULT=FAIL");
                System.exit(0);
            }
            Files.createDirectories(workDir);
            Files.createDirectories(dataDir);
            int msgPort = Integer.parseInt(opts.getOrDefault("msg_port", "47100"));
            int btPort = Integer.parseInt(opts.getOrDefault("bt_port", "51413"));
            if ("A".equals(role)) {
                runA(workDir, dataDir, msgPort, btPort, opts);
            } else if ("B".equals(role)) {
                runB(workDir, dataDir, msgPort, btPort, opts);
            } else if ("A2".equals(role)) {
                runCatchupHost(workDir, dataDir, msgPort, btPort, opts);
            } else if ("B2".equals(role)) {
                runCatchupJoin(workDir, dataDir, msgPort, btPort, opts);
            } else if ("A3".equals(role)) {
                runPublishHost(workDir, dataDir, msgPort, btPort, opts);
            } else if ("B3".equals(role)) {
                runPublicMagnetJoin(workDir, dataDir, msgPort, btPort, opts);
            } else {
                System.err.println("bad role: " + role);
                System.exit(2);
            }
        } catch (Throwable t) {
            System.out.println("M1 ? fail error=" + clean(t.toString()));
            t.printStackTrace(System.out);
            System.out.println("RESULT=FAIL");
            System.out.flush();
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ A

    static void runA(Path workDir, Path dataDir, int msgPort, int btPort, Map<String, String> opts)
            throws Exception {
        String name = opts.getOrDefault("name", "A_的笔记本");
        String groupName = opts.getOrDefault("group_name", "老板的群");
        RunLog.out("M1 A run_begin role=A run_id=" + RunLog.runId()
                + " mutant=" + mutantName()
                + " work_dir=" + clean(workDir.toString())
                + " data_dir=" + clean(dataDir.toString()));

        Events events = new Events("A");
        M1Kernel kernel = new M1Kernel(dataDir.toFile(), name, msgPort, btPort, events);
        events.attach(kernel);
        kernel.start();
        Probe probe = Probe.of(kernel);
        if (dhtOnMutant()) {
            RunLog.out("M1 A mut_dht_on applied=" + probe.turnDhtOn());
        }
        RunLog.out("M1 A bt_probe " + probe.sessionLine());
        RunLog.out("M1 A net_ports msg_port=" + kernel.localMessagePort()
                + " bt_port=" + kernel.localBtPort());
        sampler = new Sampler("A", probe, SAMPLE_INTERVAL_MS).start();

        try {
            GroupRuntime group = kernel.createGroup(groupName);
            String code = group.getInviteCode();
            RunLog.out("M1 A invite_code=" + code);
            RunLog.out("M1 A invite_pretty=" + group.getInvitePretty());
            RunLog.out("M1 A invite_tail8=" + code.substring(8));
            RunLog.out("M1 A invite_len=" + code.length());
            RunLog.out("M1 A group_id=" + group.getGroupId() + " fp=" + group.getFingerprint());

            FileEntry entry = SHARE_FIRST ? sharePayload(group, workDir) : null;

            if (!MEMBER_JOINED.await(JOIN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                fail("A", "member_join_timeout");
            }

            if (entry == null) {
                entry = sharePayload(group, workDir);
            }

            if ("no_seed".equals(MUTANT)) {
                // 变异：共享之后马上停种，B 还能看到清单但拿不到数据。
                boolean stopped = group.stopFile(entry.getFileId());
                RunLog.out("M1 A mut_no_seed stopped=" + stopped + " file_id=" + entry.getFileId());
            }

            if (!COMMENT_RECEIVED.await(WAIT_MS, TimeUnit.MILLISECONDS)) {
                RunLog.out("M1 A transfer_stats " + probe.torrentLine(entry.getFileId()));
                fail("A", "comment_timeout");
            }
            // A 侧字节真值：B 的 32 MiB 必须是从这里发出去的（不是文件大小，是 libtorrent 计数）。
            RunLog.out("M1 A transfer_stats " + probe.torrentLine(entry.getFileId()));

            boolean inStore = false;
            List<OpRecord> visible = group.visibleOps(entry.getFileId());
            for (OpRecord op : visible) {
                if (op.getOpId().equals(lastOp.getOpId())) {
                    inStore = true;
                    break;
                }
            }
            RunLog.out("M1 A op_in_store=" + inStore + " ops_visible=" + visible.size());
            RunLog.out("M1 A members=" + group.members().size() + " files=" + group.files().size());
            if (!inStore) {
                // 只有真的全过才算 PASS：以前这里先打 RESULT=PASS 再 exit 1。
                fail("A", "comment_not_in_store");
            }

            // ---------- 缺口一：等 B 冷启动后自己连回来 ----------
            // 这里不主动拨号、不做任何补救：B 重启（内核重建）之后必须由它自己连上来。
            // 判据在 B 侧（重启到绑定 ≤10 s）；A 只提供对端的现场 ——
            // 「A 侧看到 B 的平台与做种数恢复」= 重连之后那条 peer.announce 不是 unknown / 0。
            long rebindBase = events.peerOnlineEvents();
            long rebindT0 = System.currentTimeMillis();
            boolean rebound = awaitPeerOnlineEvents(events, rebindBase + 1, REBIND_WAIT_MS);
            long rebindWaitMs = System.currentTimeMillis() - rebindT0;
            // 重连成功后对端的第一条 peer.announce 才带平台与做种数，等它落地（最多 5 s）
            long announceDeadline = System.currentTimeMillis() + 5_000L;
            while (events.announcesAfterRebind() < 1 && System.currentTimeMillis() < announceDeadline) {
                Thread.sleep(50);
            }
            RunLog.out("M1 A peer_rebound ok=" + rebound
                    + " wait_ms=" + rebindWaitMs
                    + " ts=" + System.currentTimeMillis()
                    + " online_events=" + events.peerOnlineEvents()
                    + " rebind_announces=" + events.announcesAfterRebind()
                    + " dev=" + events.reboundDev()
                    + " plat=" + events.reboundPlat()
                    + " seeds=" + events.reboundSeeds());
            if (!rebound) {
                fail("A", "peer_rebound_timeout");
                return;
            }
            if (events.announcesAfterRebind() < 1
                    || events.reboundPlat().isEmpty()
                    || "unknown".equals(events.reboundPlat())
                    || events.reboundSeeds() <= 0) {
                // 「未知 / 0」就是缺陷现场的样子：链路回来了但成员运行期属性没补上。
                fail("A", "peer_plat_seeds_not_restored plat=" + events.reboundPlat()
                        + " seeds=" + events.reboundSeeds());
                return;
            }
            Thread.sleep(200);
            RunLog.out("M1 A flow_done");
            stopSampler();
            kernel.close();
            RunLog.out("RESULT=PASS");
            System.exit(0);
        } finally {
            stopSampler();
        }
    }

    // ------------------------------------------------------------------ B

    static void runB(Path workDir, Path dataDir, int msgPort, int btPort, Map<String, String> opts)
            throws Exception {
        String tail8 = opts.get("tail8");
        if (tail8 == null || tail8.isEmpty()) {
            fail("B", "missing_tail8");
            return;
        }
        List<String> manual = new ArrayList<>();
        if (opts.containsKey("manual")) {
            for (String part : opts.get("manual").split(",")) {
                if (!part.trim().isEmpty()) {
                    manual.add(part.trim());
                }
            }
        }
        String name = opts.getOrDefault("name", "B_的台式机");
        RunLog.out("M1 B run_begin role=B run_id=" + RunLog.runId()
                + " mutant=" + mutantName()
                + " work_dir=" + clean(workDir.toString())
                + " data_dir=" + clean(dataDir.toString()));

        Events events = new Events("B");
        M1Kernel kernel = new M1Kernel(dataDir.toFile(), name, msgPort, btPort, events);
        events.attach(kernel);
        kernel.start();
        Probe probe = Probe.of(kernel);
        if (dhtOnMutant()) {
            RunLog.out("M1 B mut_dht_on applied=" + probe.turnDhtOn());
        }
        RunLog.out("M1 B bt_probe " + probe.sessionLine());
        RunLog.out("M1 B net_ports msg_port=" + kernel.localMessagePort()
                + " bt_port=" + kernel.localBtPort());
        sampler = new Sampler("B", probe, SAMPLE_INTERVAL_MS).start();

        try {
            RunLog.out("M1 B join_input tail8=" + tail8 + " tail8_len=" + tail8.length()
                    + " manual=" + String.join(",", manual));

            GroupRuntime group = kernel.joinByTail8(tail8, manual, Math.min(90_000L, WAIT_MS));
            if (group == null) {
                fail("B", "join_failed");
                return;
            }
            RunLog.out("M1 B joined gid=" + group.getGroupId()
                    + " fp=" + group.getFingerprint()
                    + " members=" + group.members().size());

            FileEntry entry = waitForFile(group, WAIT_MS);
            if (entry == null) {
                fail("B", "file_not_seen");
                return;
            }
            RunLog.out("M1 B file_seen file_id=" + entry.getFileId()
                    + " name=" + clean(entry.getName())
                    + " size=" + entry.getSize()
                    + " inline_torrent=" + (entry.getTorrent().isEmpty() ? "no" : "yes"));

            if ("precopy".equals(MUTANT)) {
                // 变异：B 不走网络，直接把 A 的载荷拷进 downloads（模拟「文件本来就在盘上」）。
                if (MUTANT_COPY_SRC.isEmpty()) {
                    RunLog.out("M1 B mut_precopy skipped=no_copy_src");
                } else {
                    File dest = new File(kernel.getDownloadsDir(), entry.getName());
                    Files.createDirectories(dest.getParentFile().toPath());
                    Files.copy(Paths.get(MUTANT_COPY_SRC), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    RunLog.out("M1 B mut_precopy src=" + clean(MUTANT_COPY_SRC)
                            + " dst=" + clean(dest.getPath()) + " bytes=" + dest.length());
                }
            }

            // 缺陷回归的起点：还没开始下载时，本机在这个群一份都没在做种（seeds 必须是 0）
            int seedsBeforeDownload = group.seedingFileCount();
            RunLog.out("M1 B seeding_count phase=before_download value=" + seedsBeforeDownload + " expect=0");
            boolean started = group.startDownload(entry.getFileId());
            RunLog.out("M1 B download_start ok=" + started);
            if (!started) {
                fail("B", "download_not_started");
                return;
            }

            long dlStart = System.currentTimeMillis();
            long deadline = dlStart + WAIT_MS;
            int lastPct = -1;
            int samples = 0;
            int positive = 0;
            int maxPeers = 0;
            long firstPeerMs = -1;
            boolean complete = false;
            // 缺口二：本进程第一次看到「已下完」的时刻，用来量完成事件晚了多久。
            long completeSeenAt = 0L;
            while (System.currentTimeMillis() < deadline) {
                int peers = group.peerCount(entry.getFileId());
                samples++;
                if (peers > 0) {
                    positive++;
                    if (firstPeerMs < 0) {
                        firstPeerMs = System.currentTimeMillis() - dlStart;
                    }
                    if (peers > maxPeers) {
                        maxPeers = peers;
                    }
                }
                if (group.isComplete(entry.getFileId())) {
                    complete = true;
                    completeSeenAt = System.currentTimeMillis();
                    break;
                }
                int pct = (int) (group.progress(entry.getFileId()) * 100.0);
                if (pct != lastPct) {
                    RunLog.out("M1 B progress pct=" + pct);
                    lastPct = pct;
                }
                // 缺口二的时间基准：轮询越密，「本进程看到已下完」离真实完成越近。
                // 100 ms 足够：要判的是 500 ms 的时延，不是微秒级。
                Thread.sleep(100);
            }
            // 传输过程中的 peer 数：证明 B 真的连着 A，不是单机自嗨。
            RunLog.out("M1 B transfer_peers max=" + maxPeers
                    + " samples=" + samples
                    + " positive=" + positive
                    + " first_peer_ms=" + firstPeerMs
                    + " elapsed_ms=" + (System.currentTimeMillis() - dlStart));

            if (!complete) {
                RunLog.out("M1 B transfer_stats " + probe.torrentLine(entry.getFileId()));
                fail("B", "download_timeout");
                return;
            }
            File saved = group.savedFile(entry.getFileId());
            if (saved == null || !saved.isFile()) {
                RunLog.out("M1 B transfer_stats " + probe.torrentLine(entry.getFileId()));
                fail("B", "download_file_missing");
                return;
            }
            String sha = sha256(saved);
            RunLog.out("M1 B download_done file_id=" + entry.getFileId()
                    + " bytes=" + saved.length()
                    + " sha256=" + sha);
            // B 侧字节真值：libtorrent 的 payload 下载计数（对比文件大小，识别「文件本来就在盘上」）。
            RunLog.out("M1 B transfer_stats " + probe.torrentLine(entry.getFileId()));

            // ---------- 缺口二：完成事件必须当场推，不能等 2 s 巡查节拍 ----------
            boolean completionEvent = FILE_COMPLETED.await(5, TimeUnit.SECONDS);
            // source 由 download_completed 那一行填；内核先写行再推事件，这里仍留一个有界等待，
            // 免得一次线程切换就把快通道误判成慢通道（缺陷现场是 source="" / fast_path=false）。
            long sourceDeadline = System.currentTimeMillis() + 1000L;
            while (events.fileCompletedSource().isEmpty() && System.currentTimeMillis() < sourceDeadline) {
                Thread.sleep(5);
            }
            long completionEventAt = events.fileCompletedAtMs();
            long completionLag = (completionEvent && completeSeenAt > 0 && completionEventAt > 0)
                    ? completionEventAt - completeSeenAt
                    : Long.MAX_VALUE;
            int seedsAtComplete = group.seedingFileCount();
            String completionSource = events.fileCompletedSource();
            // 快通道 = 完成线程（告警 / 200 ms 兜底）。落回 2 s 巡查就是缺陷复发。
            // 光量时延不够：2 s 巡查的相位是随机的，恰好提前到的时候测出来也是「快」，所以两头都判：
            // ① 判定当场（≤500 ms）；② 这条事件不是 2 s 巡查产出的。
            boolean fastPath = "alert".equals(completionSource) || "sweep".equals(completionSource);
            RunLog.out("M1 B completion_event arrived=" + completionEvent
                    + " lag_ms=" + completionLag
                    + " limit_ms=" + COMPLETION_EVENT_LIMIT_MS
                    + " event_ts=" + completionEventAt
                    + " complete_seen_ts=" + completeSeenAt
                    + " seeding_at_event=" + seedsAtComplete
                    + " source=" + completionSource
                    + " fast_path=" + fastPath);
            if (!completionEvent || completionLag > COMPLETION_EVENT_LIMIT_MS || !fastPath) {
                // 旧实现：完成判定只在 2 s 巡查里做，事件最坏晚 2 s —— P3 停在「下载中 100%」。
                fail("B", "completion_event_late lag_ms=" + completionLag
                        + " arrived=" + completionEvent + " limit_ms=" + COMPLETION_EVENT_LIMIT_MS
                        + " source=" + completionSource);
                return;
            }

            // 单文件限速接口（协议 §4：一文件一 torrent + setUploadLimit）
            group.setUploadLimitKbps(entry.getFileId(), 512);
            RunLog.out("M1 B upload_limit_set file_id=" + entry.getFileId() + " kbps=512");

            OpRecord op = group.comment(entry.getFileId(), "这条能收到吗？", null);
            RunLog.out("M1 B comment_sent op_id=" + op.getOpId()
                    + " seq=" + op.getSeq()
                    + " sent_at=" + op.getTs());
            Thread.sleep(2000);

            // ---------- 做种集合 + 重启续种（缺陷回归 / 原缺口 G2）----------
            // 这一段证明「完成判定 → 做种集合」只有一份来源：本机自报的 seeds 与 P3 的做种状态都读
            // GroupRuntime.seedingFileCount()。真机缺陷（docs/m1-device.md §6-6）就是这里一直停在 0。
            int seedsAfterDownload = awaitSeedingCount(group, 1, 20_000L);
            RunLog.out("M1 B seeding_count phase=after_download value=" + seedsAfterDownload + " expect=1");

            // 休眠 → 移出做种集合；再激活 → 又回来（同一条完成判定，幂等）
            group.stopFile(entry.getFileId());
            int seedsAfterHibernate = group.seedingFileCount();
            RunLog.out("M1 B seeding_count phase=after_hibernate value=" + seedsAfterHibernate + " expect=0");
            group.startDownload(entry.getFileId());
            int seedsAfterReactivate = awaitSeedingCount(group, 1, 20_000L);
            RunLog.out("M1 B seeding_count phase=after_reactivate value=" + seedsAfterReactivate + " expect=1");

            // 再共享一个小文件：留给下面「文件缺失」用例（本机共享的做种）
            File extraShare = workDir.resolve("share").resolve("resume-extra.bin").toFile();
            createRandomFile(extraShare, 64 * 1024);
            FileEntry extraEntry = group.addFile(extraShare);
            int seedsAfterAdd = group.seedingFileCount();
            RunLog.out("M1 B file_shared_extra file_id=" + extraEntry.getFileId()
                    + " path=" + clean(extraShare.getPath())
                    + " seeding_count=" + seedsAfterAdd + " expect=2");

            // 重启续种：关掉内核再按同一份 state.json 起来（关的是内核，进程不退，
            // loadState → resumeSeeds 走的就是生产代码那条路）。
            // 采样线程必须在关内核之前停掉：Sampler 握着这个内核的会话，采一个已经停掉的会话会打出
            // source=unavailable 的行，脚本的 dht 断言会把它当坏样本（重启后的采样因此不重开）。
            stopSampler();
            kernel.close();
            // ---------- 缺口一：重启后主动重连（冷启动不再只等对端先连过来）----------
            // 这里不调 joinByTail8、不给任何手动候选：内核起来之后必须自己找到 A 并连回去。
            long onlineBase = events.peerOnlineEvents();
            long restartAt = System.currentTimeMillis();
            M1Kernel kernel2 = new M1Kernel(dataDir.toFile(), name, msgPort, btPort, events);
            events.attach(kernel2);
            kernel2.start();
            RunLog.out("M1 B restart_kernel_started phase=2 ts=" + restartAt
                    + " groups=" + kernel2.groups().size());
            GroupRuntime group2 = firstGroup(kernel2, "B");
            boolean relinked = awaitPeerOnlineEvents(events, onlineBase + 1, RECONNECT_LIMIT_MS);
            long relinkedAt = System.currentTimeMillis();
            RunLog.out("M1 B reconnect_after_restart ok=" + relinked
                    + " elapsed_ms=" + (relinkedAt - restartAt)
                    + " ts=" + relinkedAt
                    + " limit_ms=" + RECONNECT_LIMIT_MS
                    + " online=" + group2.onlinePeers().size()
                    + " groups=" + kernel2.groups().size());
            if (!relinked) {
                fail("B", "reconnect_after_restart_timeout limit_ms=" + RECONNECT_LIMIT_MS);
                return;
            }
            int seedsAfterRestart = awaitSeedingCount(group2, 2, 15_000L);
            RunLog.out("M1 B seeding_count phase=after_restart value=" + seedsAfterRestart
                    + " expect=2 groups=" + kernel2.groups().size()
                    + " files=" + group2.files().size());

            // 文件缺失：删掉那条小文件的本地副本再重启 → 它降级为休眠（不崩、不算做种），
            // 下载来的那条照常续种。
            kernel2.close();
            boolean extraDeleted = Files.deleteIfExists(extraShare.toPath());
            M1Kernel kernel3 = new M1Kernel(dataDir.toFile(), name, msgPort, btPort, events);
            events.attach(kernel3);
            kernel3.start();
            GroupRuntime group3 = firstGroup(kernel3, "B");
            int seedsAfterMissing = awaitSeedingCount(group3, 1, 10_000L);
            GroupRuntime.LocalRole missingRole = group3.localRole(extraEntry.getFileId());
            RunLog.out("M1 B seeding_count phase=after_restart_file_missing value=" + seedsAfterMissing
                    + " expect=1 file_deleted=" + extraDeleted
                    + " missing_file_role=" + missingRole + " expect=NONE");

            // 巡查对账：把「下载来的那条」的 torrent 从会话里摘掉 —— 等价于本机数据没了，但不碰磁盘文件
            // （Windows 上做种中的文件被 libtorrent 占着句柄，删不掉；真机 Android 上删得掉，走的是同一段降级动作）。
            // 两拍之内应当降级为休眠、做种数回到 0，进程不崩。
            boolean detached = detachTorrent(kernel3, entry.getFileId());
            int seedsAfterDetach = awaitSeedingCount(group3, 0, 15_000L);
            RunLog.out("M1 B seeding_count phase=after_torrent_detached value=" + seedsAfterDetach
                    + " expect=0 detached=" + detached);
            kernel3.close();

            boolean seedingOk = seedsBeforeDownload == 0
                    && seedsAfterDownload == 1
                    && seedsAfterHibernate == 0
                    && seedsAfterReactivate == 1
                    && seedsAfterAdd == 2
                    && seedsAfterRestart == 2
                    && seedsAfterMissing == 1
                    && missingRole == GroupRuntime.LocalRole.NONE
                    && detached
                    && seedsAfterDetach == 0;
            // 一次说清整条状态机的读数，脚本按 grep 就能看出是哪一格错了
            RunLog.out("M1 B seeding_transitions result=" + (seedingOk ? "pass" : "fail")
                    + " before_download=" + seedsBeforeDownload
                    + " after_download=" + seedsAfterDownload
                    + " after_hibernate=" + seedsAfterHibernate
                    + " after_reactivate=" + seedsAfterReactivate
                    + " after_add=" + seedsAfterAdd
                    + " after_restart=" + seedsAfterRestart
                    + " after_restart_file_missing=" + seedsAfterMissing
                    + " missing_role=" + missingRole
                    + " after_torrent_detached=" + seedsAfterDetach);
            RunLog.out("M1 B flow_done");
            if (!seedingOk) {
                // 回归就判 FAIL：这一格错掉时真机上 P6 的「做种 n」会跟着错，不能只留一行日志。
                fail("B", "seeding_transitions_failed");
                return;
            }
            RunLog.out("RESULT=PASS");
            System.exit(0);
        } finally {
            stopSampler();
        }
    }

    // ------------------------------------------------------------------ A2 / B2：错过广播 → 重连补差

    /**
     * A2：把「file.list 条目验签」这条线上路径单独跑一遍。
     *
     * 1. 建群 → 先共享 files 个文件（默认 33，>32），此时群里没有任何在线成员
     * 2. 注入一条签名对不上的假条目：fileId 与 name 都改过，sig 仍是被顶替那条 file.add 的原签名
     * 3. 等 B2 加入：join.ok 的 files 快照按 §10 因 >32 条置空，B2 只能靠 sync.req → file.list 补
     * 4. 等 B2 退出（链路断开）后共享第 files+1 个文件 —— 这一次广播没有任何接收方，
     *    B2 重连后只能再从 file.list 把它补回来
     *
     * 假条目是本用例的证伪支点：收端只有真的按 addedBy 公钥验签才会丢掉它。校验一旦被去掉，
     * B2 的清单里就会多出这一条，脚本的 files/ rejected / poison_present 三项断言同时 FAIL。
     * 注入走 GroupRuntime.restoreFile$<模块>（Kotlin internal 在 JVM 上是 public + 模块后缀）；
     * 找不到就当场 FAIL，绝不静默跳过——静默跳过等于把这条用例变成永远绿的空壳。
     */
    static void runCatchupHost(Path workDir, Path dataDir, int msgPort, int btPort, Map<String, String> opts)
            throws Exception {
        int fileCount = optInt(opts, "files", 33);
        int fileBytes = optInt(opts, "file_bytes", 64 * 1024);
        String name = opts.getOrDefault("name", "A2_的笔记本");
        String groupName = opts.getOrDefault("group_name", "补差验证群");
        RunLog.out("M1 A2 run_begin role=A2 run_id=" + RunLog.runId()
                + " mutant=" + mutantName()
                + " files=" + fileCount
                + " file_bytes=" + fileBytes
                + " work_dir=" + clean(workDir.toString())
                + " data_dir=" + clean(dataDir.toString()));

        Events events = new Events("A2");
        M1Kernel kernel = new M1Kernel(dataDir.toFile(), name, msgPort, btPort, events);
        events.attach(kernel);
        kernel.start();
        Probe probe = Probe.of(kernel);
        RunLog.out("M1 A2 bt_probe " + probe.sessionLine());
        RunLog.out("M1 A2 net_ports msg_port=" + kernel.localMessagePort()
                + " bt_port=" + kernel.localBtPort());
        sampler = new Sampler("A2", probe, SAMPLE_INTERVAL_MS).start();

        try {
            GroupRuntime group = kernel.createGroup(groupName);
            String code = group.getInviteCode();
            RunLog.out("M1 A2 invite_code=" + code);
            RunLog.out("M1 A2 invite_tail8=" + code.substring(8));
            RunLog.out("M1 A2 group_id=" + group.getGroupId() + " fp=" + group.getFingerprint());

            Path shareDir = workDir.resolve("share");
            Files.createDirectories(shareDir);
            List<FileEntry> shared = new ArrayList<>();
            for (int i = 0; i < fileCount; i++) {
                File payload = shareDir
                        .resolve(String.format(java.util.Locale.ROOT, "catchup-%02d.bin", i)).toFile();
                if (!payload.isFile() || payload.length() != fileBytes) {
                    createRandomFile(payload, fileBytes);
                }
                shared.add(group.addFile(payload));
            }
            RunLog.out("M1 A2 files_shared n=" + shared.size() + " manifest=" + group.files().size());

            FileEntry base = shared.get(0);
            FileEntry poison = poisonedEntry(group, base);
            restoreFileInternal(group, poison);
            RunLog.out("M1 A2 poison_injected file_id=" + poison.getFileId()
                    + " base_file_id=" + base.getFileId()
                    + " sig_hex=" + base.getSig().length()
                    + " manifest=" + group.files().size());

            if (!MEMBER_JOINED.await(JOIN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                fail("A2", "member_join_timeout");
                return;
            }
            // 这一刻发出的 join.ok 就是 B2 的入群依据：>32 条时 files 快照按 §10 置空。
            RunLog.out("M1 A2 join_window files_before_join=" + events.memberJoinedFiles
                    + " manifest=" + group.files().size()
                    + " snapshot_rule=empty_over_32");

            // B2 退出：链路断开，此后 addFile 的 file.add 广播没有任何接收方。
            if (!waitPeerState(group, false, JOIN_WAIT_MS)) {
                fail("A2", "peer_offline_timeout");
                return;
            }
            RunLog.out("M1 A2 peer_offline phase=1 online=" + group.onlinePeers().size());

            File extra = shareDir.resolve("catchup-extra.bin").toFile();
            if (!extra.isFile() || extra.length() != fileBytes) {
                createRandomFile(extra, fileBytes);
            }
            FileEntry extraEntry = group.addFile(extra);
            RunLog.out("M1 A2 extra_shared file_id=" + extraEntry.getFileId()
                    + " manifest=" + group.files().size()
                    + " online=" + group.onlinePeers().size());

            if (!waitPeerState(group, true, JOIN_WAIT_MS)) {
                fail("A2", "peer_reconnect_timeout");
                return;
            }
            RunLog.out("M1 A2 peer_reconnected online=" + group.onlinePeers().size());

            if (!waitPeerState(group, false, JOIN_WAIT_MS)) {
                fail("A2", "peer_catchup_done_timeout");
                return;
            }
            RunLog.out("M1 A2 catchup_done manifest=" + group.files().size());
            Thread.sleep(500);
            stopSampler();
            kernel.close();
            RunLog.out("RESULT=PASS");
            System.exit(0);
        } finally {
            stopSampler();
        }
    }

    /**
     * B2：phase=1 冷加入，phase=2 带着本地库重连。
     *
     * 摘要行里的两组计数就是证据本身：
     *   list_entries    内核在补差路径（file.list）入库的条数 —— 广播路径不算
     *   broadcast_adds  内核在广播路径（file.add）入库的条数
     * 错过广播的那一条新文件只要真从 file.list 补来，重连后必然 list_entries=1 / broadcast_adds=0。
     */
    static void runCatchupJoin(Path workDir, Path dataDir, int msgPort, int btPort, Map<String, String> opts)
            throws Exception {
        String tail8 = opts.get("tail8");
        if (tail8 == null || tail8.length() != 8) {
            fail("B2", "missing_tail8");
            return;
        }
        int phase = optInt(opts, "phase", 1);
        int expectFiles = optInt(opts, "expect_files", phase == 1 ? 33 : 34);
        String poisonId = opts.getOrDefault("poison", "");
        List<String> manual = new ArrayList<>();
        for (String part : opts.getOrDefault("manual", "").split(",")) {
            if (!part.trim().isEmpty()) {
                manual.add(part.trim());
            }
        }
        String name = opts.getOrDefault("name", "B2_的台式机");
        RunLog.out("M1 B2 run_begin role=B2 phase=" + phase + " run_id=" + RunLog.runId()
                + " mutant=" + mutantName()
                + " expect_files=" + expectFiles
                + " data_dir=" + clean(dataDir.toString()));

        Events events = new Events("B2");
        M1Kernel kernel = new M1Kernel(dataDir.toFile(), name, msgPort, btPort, events);
        events.attach(kernel);
        kernel.start();
        Probe probe = Probe.of(kernel);
        RunLog.out("M1 B2 bt_probe " + probe.sessionLine());
        RunLog.out("M1 B2 net_ports msg_port=" + kernel.localMessagePort()
                + " bt_port=" + kernel.localBtPort());
        sampler = new Sampler("B2", probe, SAMPLE_INTERVAL_MS).start();

        try {
            int stateFiles = 0;
            for (GroupRuntime g : kernel.groups()) {
                stateFiles += g.files().size();
            }
            RunLog.out("M1 B2 state_loaded phase=" + phase
                    + " groups=" + kernel.groups().size()
                    + " files=" + stateFiles);
            RunLog.out("M1 B2 join_input phase=" + phase + " tail8=" + tail8
                    + " manual=" + String.join(",", manual));

            GroupRuntime group;
            if (phase == 1) {
                group = kernel.joinByTail8(tail8, manual, Math.min(90_000L, WAIT_MS));
                if (group == null) {
                    fail("B2", "join_failed");
                    return;
                }
                RunLog.out("M1 B2 joined phase=1"
                        + " gid=" + group.getGroupId()
                        + " fp=" + group.getFingerprint()
                        + " members=" + group.members().size()
                        + " files=" + group.files().size());
            } else {
                // 重连不走 join 流程：双方都在彼此的成员表里，hello 通过后链路直接按群链路绑定
                // （PeerLink.onBound 的成员分支），内核自己发 peer.announce + sync.req，补差就发生
                // 在这条链路上。所以这里只把「拨号」丢到后台线程，成败不看 joinByTail8 的返回值 ——
                // 它等的是 join.ok，而这条路径上根本不会有 join.ok，硬等会白等一整轮候选超时。
                if (kernel.groups().isEmpty()) {
                    fail("B2", "no_group_after_restart");
                    return;
                }
                group = kernel.groups().get(0);
                final M1Kernel dialKernel = kernel;
                Thread dialer = new Thread(() -> {
                    GroupRuntime again = dialKernel.joinByTail8(tail8, manual, RECONNECT_DIAL_MS);
                    RunLog.out("M1 B2 redial_done phase=2 result=" + (again == null ? "no_join_flow" : "joined"));
                });
                dialer.setDaemon(true);
                dialer.setName("m1-b2-redial");
                dialer.start();
                RunLog.out("M1 B2 reconnect_start phase=2"
                        + " gid=" + group.getGroupId()
                        + " files=" + group.files().size());
            }

            long deadline = System.currentTimeMillis() + WAIT_MS;
            while (System.currentTimeMillis() < deadline && group.files().size() < expectFiles) {
                Thread.sleep(100);
            }
            int sigOk = 0;
            for (FileEntry entry : group.files()) {
                String sig = entry.getSig();
                if (sig != null && sig.length() == 128) {
                    sigOk++;
                }
            }
            boolean poisonPresent = !poisonId.isEmpty() && group.file(poisonId) != null;
            RunLog.out("M1 B2 catchup phase=" + phase
                    + " files=" + group.files().size()
                    + " expect=" + expectFiles
                    + " list_entries=" + events.fileListEntries()
                    + " broadcast_adds=" + events.fileAddBroadcasts()
                    + " rejected=" + events.fileListRejected()
                    + " rejected_bad_sig=" + events.fileListRejectedBadSig()
                    + " rejected_no_sig=" + events.fileListRejectedNoSig()
                    + " sig_ok=" + sigOk
                    + " poison_present=" + poisonPresent
                    + " poison_id=" + (poisonId.isEmpty() ? "none" : poisonId));
            if (group.files().size() < expectFiles) {
                fail("B2", "catchup_timeout files=" + group.files().size() + " expect=" + expectFiles);
                return;
            }
            RunLog.out("M1 B2 flow_done phase=" + phase);
            Thread.sleep(phase == 2 ? 2000 : 200);
            stopSampler();
            kernel.close();
            RunLog.out("RESULT=PASS");
            System.exit(0);
        } finally {
            stopSampler();
        }
    }

    // ------------------------------------------------------------------ A3 / B3：公开磁力端到端

    /**
     * A3：公开到群外（publishPublic）→ 等 B3 用纯磁力把同一份内容从**公开会话**下完。
     *
     * 与 A/B 那组的差别：这里下载方拿到的是一条 scope=public 的条目（只有 magnet、没有内联
     * .torrent），数据必须从 A3 的 PublicBtSession 走 BEP 9 元数据 + 公开 swarm 传过去。
     * 判据全部落在日志上：A3 的 public_publish 时刻、B3 的 public_metadata_arrived 时刻、
     * B3 的 bt_connect_peer/download_start 的 sess=pub、两端的 SHA-256。
     *
     * 证伪点（scripts/e2e-public.sh 的 E2E_MUTANT=publish_private）：把 publishPublic 建种时的
     * private 位翻回 1，公开会话按 BEP 27 拒种，A3 的 public_publish_done 变成 ok=false，
     * 场景当场 FAIL。
     */
    static void runPublishHost(Path workDir, Path dataDir, int msgPort, int btPort, Map<String, String> opts)
            throws Exception {
        String name = opts.getOrDefault("name", "A3_的笔记本");
        String groupName = opts.getOrDefault("group_name", "公开磁力验收群");
        RunLog.out("M1 A3 run_begin role=A3 run_id=" + RunLog.runId()
                + " mutant=" + mutantName()
                + " work_dir=" + clean(workDir.toString())
                + " data_dir=" + clean(dataDir.toString()));

        PubEvents events = new PubEvents("A3");
        M1Kernel kernel = new M1Kernel(dataDir.toFile(), name, msgPort, btPort, events);
        kernel.start();
        RunLog.out("M1 A3 bt_probe " + Probe.of(kernel).sessionLine());
        RunLog.out("M1 A3 net_ports msg_port=" + kernel.localMessagePort()
                + " bt_port=" + kernel.localBtPort());

        try {
            GroupRuntime group = kernel.createGroup(groupName);
            String code = group.getInviteCode();
            RunLog.out("M1 A3 invite_code=" + code);
            RunLog.out("M1 A3 invite_tail8=" + code.substring(8));
            RunLog.out("M1 A3 group_id=" + group.getGroupId() + " fp=" + group.getFingerprint());

            FileEntry priv = sharePayload("A3", group, workDir);
            RunLog.out("M1 A3 private_entry file_id=" + priv.getFileId()
                    + " scope=" + priv.getScope().getWire()
                    + " inline_torrent=" + (priv.getTorrent().isEmpty() ? "no" : "yes"));

            PublicPublishResult pub = kernel.publishPublic(group.getGroupId(), priv.getFileId());
            long publishDoneAt = System.currentTimeMillis();
            RunLog.out("M1 A3 public_publish_done ok=" + pub.getOk()
                    + " reason=" + pub.getReason()
                    + " private_file_id=" + priv.getFileId()
                    + " pub_infohash=" + pub.getFileId()
                    + " magnet=" + clean(pub.getMagnet())
                    + " ts=" + publishDoneAt
                    + " public_port=" + kernel.publicSessionPort()
                    + " public_torrents=" + kernel.publicTorrentCount());
            if (!pub.getOk()) {
                fail("A3", "publish_failed reason=" + pub.getReason());
                return;
            }
            FileEntry pubEntry = group.file(pub.getFileId());
            FileEntry privEntry = group.file(priv.getFileId());
            RunLog.out("M1 A3 files_state files=" + group.files().size()
                    + " private_file_id=" + (privEntry == null ? "none" : privEntry.getFileId())
                    + " private_scope=" + scopeOf(privEntry)
                    + " private_seeding=" + (group.localRole(priv.getFileId()) == GroupRuntime.LocalRole.SEEDING)
                    + " public_file_id=" + (pubEntry == null ? "none" : pubEntry.getFileId())
                    + " public_scope=" + scopeOf(pubEntry)
                    + " public_magnet=" + (pubEntry != null && !pubEntry.getMagnet().isEmpty() ? "yes" : "no")
                    + " public_inline_torrent=" + (pubEntry != null && !pubEntry.getTorrent().isEmpty() ? "yes" : "no"));

            if (!events.memberJoined.await(JOIN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                fail("A3", "member_join_timeout");
                return;
            }
            RunLog.out("M1 A3 member_joined dev=" + events.lastMemberDev
                    + " name=" + clean(events.lastMemberName)
                    + " ts=" + System.currentTimeMillis());

            if (!events.opReceived.await(WAIT_MS, TimeUnit.MILLISECONDS)) {
                fail("A3", "b_report_timeout");
                return;
            }
            RunLog.out("M1 A3 b_report body=" + clean(events.lastOpBody)
                    + " ts=" + System.currentTimeMillis());
            // B 已经发完评论，数据面不可能还在传大块；给上传计数 500 ms 落地时间。
            Thread.sleep(500);
            RunLog.out("M1 A3 public_probe " + Probe.ofPublic(kernel).torrentLine(pub.getFileId()));
            RunLog.out("M1 A3 private_probe " + Probe.of(kernel).torrentLine(priv.getFileId()));
            RunLog.out("M1 A3 sessions private_torrents=" + kernel.privateTorrentCount()
                    + " public_torrents=" + kernel.publicTorrentCount()
                    + " public_port=" + kernel.publicSessionPort());
            RunLog.out("M1 A3 flow_done");
            stopSampler();
            kernel.close();
            RunLog.out("RESULT=PASS");
            System.exit(0);
        } finally {
            stopSampler();
        }
    }

    /**
     * B3：只用后 8 位加入 → 从 A3 的公开条目拿到 magnet → 激活公开条目 → 等元数据从 A3 的
     * 公开会话过来 → 下载 → SHA-256。
     *
     * 关键口径：
     *  - **不调 addPublicMagnet 来下载**：该 API 对「群清单里已经有的 infohash」按 §3.6 判
     *    duplicate，群成员下载公开条目的生产路径是 GroupRuntime.startDownload（内部走
     *    PublicBtSession.addMagnet，同一条磁力路径）。这里先调一次 addPublicMagnet 断言
     *    duplicate，再走生产路径。
     *  - 元数据必须晚于 A3 的 public_publish，且 B3 在激活前没有 <data>/public/meta/<infohash>.torrent
     *    —— 证明元数据是刚通过公开 swarm 从 A3 取来的，不是本地缓存。
     *  - 传输面只认 sess=pub 的 bt_connect_peer / download_start；B3 的私有会话里不能有这个 torrent。
     */
    static void runPublicMagnetJoin(Path workDir, Path dataDir, int msgPort, int btPort, Map<String, String> opts)
            throws Exception {
        String tail8 = opts.get("tail8");
        if (tail8 == null || tail8.length() != 8) {
            fail("B3", "missing_tail8");
            return;
        }
        List<String> manual = new ArrayList<>();
        for (String part : opts.getOrDefault("manual", "").split(",")) {
            if (!part.trim().isEmpty()) {
                manual.add(part.trim());
            }
        }
        String name = opts.getOrDefault("name", "B3_的台式机");
        RunLog.out("M1 B3 run_begin role=B3 run_id=" + RunLog.runId()
                + " mutant=" + mutantName()
                + " work_dir=" + clean(workDir.toString())
                + " data_dir=" + clean(dataDir.toString()));

        PubEvents events = new PubEvents("B3");
        M1Kernel kernel = new M1Kernel(dataDir.toFile(), name, msgPort, btPort, events);
        kernel.start();
        RunLog.out("M1 B3 bt_probe " + Probe.of(kernel).sessionLine());
        RunLog.out("M1 B3 net_ports msg_port=" + kernel.localMessagePort()
                + " bt_port=" + kernel.localBtPort());

        try {
            RunLog.out("M1 B3 join_input tail8=" + tail8 + " manual=" + String.join(",", manual));
            GroupRuntime group = kernel.joinByTail8(tail8, manual, Math.min(90_000L, WAIT_MS));
            if (group == null) {
                fail("B3", "join_failed");
                return;
            }
            long joinedAt = System.currentTimeMillis();
            RunLog.out("M1 B3 joined gid=" + group.getGroupId()
                    + " fp=" + group.getFingerprint()
                    + " members=" + group.members().size()
                    + " files=" + group.files().size()
                    + " ts=" + joinedAt);

            FileEntry pub = waitForPublicEntry(group, WAIT_MS);
            if (pub == null) {
                fail("B3", "public_entry_not_seen");
                return;
            }
            String pubId = pub.getFileId();
            int privCount = 0;
            int pubCount = 0;
            for (FileEntry e : group.files()) {
                if (e.getScope() == FileScope.PUBLIC) {
                    pubCount++;
                } else {
                    privCount++;
                }
            }
            RunLog.out("M1 B3 public_entry_seen file_id=" + pubId
                    + " name=" + clean(pub.getName())
                    + " size=" + pub.getSize()
                    + " scope=" + pub.getScope().getWire()
                    + " magnet=" + (pub.getMagnet().isEmpty() ? "no" : "yes")
                    + " inline_torrent=" + (pub.getTorrent().isEmpty() ? "no" : "yes")
                    + " ts=" + System.currentTimeMillis());
            RunLog.out("M1 B3 entries files=" + group.files().size()
                    + " private=" + privCount + " public=" + pubCount);
            if (!pub.getMagnet().startsWith("magnet:?xt=urn:btih:" + pubId)) {
                fail("B3", "bad_magnet");
                return;
            }
            File meta = new File(new File(new File(dataDir.toFile(), "public"), "meta"), pubId + ".torrent");
            RunLog.out("M1 B3 pre_metadata_cache exists=" + meta.isFile()
                    + " path=" + clean(meta.getAbsolutePath()));

            // addPublicMagnet 对已在群清单里的 infohash 按 §3.6 判 duplicate：这是 API 口径，
            // 也说明群成员下载公开条目必须走 startDownload（内部同一条 PublicBtSession.addMagnet）。
            PublicMagnetResult dup = kernel.addPublicMagnet(group.getGroupId(), pub.getMagnet(), null, 1000);
            RunLog.out("M1 B3 add_public_magnet_probe ok=" + dup.getOk()
                    + " reason=" + dup.getReason() + " expect=duplicate");

            long portDeadline = System.currentTimeMillis() + 30_000L;
            while (events.aPubBtPort.get() <= 0 && System.currentTimeMillis() < portDeadline) {
                Thread.sleep(100);
            }
            RunLog.out("M1 B3 a_pub_bt_port port=" + events.aPubBtPort.get()
                    + " announces=" + events.pubPortAnnounces.get());

            boolean started = group.startDownload(pubId);
            RunLog.out("M1 B3 activation ok=" + started + " file_id=" + pubId
                    + " ts=" + System.currentTimeMillis());
            if (!started) {
                fail("B3", "download_not_started");
                return;
            }

            long deadline = System.currentTimeMillis() + WAIT_MS;
            while (events.metadataAt.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
            FileEntry afterMeta = group.file(pubId);
            RunLog.out("M1 B3 metadata_arrived ts=" + events.metadataAt.get()
                    + " size=" + (afterMeta == null ? -1 : afterMeta.getSize())
                    + " piece_size=" + (afterMeta == null ? -1 : afterMeta.getPieceSize())
                    + " cache_exists=" + meta.isFile()
                    + " cache_mtime=" + meta.lastModified());
            if (events.metadataAt.get() == 0) {
                RunLog.out("M1 B3 transfer_stats " + Probe.ofPublic(kernel).torrentLine(pubId));
                fail("B3", "metadata_timeout");
                return;
            }

            long dlStart = System.currentTimeMillis();
            int lastPct = -1;
            int maxPeers = 0;
            boolean complete = false;
            while (System.currentTimeMillis() < deadline) {
                int peers = group.peerCount(pubId);
                if (peers > maxPeers) {
                    maxPeers = peers;
                }
                int pct = (int) (group.progress(pubId) * 100.0);
                if (pct != lastPct) {
                    RunLog.out("M1 B3 progress pct=" + pct);
                    lastPct = pct;
                }
                if (group.isComplete(pubId)) {
                    complete = true;
                    break;
                }
                Thread.sleep(100);
            }
            RunLog.out("M1 B3 transfer_peers max=" + maxPeers
                    + " elapsed_ms=" + (System.currentTimeMillis() - dlStart));
            if (!complete) {
                RunLog.out("M1 B3 transfer_stats " + Probe.ofPublic(kernel).torrentLine(pubId));
                fail("B3", "download_timeout");
                return;
            }
            File saved = group.savedFile(pubId);
            if (saved == null || !saved.isFile()) {
                RunLog.out("M1 B3 transfer_stats " + Probe.ofPublic(kernel).torrentLine(pubId));
                fail("B3", "download_file_missing");
                return;
            }
            String sha = sha256(saved);
            RunLog.out("M1 B3 download_done file_id=" + pubId
                    + " bytes=" + saved.length()
                    + " sha256=" + sha
                    + " saved_path=" + clean(saved.getAbsolutePath())
                    + " elapsed_ms=" + (System.currentTimeMillis() - dlStart));
            RunLog.out("M1 B3 transfer_stats " + Probe.ofPublic(kernel).torrentLine(pubId));
            RunLog.out("M1 B3 private_probe " + Probe.of(kernel).torrentLine(pubId));
            RunLog.out("M1 B3 sessions private_torrents=" + kernel.privateTorrentCount()
                    + " public_torrents=" + kernel.publicTorrentCount()
                    + " public_port=" + kernel.publicSessionPort());
            RunLog.out("M1 B3 counters pub_dials=" + events.pubDials.get()
                    + " priv_dials=" + events.privDials.get()
                    + " pub_downloads=" + events.pubDownloads.get()
                    + " priv_downloads=" + events.privDownloads.get());

            OpRecord op = group.comment(pubId, "e2e-public sha256=" + sha, null);
            RunLog.out("M1 B3 comment_sent op_id=" + op.getOpId()
                    + " body=" + clean(op.getBody())
                    + " ts=" + System.currentTimeMillis());
            Thread.sleep(500);
            RunLog.out("M1 B3 flow_done");
            stopSampler();
            kernel.close();
            RunLog.out("RESULT=PASS");
            System.exit(0);
        } finally {
            stopSampler();
        }
    }

    /** 等 B3 清单里出现第一条 scope=public 的条目（join.ok 快照 / file.add 两条路都算）。 */
    static FileEntry waitForPublicEntry(GroupRuntime group, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (FileEntry entry : group.files()) {
                if (entry.getScope() == FileScope.PUBLIC) {
                    return entry;
                }
            }
            Thread.sleep(100);
        }
        return null;
    }

    static String scopeOf(FileEntry entry) {
        return entry == null ? "none" : entry.getScope().getWire();
    }

    /** 造一条「签名对不上」的条目：fileId 与 name 都改过，sig 仍是被顶替那条 file.add 的原签名。 */
    static FileEntry poisonedEntry(GroupRuntime group, FileEntry base) {
        String poisonId = "";
        for (int round = 0; round < 8 && poisonId.isEmpty(); round++) {
            String candidate = flipHex(base.getFileId(), round);
            if (!candidate.isEmpty() && group.file(candidate) == null) {
                poisonId = candidate;
            }
        }
        if (poisonId.isEmpty()) {
            throw new IllegalStateException("cannot forge a distinct file id from " + base.getFileId());
        }
        return new FileEntry(
                poisonId,
                "poisoned-" + base.getName(),
                base.getSize(),
                base.getPieceSize(),
                base.getAddedBy(),
                base.getAddedAt(),
                "magnet:?xt=urn:btih:" + poisonId,
                "",
                base.getSig());
    }

    /** 把倒数第 round+1 个字符换个 0/1，产出同一个信息哈希长度、但不等于任何真实条目的 fileId。 */
    static String flipHex(String hex, int round) {
        if (hex == null || hex.isEmpty()) {
            return "";
        }
        char[] chars = hex.toCharArray();
        int idx = chars.length - 1 - (round % chars.length);
        chars[idx] = chars[idx] == '0' ? '1' : '0';
        return new String(chars);
    }

    /**
     * 注入点：GroupRuntime 的 internal fun restoreFile 在 JVM 上叫 restoreFile$<模块名>（默认 core）。
     * 反射调用；名字对不上就抛，让用例当场 FAIL，不静默跳过。
     */
    static void restoreFileInternal(GroupRuntime group, FileEntry entry) {
        for (java.lang.reflect.Method method : group.getClass().getMethods()) {
            if (method.getName().startsWith("restoreFile$") && method.getParameterCount() == 1) {
                try {
                    method.invoke(group, entry);
                    return;
                } catch (Exception e) {
                    throw new IllegalStateException("harness injection failed: " + e, e);
                }
            }
        }
        throw new IllegalStateException(
                "harness injection point missing: GroupRuntime.restoreFile$<module> not found");
    }

    /**
     * 等 peersChanged / peer_announce 这类「成员上线」回调累计到 want 次。
     *
     * 用计数而不是一次性 latch：同一个进程里内核会重建（B 的冷启动），
     * latch 在第一轮就被消费掉了，第二轮会立刻「通过」——那是假绿。
     */
    static boolean awaitPeerOnlineEvents(Events events, long want, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (events.peerOnlineEvents() >= want) {
                return true;
            }
            Thread.sleep(50);
        }
        return events.peerOnlineEvents() >= want;
    }

    /** 等群内在线成员数是否为空变成期望状态；超时不抛，由调用方判 FAIL 并说清原因。 */
    static boolean waitPeerState(GroupRuntime group, boolean wantOnline, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if ((!group.onlinePeers().isEmpty()) == wantOnline) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    /**
     * 轮询到期望的做种数。完成判定与做种集合的推进在巡查线程（2 s 一拍），所以下载完成后
     * 读数不是立刻变的；超时返回最后一次读数，由调用方判 FAIL 并说清期望值。
     */
    static int awaitSeedingCount(GroupRuntime group, int expect, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int value = group.seedingFileCount();
        while (value != expect && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            value = group.seedingFileCount();
        }
        return value;
    }

    /**
     * 测试用：把一条正在做种的 torrent 从会话里摘掉，模拟「本机数据没了」，不碰磁盘文件。
     *
     * Windows 上做种中的文件被 libtorrent 占着句柄、删不掉，所以「文件没了 → 移出做种集合」这条只能用
     * 最近的同源路径证明：降级动作（GroupRuntime.demote）是同一段代码。反射取 BtSession —— core 没有把
     * 这个动作暴露成公开 API，harness 一贯用反射（见 Probe）。
     */
    static boolean detachTorrent(Object kernel, String fileId) {
        try {
            Object bt = kernelField(kernel, "bt");
            java.lang.reflect.Method m = bt.getClass().getMethod("removeTorrent", String.class);
            return Boolean.TRUE.equals(m.invoke(bt, fileId));
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object kernelField(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** 重启后从 state.json 恢复出来的那个群；恢复不出来就当场 FAIL，不静默跳过。 */
    static GroupRuntime firstGroup(M1Kernel kernel, String role) {
        if (kernel.groups().isEmpty()) {
            fail(role, "no_group_after_restart");
        }
        return kernel.groups().get(0);
    }

    static int optInt(Map<String, String> opts, String key, int def) {
        String raw = opts.get(key);
        if (raw == null || raw.trim().isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 生成/复用 32 MiB 载荷 → 建 v1 私有种 → 共享（做种并广播 file.add）。 */
    static FileEntry sharePayload(GroupRuntime group, Path workDir) throws Exception {
        return sharePayload("A", group, workDir);
    }

    /** 同一段共享逻辑，日志角色可换（A3 公开磁力验收用）。 */
    static FileEntry sharePayload(String role, GroupRuntime group, Path workDir) throws Exception {
        Path shareDir = workDir.resolve("share");
        Files.createDirectories(shareDir);
        File payload = shareDir.resolve("payload-32m.bin").toFile();
        if (!payload.isFile() || payload.length() != FILE_SIZE) {
            createRandomFile(payload, FILE_SIZE);
        }
        String sha = sha256(payload);
        RunLog.out("M1 " + role + " file_sha256=" + sha + " file_bytes=" + payload.length());

        FileEntry entry = group.addFile(payload);
        RunLog.out("M1 " + role + " file_shared file_id=" + entry.getFileId()
                + " name=" + clean(entry.getName())
                + " size=" + entry.getSize()
                + " piece_size=" + entry.getPieceSize());
        return entry;
    }

    // ------------------------------------------------------------------ 工具

    static boolean dhtOnMutant() {
        return "dht_on".equals(MUTANT);
    }

    static String mutantName() {
        return MUTANT.isEmpty() ? "none" : MUTANT;
    }

    static void stopSampler() {
        Sampler s = sampler;
        if (s != null) {
            s.stop();
        }
    }

    static FileEntry waitForFile(GroupRuntime group, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<FileEntry> files = group.files();
            if (!files.isEmpty()) {
                return files.get(0);
            }
            if (FILE_SEEN.await(200, TimeUnit.MILLISECONDS)) {
                List<FileEntry> now = group.files();
                if (!now.isEmpty()) {
                    return now.get(0);
                }
            }
        }
        return lastFile;
    }

    static void fail(String role, String reason) {
        RunLog.out("M1 " + role + " FAIL reason=" + reason);
        stopSampler();
        RunLog.out("RESULT=FAIL");
        System.out.flush();
        System.exit(1);
    }

    static void createRandomFile(File file, long size) throws IOException {
        file.getParentFile().mkdirs();
        Random random = new Random(20261106L);
        byte[] buffer = new byte[1 << 20];
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            long remaining = size;
            while (remaining > 0) {
                int n = (int) Math.min(buffer.length, remaining);
                random.nextBytes(buffer);
                raf.write(buffer, 0, n);
                remaining -= n;
            }
        }
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

    static int parseIntOr(String raw, int def) {
        if (raw == null || raw.trim().isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\s]+", "_");
    }

    static long longProp(String key, long def) {
        try {
            String raw = System.getProperty(key);
            return raw == null || raw.trim().isEmpty() ? def : Long.parseLong(raw.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    // ------------------------------------------------------------------ DHT 采样

    /** 定期直读 libtorrent 会话：dht_nodes / dht_running / enable_dht 三项各采一条。 */
    static final class Sampler {
        private final String role;
        private final Probe probe;
        private final long intervalMs;
        private volatile boolean running = true;
        private Thread thread;
        private int seq;

        Sampler(String role, Probe probe, long intervalMs) {
            this.role = role;
            this.probe = probe;
            this.intervalMs = intervalMs;
        }

        Sampler start() {
            sample();
            thread = new Thread(() -> {
                while (running) {
                    try {
                        Thread.sleep(intervalMs);
                    } catch (InterruptedException e) {
                        break;
                    }
                    if (!running) {
                        break;
                    }
                    sample();
                }
            });
            thread.setDaemon(true);
            thread.setName("m1-dht-sampler-" + role);
            thread.start();
            return this;
        }

        private void sample() {
            int i;
            synchronized (this) {
                i = ++seq;
            }
            String body;
            try {
                body = probe.sampleLine();
            } catch (Throwable t) {
                body = "source=unavailable error=" + clean(t.toString());
            }
            RunLog.out("M1 " + role + " dht_sample seq=" + i + " " + body);
        }

        void stop() {
            running = false;
            Thread t = thread;
            if (t != null) {
                t.interrupt();
                try {
                    t.join(1500);
                } catch (InterruptedException ignored) {
                    // 退出路径上不再纠缠
                }
            }
        }
    }

    // ------------------------------------------------------------------ 事件出口

    static final class Events extends KernelEvents {
        private final String role;
        /** op_received 要用内核的跨设备时钟口径算时延，构造后再挂上（Events 先于 kernel 创建）。 */
        private volatile M1Kernel kernel;

        /**
         * 补差用例的分流计数：内核在广播路径打 file_add、在补差路径（join.ok 快照 / file.list）打
         * file_list_entry / file_list_rejected。相同的条目走哪条路进来，靠这两个独立的计数区分，
         * 不靠时间窗猜。
         */
        private final java.util.concurrent.atomic.AtomicInteger fileListEntries =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger fileAddBroadcasts =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger fileListRejected =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger fileListRejectedBadSig =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger fileListRejectedNoSig =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicReference<String> lastRejectedFileId =
                new java.util.concurrent.atomic.AtomicReference<>("");
        /** memberJoined 那一刻本群的清单长度：join.ok 快照是否该置空（§10 >32 条）就看它。 */
        volatile int memberJoinedFiles = -1;

        // ---- 缺口一：成员上线的次数与「重连之后对端自报了什么」 ----

        /**
         * peersChanged(online=true) 的次数。第一次是加入时那条链路，第二次往后才是「重启后连回来」。
         * 用它做判据而不是「在线成员非空」：后者在重启瞬间可能还留着上一轮的值。
         */
        private final java.util.concurrent.atomic.AtomicInteger peerOnlineEvents =
                new java.util.concurrent.atomic.AtomicInteger();

        /** 第 2 次（含）之后收到的 peer.announce 条数：重连之后对端补报平台与做种数靠它。 */
        private final java.util.concurrent.atomic.AtomicInteger announcesAfterRebind =
                new java.util.concurrent.atomic.AtomicInteger();

        private final java.util.concurrent.atomic.AtomicReference<String> reboundDev =
                new java.util.concurrent.atomic.AtomicReference<>("");
        private final java.util.concurrent.atomic.AtomicReference<String> reboundPlat =
                new java.util.concurrent.atomic.AtomicReference<>("");
        private volatile int reboundSeeds = -1;

        // ---- 缺口二：完成事件到达的时刻与它走的哪条路 ----

        private final java.util.concurrent.atomic.AtomicLong fileCompletedAtMs =
                new java.util.concurrent.atomic.AtomicLong(0L);
        private final java.util.concurrent.atomic.AtomicReference<String> fileCompletedSource =
                new java.util.concurrent.atomic.AtomicReference<>("");

        int peerOnlineEvents() {
            return peerOnlineEvents.get();
        }

        int announcesAfterRebind() {
            return announcesAfterRebind.get();
        }

        String reboundDev() {
            return reboundDev.get();
        }

        String reboundPlat() {
            return reboundPlat.get();
        }

        int reboundSeeds() {
            return reboundSeeds;
        }

        long fileCompletedAtMs() {
            return fileCompletedAtMs.get();
        }

        String fileCompletedSource() {
            return fileCompletedSource.get();
        }

        Events(String role) {
            this.role = role;
        }

        void attach(M1Kernel kernel) {
            this.kernel = kernel;
        }

        int fileListEntries() {
            return fileListEntries.get();
        }

        int fileAddBroadcasts() {
            return fileAddBroadcasts.get();
        }

        int fileListRejected() {
            return fileListRejected.get();
        }

        int fileListRejectedBadSig() {
            return fileListRejectedBadSig.get();
        }

        int fileListRejectedNoSig() {
            return fileListRejectedNoSig.get();
        }

        String lastRejectedFileId() {
            return lastRejectedFileId.get();
        }

        @Override
        public void peersChanged(GroupRuntime group, String deviceId, boolean online) {
            if (online) {
                peerOnlineEvents.incrementAndGet();
            }
            // 在线成员数一起打出来：P6 刷新读的就是这几个数。
            log("peers_changed", Map.of(
                    "dev", deviceId,
                    "online", String.valueOf(online),
                    "gid", group == null ? "" : group.getGroupId(),
                    "online_peers", String.valueOf(group == null ? -1 : group.onlinePeers().size())));
        }

        @Override
        public void log(String event, Map<String, String> fields) {
            if ("download_completed".equals(event)) {
                // 完成事件走的哪条路（alert = 告警当场 / sweep = 200 ms 兜底 / monitor = 2 s 巡查）
                fileCompletedSource.set(fields.getOrDefault("source", ""));
            }
            if ("peer_announce".equals(event) && peerOnlineEvents.get() >= 2) {
                // 重连之后（第 2 次及以后的上线）对端补报的平台与做种数：
                // 「A 侧看到 B 的 plat/seeds 恢复」就是这里读到的值，不再靠界面截图。
                announcesAfterRebind.incrementAndGet();
                reboundDev.set(fields.getOrDefault("dev", ""));
                reboundPlat.set(fields.getOrDefault("plat", ""));
                reboundSeeds = parseIntOr(fields.get("seeds"), -1);
            }
            if ("file_list_entry".equals(event)) {
                fileListEntries.incrementAndGet();
            } else if ("file_add".equals(event)) {
                fileAddBroadcasts.incrementAndGet();
            } else if ("file_list_rejected".equals(event)) {
                fileListRejected.incrementAndGet();
                String reason = fields.get("reason");
                if ("bad_sig".equals(reason)) {
                    fileListRejectedBadSig.incrementAndGet();
                } else if ("no_sig".equals(reason)) {
                    fileListRejectedNoSig.incrementAndGet();
                }
                String fileId = fields.get("file_id");
                if (fileId != null && !fileId.isEmpty()) {
                    lastRejectedFileId.set(fileId);
                }
            }
            StringBuilder sb = new StringBuilder("M1 ");
            sb.append(role).append(' ').append(event);
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

        @Override
        public void memberJoined(GroupRuntime group, String deviceId, String name) {
            if (group != null) {
                memberJoinedFiles = group.files().size();
            }
            log("member_joined", Map.of("dev", deviceId, "name", name));
            MEMBER_JOINED.countDown();
        }

        @Override
        public void fileAdded(GroupRuntime group, FileEntry entry) {
            lastFile = entry;
            log("file_add_event", Map.of(
                    "file_id", entry.getFileId(),
                    "size", String.valueOf(entry.getSize())));
            FILE_SEEN.countDown();
        }

        @Override
        public void fileCompleted(GroupRuntime group, FileEntry entry) {
            // 回调进来的这一刻就是「完成判定当场」：脚本 / 内核日志用它量缺口二修好了没有。
            fileCompletedAtMs.compareAndSet(0L, System.currentTimeMillis());
            log("file_completed", Map.of("file_id", entry.getFileId()));
            FILE_COMPLETED.countDown();
        }

        @Override
        public void opReceived(GroupRuntime group, OpRecord op) {
            lastOp = op;
            // 时延必须只用本机时钟：op.ts 是对端墙钟，直接减本机墙钟在时钟不同步时会变成负数
            // （协议里 ts 只用于展示，排序走 lamport/author/seq）。
            long recvAt = System.currentTimeMillis();
            M1Kernel k = kernel;
            long latency = k == null ? 1L : k.latencyMillis(op.getAuthor(), op.getTs(), recvAt);
            log("op_received", Map.of(
                    "op_id", op.getOpId(),
                    "author", op.getAuthor(),
                    "seq", String.valueOf(op.getSeq()),
                    "latency_ms", String.valueOf(latency),
                    "recv_at", String.valueOf(recvAt),
                    "body", op.getBody()));
            COMMENT_RECEIVED.countDown();
        }
    }

    // ------------------------------------------------------------------ 公开磁力验收的事件出口

    /**
     * A3/B3 专用的事件出口：在既有 Events 之外补三样东西 ——
     *  1. 每一行带 `ts=<本机毫秒>`：元数据到达与 A3 发布完成的先后顺序只按本机时钟比；
     *  2. 只数 `bt_connect_peer` / `download_start` 的 sess=pub|priv：传输面走哪套会话是断言本身；
     *  3. peer.announce 里 A3 的 pubBtPort：B3 的公开拨号目标。
     *
     * 不改 Events 的输出格式：scripts/m1-headless.sh 的 grep 口径保持原样。
     */
    static final class PubEvents extends KernelEvents {
        private final String role;
        final CountDownLatch memberJoined = new CountDownLatch(1);
        final CountDownLatch opReceived = new CountDownLatch(1);
        final AtomicLong metadataAt = new AtomicLong(0);
        final AtomicInteger pubDials = new AtomicInteger();
        final AtomicInteger privDials = new AtomicInteger();
        final AtomicInteger pubDownloads = new AtomicInteger();
        final AtomicInteger privDownloads = new AtomicInteger();
        final AtomicInteger pubPortAnnounces = new AtomicInteger();
        final AtomicInteger aPubBtPort = new AtomicInteger(0);
        volatile String lastMemberDev = "";
        volatile String lastMemberName = "";
        volatile String lastOpBody = "";
        volatile long fileCompletedAt = 0L;

        PubEvents(String role) {
            this.role = role;
        }

        @Override
        public void log(String event, Map<String, String> fields) {
            long now = System.currentTimeMillis();
            if ("public_metadata_arrived".equals(event)
                    || "pub_magnet_metadata_received".equals(event)) {
                metadataAt.compareAndSet(0L, now);
            } else if ("bt_connect_peer".equals(event)) {
                String sess = fields.getOrDefault("sess", "");
                if ("pub".equals(sess)) {
                    pubDials.incrementAndGet();
                } else if ("priv".equals(sess)) {
                    privDials.incrementAndGet();
                }
            } else if ("download_start".equals(event)) {
                String sess = fields.getOrDefault("sess", "");
                if ("pub".equals(sess)) {
                    pubDownloads.incrementAndGet();
                } else if ("priv".equals(sess)) {
                    privDownloads.incrementAndGet();
                }
            } else if ("peer_announce".equals(event)) {
                int port = parseIntOr(fields.get("pub_bt_port"), 0);
                if (port > 0) {
                    aPubBtPort.set(port);
                    pubPortAnnounces.incrementAndGet();
                }
            }
            StringBuilder sb = new StringBuilder("M1 ");
            sb.append(role).append(' ').append(event);
            for (Map.Entry<String, String> e : fields.entrySet()) {
                String v = e.getValue();
                if (v == null || v.isEmpty()) {
                    continue;
                }
                sb.append(' ').append(e.getKey()).append('=').append(clean(v));
            }
            sb.append(" ts=").append(now);
            System.out.println(sb);
            System.out.flush();
        }

        @Override
        public void memberJoined(GroupRuntime group, String deviceId, String name) {
            lastMemberDev = deviceId == null ? "" : deviceId;
            lastMemberName = name == null ? "" : name;
            log("member_joined", Map.of("dev", lastMemberDev, "name", lastMemberName));
            memberJoined.countDown();
        }

        @Override
        public void fileAdded(GroupRuntime group, FileEntry entry) {
            log("file_add_event", Map.of(
                    "file_id", entry.getFileId(),
                    "scope", entry.getScope().getWire(),
                    "size", String.valueOf(entry.getSize())));
        }

        @Override
        public void fileCompleted(GroupRuntime group, FileEntry entry) {
            log("file_completed", Map.of("file_id", entry.getFileId()));
        }

        @Override
        public void opReceived(GroupRuntime group, OpRecord op) {
            lastOpBody = op.getBody() == null ? "" : op.getBody();
            log("op_received", Map.of(
                    "op_id", op.getOpId(),
                    "author", op.getAuthor(),
                    "seq", String.valueOf(op.getSeq()),
                    "body", lastOpBody));
            opReceived.countDown();
        }
    }
}
