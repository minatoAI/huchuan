package app.huchuan.harness;

import app.huchuan.core.link.LinkDefaults;
import app.huchuan.core.link.LinkEvents;
import app.huchuan.core.link.LinkProtocol;
import app.huchuan.core.link.LinkState;
import app.huchuan.core.link.LinkTimeouts;
import app.huchuan.core.link.ShareCreateResult;
import app.huchuan.core.link.ShareEndpoint;
import app.huchuan.core.link.ShareFile;
import app.huchuan.core.link.ShareListResult;
import app.huchuan.core.link.ShareLookupResult;
import app.huchuan.core.link.ShareRegistry;
import app.huchuan.core.link.ShareRevokeResult;
import app.huchuan.core.link.SignalClient;
import app.huchuan.core.link.Transport;
import app.huchuan.core.link.TransportFactory;
import app.huchuan.core.m1.DeviceIdentity;
import app.huchuan.core.m1.Domains;
import app.huchuan.core.m1.J;
import app.huchuan.core.m1.Json;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * 链接分享服务端交互层的无头验收（harness，不碰真 WebRTC）。
 *
 * 四组断言，每组都有一个能把它打红的现实缺陷：
 *   1. 注册表：POST /api/share、GET /api/shares、DELETE /api/share/{id} 的路径、请求体、
 *      鉴权字段（撤销签名）逐项对照 worker，签名用 DeviceIdentity 独立验一遍。
 *   2. 信令状态机：分享者 + 访客在本机 mock WS 上完成 share-join / share-incoming /
 *      offer / answer，两边都走完 IDLE → SIGNALING → CONNECTED；offer 的 DTLS 指纹签名
 *      由访客验过，状态再进 TRANSFERRING。
 *   3. 断线重连：分享者掉线后访客按 spike 的结论重新 join（Worker 不重放 share-incoming），
 *      回到 CONNECTED；重连次数进日志。
 *   4. 超时与撤销：不来的 share-incoming 必须把状态打到 FAILED 且不抛异常；撤销后房间里的
 *      人收 bye gone，新访客连不上（HTTP 410，且根本没有 share-join 帧发出）。
 *
 * 断言全走生产类：[ShareRegistry] / [SignalClient] / [LinkProtocol]。mock 只模拟服务端语义。
 */
final class LinkSelfTest {

    private static final String OFFER_SDP =
            "v=0\r\n"
            + "o=- 4611731400430051336 2 IN IP4 127.0.0.1\r\n"
            + "s=-\r\n"
            + "t=0 0\r\n"
            + "a=fingerprint:sha-256 7B:8B:F0:65:5F:78:E2:51:3B:AC:6F:BA:75:32:46:5C:"
            + "B2:21:5A:1B:86:1D:36:08:9F:3C:5E:07:5A:1C:2B:4D\r\n"
            + "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";

    private static final String ANSWER_SDP = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n";

    private LinkSelfTest() {
    }

    static void run(BiConsumer<String, Boolean> check) {
        int[] counts = new int[2];
        BiConsumer<String, Boolean> counting = (name, ok) -> {
            counts[0]++;
            if (!ok) {
                counts[1]++;
            }
            check.accept(name, ok);
        };
        MockLinkServer server = null;
        List<SignalClient> clients = new ArrayList<>();
        try {
            server = new MockLinkServer();
            byteLayer(counting);
            registryLayer(counting, server);
            signalLayer(counting, server, clients);
            System.out.println("selftest.link checks=" + counts[0] + " failures=" + counts[1]
                    + " requests=" + server.requests().size()
                    + " ws_upgrades=" + server.upgrades().size()
                    + " join_frames=" + server.joinFrameCount());
        } catch (Throwable error) {
            counting.accept("link.exception", false);
            error.printStackTrace(System.out);
        } finally {
            for (SignalClient client : clients) {
                try {
                    client.close();
                } catch (Throwable ignored) {
                    // 收尾
                }
            }
            if (server != null) {
                server.close();
            }
        }
    }

    /* -------------------------------------------------------------- 1. 字节层 */

