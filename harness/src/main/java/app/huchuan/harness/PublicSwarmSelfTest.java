package app.huchuan.harness;

import app.huchuan.core.FileScope;
import app.huchuan.core.m1.AlertTypeCompat;
import app.huchuan.core.m1.BtSession;
import app.huchuan.core.m1.GroupRuntime;
import app.huchuan.core.m1.KernelEvents;
import app.huchuan.core.m1.M1Kernel;
import app.huchuan.core.m1.FileEntry;
import app.huchuan.core.m1.FileEntryMerge;
import app.huchuan.core.m1.Json;
import app.huchuan.core.m1.MagnetLink;
import app.huchuan.core.m1.PublicBtSession;
import app.huchuan.core.m1.PublicMagnetResult;
import app.huchuan.core.m1.SessionRoute;

import org.libtorrent4j.TorrentBuilder;
import org.libtorrent4j.TorrentFlags;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.Alerts;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * 公开种子社区（public-swarm）的内核自检。五组断言，每组都有一个能把它打红的现实缺陷：
 *
 *   1. 会话参数（[sessions]）：公开会话 DHT 开、LSD 关、uTP 开、端口 >= 6881；私有会话 DHT 关。
 *      打红方式：把 PublicBtSession 的 settings 改成和私有会话一样，或者把端口起点改成 51413。
 *   2. per-torrent flags：公开侧显式清零（PEX 位必须是 0）、私有侧 0x380000。
 *      打红方式：公开会话里复用 BtSession.PRIVATE_FLAGS —— 公开种子当场失去 DHT/PEX。
 *   3. 会话归属（[routing]）：按 TorrentInfo.isPrivate 选会话，公开会话拒绝 private 种子，
 *      私有会话拒绝 public 种子。打红方式：去掉任何一侧的 accepts 校验。
 *   4. 磁力导入（[magnet]）：磁力加种进的是**公开**会话（不是私有会话）、重复判重、非法链接一句话、
 *      五种失败各有一句不同的话。打红方式：把 addMagnet 的会话换成 kernel.bt，或者让失败抛异常。
 *   5. 去重与零迁移（[entries]）：同一 infohash 两种 scope 到达时只留 private，正反两种顺序各一遍；
 *      旧 JSON（没有 scope 字段）读回来是 private，写出时带 scope。打红方式：把 scope 当必填字段，
 *      或者去掉「public 让位 private」那一条。
 */
final class PublicSwarmSelfTest {

    private PublicSwarmSelfTest() {
    }

    /** 一个 20 字节的假 infohash（打红不留空白，断言只关心形式）。 */
    private static String fakeInfoHash(int seed) {
        byte[] raw = new byte[20];
        new Random(seed).nextBytes(raw);
        StringBuilder sb = new StringBuilder(40);
        for (byte b : raw) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 本组自己的计数：SelfTest 的 check 只在失败时出声，这里补一行可 grep 的正向证据。 */
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
        File root = new File(System.getProperty("java.io.tmpdir"), "huchuan-selftest-public-" + System.nanoTime());
        root.mkdirs();
        kernelMagnet(check);
        BtSession priv = null;
        PublicBtSession pub = null;
        try {
            priv = new BtSession(0, new File(root, "bt"), true);
            int privPort = priv.start();
            pub = new PublicBtSession(new File(root, "public"), PublicBtSession.DEFAULT_PORT, null);
            int pubPort = pub.start();
            sessions(check, priv, privPort, pub, pubPort);
            alerts(check, pub);
            flags(check, priv, pub);
            routing(check, root, priv, pub);
            magnet(check, root, priv, pub);
        } catch (Throwable t) {
            check.accept("public.exception (" + t + ")", false);
            t.printStackTrace(System.out);
        } finally {
            if (pub != null) {
                try {
                    pub.stop();
                } catch (Throwable ignored) {
                    // 停不掉不影响断言结论
                }
            }
            if (priv != null) {
                try {
                    priv.stop();
                } catch (Throwable ignored) {
                    // 同上
                }
            }
        }
        entries(check);
        deleteTree(root);
        // 正向证据（可 grep）：这一组断言了几条、红了几条。RESULT=PASS 与它必须同时成立。
        System.out.println("selftest.public checks=" + COUNTS[0] + " failures=" + COUNTS[1]);
    }

