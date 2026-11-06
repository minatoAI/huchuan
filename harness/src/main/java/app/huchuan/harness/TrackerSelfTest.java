package app.huchuan.harness;

import app.huchuan.core.m1.Candidate;
import app.huchuan.core.m1.CandidateMerge;
import app.huchuan.core.m1.GroupRuntime;
import app.huchuan.core.m1.KernelEvents;
import app.huchuan.core.m1.M1Kernel;
import app.huchuan.core.m1.TrackerProtocol;

import java.io.File;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 跨网发现（自建 tracker）的无头验收。
 *
 * 三组断言，每组都有一个能把它打红的现实缺陷：
 *
 *   1. 字节层（[protocol] / [mergeRules]）：groupHash20 与 passkey 的公式、小时边界、打码、
 *      合并优先级。SelfTest 自己用 MessageDigest / Mac 独立算一遍期望值 —— 内核那边一旦把
 *      domain、拼接顺序、整数写法改掉一个字符，这里立刻对不上。
 *   2. 开着 tracker 的内核（[enabledKernel]）：真起一个内核 + 本地 mock tracker，断言
 *      注册收到了正确的 infoHash / gid / passkey；announce 的 info_hash 是 20 字节的
 *      %XX 原文、peer_id 20 字节、port 是**消息层**端口、left=0、compact=1、numwant=32、
 *      passkey 与注册时同一把、event=started；返回的 peers 进了群候选表；日志里 passkey
 *      是打码的；关内核时补了一条 event=stopped；所有 HTTP 只发往配置的 tracker。
 *   3. 关着 tracker 的内核（[disabledKernel]）：baseUrl 为空时开关是关的、事件里只有
 *      tracker_disabled、候选表为空，并且**一次 HTTP 请求都没有**（用 JVM 的 ProxySelector
 *      记录所有 HttpURLConnection 的目的地 —— 哪怕有人偷偷写死一个线上默认地址也会被抓到）。
 */
final class TrackerSelfTest {

    private TrackerSelfTest() {
    }

    /** 本组断言自己的计数与现场数字：SelfTest 的 check 只在失败时出声，这里补一行可 grep 的正向证据。 */
    private static final int[] COUNTS = new int[2];

    private static volatile int observedRegister;
    private static volatile int observedAnnounce;
    private static volatile int observedCandidates;
    private static volatile int observedHttpUris;
    private static volatile int observedDisabledRequests = -1;

    static void run(BiConsumer<String, Boolean> check) {
        COUNTS[0] = 0;
        COUNTS[1] = 0;
        BiConsumer<String, Boolean> counting = (name, ok) -> {
            COUNTS[0]++;
            if (!ok) {
                COUNTS[1]++;
            }
            check.accept(name, ok);
        };
        protocol(counting);
        mergeRules(counting);
        File root = new File(System.getProperty("java.io.tmpdir"),
                "huchuan-selftest-tracker-" + System.nanoTime());
        root.mkdirs();
        ProxyProbe probe = ProxyProbe.install();
        try {
            enabledKernel(counting, root, probe);
            disabledKernel(counting, root, probe);
        } catch (Throwable t) {
            counting.accept("tracker.exception", false);
            t.printStackTrace(System.out);
        } finally {
            probe.close();
            deleteTree(root);
        }
        System.out.println("selftest.tracker checks=" + COUNTS[0]
                + " failures=" + COUNTS[1]
                + " register_requests=" + observedRegister
                + " announce_requests=" + observedAnnounce
                + " candidates_from_tracker=" + observedCandidates
                + " tracker_http_uris=" + observedHttpUris
                + " disabled_kernel_requests=" + observedDisabledRequests);
    }

    // ---------------------------------------------------------------- 1. 字节层

    /**
     * 公式与打码。期望值全部在本类里用 JDK 原语独立算：
     *   groupHash20 = SHA-256(UTF8("huchuan/gh1/v1") ‖ codeBytes)[0..20]
     *   passkey     = hex(HMAC-SHA256(kGrp, UTF8("huchuan/trk1/v1") ‖ UTF8(gid) ‖ UTF8(小时十进制)))
     */
    private static void protocol(BiConsumer<String, Boolean> check) {
        byte[] codeA = new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88, (byte) 0x99, (byte) 0xaa};
        byte[] codeB = codeA.clone();
        codeB[9] = (byte) 0xab;