    private static void byteLayer(BiConsumer<String, Boolean> check) {
        check.accept("link.default_base_url", "https://huchuan-a9f.pages.dev".equals(LinkDefaults.BASE_URL));
        check.accept("link.base_url_empty_uses_default",
                LinkDefaults.BASE_URL.equals(LinkProtocol.INSTANCE.baseUrlOf("")));
        check.accept("link.signal_url_https_to_wss",
                "wss://huchuan-a9f.pages.dev/signal".equals(LinkProtocol.INSTANCE.signalUrl(LinkDefaults.BASE_URL)));
        check.accept("link.signal_url_http_to_ws",
                "ws://127.0.0.1:1234/signal".equals(LinkProtocol.INSTANCE.signalUrl("http://127.0.0.1:1234")));

        SecureRandom random = new SecureRandom();
        String token = LinkProtocol.INSTANCE.newToken(random);
        check.accept("link.token_26_chars", token.length() == 26);
        check.accept("link.token_is_crockford", token.matches("[0-9A-HJKMNP-TV-Z]{26}"));
        check.accept("link.token_valid", LinkProtocol.INSTANCE.validToken(token));
        check.accept("link.token_rejects_short", !LinkProtocol.INSTANCE.validToken("ABC"));

        String fingerprint = LinkProtocol.INSTANCE.fingerprintOfSdp(OFFER_SDP);
        check.accept("link.sdp_fingerprint_extracted", fingerprint.startsWith("sha-256 ")
                && fingerprint.contains("7B:8B:F0:65"));
        check.accept("link.sdp_without_fingerprint_empty",
                LinkProtocol.INSTANCE.fingerprintOfSdp("v=0\r\n").isEmpty());
        check.accept("link.guest_limit_is_1", LinkDefaults.MAX_GUESTS == 1);
        check.accept("link.signal_budget_15s", LinkDefaults.SIGNAL_BUDGET_MS == 15_000L);
        check.accept("link.transfer_idle_90s", LinkDefaults.TRANSFER_IDLE_MS == 90_000L);
    }

    /* -------------------------------------------------------------- 2. 注册表 */