    // ---------------------------------------------------------------- 0. 内核路径：磁力导入走公开会话

    /**
     * `kernel.addPublicMagnet` 的端到端路径（真起一个内核 + 真建一个群）。
     *
     * 证伪点：把 kernel 里那次加种换成 `bt`（私有会话）—— `public_torrent_count` 会变 0、
     * `private_torrent_count` 会变 1，`pub_magnet_added` 日志也不会出现。
     */
    private static void kernelMagnet(BiConsumer<String, Boolean> check) {
        File dir = new File(System.getProperty("java.io.tmpdir"), "huchuan-selftest-pubkernel-" + System.nanoTime());
        dir.mkdirs();
        List<String> events = new CopyOnWriteArrayList<>();
        Map<String, String> lastMagnetEvent = new ConcurrentHashMap<>();
        M1Kernel kernel = null;
        try {
            KernelEvents sink = new KernelEvents() {
                @Override
                public void log(String event, Map<String, String> fields) {
                    events.add(event);
                    if ("public_magnet".equals(event)) {
                        lastMagnetEvent.putAll(fields);
                    }
                }
            };
            kernel = new M1Kernel(dir, "public-selftest", 47350, 53313, sink);
            kernel.start();
            GroupRuntime group = kernel.createGroup("公开种子自检群");

            String hash = fakeInfoHash(53);
            String magnet = "magnet:?xt=urn:btih:" + hash + "&dn=kernel-selftest.bin&xl=4096";
            PublicMagnetResult result = kernel.addPublicMagnet(group.getGroupId(), magnet, "内核磁力", 900);

            check.accept("public.kernel_magnet_public_torrent (" + result.getReason() + ")",
                    kernel.publicTorrentCount() == 1);
            check.accept("public.kernel_magnet_not_in_private_session (private="
                            + kernel.privateTorrentCount() + ")",
                    kernel.privateTorrentCount() == 0);
            check.accept("public.kernel_magnet_public_port (port=" + kernel.publicSessionPort() + ")",
                    kernel.getPublicSessionRunning() && kernel.publicSessionPort() >= PublicBtSession.DEFAULT_PORT);
            check.accept("public.kernel_magnet_event", events.contains("pub_magnet_added"));
            check.accept("public.kernel_magnet_entry (scope="
                            + (result.getEntry() == null ? "none" : result.getEntry().getScope()) + ")",
                    result.getEntry() != null
                            && result.getEntry().getScope() == FileScope.PUBLIC
                            && result.getEntry().getFileId().equals(hash)
                            && !result.getEntry().getMagnet().isEmpty()
                            && result.getEntry().getTorrent().isEmpty());
            check.accept("public.kernel_magnet_entry_broadcast", events.contains("file_public_added"));
            check.accept("public.kernel_magnet_awaiting_source (reason=" + result.getReason() + ")",
                    !result.getOk()
                            && (PublicMagnetResult.NO_SOURCE.equals(result.getReason())
                            || PublicMagnetResult.TIMEOUT.equals(result.getReason()))
                            && PublicMagnetResult.messageFor(result.getReason()).equals(result.getMessage()));
            check.accept("public.kernel_magnet_log_fields",
                    hash.equals(lastMagnetEvent.get("file_id"))
                            && lastMagnetEvent.containsKey("port")
                            && lastMagnetEvent.containsKey("torrents"));
            // 同群内按 fileId 去重：第二次导入同一个 infohash 直接判重复，不再加第二个 torrent。
            PublicMagnetResult again = kernel.addPublicMagnet(group.getGroupId(), magnet, null, 200);
            check.accept("public.kernel_magnet_duplicate (" + again.getReason() + ")",
                    PublicMagnetResult.DUPLICATE.equals(again.getReason())
                            && kernel.publicTorrentCount() == 1);
        } catch (Throwable t) {
            check.accept("public.kernel_exception (" + t + ")", false);
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

    // ---------------------------------------------------------------- 1. 会话参数

    private static void sessions(BiConsumer<String, Boolean> check, BtSession priv, int privPort,
                                 PublicBtSession pub, int pubPort) {
        check.accept("public.session_started (port=" + pubPort + ")", pubPort > 0);
        // 端口段：6881 起顺延 100，绝不落到私有段（51413 起）。
        check.accept("public.port_ge_6881 (port=" + pubPort + ")",
                pubPort >= PublicBtSession.DEFAULT_PORT
                        && pubPort < PublicBtSession.DEFAULT_PORT + PublicBtSession.PORT_TRIES);
        check.accept("public.private_port_not_6881 (port=" + privPort + ")",
                privPort > 0 && privPort != pubPort);
        // DHT：公开开、私有关。两个方向都要能证伪。
        check.accept("public.dht_on", pub.enableDhtSetting());
        // DHT 节点是会话启动之后才起来的，给最多 5 s；settings 里 enable_dht=false 时它永远不会变真，
        // 所以这条断言照样能证伪（把公开会话的 settings 换回私有那套，这里一定红）。
        boolean dhtRunning = waitUntil(pub::isDhtRunning, 5000);
        System.out.println("selftest.info public.dht_running=" + dhtRunning + " nodes=" + pub.dhtNodes());
        check.accept("public.dht_running (nodes=" + pub.dhtNodes() + ")", dhtRunning);
        check.accept("public.private_dht_off", !priv.enableDhtSetting());
        check.accept("public.private_dht_not_running", !priv.isDhtRunning());
        // LSD 关、uTP 开：公开侧写死（public-swarm §2.1/§2.3）。
        check.accept("public.lsd_off", !pub.enableLsdSetting());
        check.accept("public.utp_in_and_out_on", pub.enableIncomingUtp() && pub.enableOutgoingUtp());
        // 数据目录：<data>/public/data，元数据缓存 <data>/public/meta，不与私有 <data>/bt 混用。
        check.accept("public.data_dir_is_public",
                "data".equals(pub.getDataDir().getName())
                        && pub.getDataDir().getParentFile() != null
                        && "public".equals(pub.getDataDir().getParentFile().getName()));
        check.accept("public.meta_dir_is_public",
                "meta".equals(pub.getMetaDir().getName())
                        && pub.getMetaDir().getParentFile() != null
                        && "public".equals(pub.getMetaDir().getParentFile().getName()));
    }

    // ---------------------------------------------------------------- 1.5 告警类型映射表补洞 + 收敛

    /**
     * 公开会话开 DHT 后「收一段时间告警不把进程带走」的回归断言（对应真机 FATAL：
     * `SessionManager-alertsLoop` 里 NPE 在 `AlertType.ordinal()`）。
     *
     * 三件事各有一个能把它打红的现实缺陷：
     *
     * 1. **补洞本身**：拿一张合成的 108 槽表（105/106/107 是空洞，等价于 Android 自编 .so 的形状）
     *    过一遍 [AlertTypeCompat.fillTable]，要求恰好补这 3 个槽且补成 UNKNOWN。
     *    打红方式：把 fillTable 改成不填 / 填错值。
     * 2. **真会话的表**：native 报几种告警，`AlertType.fromSwig(i)` 就必须在这几个下标上都非 null。
     *    打红方式：Windows 上这条恒真（native 报 105、没有空洞），所以 Android 上跑才有杀伤力；
     *    真正能跨平台打红的是第 1 条。
     * 3. **公开会话实际下发的 mask**：不能含 ip_block（崩溃那条 `ip_ban_alert` 的类别），
     *    但必须含 status / storage / error（会话自己要用的那几类）。
     *    打红方式：去掉 applyPublicAlertMask —— libtorrent4j 的默认 mask 是 all_categories，
     *    含 ip_block，这条立刻红。
     * 4. **持续收告警**：DHT 开着跑 `SOAK_MS`，结束后告警线程必须还活着、会话还在跑。
     *    打红方式：真机上不补表就是这条挂（线程被 NPE 带走）；桌面上补表逻辑坏掉也可能让它挂。
     */
    private static void alerts(BiConsumer<String, Boolean> check, PublicBtSession pub) {
        // --- 1. 合成 Android 形状的表：0..104 有值、105..107 是 null
        AlertType[] synthetic = new AlertType[108];
        for (int i = 0; i < synthetic.length; i++) {
            synthetic[i] = i < 105 ? AlertType.TORRENT_FINISHED : null;
        }
        int[] filled = AlertTypeCompat.fillTable(synthetic, AlertType.UNKNOWN);
        check.accept("public.alerttype_fill_android_gap (filled=" + Arrays.toString(filled) + ")",
                Arrays.equals(filled, new int[]{105, 106, 107}));
        boolean noNullLeft = true;
        for (AlertType t : synthetic) {
            if (t == null) {
                noNullLeft = false;
                break;
            }
        }
        check.accept("public.alerttype_fill_leaves_no_null", noNullLeft);
        check.accept("public.alerttype_fill_uses_unknown",
                synthetic[105] == AlertType.UNKNOWN
                        && synthetic[106] == AlertType.UNKNOWN
                        && synthetic[107] == AlertType.UNKNOWN);
        // 已知类型一个都不许动：补洞只补空洞。
        check.accept("public.alerttype_fill_keeps_known", synthetic[0] == AlertType.TORRENT_FINISHED);
        // 幂等：再补一次什么都不该填。
        check.accept("public.alerttype_fill_idempotent",
                AlertTypeCompat.fillTable(synthetic, AlertType.UNKNOWN).length == 0);

        // --- 2. 真会话的 ALERT 表：native 报几种，就有几个下标必须能映射
        AlertTypeCompat.Report report = AlertTypeCompat.install();
        check.accept("public.alerttype_install_clean (" + report + ")", report.isClean());
        int holes = 0;
        for (int i = 0; i < Alerts.NUM_ALERT_TYPES; i++) {
            if (AlertType.fromSwig(i) == null) {
                holes++;
            }
        }
        check.accept("public.alerttype_no_holes (native=" + Alerts.NUM_ALERT_TYPES + " holes=" + holes + ")",
                holes == 0);

        // --- 3. 公开会话真实生效的 alert_mask
        int mask = pub.alertMaskSetting();
        int ipBlock = PublicBtSession.ipBlockCategory();
        check.accept("public.alert_mask_no_ip_block (mask=0x" + Integer.toHexString(mask) + ")",
                mask != 0 && (mask & ipBlock) == 0);
        int need = PublicBtSession.publicAlertMask();
        check.accept("public.alert_mask_has_needed_categories", (mask & need) == need);

        // --- 4. 持续收告警：DHT 开着跑一段时间，告警线程不能死
        List<Thread> loops = alertLoopThreads();
        check.accept("public.alerts_loop_thread_present (n=" + loops.size() + ")", !loops.isEmpty());
        long until = System.currentTimeMillis() + SOAK_MS;
        boolean stillRunning = true;
        while (System.currentTimeMillis() < until) {
            if (!pub.isRunning()) {
                stillRunning = false;
                break;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        boolean allAlive = true;
        for (Thread t : loops) {
            if (!t.isAlive()) {
                allAlive = false;
                break;
            }
        }
        check.accept("public.alerts_loop_survives_soak (" + SOAK_MS + "ms)", allAlive);
        check.accept("public.session_alive_after_soak", stillRunning && pub.isRunning());
        // 收完之后 DHT 还在转 —— 说明是「收告警」这件事本身没出问题，不是会话被顺手关了。
        check.accept("public.dht_still_running_after_soak", pub.isDhtRunning());
        System.out.println("selftest.info public.alerts soak_ms=" + SOAK_MS
                + " loop_threads=" + loops.size() + " mask=0x" + Integer.toHexString(mask));
    }

    /** libtorrent4j 的告警线程（`SessionManager` 里 `new Thread(r, "SessionManager-alertsLoop")`）。 */
    private static List<Thread> alertLoopThreads() {
        List<Thread> out = new ArrayList<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && "SessionManager-alertsLoop".equals(t.getName())) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 这一段 soak 的时长。默认 30 s（真机上崩溃出现在导入磁力后约 2 分钟，这里只做「线程不死」的回归，
     * 不需要跑满 2 分钟）；`-Dm1.alertSoakMs=1000` 可以缩短，方便本地反复跑。
     */
    private static final long SOAK_MS = Long.getLong("m1.alertSoakMs", 30000L);

    // ---------------------------------------------------------------- 2. per-torrent flags

    private static void flags(BiConsumer<String, Boolean> check, BtSession priv, PublicBtSession pub) {
        int publicBits = PublicBtSession.publicFlagsBits();
        check.accept("public.flags_cleared (" + Integer.toHexString(publicBits) + ")", publicBits == 0);
        check.accept("public.pex_not_disabled",
                (publicBits & TorrentFlags.DISABLE_PEX.to_int()) == 0);
        check.accept("public.dht_not_disabled",
                (publicBits & TorrentFlags.DISABLE_DHT.to_int()) == 0);
        int privateBits = BtSession.Companion.getPRIVATE_FLAGS().to_int();
        check.accept("public.private_flags_0x380000 (" + Integer.toHexString(privateBits) + ")",
                privateBits == 0x380000);
    }

    // ---------------------------------------------------------------- 3. 会话归属

    private static void routing(BiConsumer<String, Boolean> check, File root, BtSession priv, PublicBtSession pub) {
        try {
            File payload = new File(root, "route-private.bin");
            writeRandom(payload, 256 * 1024);
            BtSession.SeededTorrent seeded = priv.createPrivateTorrent(payload);
            File saveDir = new File(root, "route-save");
            saveDir.mkdirs();
            TorrentInfo privateTorrent = new TorrentInfo(seeded.getTorrentBytes());
            check.accept("public.private_torrent_is_private", privateTorrent.isPrivate());
            // 公开会话必须拒绝 private=1 的种子（public-swarm §1.2）。
            check.accept("public.rejects_private_torrent", !pub.accepts(true));
            check.accept("public.add_rejects_private_torrent",
                    !pub.addTorrent(privateTorrent, saveDir, false) && !pub.has(seeded.getFileId()));
            // 反方向：私有会话必须拒绝 private=0 的种子。
            File publicPayload = new File(root, "route-public.bin");
            writeRandom(publicPayload, 256 * 1024);
            TorrentBuilder builder = new TorrentBuilder()
                    .path(publicPayload)
                    .setPrivate(false)
                    .flags(TorrentBuilder.V1_ONLY)
                    .pieceSize(16384);
            byte[] publicBytes = builder.generate().entry().bencode();
            TorrentInfo publicTorrent = new TorrentInfo(publicBytes);
            check.accept("public.public_torrent_is_not_private", !publicTorrent.isPrivate());
            check.accept("public.private_rejects_public_torrent", !priv.accepts(false));
            check.accept("public.private_add_rejects_public_torrent",
                    !priv.addDownload(publicTorrent.infoHash().toString(), publicBytes, saveDir));
            // 路由函数本身就是判据：private 位决定会话，条目上的 scope 字段不参与。
            check.accept("public.route_private", SessionRoute.of(true).equals(SessionRoute.PRIVATE));
            check.accept("public.route_public", SessionRoute.of(false).equals(SessionRoute.PUBLIC));
            check.accept("public.route_private_torrent",
                    SessionRoute.of(privateTorrent.isPrivate()).equals(SessionRoute.PRIVATE));
            check.accept("public.route_public_torrent",
                    SessionRoute.of(publicTorrent.isPrivate()).equals(SessionRoute.PUBLIC));
        } catch (Throwable t) {
            check.accept("public.routing_exception (" + t + ")", false);
            t.printStackTrace(System.out);
        }
    }

    // ---------------------------------------------------------------- 4. 磁力导入

    private static void magnet(BiConsumer<String, Boolean> check, File root, BtSession priv, PublicBtSession pub) {
        String hash = fakeInfoHash(31);
        String magnet = "magnet:?xt=urn:btih:" + hash + "&dn=selftest.bin&xl=1024";
        File saveDir = new File(root, "magnet-data");
        saveDir.mkdirs();

        PublicMagnetResult result = pub.addMagnet(magnet, saveDir, 800);
        // 磁力加种进的是公开会话：torrent 在公开会话里（不是私有会话），端口是 6881 段。
        check.accept("public.magnet_added_to_public_session (reason=" + result.getReason() + ")",
                pub.has(hash) && !priv.has(hash));
        check.accept("public.magnet_unknown_source_no_metadata (" + result.getReason() + ")",
                !result.getOk()
                        && (result.getReason().equals(PublicMagnetResult.NO_SOURCE)
                        || result.getReason().equals(PublicMagnetResult.TIMEOUT)));
        check.accept("public.magnet_failure_message_is_one_line ("
                        + PublicMagnetResult.messageFor(result.getReason()) + ")",
                PublicMagnetResult.messageFor(result.getReason()).equals(result.getMessage())
                        && !result.getMessage().isEmpty()
                        && result.getMessage().indexOf('\n') < 0);

        // 重复：同一个 infohash 第二次导入不再建 torrent。
        PublicMagnetResult dup = pub.addMagnet(magnet, saveDir, 200);
        check.accept("public.magnet_duplicate (" + dup.getReason() + ")",
                PublicMagnetResult.DUPLICATE.equals(dup.getReason())
                        && PublicMagnetResult.messageFor(PublicMagnetResult.DUPLICATE).equals(dup.getMessage()));

        // 非法链接：不抛异常、不建 torrent，一句话。
        PublicMagnetResult bad = pub.addMagnet("https://example.com/not-a-magnet.torrent", saveDir, 50);
        check.accept("public.magnet_invalid_link (" + bad.getReason() + ")",
                PublicMagnetResult.INVALID_LINK.equals(bad.getReason())
                        && !bad.getMessage().isEmpty()
                        && bad.getFileId().isEmpty());
        check.accept("public.magnet_v2_rejected",
                PublicMagnetResult.INVALID_LINK.equals(
                        pub.addMagnet("magnet:?xt=urn:btmh:1220" + hash, saveDir, 50).getReason()));

        // 五种失败各一句话，且彼此不同（§3.1 的表）。
        Set<String> messages = new HashSet<>();
        boolean allNonEmpty = true;
        for (String reason : PublicMagnetResult.failureReasons()) {
            String message = PublicMagnetResult.messageFor(reason);
            if (message.isEmpty() || message.indexOf('\n') >= 0) {
                allNonEmpty = false;
            }
            messages.add(message);
        }
        check.accept("public.failure_messages_all_present (" + messages.size() + ")", allNonEmpty);
        check.accept("public.failure_messages_distinct (" + messages.size() + ")", messages.size() == 5);

        // 磁力解析：hex 与 base32 两种写法归一到同一个 fileId；tr 原样保留，我们不加 tracker。
        MagnetLink hexLink = MagnetLink.parse(magnet);
        MagnetLink b32Link = MagnetLink.parse("magnet:?xt=urn:btih:" + base32Of(hash) + "&dn=selftest.bin");
        check.accept("public.magnet_parse_hex", hexLink != null && hash.equals(hexLink.getFileId()));
        check.accept("public.magnet_parse_base32", b32Link != null && hash.equals(b32Link.getFileId()));
        check.accept("public.magnet_no_bare_tracker",
                hexLink != null && hexLink.getTrackers().isEmpty()
                        && !hexLink.getNormalized().contains("&tr="));
        MagnetLink withTracker = MagnetLink.parse(magnet + "&tr=udp%3A%2F%2Ftracker.example%3A80");
        check.accept("public.magnet_keeps_source_tracker",
                withTracker != null && withTracker.getTrackers().size() == 1
                        && withTracker.getNormalized().contains("&tr="));
    }

    // ---------------------------------------------------------------- 5. 去重 + 零迁移

    private static void entries(BiConsumer<String, Boolean> check) {
        String hash = fakeInfoHash(47);
        FileEntry publicEntry = new FileEntry(hash, "公开行", 0L, 0, "dev-a", 1L,
                "magnet:?xt=urn:btih:" + hash, "", "sig-pub", FileScope.PUBLIC);
        FileEntry privateEntry = new FileEntry(hash, "私有行", 4096L, 16384, "dev-b", 2L,
                "magnet:?xt=urn:btih:" + hash, "dG9ycmVudA==", "sig-priv", FileScope.PRIVATE);

        // 顺序一：public 先到，private 后到 → 留 private（PROMOTE）。
        Map<String, FileEntry> tableOne = new LinkedHashMap<>();
        FileEntryMerge.Result first = FileEntryMerge.INSTANCE.merge(tableOne.get(hash), publicEntry);
        if (first.getEntry() != null) {
            tableOne.put(hash, first.getEntry());
        }
        FileEntryMerge.Result second = FileEntryMerge.INSTANCE.merge(tableOne.get(hash), privateEntry);
        if (second.getEntry() != null) {
            tableOne.put(hash, second.getEntry());
        }
        check.accept("public.dedup_public_first_add", first.getAction() == FileEntryMerge.Action.ADD);
        check.accept("public.dedup_public_first_promote",
                second.getAction() == FileEntryMerge.Action.PROMOTE
                        && tableOne.get(hash).getScope() == FileScope.PRIVATE);

        // 顺序二：private 先到，public 后到 → 丢弃 public（DROP），清单里仍是 private。
        Map<String, FileEntry> tableTwo = new LinkedHashMap<>();
        FileEntryMerge.Result third = FileEntryMerge.INSTANCE.merge(tableTwo.get(hash), privateEntry);
        if (third.getEntry() != null) {
            tableTwo.put(hash, third.getEntry());
        }
        FileEntryMerge.Result fourth = FileEntryMerge.INSTANCE.merge(tableTwo.get(hash), publicEntry);
        if (fourth.getEntry() != null) {
            tableTwo.put(hash, fourth.getEntry());
        }
        check.accept("public.dedup_private_first_add", third.getAction() == FileEntryMerge.Action.ADD);
        check.accept("public.dedup_private_first_drop",
                fourth.getAction() == FileEntryMerge.Action.DROP && fourth.getEntry() == null);
        check.accept("public.dedup_private_first_keeps_private",
                tableTwo.get(hash).getScope() == FileScope.PRIVATE
                        && tableTwo.get(hash).getTorrent().equals("dG9ycmVudA=="));
        check.accept("public.dedup_same_scope_keeps_one",
                FileEntryMerge.INSTANCE.merge(privateEntry, privateEntry).getAction()
                        == FileEntryMerge.Action.KEEP);

        // 零迁移：旧 state.json 里没有 scope 字段的条目读回来是 private，写出时补上 scope。
        String legacy = "{\"fileId\":\"" + hash + "\",\"name\":\"旧条目\",\"size\":4096,\"pieceSize\":16384,"
                + "\"addedBy\":\"dev-a\",\"addedAt\":1,\"magnet\":\"\",\"torrent\":\"\",\"sig\":\"sig\"}";
        FileEntry restored = FileEntry.Companion.fromJson(Json.INSTANCE.parse(legacy));
        check.accept("public.legacy_entry_scope_private", restored.getScope() == FileScope.PRIVATE);
        String rewritten = Json.INSTANCE.write(restored.toJson());
        check.accept("public.entry_json_carries_scope", rewritten.contains("\"scope\":\"private\""));
        FileEntry publicRestored = FileEntry.Companion.fromJson(Json.INSTANCE.parse(
                "{\"fileId\":\"" + hash + "\",\"name\":\"公开条目\",\"size\":0,\"pieceSize\":0,"
                        + "\"addedBy\":\"dev-a\",\"addedAt\":1,\"magnet\":\"magnet:x\",\"torrent\":\"\","
                        + "\"sig\":\"sig\",\"scope\":\"public\"}"));
        check.accept("public.entry_json_reads_public", publicRestored.getScope() == FileScope.PUBLIC
                && publicRestored.isPublic() && publicRestored.getSize() == 0L);
    }

    // ---------------------------------------------------------------- 工具

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /** RFC 4648 base32（BEP 9 允许的另一种 xt 写法）。 */
    private static String base32Of(String hex) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        byte[] raw = new byte[hex.length() / 2];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : raw) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(alphabet.charAt((buffer >> bits) & 0x1f));
            }
        }
        if (bits > 0) {
            sb.append(alphabet.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return sb.toString();
    }

    private static void writeRandom(File file, int bytes) throws Exception {
        byte[] chunk = new byte[64 * 1024];
        Random random = new Random(11);
        try (FileOutputStream out = new FileOutputStream(file)) {
            int written = 0;
            while (written < bytes) {
                random.nextBytes(chunk);
                int n = Math.min(chunk.length, bytes - written);
                out.write(chunk, 0, n);
                written += n;
            }
        }
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
        file.delete();
    }
}