        byte[] expectedHashA = first20(sha256(concat("huchuan/gh1/v1".getBytes(StandardCharsets.UTF_8), codeA)));
        byte[] actualHashA = TrackerProtocol.INSTANCE.groupHash20(codeA);
        check.accept("tracker.group_hash20_is_sha256_of_domain_and_code", java.util.Arrays.equals(expectedHashA, actualHashA));
        check.accept("tracker.group_hash20_is_20_bytes", actualHashA.length == 20);
        check.accept("tracker.group_hash20_hex_is_40_chars",
                TrackerProtocol.INSTANCE.groupHash20Hex(codeA).matches("[0-9a-f]{40}"));
        check.accept("tracker.group_hash20_changes_with_code",
                !java.util.Arrays.equals(actualHashA, TrackerProtocol.INSTANCE.groupHash20(codeB)));

        byte[] kGrp = new byte[32];
        for (int i = 0; i < kGrp.length; i++) {
            kGrp[i] = (byte) (0x40 + i);
        }
        String gid = "7Q2F9C4M1XZ8KD3V";
        long hour = 494_000L;
        String expectedPasskey = hmacHex(kGrp,
                concat("huchuan/trk1/v1".getBytes(StandardCharsets.UTF_8),
                        gid.getBytes(StandardCharsets.UTF_8),
                        Long.toString(hour).getBytes(StandardCharsets.UTF_8)));
        String actualPasskey = TrackerProtocol.INSTANCE.passkeyHex(kGrp, gid, hour);
        check.accept("tracker.passkey_matches_protocol_formula", expectedPasskey.equals(actualPasskey));
        check.accept("tracker.passkey_is_64_lower_hex", actualPasskey.matches("[0-9a-f]{64}"));
        check.accept("tracker.passkey_rotates_per_hour",
                !actualPasskey.equals(TrackerProtocol.INSTANCE.passkeyHex(kGrp, gid, hour + 1)));
        check.accept("tracker.passkey_hour_from_clock",
                actualPasskey.equals(TrackerProtocol.INSTANCE.passkeyHexAt(kGrp, gid, hour * 3_600_000L + 5)));
        check.accept("tracker.passkey_is_gid_bound",
                !actualPasskey.equals(TrackerProtocol.INSTANCE.passkeyHex(kGrp, "7Q2F9C4M1XZ8KD3W", hour)));
        check.accept("tracker.passkey_is_key_bound", !actualPasskey.equals(
                TrackerProtocol.INSTANCE.passkeyHex(new byte[32], gid, hour)));

        check.accept("tracker.hour_boundary_before", TrackerProtocol.INSTANCE.hourOf(3_599_999L) == 0L);
        check.accept("tracker.hour_boundary_after", TrackerProtocol.INSTANCE.hourOf(3_600_000L) == 1L);
        check.accept("tracker.hour_of_zero", TrackerProtocol.INSTANCE.hourOf(0L) == 0L);
        check.accept("tracker.hour_is_utc",
                TrackerProtocol.INSTANCE.hourOf(1_762_381_200_400L) == (1_762_381_200_400L / 3_600_000L));

        // 打码：日志里只能看到前 4 个字符
        String masked = TrackerProtocol.INSTANCE.mask(actualPasskey);
        check.accept("tracker.mask_keeps_4_chars", ("abcd" + "****").equals(TrackerProtocol.INSTANCE.mask("abcdef0123456789")));
        check.accept("tracker.mask_hides_secret", !masked.contains(actualPasskey.substring(4)));
        check.accept("tracker.mask_of_empty", "".equals(TrackerProtocol.INSTANCE.mask("")));
        check.accept("tracker.mask_of_short", "****".equals(TrackerProtocol.INSTANCE.mask("abc")));