    private static void registryLayer(BiConsumer<String, Boolean> check, MockLinkServer server) {
        DeviceIdentity owner = DeviceIdentity.Companion.generate();
        Recorder events = new Recorder("registry");
        ShareRegistry registry = new ShareRegistry(server.baseUrl(), owner, events);

        ShareCreateResult created = registry.create(
                List.of(new ShareFile("file-1", "季度报告.pdf", 1024L, "application/pdf")),
                3600L);
        check.accept("link.registry.create_ok", created.getOk());
        check.accept("link.registry.create_share_id", created.getShareId().matches("[0-9a-f]{32}"));
        check.accept("link.registry.create_token_26", LinkProtocol.INSTANCE.validToken(created.getToken()));
        check.accept("link.registry.create_ttl_honoured", created.getExpiresAt() - created.getCreatedAt() == 3_600_000L);
        check.accept("link.registry.create_guest_limit_1", created.getGuestLimit() == LinkDefaults.MAX_GUESTS);
        check.accept("link.registry.create_link_fragment",
                created.getLink().startsWith(server.baseUrl() + "/#s=")
                        && created.getLink().endsWith(created.getToken()));

        MockLinkServer.Recorded createRequest = server.lastRequest("POST", "/api/share");
        check.accept("link.registry.create_request_recorded", createRequest != null);
        if (createRequest != null) {
            J body = parse(createRequest.body);
            check.accept("link.registry.create_body_token", created.getToken().equals(body.str("token")));
            check.accept("link.registry.create_body_device_id", owner.getDeviceId().equals(body.str("deviceId")));
            check.accept("link.registry.create_body_pub", MockLinkServer.hex(owner.getPub64()).equals(body.str("pub")));
            check.accept("link.registry.create_body_ttl", Long.valueOf(3600L).equals(body.num("ttlSec")));
            List<J> files = body.arr("files");
            check.accept("link.registry.create_body_files", files != null && files.size() == 1
                    && "季度报告.pdf".equals(files.get(0).str("name"))
                    && Long.valueOf(1024L).equals(files.get(0).num("size"))
                    && "application/pdf".equals(files.get(0).str("mime")));
            String contentType = createRequest.headers.get("content-type");
            check.accept("link.registry.create_content_type",
                    contentType != null && contentType.startsWith("application/json"));
        } else {
            check.accept("link.registry.create_body_token", false);
            check.accept("link.registry.create_body_device_id", false);
            check.accept("link.registry.create_body_pub", false);
            check.accept("link.registry.create_body_ttl", false);
            check.accept("link.registry.create_body_files", false);
            check.accept("link.registry.create_content_type", false);
        }

        ShareListResult listed = registry.list();
        check.accept("link.registry.list_ok", listed.getOk());
        check.accept("link.registry.list_has_created", listed.getShares().size() == 1
                && created.getShareId().equals(listed.getShares().get(0).getShareId()));
        MockLinkServer.Recorded listRequest = server.lastRequest("GET", "/api/shares");
        check.accept("link.registry.list_query_device_id",
                listRequest != null && owner.getDeviceId().equals(listRequest.query.get("deviceId")));

        ShareCreateResult second = registry.create(
                List.of(new ShareFile("file-2", "a.bin", 2048L, "application/octet-stream")),
                60L);
        check.accept("link.registry.second_create_ok", second.getOk());
        ShareRevokeResult revoked = registry.revoke(second.getToken(), second.getShareId());
        check.accept("link.registry.revoke_ok", revoked.getOk() && !revoked.getAlreadyRevoked());
        check.accept("link.registry.revoke_share_id", second.getShareId().equals(revoked.getShareId()));

        MockLinkServer.Recorded revokeRequest = server.lastRequest("DELETE", "/api/share/");
        check.accept("link.registry.revoke_path", revokeRequest != null
                && revokeRequest.path.equals("/api/share/" + second.getShareId()));
        if (revokeRequest != null) {
            J body = parse(revokeRequest.body);
            String bodyDeviceId = body.str("deviceId");
            String bodyToken = body.str("token");
            String nonce = body.str("nonce");
            String sig = body.str("sig");
            Long ts = body.num("ts");
            check.accept("link.registry.revoke_body_identity",
                    owner.getDeviceId().equals(bodyDeviceId) && second.getToken().equals(bodyToken));
            check.accept("link.registry.revoke_body_nonce", nonce != null && nonce.matches("[A-Za-z0-9_-]{8,64}"));
            check.accept("link.registry.revoke_body_ts",
                    ts != null && Math.abs(System.currentTimeMillis() / 1000L - ts) <= 300L);
            check.accept("link.registry.revoke_signature",
                    sig != null && sig.matches("[0-9a-f]{128}")
                            && DeviceIdentity.Companion.verify(
                            owner.getPub64(),
                            sig,
                            Domains.LINK_REVOKE,
                            LinkProtocol.INSTANCE.revokeFields(bodyDeviceId, bodyToken, ts, nonce))
                            && !DeviceIdentity.Companion.verify(
                            owner.getPub64(),
                            sig,
                            Domains.LINK_SHARE,
                            LinkProtocol.INSTANCE.revokeFields(bodyDeviceId, bodyToken, ts, nonce)));
        } else {
            check.accept("link.registry.revoke_body_identity", false);
            check.accept("link.registry.revoke_body_nonce", false);
            check.accept("link.registry.revoke_body_ts", false);
            check.accept("link.registry.revoke_signature", false);
        }

        ShareListResult afterRevoke = registry.list();
        check.accept("link.registry.list_excludes_revoked", afterRevoke.getOk()
                && afterRevoke.getShares().size() == 1
                && created.getShareId().equals(afterRevoke.getShares().get(0).getShareId()));

        ShareLookupResult revokedLookup = registry.get(second.getToken());
        check.accept("link.registry.revoked_lookup", !revokedLookup.getOk()
                && "revoked".equals(revokedLookup.getStatus()));
        check.accept("link.registry.revoked_endpoint_null", revokedLookup.endpoint() == null);

        ShareLookupResult kept = registry.get(created.getToken());
        check.accept("link.registry.lookup_created", kept.getOk()
                && ("offline".equals(kept.getStatus()) || "available".equals(kept.getStatus()))
                && created.getShareId().equals(kept.getShareId())
                && owner.getDeviceId().equals(kept.getDeviceId()));
        check.accept("link.registry.logs_present", events.logEvents.contains("share_create")
                && events.logEvents.contains("share_revoke")
                && events.logEvents.contains("share_list"));
    }

