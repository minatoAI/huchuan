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
import app.huchuan.core.m1.PublicPublishResult;
import app.huchuan.core.m1.SessionRoute;

import org.libtorrent4j.TorrentBuilder;
import org.libtorrent4j.TorrentFlags;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.alerts.AlertType;
import org.libtorrent4j.alerts.Alerts;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
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
 * 公开种子社区（public-swarm）的内核自检。六组断言，每组都有一个能把它打红的现实缺陷：
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
 *   6. 公开到群外（[publish]）：私有文件另建 private=0 的新种子（infohash 与私有不同）、磁力 btih
 *      对齐新 infohash、公开条目出现而私有条目保留、重复调用幂等、unpublish 只移除公开条目。
 *      打红方式：把 TorrentBuild 的 private 参数传 true，或者让重复调用再建一个 torrent。
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
        publish(check);
        importParking(check);
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

    // ---------------------------------------------------------------- 6. 公开到群外

    /**
     * `kernel.publishPublic` / `unpublishPublic` 的端到端路径（真起内核 + 真建群 + 真文件）。
     *
     * 证伪点：把 TorrentBuild 的 private 参数写成 true —— 新种子的 infohash 会与私有相同，
     * 磁力 btih / 两条条目 / 公开会话计数一起变红（实测 19 条红）；让幂等快路径失效 ——
     * 第二次调用的 reused 标志 / 条目数会红；unpublish 改成删磁盘文件 —— file_kept 那条红；
     * hidden / published 不落盘 —— 两次重启后的三条断言红。
     */
    private static void publish(BiConsumer<String, Boolean> check) {
        File dir = new File(System.getProperty("java.io.tmpdir"), "huchuan-selftest-pubpublish-" + System.nanoTime());
        dir.mkdirs();
        List<String> events = new CopyOnWriteArrayList<>();
        Map<String, String> lastPublish = new ConcurrentHashMap<>();
        Map<String, String> lastRejected = new ConcurrentHashMap<>();
        Map<String, String> lastUnpublish = new ConcurrentHashMap<>();
        M1Kernel kernel = null;
        try {
            File payload = new File(dir, "publish-payload.bin");
            writeRandom(payload, 512 * 1024);
            KernelEvents sink = new KernelEvents() {
                @Override
                public void log(String event, Map<String, String> fields) {
                    events.add(event);
                    if ("public_publish".equals(event)) {
                        lastPublish.putAll(fields);
                    } else if ("public_publish_rejected".equals(event)) {
                        lastRejected.putAll(fields);
                    } else if ("public_unpublish".equals(event)) {
                        lastUnpublish.putAll(fields);
                    }
                }
            };
            kernel = new M1Kernel(new File(dir, "data"), "publish-selftest", 47380, 53413, sink);
            kernel.start();
            GroupRuntime group = kernel.createGroup("公开到群外自检群");
            FileEntry priv = group.addFile(payload);

            // 纯函数：每个失败码都有中文文案、都不换行、彼此不同。
            Set<String> messages = new HashSet<>();
            boolean allNonEmpty = true;
            for (String reason : PublicPublishResult.failureReasons()) {
                String message = PublicPublishResult.messageFor(reason);
                if (message.isEmpty() || message.indexOf('\n') >= 0) {
                    allNonEmpty = false;
                }
                messages.add(message);
            }
            check.accept("public.publish_failure_messages_all_present (" + messages.size() + ")", allNonEmpty);
            check.accept("public.publish_failure_messages_distinct (" + messages.size() + ")",
                    messages.size() == PublicPublishResult.failureReasons().length);

            // 失败码：群不存在 / 文件不在群里。都必须是返回值，不抛异常。
            String gid = group.getGroupId();
            PublicPublishResult noGroup = kernel.publishPublic("no-such-group", priv.getFileId());
            check.accept("public.publish_no_group (" + noGroup.getReason() + ")",
                    PublicPublishResult.NO_GROUP.equals(noGroup.getReason())
                            && PublicPublishResult.messageFor(noGroup.getReason()).equals(noGroup.getMessage()));
            PublicPublishResult noFile = kernel.publishPublic(gid, fakeInfoHash(77));
            check.accept("public.publish_no_file (" + noFile.getReason() + ")",
                    PublicPublishResult.NO_FILE.equals(noFile.getReason())
                            && PublicPublishResult.messageFor(noFile.getReason()).equals(noFile.getMessage())
                            && "no_file".equals(lastRejected.get("reason"))
                            && events.contains("public_publish_rejected"));

            // 成功：新公开种子 = 新 infohash + 公开会话在做种 + 一条 public 条目。
            PublicPublishResult first = kernel.publishPublic(gid, priv.getFileId());
            String pubId = first.getFileId();
            check.accept("public.publish_ok (" + first.getReason() + ")",
                    first.getOk() && first.getMessage().isEmpty());
            check.accept("public.publish_new_infohash", !pubId.isEmpty() && !pubId.equals(priv.getFileId()));
            MagnetLink magnet = MagnetLink.parse(first.getMagnet());
            check.accept("public.publish_magnet_btih (magnet=" + first.getMagnet() + ")",
                    magnet != null && pubId.equals(magnet.getFileId()));
            check.accept("public.publish_entry_public",
                    first.getEntry() != null
                            && first.getEntry().getFileId().equals(pubId)
                            && first.getEntry().getScope() == FileScope.PUBLIC
                            && !first.getEntry().getMagnet().isEmpty()
                            && first.getEntry().getTorrent().isEmpty());
            check.accept("public.publish_entry_in_group",
                    group.file(pubId) != null
                            && group.file(pubId).getScope() == FileScope.PUBLIC);
            check.accept("public.publish_private_kept",
                    group.file(priv.getFileId()) != null
                            && group.file(priv.getFileId()).getScope() == FileScope.PRIVATE
                            && !group.file(priv.getFileId()).getTorrent().isEmpty());
            check.accept("public.publish_public_session (torrents=" + kernel.publicTorrentCount() + ")",
                    kernel.getPublicSessionRunning() && kernel.publicTorrentCount() == 1);
            check.accept("public.publish_private_session_untouched (torrents=" + kernel.privateTorrentCount() + ")",
                    kernel.privateTorrentCount() == 1);

            // 新种子本身：private=false、v1、infohash 与目录 / 磁力一致。
            byte[] pubBytes = kernel.publicTorrentBytes(pubId);
            check.accept("public.publish_torrent_cached", pubBytes != null && pubBytes.length > 0);
            TorrentInfo pubInfo = pubBytes == null ? null : new TorrentInfo(pubBytes);
            check.accept("public.publish_torrent_not_private", pubInfo != null && !pubInfo.isPrivate());
            check.accept("public.publish_torrent_v1", pubInfo != null && pubInfo.hasV1() && !pubInfo.hasV2());
            check.accept("public.publish_torrent_hash_matches",
                    pubInfo != null && pubId.equals(pubInfo.infoHash().toString()));
            check.accept("public.publish_torrent_size",
                    pubInfo != null && pubInfo.totalSize() == payload.length());

            // 日志口径：file_id 是原私有条目，pub_infohash 是新公开种子。
            check.accept("public.publish_log_fields",
                    priv.getFileId().equals(lastPublish.get("file_id"))
                            && pubId.equals(lastPublish.get("pub_infohash")));
            System.out.println("selftest.info public.publish file_id=" + priv.getFileId()
                    + " pub_infohash=" + pubId
                    + " magnet=" + first.getMagnet()
                    + " torrents=" + kernel.publicTorrentCount());

            // 幂等：同一私有 fileId 第二次返回同一条公开条目，不新增条目 / torrent。
            int filesBefore = group.files().size();
            int torrentsBefore = kernel.publicTorrentCount();
            PublicPublishResult again = kernel.publishPublic(gid, priv.getFileId());
            check.accept("public.publish_idempotent",
                    again.getOk()
                            && pubId.equals(again.getFileId())
                            && group.files().size() == filesBefore
                            && kernel.publicTorrentCount() == torrentsBefore
                            && "true".equals(lastPublish.get("reused")));
            // 把公开条目的 id 传进来同样是幂等的（宿主重试可能拿的是新 id）。
            PublicPublishResult byPublicId = kernel.publishPublic(gid, pubId);
            check.accept("public.publish_idempotent_by_public_id",
                    byPublicId.getOk() && pubId.equals(byPublicId.getFileId())
                            && kernel.publicTorrentCount() == torrentsBefore);

            // unpublish：公开条目消失、公开 torrent 停掉、私有条目与磁盘文件都不受影响。
            boolean removed = kernel.unpublishPublic(gid, pubId);
            check.accept("public.unpublish_removed", removed);
            check.accept("public.unpublish_entry_gone", group.file(pubId) == null);
            check.accept("public.unpublish_private_kept",
                    group.file(priv.getFileId()) != null
                            && group.file(priv.getFileId()).getScope() == FileScope.PRIVATE
                            && group.localRole(priv.getFileId()) == GroupRuntime.LocalRole.SEEDING);
            check.accept("public.unpublish_torrent_stopped",
                    kernel.publicTorrentCount() == 0 && !kernel.getPublicSessionRunning());
            check.accept("public.unpublish_file_kept", payload.isFile());
            check.accept("public.unpublish_log_fields", pubId.equals(lastUnpublish.get("pub_infohash")));

            // 重新发布：隐藏标记不能把同一个文件永久挡住（重新发布仍是同一个公开 infohash）。
            PublicPublishResult republished = kernel.publishPublic(gid, priv.getFileId());
            check.accept("public.publish_after_unpublish",
                    republished.getOk()
                            && pubId.equals(republished.getFileId())
                            && group.file(pubId) != null
                            && kernel.publicTorrentCount() == 1);
            // 取消公开也接受原始私有 id（经发布映射反查），并且只移除公开条目。
            check.accept("public.unpublish_by_private_id",
                    kernel.unpublishPublic(gid, priv.getFileId())
                            && group.file(pubId) == null
                            && group.file(priv.getFileId()) != null);

            // 重启后 hidden 仍在：被移除的公开条目不能从 state.json 或对端 file.list 复活。
            kernel.close();
            kernel = new M1Kernel(new File(dir, "data"), "publish-selftest", 47380, 53413, sink);
            kernel.start();
            GroupRuntime restarted = kernel.group(gid);
            check.accept("public.publish_restart_hidden_persisted",
                    restarted != null
                            && restarted.file(pubId) == null
                            && restarted.file(priv.getFileId()) != null);
            // 重新发布：隐藏标记自动撤销，且因为 public infohash 是内容确定的，拿回同一个 pubId。
            PublicPublishResult afterRestart = kernel.publishPublic(gid, priv.getFileId());
            check.accept("public.publish_restart_republish",
                    afterRestart.getOk()
                            && pubId.equals(afterRestart.getFileId())
                            && restarted.file(pubId) != null
                            && kernel.publicTorrentCount() == 1);

            // 再重启一次：published 映射落盘，重复发布走幂等快路径（resumeSeeds 已把公开种挂回来）。
            kernel.close();
            kernel = new M1Kernel(new File(dir, "data"), "publish-selftest", 47380, 53413, sink);
            kernel.start();
            PublicPublishResult idempotentAfterRestart = kernel.publishPublic(gid, priv.getFileId());
            check.accept("public.publish_restart_idempotent",
                    idempotentAfterRestart.getOk()
                            && pubId.equals(idempotentAfterRestart.getFileId())
                            && "true".equals(lastPublish.get("reused"))
                            && kernel.publicTorrentCount() == 1
                            && kernel.group(gid) != null
                            && kernel.group(gid).file(priv.getFileId()) != null);
        } catch (Throwable t) {
            check.accept("public.publish_exception (" + t + ")", false);
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

    // ---------------------------------------------------------------- 7. 导入后不下载（状态与内核一致）

    /**
     * 真实缺陷的回归（docs/reports/public-swarm-real-world.md 风险 1）：磁力导入回了「休眠 0%」，
     * 但同一时刻公开会话里的 torrent 已经在真下载。判据不是「谁说了什么」而是内核读数：
     *
     *   1. 导入之后（元数据与 .torrent 缓存都到手）内核里**没有这条任务**：kernelState=ABSENT、
     *      字节数 0、盘上无数据 —— 界面写「休眠」时内核必须真的没在传；
     *   2. 激活之后必须真的在传：kernelState=DOWNLOADING/SEEDING、字节数在涨、percent 与字节自洽；
     *   3. 休眠之后必须真的停：kernelState=ABSENT、角色 NONE、内核字节数回 0；
     *   4. 诊断读口要有值：dhtNodes / tracker announce 结果与下次时间 / peer 端点列表。
     *
     * 装置是真的：本机一个公开会话做种（16 MiB）、一个本机 BEP 3 tracker（[MockTracker]）负责牵线、
     * 一个真内核跑导入 → 激活 → 休眠。打红方式：把 [PublicBtSession.addMagnet] 的 importOnly
     * 改回 false（导入即下载 → 第 1 组红），或者把 GroupRuntime.localRole 改回读本地任务表
     * （角色永远 NONE → 第 2 组红）。
     */
    private static void importParking(BiConsumer<String, Boolean> check) {
        File root = new File(System.getProperty("java.io.tmpdir"), "huchuan-selftest-park-" + System.nanoTime());
        root.mkdirs();
        MockTracker tracker = null;
        PublicBtSession seeder = null;
        M1Kernel kernel = null;
        try {
            // --- 装置：真种子 + 真做种会话 + 本机 tracker
            File seedDir = new File(root, "seed-data");
            seedDir.mkdirs();
            File payload = new File(seedDir, PAYLOAD_NAME);
            writeRandom(payload, PAYLOAD_BYTES);
            tracker = new MockTracker();
            // 短 interval：导入时 announce 一次拿到做种端点，激活后 libtorrent 会很快再 announce 一次。
            // 默认的 600 s 会让「激活 → 连上」等上几十秒，测试就变成在等一个与本缺陷无关的超时。
            tracker.setIntervalSeconds(10, 5);
            String announce = tracker.baseUrl() + "/announce";
            byte[] torrentBytes = new TorrentBuilder()
                    .path(payload)
                    .setPrivate(false)
                    .flags(TorrentBuilder.V1_ONLY)
                    .pieceSize(65536)
                    .addTracker(announce)
                    .generate().entry().bencode();
            TorrentInfo ti = new TorrentInfo(torrentBytes);
            String fileId = ti.infoHash().toString();

            seeder = new PublicBtSession(new File(root, "seed-public"), SEEDER_PORT, null);
            int seederPort = seeder.start();
            boolean offered = seeder.addTorrent(ti, seedDir, true);
            tracker.addPeer("127.0.0.1", seederPort);
            check.accept("public.park_seeder_up (port=" + seederPort + ")", offered);

            KernelEvents sink = new KernelEvents() {
                @Override
                public void log(String event, Map<String, String> fields) {
                }
            };
            kernel = new M1Kernel(new File(root, "kernel"), "park-selftest", 47490, 53490, sink);
            kernel.start();
            GroupRuntime group = kernel.createGroup("导入后不下载");

            String magnet = "magnet:?xt=urn:btih:" + fileId + "&dn=" + PAYLOAD_NAME
                    + "&tr=" + URLEncoder.encode(announce, "UTF-8");
            PublicMagnetResult result = kernel.addPublicMagnet(
                    group.getGroupId(), magnet, PAYLOAD_NAME, METADATA_WAIT_MS);
            check.accept("public.park_metadata_received (reason=" + result.getReason() + ")", result.getOk());
            check.accept("public.park_torrent_cached (path=" + result.getMetadataPath() + ")",
                    !result.getMetadataPath().isEmpty() && new File(result.getMetadataPath()).isFile());

            // --- 1. 导入之后：不在会话里（休眠），一个字节都不下
            final M1Kernel importing = kernel;
            // 摘任务是异步的（告警线程只入队）：等它退场，再断言状态。
            boolean retired = waitUntil(
                    () -> group.kernelState(fileId) == GroupRuntime.KernelState.ABSENT,
                    PARK_OBSERVE_MS);
            check.accept("public.park_kernel_state (state=" + group.kernelState(fileId) + ")", retired);
            check.accept("public.park_not_in_session (torrents=" + importing.publicTorrentCount() + ")",
                    importing.publicTorrentCount() == 0);
            check.accept("public.park_role_none (role=" + group.localRole(fileId) + ")",
                    group.localRole(fileId) == GroupRuntime.LocalRole.NONE);
            check.accept("public.park_percent_zero (percent=" + group.percent(fileId) + ")",
                    group.percent(fileId) == 0);
            check.accept("public.park_bytes_zero (bytes=" + group.downloadedBytes(fileId) + ")",
                    group.downloadedBytes(fileId) == 0L);
            // 停三秒再看：休眠期间内核不该往盘上写一个字节（真缺陷里这 3 s 涨了 10 MB 量级）。
            // 导入路径最早能在「元数据到手」那一刻叫停（再早连 info 字典都拿不到），所以这里要求 0 字节；
            // 另一个方向也钉住：三秒里不能有任何增长（增长=还在下）。
            File onDisk = new File(new File(new File(kernel.getDataDir(), "public"), "data"), PAYLOAD_NAME);
            long diskAtImport = onDisk.isFile() ? onDisk.length() : 0L;
            Thread.sleep(PARK_OBSERVE_MS);
            long diskBytes = onDisk.isFile() ? onDisk.length() : 0L;
            check.accept("public.park_no_data_on_disk (at_import=" + diskAtImport + " after_3s=" + diskBytes + ")",
                    diskAtImport == 0L && diskBytes == 0L);
            check.accept("public.park_bytes_still_zero (bytes=" + group.downloadedBytes(fileId) + ")",
                    group.downloadedBytes(fileId) == 0L);


            // --- 3. 激活：真的开始下载，且 state 与字节数自洽
            check.accept("public.activate_started", group.startDownload(fileId));
            // 4 MiB 走回环可能一瞬间就下完：从激活那一刻起每 5 ms 采一次样，
            // 同时收集「字节真的动了」与「诊断读口里真的出现了对端」这两个事实。
            final boolean[] sawBytes = {false};
            final StringBuilder endpoints = new StringBuilder();
            boolean observed = observeTransfer(group, fileId, importing, ACTIVATE_WAIT_MS, sawBytes, endpoints);
            check.accept("public.activate_downloading (state=" + group.kernelState(fileId)
                    + " bytes=" + group.downloadedBytes(fileId) + ")", sawBytes[0]);
            check.accept("public.diag_peers (loopback_seen=" + hasLoopback(endpoints) + " endpoints=" + endpoints + ")",
                    observed && hasLoopback(endpoints));
            PublicBtSession.Diagnostics live = kernel.publicDiagnostics();
            PublicBtSession.Diagnostics.Tracker liveTracker = repliedTracker(live, announce);
            System.out.println("selftest.info public.diag.live loopback_seen=" + hasLoopback(endpoints)
                    + " endpoints=" + endpoints
                    + " dht_nodes=" + live.getDhtNodes()
                    + " torrents=" + live.getTorrents()
                    + " next_announce_ms=" + (liveTracker == null ? -1 : liveTracker.getNextAnnounceInMs()));
            // 诊断读口（DHT / tracker announce / peer 端点）：会话里有任务、真的在传时必须读得到真值。
            check.accept("public.diag_running (running=" + live.getRunning() + ")", live.getRunning());
            check.accept("public.diag_port (port=" + live.getPort() + ")",
                    live.getPort() >= PublicBtSession.DEFAULT_PORT);
            check.accept("public.diag_torrents (n=" + live.getTorrents() + ")", live.getTorrents() == 1);
            check.accept("public.diag_dht (running=" + live.getDhtRunning() + " nodes=" + live.getDhtNodes() + ")",
                    live.getDhtRunning() && live.getDhtNodes() >= 0L);
            check.accept("public.diag_tracker_replied (state=" + (liveTracker == null ? "none" : liveTracker.getState())
                            + " peers=" + (liveTracker == null ? 0 : liveTracker.getPeers()) + ")",
                    liveTracker != null && liveTracker.getOk() && "replied".equals(liveTracker.getState())
                            && liveTracker.getLastAnnounceAt() > 0L && liveTracker.getFailures() == 0);
            check.accept("public.diag_tracker_next_announce (ms=" + (liveTracker == null ? -1 : liveTracker.getNextAnnounceInMs())
                            + " at=" + (liveTracker == null ? -1 : liveTracker.getNextAnnounceAt()) + ")",
                    liveTracker != null && liveTracker.getNextAnnounceInMs() > 0L
                            && liveTracker.getNextAnnounceAt() > liveTracker.getLastAnnounceAt());
            long done = group.downloadedBytes(fileId);
            long total = group.totalBytes(fileId);
            int expected = total > 0L ? (int) (done * 100L / total) : -1;
            check.accept("public.activate_total_bytes (total=" + total + ")", total == PAYLOAD_BYTES);
            check.accept("public.activate_percent_matches_bytes (percent=" + group.percent(fileId)
                    + " expected=" + expected + ")",
                    expected >= 0 && group.percent(fileId) == expected);
            check.accept("public.activate_progress_in_range", group.percent(fileId) >= 0 && group.percent(fileId) <= 100);

            boolean finished = waitUntil(() -> group.kernelState(fileId) == GroupRuntime.KernelState.SEEDING,
                    DOWNLOAD_WAIT_MS);
            check.accept("public.download_seeding (state=" + group.kernelState(fileId)
                    + " percent=" + group.percent(fileId) + ")", finished);
            check.accept("public.download_percent_100 (percent=" + group.percent(fileId) + ")",
                    group.percent(fileId) == 100);

            // --- 4. 休眠：内核里真停（torrent 摘掉、字节数回 0），界面回「休眠」
            check.accept("public.hibernate_stopped", group.stopFile(fileId));
            check.accept("public.hibernate_state_sleeping (state=" + group.kernelState(fileId)
                    + " role=" + group.localRole(fileId) + ")",
                    group.kernelState(fileId) == GroupRuntime.KernelState.ABSENT
                            && group.localRole(fileId) == GroupRuntime.LocalRole.NONE);
            check.accept("public.hibernate_bytes_zero (bytes=" + group.downloadedBytes(fileId) + ")",
                    group.downloadedBytes(fileId) == 0L);

            // --- 5. 再激活：从 .torrent 缓存挂回来，立刻能做种（开关两个方向都成立）
            check.accept("public.reactivate_started", group.startDownload(fileId));
            check.accept("public.reactivate_seeding (state=" + group.kernelState(fileId) + ")",
                    waitUntil(() -> group.kernelState(fileId) == GroupRuntime.KernelState.SEEDING, DOWNLOAD_WAIT_MS));
        } catch (Throwable t) {
            check.accept("public.park_exception (" + t + ")", false);
            t.printStackTrace(System.out);
        } finally {
            if (kernel != null) {
                try {
                    kernel.close();
                } catch (Throwable ignored) {
                    // 同上
                }
            }
            if (seeder != null) {
                try {
                    seeder.stop();
                } catch (Throwable ignored) {
                    // 同上
                }
            }
            if (tracker != null) {
                try {
                    tracker.close();
                } catch (Throwable ignored) {
                    // 同上
                }
            }
            deleteTree(root);
        }
    }

    private static PublicBtSession.Diagnostics.Tracker repliedTracker(
            PublicBtSession.Diagnostics diag, String url) {
        for (PublicBtSession.Diagnostics.Tracker t : diag.getTrackers()) {
            if (t.getUrl().equals(url)) {
                return t;
            }
        }
        return null;
    }

    private static boolean anyConnectedPeer(PublicBtSession.Diagnostics diag) {
        for (PublicBtSession.Diagnostics.PeerEndpoint p : diag.getPeers()) {
            if (p.getConnected()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 激活之后的观测：从这一刻起每 5 ms 采一次公开会话诊断，直到「字节真的动了」与
     * 「诊断读口里出现回环对端」两个事实都拿到，或者预算用完。
     *
     * 为什么这么密：回环上 16 MiB 可能在几十毫秒里传完，慢采样会把整段传输采样漏掉。
     * 判据用回环**主机**而不是做种会话的监听端口：连接可能是对端拨进来的，那时 peer_info 给的是
     * 它的临时源端口。本装置里除做种会话外没有别的回环 peer。
     */
    private static boolean observeTransfer(GroupRuntime group, String fileId, M1Kernel kernel,
                                          long budgetMs, boolean[] sawBytes, StringBuilder endpoints) {
        long deadline = System.currentTimeMillis() + budgetMs;
        boolean sawLoopback = false;
        while (System.currentTimeMillis() < deadline) {
            for (PublicBtSession.Diagnostics.PeerEndpoint p : kernel.publicDiagnostics().getPeers()) {
                if (endpoints.indexOf(p.getEndpoint()) < 0) {
                    endpoints.append(p.getEndpoint()).append(' ');
                }
                if (p.getEndpoint().startsWith("127.0.0.1:")) {
                    sawLoopback = true;
                }
            }
            if (group.downloadedBytes(fileId) > 0L) {
                sawBytes[0] = true;
            }
            if (sawLoopback && sawBytes[0]) {
                return true;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return sawLoopback && sawBytes[0];
    }

    /** 观测到的端点串里有没有回环主机（本机做种会话拨进来 / 被拨出去都算）。 */
    private static boolean hasLoopback(CharSequence endpoints) {
        return endpoints.toString().contains("127.0.0.1:");
    }

    /** 16 MiB：小到几秒下完，又足够让「百分比与字节」可分、连接能被采样到。 */
    private static final int PAYLOAD_BYTES = 16 * 1024 * 1024;
    private static final String PAYLOAD_NAME = "park-payload.bin";
    /** 做种侧的公开会话端口：避开内核默认的 6881 段，免得两边抢同一批端口。 */
    private static final int SEEDER_PORT = 6960;
    private static final long METADATA_WAIT_MS = 20000L;
    private static final long PARK_OBSERVE_MS = 3000L;
    private static final long DIAG_WAIT_MS = 20000L;
    private static final long ACTIVATE_WAIT_MS = 90000L;
    /** 等 peer 端点出现在诊断读口里的上限（激活之后连接要重新建立）。 */
    private static final long PEER_WAIT_MS = 60000L;
    private static final long DOWNLOAD_WAIT_MS = 90000L;

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