        // peer_id：20 字节、同一台设备恒定（服务端按它去重）
        byte[] peerId = TrackerProtocol.INSTANCE.peerId("7Q2F9C4M1XZ8KD3V");
        check.accept("tracker.peer_id_is_20_bytes", peerId.length == 20);
        check.accept("tracker.peer_id_is_stable",
                java.util.Arrays.equals(peerId, TrackerProtocol.INSTANCE.peerId("7Q2F9C4M1XZ8KD3V")));
        check.accept("tracker.peer_id_is_ascii", new String(peerId, StandardCharsets.US_ASCII).startsWith("-HC"));
    }

    /**
     * 协议 §3.4 的合并规则：manual(3) > lan(2) > tracker(1)，同键留高优先级，每群 128 上限。
     * 这条断言直接对应「tracker 的 peer 并入候选表时不许顶掉局域网地址」。
     */
    private static void mergeRules(BiConsumer<String, Boolean> check) {
        Candidate lanPeer = new Candidate("192.168.1.10", 47100, "lan");
        Candidate manualPeer = new Candidate("203.0.113.9", 47200, "manual");
        Candidate sameAsLanFromTracker = new Candidate("192.168.1.10", 47100, "tracker");
        Candidate trackerOnly = new Candidate("198.51.100.7", 47100, "tracker");
        Candidate badPort = new Candidate("198.51.100.8", 0, "tracker");

        List<Candidate> merged = CandidateMerge.INSTANCE.merge(List.of(
                List.of(manualPeer),
                List.of(lanPeer, badPort),
                List.of(sameAsLanFromTracker, trackerOnly)));

        check.accept("tracker.merge_dedupes_same_endpoint", merged.size() == 3);
        check.accept("tracker.merge_drops_invalid_port", merged.stream().noneMatch(c -> c.getPort() == 0));
        check.accept("tracker.merge_manual_first", "manual".equals(merged.get(0).getSource()));
        check.accept("tracker.merge_lan_beats_tracker", merged.stream()
                .anyMatch(c -> c.getKey().equals("192.168.1.10:47100") && "lan".equals(c.getSource())));
        check.accept("tracker.merge_tracker_only_kept", merged.stream()
                .anyMatch(c -> c.getKey().equals("198.51.100.7:47100") && "tracker".equals(c.getSource())));
        check.accept("tracker.merge_priority_order_is_monotonic", isMonotonic(merged));
        check.accept("tracker.merge_rank_values",
                CandidateMerge.INSTANCE.rank("manual") == 3
                        && CandidateMerge.INSTANCE.rank("peer") == 3
                        && CandidateMerge.INSTANCE.rank("lan") == 2
                        && CandidateMerge.INSTANCE.rank("tracker") == 1);

        List<Candidate> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            many.add(new Candidate("10.9." + (i / 250) + "." + (i % 250), 41000 + i, "tracker"));
        }
        List<Candidate> capped = CandidateMerge.INSTANCE.merge(List.of(many));
        check.accept("tracker.merge_respects_128_cap", capped.size() == CandidateMerge.MAX_PER_GROUP);
        check.accept("tracker.merge_cap_is_128", CandidateMerge.MAX_PER_GROUP == 128);
    }

    // ---------------------------------------------------------------- 2. 开着 tracker

    /**
     * 真内核 + 本地 mock tracker。断言的是「客户端到底发了什么」，不是我们的意图。
     */
    private static void enabledKernel(BiConsumer<String, Boolean> check, File root, ProxyProbe probe)
            throws Exception {
        try (MockTracker mock = new MockTracker()) {
            // 刻意不用内核的缺省值 600：下发 900 才能证明「interval 以服务端为准」
            mock.setIntervalSeconds(900, 300);
            mock.addPeer("127.0.0.1", 47999);
            mock.addPeer("203.0.113.7", 47100);
            mock.addPeer("2001:db8::1", 48000);

            Recorder events = new Recorder();
            File dir = new File(root, "on");
            dir.mkdirs();
            M1Kernel kernel = new M1Kernel(dir, "tracker-A", 47610, 0, events);
            check.accept("tracker.default_base_url_is_empty", kernel.getTrackerBaseUrl().isEmpty());
            check.accept("tracker.default_off", !kernel.getTrackerEnabled());

            kernel.setTrackerBaseUrl(mock.baseUrl());
            int mark = probe.count();
            kernel.start();
            try {
                check.accept("tracker.enabled_after_config", kernel.getTrackerEnabled());
                check.accept("tracker.base_url_normalized",
                        mock.baseUrl().equals(kernel.getTrackerBaseUrl()));

                GroupRuntime group = kernel.createGroup("tracker-验收群");
                boolean arrived = await(() -> mock.registerCount() >= 1 && mock.announceCount() >= 1, 20_000L);
                check.accept("tracker.register_and_announce_arrived", arrived);

                // ---- 注册
                check.accept("tracker.register_uses_post",
                        "POST /api/tracker/register".equals(mock.lastRegisterRequestLine()));
                String registerBody = mock.lastRegisterBody();
                String infoHash = group(registerBody, "\"infoHash\":\"([0-9a-f]{40})\"");
                String gid = group(registerBody, "\"gid\":\"([0-9A-Z]{16})\"");
                String passkey = group(registerBody, "\"passkey\":\"([0-9a-f]{64})\"");
                check.accept("tracker.register_info_hash_40_hex", infoHash != null);
                check.accept("tracker.register_gid_is_local_group", group.getGroupId().equals(gid));
                check.accept("tracker.register_passkey_64_hex", passkey != null);
                check.accept("tracker.register_without_prev_on_fresh_swarm", !registerBody.contains("prevPasskey"));

                // ---- announce
                String query = mock.lastAnnounceQuery();
                byte[] announcedHash = MockTracker.bytesParam(query, "info_hash");
                check.accept("tracker.announce_info_hash_is_20_bytes",
                        announcedHash != null && announcedHash.length == 20);
                check.accept("tracker.announce_info_hash_matches_group_hash20",
                        announcedHash != null && hex(announcedHash).equals(infoHash));
                check.accept("tracker.announce_info_hash_percent_encoded",
                        MockTracker.isFullyPercentEncoded(query, "info_hash"));
                byte[] announcedPeerId = MockTracker.bytesParam(query, "peer_id");
                check.accept("tracker.announce_peer_id_is_20_bytes",
                        announcedPeerId != null && announcedPeerId.length == 20);
                check.accept("tracker.announce_port_is_message_port",
                        String.valueOf(kernel.localMessagePort()).equals(MockTracker.textParam(query, "port")));
                check.accept("tracker.announce_left_zero", "0".equals(MockTracker.textParam(query, "left")));
                check.accept("tracker.announce_compact_one", "1".equals(MockTracker.textParam(query, "compact")));
                check.accept("tracker.announce_numwant_32", "32".equals(MockTracker.textParam(query, "numwant")));
                check.accept("tracker.announce_event_started", "started".equals(MockTracker.textParam(query, "event")));
                check.accept("tracker.announce_uses_registered_passkey",
                        passkey != null && passkey.equals(MockTracker.textParam(query, "passkey")));

                // ---- peers 并入候选表（source=tracker）
                await(() -> group.candidates().size() >= 3, 5_000L);
                List<Candidate> candidates = group.candidates();
                observedCandidates = candidates.size();
                check.accept("tracker.peers_merged_into_candidate_table", candidates.size() == 3);
                check.accept("tracker.peers_merged_as_tracker_source",
                        candidates.stream().allMatch(c -> "tracker".equals(c.getSource())));
                check.accept("tracker.peers_merged_v4",
                        candidates.stream().anyMatch(c -> c.getKey().equals("127.0.0.1:47999"))
                                && candidates.stream().anyMatch(c -> c.getKey().equals("203.0.113.7:47100")));
                check.accept("tracker.peers_merged_v6",
                        candidates.stream().anyMatch(c -> c.getHost().equals("2001:db8::1") && c.getPort() == 48000));

                // ---- 日志里 passkey 打码
                check.accept("tracker.register_logged", events.has("tracker_register"));
                check.accept("tracker.announce_logged", events.has("tracker_announce"));
                check.accept("tracker.interval_follows_server", events.contains("interval=900"));
                check.accept("tracker.log_has_masked_passkey", passkey != null
                        && events.contains("passkey_masked=" + TrackerProtocol.INSTANCE.mask(passkey)));
                check.accept("tracker.log_never_contains_raw_passkey", passkey != null && !events.contains(passkey));

                // ---- 只报给我们的 tracker：本进程所有 HTTP 目的地都是这一个
                List<String> uris = probe.since(mark);
                check.accept("tracker.http_only_to_configured_base", !uris.isEmpty()
                        && uris.stream().allMatch(u -> u.startsWith(mock.baseUrl() + "/")));
                check.accept("tracker.http_never_public_tracker",
                        uris.stream().noneMatch(u -> u.contains("workers.dev")));
            } finally {
                kernel.close();
            }
            // 关机时应当补一条 event=stopped（让别的成员别再来拨这个端点）
            check.accept("tracker.stopped_event_sent_on_close",
                    mock.announceQueries().stream().anyMatch(q -> "stopped".equals(MockTracker.textParam(q, "event"))));
            observedRegister = mock.registerCount();
            observedAnnounce = mock.announceCount();
            observedHttpUris = probe.since(mark).size();
        }
    }

    // ---------------------------------------------------------------- 3. 关着 tracker

    /**
     * baseUrl 为空 = 整个功能关闭。除了开关与事件，最硬的一条是 ProxyProbe：
     * 它为**每一次** HttpURLConnection 记录目的地，所以「零请求」是真的零，不是「我没看见」。
     */
    private static void disabledKernel(BiConsumer<String, Boolean> check, File root, ProxyProbe probe)
            throws Exception {
        Recorder events = new Recorder();
        File dir = new File(root, "off");
        dir.mkdirs();
        M1Kernel kernel = new M1Kernel(dir, "tracker-off", 47620, 0, events);
        int mark = probe.count();
        kernel.start();
        try {
            check.accept("tracker.disabled_flag_is_off", !kernel.getTrackerEnabled());
            check.accept("tracker.disabled_base_url_empty", kernel.getTrackerBaseUrl().isEmpty());
            GroupRuntime group = kernel.createGroup("关掉 tracker 的群");
            Thread.sleep(1_500L);
            check.accept("tracker.disabled_logged", events.has("tracker_disabled"));
            check.accept("tracker.disabled_no_other_tracker_events",
                    events.prefixCount("tracker_") == 1);
            check.accept("tracker.disabled_candidate_table_empty", group.candidates().isEmpty());
            observedDisabledRequests = probe.count() - mark;
            check.accept("tracker.disabled_sends_no_request", observedDisabledRequests == 0);
        } finally {
            kernel.close();
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 事件出口：把内核日志的行原样收起来，供「打码 / 有没有 tracker 事件」这类断言读。 */
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
            return lines().stream().anyMatch(l -> l.equals(event) || l.startsWith(event + " "));
        }

        int prefixCount(String prefix) {
            return (int) lines().stream().filter(l -> l.startsWith(prefix)).count();
        }

        boolean contains(String needle) {
            return lines().stream().anyMatch(l -> l.contains(needle));
        }
    }

    /**
     * 记录所有 HttpURLConnection 的目的地。
     *
     * 这是「空 baseUrl 时不发任何请求」唯一可证伪的测法：不给内核任何地址，只要它还在往
     * 某个地方发 HTTP（例如有人写死了线上 Worker 地址做默认值），ProxySelector 就会被问到。
     * 装上去之后 JVM 对每一次 http/https 连接都会走 select()（实测 JDK 17）。
     */
    static final class ProxyProbe extends ProxySelector {

        private final ProxySelector previous;
        private final List<String> uris = Collections.synchronizedList(new ArrayList<>());

        private ProxyProbe(ProxySelector previous) {
            this.previous = previous;
        }

        static ProxyProbe install() {
            ProxyProbe probe = new ProxyProbe(ProxySelector.getDefault());
            ProxySelector.setDefault(probe);
            return probe;
        }

        @Override
        public List<Proxy> select(URI uri) {
            uris.add(uri.toString());
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException error) {
            // 探针只记账
        }

        int count() {
            return uris.size();
        }

        List<String> since(int mark) {
            synchronized (uris) {
                if (mark >= uris.size()) {
                    return List.of();
                }
                return new ArrayList<>(uris.subList(mark, uris.size()));
            }
        }

        void close() {
            ProxySelector.setDefault(previous);
        }
    }

    private static boolean await(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                return condition.getAsBoolean();
            }
        }
        return condition.getAsBoolean();
    }

    private static boolean isMonotonic(List<Candidate> candidates) {
        int last = Integer.MAX_VALUE;
        for (Candidate candidate : candidates) {
            int rank = CandidateMerge.INSTANCE.rank(candidate.getSource());
            if (rank > last) {
                return false;
            }
            last = rank;
        }
        return true;
    }

    private static String group(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] out = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    private static byte[] first20(byte[] value) {
        byte[] out = new byte[20];
        System.arraycopy(value, 0, out, 0, 20);
        return out;
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hmacHex(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return hex(mac.doFinal(message));
        } catch (Exception e) {
            throw new IllegalStateException(e);
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
        // Windows 上刚停掉的会话 / mDNS 可能还占着句柄：删不掉就算了，临时目录不参与断言。
        file.delete();
    }
}