    /* -------------------------------------------------------------- 3. 信令 + 重连 + 撤销 */

    private static void signalLayer(BiConsumer<String, Boolean> check, MockLinkServer server, List<SignalClient> clients) {
        DeviceIdentity owner = DeviceIdentity.Companion.generate();
        DeviceIdentity guest = DeviceIdentity.Companion.generate();
        Recorder ownerEvents = new Recorder("owner");
        Recorder guestEvents = new Recorder("guest");
        ShareRegistry registry = new ShareRegistry(server.baseUrl(), owner, new Recorder("signal-registry"));
        LinkTimeouts fast = new LinkTimeouts(5_000L, 60_000L, 100L, 1_000L, 10, 60L);
        TransportFactory factory = new TransportFactory() {
            @Override
            public Transport create() {
                return new JavaWsTransport();
            }
        };

        ShareCreateResult created = registry.create(
                List.of(new ShareFile("file-3", "video.mp4", 4_194_304L, "video/mp4")),
                3600L);
        ShareEndpoint sharerEndpoint = created.endpoint();
        check.accept("link.signal.sharer_endpoint", sharerEndpoint != null);

        SignalClient sharer = SignalClient.Companion.forSharer(sharerEndpoint, owner, factory, ownerEvents, fast);
        ownerEvents.client = sharer;
        clients.add(sharer);
        check.accept("link.signal.sharer_start", sharer.start());
        check.accept("link.signal.sharer_signaling", sharer.awaitState(LinkState.SIGNALING, 3_000L));
        check.accept("link.signal.sharer_open", await(() -> ownerEvents.logEvents.contains("link_open"), 5_000L));

        ShareLookupResult online = registry.get(created.getToken());
        check.accept("link.signal.lookup_available", online.getOk() && "available".equals(online.getStatus()));
        ShareEndpoint guestEndpoint = online.endpoint();
        check.accept("link.signal.guest_endpoint", guestEndpoint != null
                && owner.getDeviceId().equals(guestEndpoint.getSharerDeviceId())
                && MockLinkServer.hex(owner.getPub64()).equalsIgnoreCase(guestEndpoint.getSharerPubHex()));

        SignalClient guestClient = SignalClient.Companion.forGuest(guestEndpoint, guest, factory, guestEvents, fast);
        guestEvents.client = guestClient;
        clients.add(guestClient);
        check.accept("link.signal.guest_start", guestClient.start());
        check.accept("link.signal.guest_connected", guestClient.awaitState(LinkState.CONNECTED, 8_000L));
        check.accept("link.signal.sharer_connected", sharer.awaitState(LinkState.CONNECTED, 8_000L));
        check.accept("link.signal.share_incoming", ownerEvents.incomingCount.get() == 1
                && !ownerEvents.lastVisitorId.isEmpty());
        check.accept("link.signal.offer_delivered", guestEvents.offerCount.get() == 1);
        check.accept("link.signal.offer_signature_verified", verifyOffer(owner, created, guestEvents));

        check.accept("link.signal.state_order_sharer",
                ownerEvents.transitions.contains("IDLE->SIGNALING")
                        && ownerEvents.transitions.contains("SIGNALING->CONNECTED"));
        check.accept("link.signal.state_order_guest",
                guestEvents.transitions.contains("IDLE->SIGNALING")
                        && guestEvents.transitions.contains("SIGNALING->CONNECTED"));
        check.accept("link.signal.logs_present",
                ownerEvents.logEvents.contains("link_open")
                        && ownerEvents.logEvents.contains("link_connected")
                        && guestEvents.logEvents.contains("link_offer_verified"));

        MockLinkServer.WsUpgrade sharerUpgrade = server.lastUpgrade("sharer");
        check.accept("link.signal.sharer_ws_auth", sharerUpgrade != null
                && sharerUpgrade.status == 101
                && sharerUpgrade.sigOk
                && sharerUpgrade.query.containsKey("dev")
                && sharerUpgrade.query.containsKey("ts")
                && sharerUpgrade.query.containsKey("nonce")
                && sharerUpgrade.query.containsKey("sig"));
        MockLinkServer.WsUpgrade guestUpgrade = server.lastUpgrade("guest");
        check.accept("link.signal.guest_ws_anonymous", guestUpgrade != null
                && guestUpgrade.status == 101
                && guestUpgrade.query.get("ep") != null
                && !guestUpgrade.query.containsKey("dev"));

        sharer.markTransferring();
        guestClient.markTransferring();
        check.accept("link.signal.transferring", sharer.awaitState(LinkState.TRANSFERRING, 2_000L)
                && guestClient.awaitState(LinkState.TRANSFERRING, 2_000L));
        guestClient.noteProgress();

        // ---- 断线重连：分享者掉线，访客按 spike 的结论重新 join ----
        Recorder owner2Events = new Recorder("owner2");
        SignalClient sharer2 = SignalClient.Companion.forSharer(sharerEndpoint, owner, factory, owner2Events, fast);
        owner2Events.client = sharer2;
        clients.add(sharer2);
        sharer.close();
        check.accept("link.reconnect.new_sharer_start", sharer2.start());
        boolean reconnected = await(() -> guestEvents.connectedCount.get() >= 2
                && guestClient.getState() == LinkState.CONNECTED
                && guestClient.getReconnectAttempts() >= 1, 10_000L);
        check.accept("link.reconnect.guest_connected_again", reconnected);
        check.accept("link.reconnect.new_sharer_connected", sharer2.awaitState(LinkState.CONNECTED, 8_000L));
        check.accept("link.reconnect.attempt_logged",
                guestEvents.logEvents.contains("link_reconnect")
                        && (guestEvents.transitions.contains("TRANSFERRING->SIGNALING")
                        || guestEvents.transitions.contains("CONNECTED->SIGNALING")));
        check.accept("link.reconnect.sharer_ws_auth_again", server.lastUpgrade("sharer") != null
                && server.lastUpgrade("sharer").sigOk);

        // ---- 撤销：房间里的人收 bye gone，新访客 410 且不发 share-join ----
        ShareRevokeResult revoked = registry.revoke(created.getToken(), created.getShareId());
        check.accept("link.revoke.live_ok", revoked.getOk());
        check.accept("link.revoke.guest_kicked", guestClient.awaitState(LinkState.FAILED, 5_000L)
                && "revoked".equals(guestClient.getFailureReason()));
        check.accept("link.revoke.sharer_kicked", sharer2.awaitState(LinkState.FAILED, 5_000L)
                && "revoked".equals(sharer2.getFailureReason()));
        check.accept("link.revoke.lookup_status", "revoked".equals(registry.get(created.getToken()).getStatus()));

        int joinsBefore = server.joinFrameCount();
        Recorder rejectedEvents = new Recorder("rejected");
        SignalClient rejected = SignalClient.Companion.forGuest(guestEndpoint, guest, factory, rejectedEvents, fast);
        clients.add(rejected);
        check.accept("link.revoke.new_guest_start", rejected.start());
        check.accept("link.revoke.new_guest_failed", rejected.awaitState(LinkState.FAILED, 5_000L)
                && "revoked".equals(rejected.getFailureReason()));
        check.accept("link.revoke.new_guest_no_join_frame", server.joinFrameCount() == joinsBefore);
        MockLinkServer.WsUpgrade rejectedUpgrade = server.lastUpgrade("guest");
        check.accept("link.revoke.new_guest_http_410", rejectedUpgrade != null
                && rejectedUpgrade.status == 410
                && created.getShareId().equals(rejectedUpgrade.shareId));

        // ---- 超时：不来的 share-incoming 必须 FAILED，且不抛异常 ----
        Recorder timeoutEvents = new Recorder("timeout");
        ShareCreateResult timeoutShare = registry.create(
                List.of(new ShareFile("file-4", "slow.bin", 16L, "application/octet-stream")),
                60L);
        LinkTimeouts shortBudget = new LinkTimeouts(250L, 60_000L, 100L, 1_000L, 5, 60L);
        SignalClient slow = SignalClient.Companion.forSharer(
                timeoutShare.endpoint(), owner, factory, timeoutEvents, shortBudget);
        clients.add(slow);
        check.accept("link.timeout.start", slow.start());
        check.accept("link.timeout.failed", slow.awaitState(LinkState.FAILED, 5_000L));
        check.accept("link.timeout.reason", "connect-failed".equals(slow.getFailureReason()));
        check.accept("link.timeout.logged", timeoutEvents.logEvents.contains("link_timeout")
                && timeoutEvents.transitions.contains("SIGNALING->FAILED"));
        check.accept("link.timeout.failure_callback", "connect-failed".equals(timeoutEvents.lastFailure));
    }

