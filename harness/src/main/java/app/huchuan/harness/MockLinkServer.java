package app.huchuan.harness;

import app.huchuan.core.link.LinkDefaults;
import app.huchuan.core.link.LinkProtocol;
import app.huchuan.core.m1.B32;
import app.huchuan.core.m1.DeviceIdentity;
import app.huchuan.core.m1.J;
import app.huchuan.core.m1.Json;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 链接分享的本机 mock 服务：一个裸 ServerSocket 同时讲 HTTP 与 WebSocket（RFC 6455）。
 *
 * 为什么不用 com.sun.net.httpserver：它拿不到升级后的原始 socket，而 /signal 必须在同一个
 * 端口上完成 101 握手。真实 worker（worker/src/room.js、share.js）的语义在这里做最小复刻：
 *   - POST /api/share、GET /api/share/{token}、GET /api/shares、DELETE /api/share/{id}
 *   - 分享者 /signal 查询串签名校验（huchuan/ws1/v1）
 *   - 访客 share-join 校验 token / revoked，转发 share-incoming / offer / answer / ice / bye
 *   - 撤销后新连接直接 HTTP 410，房间里的人收到 bye{gone}
 *
 * 所有收到的请求与 WS 升级都留记录，测试直接断言请求体与鉴权字段，不靠"看日志猜"。
 */
final class MockLinkServer implements Closeable {

    static final class Recorded {
        final String method;
        final String path;
        final Map<String, String> query;
        final Map<String, String> headers;
        final String body;
        volatile int status;