    /* -------------------------------------------------------------- 工具 */

    private static boolean verifyOffer(DeviceIdentity owner, ShareCreateResult created, Recorder guestEvents) {
        String sdp = guestEvents.lastOfferSdp;
        String sig = guestEvents.lastOfferSig;
        if (sdp == null || sig == null) {
            return false;
        }
        String fingerprint = LinkProtocol.INSTANCE.fingerprintOfSdp(sdp);
        if (fingerprint.isEmpty()) {
            return false;
        }
        return DeviceIdentity.Companion.verify(
                owner.getPub64(),
                sig,
                Domains.LINK_SHARE,
                LinkProtocol.INSTANCE.offerFields(created.getToken(), fingerprint, created.getExpiresAt()));
    }

    private static J parse(String text) {
        try {
            return Json.INSTANCE.parse(text);
        } catch (Throwable error) {
            throw new IllegalStateException("bad json in mock request: " + text, error);
        }
    }

    private static boolean await(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return condition.getAsBoolean();
            }
        }
        return condition.getAsBoolean();
    }

    /** 记录事件与状态跳转；测试断言直接读列表，不需要解析日志。 */
    private static class Recorder extends LinkEvents {
        private final String label;
        final List<String> logEvents = Collections.synchronizedList(new ArrayList<>());
        final List<String> transitions = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger incomingCount = new AtomicInteger();
        final AtomicInteger offerCount = new AtomicInteger();
        final AtomicInteger answerCount = new AtomicInteger();
        final AtomicInteger connectedCount = new AtomicInteger();
        volatile SignalClient client;
        volatile String lastOfferSdp;
        volatile String lastOfferSig;
        volatile String lastVisitorId = "";
        volatile String lastFailure = "";

        Recorder(String label) {
            this.label = label;
        }

        @Override
        public void log(String event, Map<String, String> fields) {
            logEvents.add(event);
            System.out.println("link.evt label=" + label + " event=" + event + " fields=" + fields);
        }

        @Override
        public void stateChanged(LinkState from, LinkState to) {
            transitions.add(from.name() + "->" + to.name());
            if (to == LinkState.CONNECTED) {
                connectedCount.incrementAndGet();
            }
        }

        @Override
        public void incoming(String shareId, String visitorId, String epPub) {
            incomingCount.incrementAndGet();
            lastVisitorId = visitorId;
            SignalClient current = client;
            if (current != null) {
                current.sendOffer(OFFER_SDP);
            }
        }

        @Override
        public void offer(String sdp, String sig) {
            offerCount.incrementAndGet();
            lastOfferSdp = sdp;
            lastOfferSig = sig;
            SignalClient current = client;
            if (current != null) {
                current.sendAnswer(ANSWER_SDP);
            }
        }

        @Override
        public void answer(String sdp) {
            answerCount.incrementAndGet();
        }

        @Override
        public void failure(String code) {
            lastFailure = code;
        }
    }
}