        Recorded(String method, String path, Map<String, String> query, Map<String, String> headers, String body, int status) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
            this.status = status;
        }
    }

    static final class WsUpgrade {
        final String role;
        final String shareId;
        final Map<String, String> query;
        final int status;
        final boolean sigOk;

        WsUpgrade(String role, String shareId, Map<String, String> query, int status, boolean sigOk) {
            this.role = role;
            this.shareId = shareId;
            this.query = query;
            this.status = status;
            this.sigOk = sigOk;
        }
    }

    static final class Share {
        final String token;
        final String shareId;
        final String deviceId;
        final String pub;
        final List<J> files;
        final long ttlSec;
        final long createdAt;
        final long expiresAt;
        final AtomicInteger visitCount = new AtomicInteger();
        volatile boolean revoked;
        /**
         * 测试开关：为 true 时访客的 share-join 不再转给分享者（分享者"装死"）。
         * 用来把访客晾在 SIGNALING 上，验证访客口径的 15 s 建链预算。
         */
        volatile boolean muteIncoming;
        volatile Rfc6455 sharer;
        volatile Rfc6455 guest;

        Share(String token, String shareId, String deviceId, String pub, List<J> files, long ttlSec, long createdAt, long expiresAt) {
            this.token = token;
            this.shareId = shareId;
            this.deviceId = deviceId;
            this.pub = pub;
            this.files = files;
            this.ttlSec = ttlSec;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }
    }

    private final ServerSocket serverSocket;
    private final ExecutorService handlers;
    private final Thread acceptor;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Share> byId = new ConcurrentHashMap<>();
    private final Map<String, Share> byToken = new ConcurrentHashMap<>();
    private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<WsUpgrade> upgrades = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger joinFrames = new AtomicInteger();
    private volatile boolean closed;

    MockLinkServer() throws IOException {
        serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        handlers = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "mock-link-handler");
            thread.setDaemon(true);
            return thread;
        });
        acceptor = new Thread(this::acceptLoop, "mock-link-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    List<Recorded> requests() {
        synchronized (requests) {
            return new ArrayList<>(requests);
        }
    }

    Recorded lastRequest(String method, String pathPrefix) {
        synchronized (requests) {
            for (int i = requests.size() - 1; i >= 0; i--) {
                Recorded item = requests.get(i);
                if (item.method.equals(method) && item.path.startsWith(pathPrefix)) {
                    return item;
                }
            }
        }
        return null;
    }

    List<WsUpgrade> upgrades() {
        synchronized (upgrades) {
            return new ArrayList<>(upgrades);
        }
    }

    WsUpgrade lastUpgrade(String role) {
        synchronized (upgrades) {
            for (int i = upgrades.size() - 1; i >= 0; i--) {
                if (upgrades.get(i).role.equals(role)) {
                    return upgrades.get(i);
                }
            }
        }
        return null;
    }

    int joinFrameCount() {
        return joinFrames.get();
    }

    Share shareByToken(String token) {
        return byToken.get(normalizeToken(token));
    }

    /* -------------------------------------------------------------- 连接入口 */

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(10_000);
                socket.setTcpNoDelay(true);
                handlers.execute(() -> handle(socket));
            } catch (IOException error) {
                if (!closed) {
                    // 测试收尾时地址被关，正常
                }
            }
        }
    }

    private void handle(Socket socket) {
        boolean handedOff = false;
        try {
            InputStream in = socket.getInputStream();
            byte[] headerBytes = readHeader(in);
            if (headerBytes == null) {
                return;
            }
            String headerText = new String(headerBytes, StandardCharsets.ISO_8859_1);
            String[] lines = headerText.split("\r\n");
            if (lines.length == 0) {
                return;
            }
            String[] requestLine = lines[0].split(" ");
            if (requestLine.length < 3) {
                return;
            }
            String method = requestLine[0];
            String target = requestLine[1];
            Map<String, String> headers = parseHeaders(lines);
            String path = target;
            String rawQuery = "";
            int question = target.indexOf('?');
            if (question >= 0) {
                path = target.substring(0, question);
                rawQuery = target.substring(question + 1);
            }
            Map<String, String> query = parseQuery(rawQuery);
            int contentLength = parseInt(headers.get("content-length"), 0);
            byte[] body = contentLength > 0 ? readFully(in, contentLength) : new byte[0];

            if (isWebSocket(headers)) {
                handedOff = handOffWebSocket(socket, path, query, headers);
                return;
            }
            handleHttp(socket, method, path, query, headers, new String(body, StandardCharsets.UTF_8));
        } catch (Throwable error) {
            // 测试进程里不吞现场：打到 stderr，失败断言会看到
            if (!closed) {
                System.out.println("mock-link.error " + error);
            }
        } finally {
            if (!handedOff) {
                closeQuietly(socket);
            }
        }
    }

    /* -------------------------------------------------------------- HTTP */

    private void handleHttp(Socket socket, String method, String path, Map<String, String> query,
                            Map<String, String> headers, String body) throws IOException {
        Recorded recorded = record(method, path, query, headers, body, 0);
        int status;
        if ("OPTIONS".equals(method) && path.startsWith("/api/")) {
            status = 204;
            respond(socket, 204, "No Content", "");
        } else if ("POST".equals(method) && "/api/share".equals(path)) {
            status = createShare(socket, query, headers, body);
        } else if ("GET".equals(method) && path.startsWith("/api/share/")) {
            status = getShare(socket, path.substring("/api/share/".length()), query, headers, body);
        } else if ("DELETE".equals(method) && path.startsWith("/api/share/")) {
            status = revokeShare(socket, path.substring("/api/share/".length()), query, headers, body);
        } else if ("GET".equals(method) && "/api/shares".equals(path)) {
            status = listShares(socket, query, headers, body);
        } else {
            status = 404;
            respondJson(socket, 404, obj("ok", bool(false), "error", str("not-found")));
        }
        recorded.status = status;
    }

    private int createShare(Socket socket, Map<String, String> query, Map<String, String> headers, String body) throws IOException {
        J parsed;
        try {
            parsed = Json.INSTANCE.parse(body);
        } catch (Throwable error) {
            respondJson(socket, 400, obj("ok", bool(false), "error", str("bad-json")));
            return 400;
        }
        String pub = parsed.str("pub");
        if (pub == null || !pub.matches("[0-9a-fA-F]{128}")) {
            respondJson(socket, 400, obj("ok", bool(false), "error", str("pub")));
            return 400;
        }
        String deviceId = normalizeToken(parsed.str("deviceId"));
        if (deviceId.isEmpty() || deviceId.length() != 16
                || !deviceId.equals(DeviceIdentity.Companion.deviceIdOf(unhex(pub)))) {
            respondJson(socket, 400, obj("ok", bool(false), "error", str("deviceId")));
            return 400;
        }
        List<J> files = new ArrayList<>();
        List<J> input = parsed.arr("files");
        if (input == null || input.isEmpty() || input.size() > LinkDefaults.MAX_FILES) {
            respondJson(socket, 400, obj("ok", bool(false), "error", str("files")));
            return 400;
        }
        for (J item : input) {
            String name = item.str("name");
            Long size = item.num("size");
            if (name == null || name.isEmpty() || size == null || size < 0 || size > LinkDefaults.MAX_FILE_BYTES) {
                respondJson(socket, 400, obj("ok", bool(false), "error", str("files")));
                return 400;
            }
            files.add(obj("name", str(name), "size", num(size), "mime", str(item.str("mime") == null ? "" : item.str("mime"))));
        }
        String token = normalizeToken(parsed.str("token"));
        if (token.isEmpty()) {
            token = newToken();
        }
        if (!LinkProtocol.INSTANCE.validToken(token)) {
            respondJson(socket, 400, obj("ok", bool(false), "error", str("token")));
            return 400;
        }
        Long requestedTtl = parsed.num("ttlSec");
        long ttl = requestedTtl == null || requestedTtl <= 0
                ? LinkDefaults.TTL_DEFAULT_SEC
                : Math.max(LinkDefaults.TTL_MIN_SEC, Math.min(LinkDefaults.TTL_MAX_SEC, requestedTtl));
        long createdAt = System.currentTimeMillis();
        long expiresAt = createdAt + ttl * 1000L;
        String shareId = randomHex(16);
        Share share = new Share(token, shareId, deviceId, pub.toLowerCase(Locale.ROOT), files, ttl, createdAt, expiresAt);
        byId.put(shareId, share);
        byToken.put(token, share);
        respondJson(socket, 201, obj(
                "ok", bool(true),
                "shareId", str(shareId),
                "token", str(token),
                "createdAt", num(createdAt),
                "expiresAt", num(expiresAt)
        ));
        return 201;
    }

    private int getShare(Socket socket, String rawToken, Map<String, String> query,
                         Map<String, String> headers, String body) throws IOException {
        Share share = byToken.get(normalizeToken(rawToken));
        if (share == null) {
            respondJson(socket, 404, obj("status", str("not-found")));
            return 404;
        }
        respondJson(socket, 200, obj(
                "status", str(statusOf(share)),
                "shareId", str(share.shareId),
                "deviceId", str(share.deviceId),
                "pub", str(share.pub),
                "files", arr(share.files),
                "createdAt", num(share.createdAt),
                "expiresAt", num(share.expiresAt),
                "visitCount", num(share.visitCount.get())
        ));
        return 200;
    }

    private int listShares(Socket socket, Map<String, String> query,
                           Map<String, String> headers, String body) throws IOException {
        String deviceId = normalizeToken(query.get("deviceId"));
        if (deviceId.isEmpty()) {
            respondJson(socket, 400, obj("ok", bool(false), "error", str("deviceId")));
            return 400;
        }
        List<J> shares = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Share share : byId.values()) {
            if (!share.deviceId.equals(deviceId) || share.revoked || share.expiresAt <= now) {
                continue;
            }
            shares.add(obj(
                    "shareId", str(share.shareId),
                    "files", arr(share.files),
                    "createdAt", num(share.createdAt),
                    "expiresAt", num(share.expiresAt),
                    "visitCount", num(share.visitCount.get())
            ));
        }
        respondJson(socket, 200, obj("shares", arr(shares)));
        return 200;
    }

    private int revokeShare(Socket socket, String idOrToken, Map<String, String> query,
                            Map<String, String> headers, String body) throws IOException {
        J parsed;
        try {
            parsed = Json.INSTANCE.parse(body);
        } catch (Throwable error) {
            respondJson(socket, 400, obj("ok", bool(false), "error", str("bad-json")));
            return 400;
        }
        String deviceId = normalizeToken(parsed.str("deviceId"));
        String token = normalizeToken(parsed.str("token"));
        String nonce = parsed.str("nonce");
        String sig = parsed.str("sig");
        Long ts = parsed.num("ts");
        Share share = byId.get(idOrToken);
        if (share == null) {
            share = byToken.get(normalizeToken(idOrToken));
        }
        if (share == null) {
            respondJson(socket, 404, obj("ok", bool(false), "error", str("not-found")));
            return 404;
        }
        if (!share.deviceId.equals(deviceId)) {
            respondJson(socket, 403, obj("ok", bool(false), "error", str("not-owner")));
            return 403;
        }
        if (!share.token.equals(token)) {
            respondJson(socket, 400, obj("ok", bool(false), "error", str("token")));
            return 400;
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        if (ts == null || Math.abs(nowSec - ts) > 300L) {
            respondJson(socket, 400, obj("ok", bool(false), "error", str("ts")));
            return 400;
        }
        if (nonce == null || !nonce.matches("[A-Za-z0-9_-]{8,64}")) {
            respondJson(socket, 400, obj("ok", bool(false), "error", str("nonce")));
            return 400;
        }
        if (sig == null || !sig.matches("[0-9a-fA-F]{128}")) {
            respondJson(socket, 400, obj("ok", bool(false), "error", str("sig")));
            return 400;
        }
        boolean verified = DeviceIdentity.Companion.verify(
                unhex(share.pub),
                sig,
                "huchuan/revoke1/v1",
                LinkProtocol.INSTANCE.revokeFields(deviceId, token, ts, nonce)
        );
        if (!verified) {
            respondJson(socket, 403, obj("ok", bool(false), "error", str("bad-signature")));
            return 403;
        }
        boolean already = share.revoked;
        share.revoked = true;
        kickRoom(share);
        respondJson(socket, 200, obj(
                "ok", bool(true),
                "shareId", str(share.shareId),
                "revokedAt", num(System.currentTimeMillis()),
                "alreadyRevoked", bool(already)
        ));
        return 200;
    }

    private void kickRoom(Share share) {
        Rfc6455 first;
        Rfc6455 second;
        synchronized (share) {
            first = share.sharer;
            second = share.guest;
        }
        if (first != null) {
            first.sendByeAndClose("gone", 4001);
        }
        if (second != null) {
            second.sendByeAndClose("gone", 4001);
        }
    }

    /* -------------------------------------------------------------- WebSocket 握手 */

    private boolean isWebSocket(Map<String, String> headers) {
        String upgrade = headers.get("upgrade");
        return upgrade != null && upgrade.toLowerCase(Locale.ROOT).contains("websocket");
    }

    private boolean handOffWebSocket(Socket socket, String path, Map<String, String> query,
                                     Map<String, String> headers) throws IOException {
        if (!"/signal".equals(path) && !"/ws".equals(path)) {
            respondJson(socket, 404, obj("ok", bool(false), "error", str("not-found")));
            return false;
        }
        String shareId = query.get("share");
        Share share = shareId == null ? null : byId.get(shareId);
        String role = query.getOrDefault("role", query.containsKey("ep") ? "guest" : "sharer");
        if (share == null || share.revoked || share.expiresAt <= System.currentTimeMillis()) {
            recordUpgrade(role, shareId, query, 410, false);
            respond(socket, 410, "Gone", "");
            return false;
        }
        if ("sharer".equals(role)) {
            String dev = LinkProtocol.INSTANCE.normalizeDeviceId(query.get("dev"));
            String nonce = query.get("nonce");
            String sig = query.get("sig");
            Long ts = parseLong(query.get("ts"));
            boolean sigOk = false;
            if (ts != null && nonce != null && sig != null
                    && nonce.matches("[A-Za-z0-9_-]{8,64}")
                    && sig.matches("[0-9a-fA-F]{128}")
                    && share.deviceId.equals(dev)
                    && Math.abs(System.currentTimeMillis() / 1000L - ts) <= 300L) {
                sigOk = DeviceIdentity.Companion.verify(
                        unhex(share.pub),
                        sig,
                        "huchuan/ws1/v1",
                        LinkProtocol.INSTANCE.wsFields(dev, nonce, ts)
                );
            }
            if (!sigOk) {
                recordUpgrade(role, shareId, query, 403, false);
                respond(socket, 403, "Forbidden", "");
                return false;
            }
            String key = headers.get("sec-websocket-key");
            if (key == null) {
                recordUpgrade(role, shareId, query, 400, sigOk);
                respond(socket, 400, "Bad Request", "");
                return false;
            }
            Rfc6455 connection = new Rfc6455(this, socket, "sharer", share);
            Rfc6455 previous;
            synchronized (share) {
                previous = share.sharer;
                share.sharer = connection;
            }
            recordUpgrade(role, shareId, query, 101, true);
            if (!upgrade(socket, key)) {
                synchronized (share) {
                    if (share.sharer == connection) {
                        share.sharer = previous;
                    }
                }
                recordUpgrade(role, shareId, query, 400, sigOk);
                respond(socket, 400, "Bad Request", "");
                return false;
            }
            if (previous != null) {
                previous.close(1001, "replaced");
            }
            connection.start();
            return true;
        }

        if (!"guest".equals(role)) {
            recordUpgrade(role, shareId, query, 400, false);
            respond(socket, 400, "Bad Request", "");
            return false;
        }
        String ep = query.get("ep");
        if (ep == null || !ep.matches("[A-Za-z0-9_-]{8,32}")) {
            recordUpgrade(role, shareId, query, 400, false);
            respond(socket, 400, "Bad Request", "");
            return false;
        }
        String key = headers.get("sec-websocket-key");
        if (key == null) {
            recordUpgrade(role, shareId, query, 400, false);
            respond(socket, 400, "Bad Request", "");
            return false;
        }
        boolean busy;
        synchronized (share) {
            busy = share.guest != null;
        }
        Rfc6455 connection = new Rfc6455(this, socket, "guest", share);
        if (!busy) {
            synchronized (share) {
                share.guest = connection;
            }
        }
        recordUpgrade(role, shareId, query, 101, false);
        if (!upgrade(socket, key)) {
            if (!busy) {
                synchronized (share) {
                    if (share.guest == connection) {
                        share.guest = null;
                    }
                }
            }
            recordUpgrade(role, shareId, query, 400, false);
            respond(socket, 400, "Bad Request", "");
            return false;
        }
        connection.start();
        if (busy) {
            connection.sendText("{\"t\":\"err\",\"code\":\"busy\"}");
            connection.sendByeAndClose("busy", 4000);
        }
        return true;
    }

    private void recordUpgrade(String role, String shareId, Map<String, String> query, int status, boolean sigOk) {
        upgrades.add(new WsUpgrade(role == null ? "" : role, shareId == null ? "" : shareId, new LinkedHashMap<>(query), status, sigOk));
    }

    private boolean upgrade(Socket socket, String key) throws IOException {
        String accept;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
            accept = Base64.getEncoder().encodeToString(digest);
        } catch (Exception error) {
            return false;
        }
        OutputStream out = socket.getOutputStream();
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        return true;
    }

    /* -------------------------------------------------------------- 房间语义 */

    void onText(Rfc6455 connection, String text) {
        Share share = connection.share;
        J message;
        try {
            message = Json.INSTANCE.parse(text);
        } catch (Throwable error) {
            return;
        }
        String type = message.str("t");
        if (type == null) {
            return;
        }
        if ("guest".equals(connection.role)) {
            if ("share-join".equals(type)) {
                joinFrames.incrementAndGet();
                String token = normalizeToken(message.str("token"));
                if (share.revoked || !share.token.equals(token)) {
                    connection.sendText("{\"t\":\"err\",\"code\":\"gone\"}");
                    connection.close(4005, "gone");
                    return;
                }
                Rfc6455 sharer;
                synchronized (share) {
                    sharer = share.sharer;
                }
                if (sharer == null) {
                    connection.sendText("{\"t\":\"bye\",\"code\":\"offline\"}");
                    connection.close(4002, "offline");
                    return;
                }
                connection.joined = true;
                connection.visitorId = randomHex(8);
                share.visitCount.incrementAndGet();
                connection.sendText(Json.INSTANCE.write(obj(
                        "t", str("joined"),
                        "shareId", str(share.shareId),
                        "visitorId", str(connection.visitorId)
                )));
                if (!share.muteIncoming) {
                    sharer.sendText(Json.INSTANCE.write(obj(
                            "t", str("share-incoming"),
                            "shareId", str(share.shareId),
                            "visitorId", str(connection.visitorId),
                            "epPub", str(message.str("epPub") == null ? "" : message.str("epPub"))
                    )));
                }
                return;
            }
            if ("answer".equals(type) || "ice".equals(type) || "bye".equals(type)) {
                Rfc6455 sharer;
                synchronized (share) {
                    sharer = share.sharer;
                }
                if (sharer != null) {
                    sharer.sendText(text);
                }
            }
            return;
        }

        if ("offer".equals(type) || "ice".equals(type) || "bye".equals(type) || "err".equals(type)) {
            Rfc6455 guest;
            synchronized (share) {
                guest = share.guest;
            }
            if (guest != null && guest.joined) {
                guest.sendText(text);
            }
        }
    }

    void onClose(Rfc6455 connection) {
        Share share = connection.share;
        boolean revoked = share.revoked;
        if ("sharer".equals(connection.role)) {
            Rfc6455 guest;
            synchronized (share) {
                if (share.sharer == connection) {
                    share.sharer = null;
                }
                guest = share.guest;
            }
            // 与 worker/src/room.js 的 webSocketClose 一致：旧分享者断开就通知房间里的人，
            // 即使新分享者已经接上；访客重新 join 后由新分享者补 offer。
            if (!revoked && guest != null && guest.joined) {
                guest.sendText("{\"t\":\"bye\",\"code\":\"peer-left\"}");
            }
        } else {
            Rfc6455 sharer;
            synchronized (share) {
                if (share.guest == connection) {
                    share.guest = null;
                }
                sharer = share.sharer;
            }
            if (!revoked && sharer != null && connection.joined) {
                sharer.sendText(Json.INSTANCE.write(obj(
                        "t", str("bye"),
                        "code", str("peer-left"),
                        "visitorId", str(connection.visitorId)
                )));
            }
        }
    }

    private String statusOf(Share share) {
        if (share.revoked) {
            return "revoked";
        }
        if (share.expiresAt <= System.currentTimeMillis()) {
            return "expired";
        }
        return share.sharer != null ? "available" : "offline";
    }

    /* -------------------------------------------------------------- 工具 */

    private Recorded record(String method, String path, Map<String, String> query,
                            Map<String, String> headers, String body, int status) {
        Recorded recorded = new Recorded(method, path, new LinkedHashMap<>(query), new LinkedHashMap<>(headers), body, status);
        requests.add(recorded);
        return recorded;
    }

    private void respondJson(Socket socket, int status, J.Obj body) throws IOException {
        String text = Json.INSTANCE.write(body);
        respond(socket, status, statusText(status), text, true);
    }

    private void respond(Socket socket, int status, String reason, String body) throws IOException {
        respond(socket, status, reason, body, false);
    }

    private void respond(Socket socket, int status, String reason, String body, boolean json) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + (json ? "Content-Type: application/json; charset=utf-8\r\n" : "Content-Type: text/plain; charset=utf-8\r\n")
                + "Content-Length: " + payload.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(payload);
        out.flush();
        closeQuietly(socket);
    }

    private static String statusText(int status) {
        switch (status) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 410: return "Gone";
            case 429: return "Too Many Requests";
            default: return "Status";
        }
    }

    private static byte[] readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int state = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                return out.size() == 0 ? null : out.toByteArray();
            }
            out.write(b);
            if ((state == 0 || state == 2) && b == '\r') {
                state += 1;
            } else if ((state == 1 || state == 3) && b == '\n') {
                state += 1;
                if (state == 4) {
                    return out.toByteArray();
                }
            } else {
                state = b == '\r' ? 1 : 0;
            }
            if (out.size() > 64 * 1024) {
                throw new IOException("header too large");
            }
        }
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int got = in.read(data, offset, length - offset);
            if (got < 0) {
                throw new IOException("truncated body");
            }
            offset += got;
        }
        return data;
    }

    private static Map<String, String> parseHeaders(String[] lines) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
        }
        return headers;
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> query = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return query;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                query.put(URLDecoder.decode(key, "UTF-8"), URLDecoder.decode(value, "UTF-8"));
            } catch (Exception error) {
                query.put(key, value);
            }
        }
        return query;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static String normalizeToken(String raw) {
        if (raw == null) {
            return "";
        }
        return B32.INSTANCE.normalize(raw);
    }

    private String newToken() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return B32.INSTANCE.encode(bytes);
    }

    String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        random.nextBytes(data);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte value : data) {
            sb.append(String.format("%02x", value & 0xff));
        }
        return sb.toString();
    }

    static byte[] unhex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte value : data) {
            sb.append(String.format("%02x", value & 0xff));
        }
        return sb.toString();
    }

    private static J.Str str(String value) {
        return new J.Str(value);
    }

    private static J.Num num(long value) {
        return new J.Num(value);
    }

    private static J.Bool bool(boolean value) {
        return new J.Bool(value);
    }

    private static J.Arr arr(List<J> values) {
        return new J.Arr(values);
    }

    private static J.Obj obj(Object... pairs) {
        Map<String, J> fields = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            fields.put((String) pairs[i], (J) pairs[i + 1]);
        }
        return new J.Obj(fields);
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException error) {
            // ignore
        }
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(serverSocket);
        handlers.shutdownNow();
    }

    /* -------------------------------------------------------------- RFC 6455 服务端 */

    static final class Rfc6455 {
        private final MockLinkServer server;
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final Object writeLock = new Object();
        private volatile boolean closed;
        final String role;
        final Share share;
        volatile String visitorId = "";
        volatile boolean joined;

        Rfc6455(MockLinkServer server, Socket socket, String role, Share share) throws IOException {
            this.server = server;
            this.socket = socket;
            this.role = role;
            this.share = share;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
            // 升级后这条连接是长连接（分享者要挂很久等访客）：撤掉 HTTP 阶段的 10 s 读超时，
            // 否则 mock 会每 10 s 把空闲的 WS 掐掉（客户端看到 1006），这不是 worker 的行为。
            socket.setSoTimeout(0);
        }

        void start() {
            Thread thread = new Thread(this::readLoop, "mock-link-ws-" + role);
            thread.setDaemon(true);
            thread.start();
        }

        private void readLoop() {
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            try {
                while (!closed) {
                    int b0 = in.read();
                    if (b0 < 0) {
                        break;
                    }
                    boolean fin = (b0 & 0x80) != 0;
                    int opcode = b0 & 0x0f;
                    int b1 = in.read();
                    if (b1 < 0) {
                        break;
                    }
                    boolean masked = (b1 & 0x80) != 0;
                    long length = b1 & 0x7f;
                    if (length == 126) {
                        length = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
                    } else if (length == 127) {
                        length = 0;
                        for (int i = 0; i < 8; i++) {
                            length = (length << 8) | (in.read() & 0xff);
                        }
                    }
                    if (length < 0 || length > 1024 * 1024) {
                        close(1009, "too-large");
                        break;
                    }
                    byte[] mask = new byte[4];
                    if (masked) {
                        readFully(in, mask);
                    }
                    byte[] payload = readFullyBytes(in, (int) length);
                    if (masked) {
                        for (int i = 0; i < payload.length; i++) {
                            payload[i] ^= mask[i % 4];
                        }
                    }
                    if (opcode == 0x8) {
                        sendFrame(0x8, payload);
                        break;
                    }
                    if (opcode == 0x9) {
                        sendFrame(0xA, payload);
                        continue;
                    }
                    if (opcode == 0xA) {
                        continue;
                    }
                    if (opcode == 0x1 || opcode == 0x0) {
                        message.write(payload);
                        if (fin) {
                            server.onText(this, message.toString("UTF-8"));
                            message.reset();
                        }
                    }
                }
            } catch (IOException error) {
                // 对端直接断开
            } finally {
                closed = true;
                closeQuietly(socket);
                server.onClose(this);
            }
        }

        void sendText(String text) {
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            sendFrame(0x1, payload);
        }

        void sendByeAndClose(String code, int closeCode) {
            sendText(Json.INSTANCE.write(obj("t", str("bye"), "code", str(code))));
            close(closeCode, code);
        }

        void close(int closeCode, String reason) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                byte[] text = reason.getBytes(StandardCharsets.UTF_8);
                int length = Math.min(text.length, 123);
                byte[] payload = new byte[2 + length];
                payload[0] = (byte) ((closeCode >> 8) & 0xff);
                payload[1] = (byte) (closeCode & 0xff);
                System.arraycopy(text, 0, payload, 2, length);
                sendFrame(0x8, payload);
            } catch (Throwable error) {
                // 对端已断开
            } finally {
                closeQuietly(socket);
            }
        }

        private void sendFrame(int opcode, byte[] payload) {
            synchronized (writeLock) {
                try {
                    out.write(0x80 | opcode);
                    if (payload.length < 126) {
                        out.write(payload.length);
                    } else if (payload.length <= 0xffff) {
                        out.write(126);
                        out.write((payload.length >> 8) & 0xff);
                        out.write(payload.length & 0xff);
                    } else {
                        out.write(127);
                        long length = payload.length;
                        for (int i = 7; i >= 0; i--) {
                            out.write((int) ((length >> (i * 8)) & 0xff));
                        }
                    }
                    out.write(payload);
                    out.flush();
                } catch (IOException error) {
                    closed = true;
                }
            }
        }

        private static void readFully(InputStream in, byte[] buffer) throws IOException {
            readFullyBytes(in, buffer.length, buffer);
        }

        private static byte[] readFullyBytes(InputStream in, int length) throws IOException {
            byte[] buffer = new byte[length];
            readFullyBytes(in, length, buffer);
            return buffer;
        }

        private static void readFullyBytes(InputStream in, int length, byte[] buffer) throws IOException {
            int offset = 0;
            while (offset < length) {
                int got = in.read(buffer, offset, length - offset);
                if (got < 0) {
                    throw new IOException("truncated frame");
                }
                offset += got;
            }
        }
    }
}
