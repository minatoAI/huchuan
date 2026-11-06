package app.huchuan.harness;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地最小 BEP 3 tracker（跨网发现的验收装置）。
 *
 * 为什么是本机进程而不是线上 Worker：本机访问 *.workers.dev 被 DNS 污染 / SNI 拦截（任务口径），
 * 而且验收必须可重复 —— 线上 tracker 的 peer 表、passkey 状态都不由我们控制。这里用 JDK 自带的
 * `com.sun.net.httpserver` 起一个真 HTTP 服务，把「客户端到底发了什么」原样记下来：
 *
 *   POST /api/tracker/register  记 method + path + 原始 JSON body，回 `{"ok":true,"created":true}`
 *   GET  /announce              记**原始查询串**（info_hash 是二进制，必须看 %XX 原文），回 bencode
 *
 * 断言方（[TrackerSelfTest]）从 [lastRegisterBody] / [lastAnnounceQuery] 里取值比对，
 * 所以「注册了、announce 参数对、peer 被合并」三件事都是可证伪的：客户端少发一个参数、
 * 少打一次请求，这里就是空的。
 */
final class MockTracker implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    private final AtomicInteger registerCount = new AtomicInteger();
    private final AtomicInteger announceCount = new AtomicInteger();
    private final List<String> registerBodies = Collections.synchronizedList(new ArrayList<>());
    private final List<String> registerRequestLines = Collections.synchronizedList(new ArrayList<>());
    private final List<String> announceQueries = Collections.synchronizedList(new ArrayList<>());
    private final ByteArrayOutputStream peers4 = new ByteArrayOutputStream();
    private final ByteArrayOutputStream peers6 = new ByteArrayOutputStream();

    private volatile long intervalSeconds = 600;
    private volatile long minIntervalSeconds = 300;
    private volatile String failureReason = "";
    private volatile String registerReply = "{\"ok\":true,\"created\":true}";
    private volatile int announceStatus = 200;

    MockTracker() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tracker/register", this::onRegister);
        server.createContext("/announce", this::onAnnounce);
        server.setExecutor(pool);
        server.start();
    }

    /** `http://127.0.0.1:<port>`：内核拿它当 trackerBaseUrl。 */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int totalRequests() {
        return registerCount.get() + announceCount.get();
    }

    int registerCount() {
        return registerCount.get();
    }

    int announceCount() {
        return announceCount.get();
    }

    String lastRegisterBody() {
        synchronized (registerBodies) {
            return registerBodies.isEmpty() ? "" : registerBodies.get(registerBodies.size() - 1);
        }
    }

    String lastRegisterRequestLine() {
        synchronized (registerRequestLines) {
            return registerRequestLines.isEmpty() ? "" : registerRequestLines.get(registerRequestLines.size() - 1);
        }
    }

    String lastAnnounceQuery() {
        synchronized (announceQueries) {
            return announceQueries.isEmpty() ? "" : announceQueries.get(announceQueries.size() - 1);
        }
    }

    List<String> announceQueries() {
        synchronized (announceQueries) {
            return new ArrayList<>(announceQueries);
        }
    }

    void setIntervalSeconds(long seconds, long minSeconds) {
        intervalSeconds = seconds;
        minIntervalSeconds = minSeconds;
    }

    void setFailureReason(String reason) {
        failureReason = reason == null ? "" : reason;
    }

    /** 往 compact peers 里加一条：IPv4 字面量 → 6 字节，IPv6 字面量 → 18 字节。 */
    void addPeer(String literalIp, int port) throws IOException {
        byte[] addr = InetAddress.getByName(literalIp).getAddress();
        byte[] tail = {(byte) ((port >> 8) & 0xff), (byte) (port & 0xff)};
        if (addr.length == 4) {
            peers4.write(addr);
            peers4.write(tail);
        } else if (addr.length == 16) {
            peers6.write(addr);
            peers6.write(tail);
        } else {
            throw new IllegalArgumentException("not an IP literal: " + literalIp);
        }
    }

    private void onRegister(HttpExchange exchange) throws IOException {
        registerCount.incrementAndGet();
        registerRequestLines.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
        registerBodies.add(readBody(exchange.getRequestBody()));
        byte[] body = registerReply.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void onAnnounce(HttpExchange exchange) throws IOException {
        announceCount.incrementAndGet();
        announceQueries.add(exchange.getRequestURI().getRawQuery());
        byte[] body;
        synchronized (peers4) {
            body = announceBody(
                    intervalSeconds,
                    minIntervalSeconds,
                    peers4.toByteArray(),
                    peers6.toByteArray(),
                    failureReason);
        }
        exchange.getResponseHeaders().add("content-type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(announceStatus, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static String readBody(InputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    /** BEP 3 的 announce 应答：normal 与 failure 两种形态都按 bencode 拼字节。 */
    static byte[] announceBody(long interval, long minInterval, byte[] peers, byte[] peers6, String failure)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('d');
        if (failure != null && !failure.isEmpty()) {
            out.write("14:failure reason".getBytes(StandardCharsets.US_ASCII));
            writeString(out, failure.getBytes(StandardCharsets.UTF_8));
        } else {
            out.write(("8:intervali" + interval + "e").getBytes(StandardCharsets.US_ASCII));
            out.write(("12:min intervali" + minInterval + "e").getBytes(StandardCharsets.US_ASCII));
            out.write("8:completei1e".getBytes(StandardCharsets.US_ASCII));
            out.write("10:incompletei0e".getBytes(StandardCharsets.US_ASCII));
            out.write("5:peers".getBytes(StandardCharsets.US_ASCII));
            writeString(out, peers);
            out.write("6:peers6".getBytes(StandardCharsets.US_ASCII));
            writeString(out, peers6);
        }
        out.write('e');
        return out.toByteArray();
    }

    private static void writeString(ByteArrayOutputStream out, byte[] value) throws IOException {
        out.write(String.valueOf(value.length).getBytes(StandardCharsets.US_ASCII));
        out.write(':');
        out.write(value);
    }

    /** 原始查询串里某个参数的**字节值**（info_hash / peer_id 是二进制，不能当文本读）。 */
    static byte[] bytesParam(String rawQuery, String key) {
        if (rawQuery == null) {
            return null;
        }
        for (String chunk : rawQuery.split("&")) {
            int cut = chunk.indexOf('=');
            if (cut <= 0) {
                continue;
            }
            if (!chunk.substring(0, cut).equals(key)) {
                continue;
            }
            return percentDecode(chunk.substring(cut + 1));
        }
        return null;
    }

    /** 原始查询串里某个参数的文本值；不存在返回 null。 */
    static String textParam(String rawQuery, String key) {
        byte[] raw = bytesParam(rawQuery, key);
        return raw == null ? null : new String(raw, StandardCharsets.UTF_8);
    }

    /** 参数有没有被百分号编码（info_hash 必须整条都是 %XX：二进制不能原样塞进 URL）。 */
    static boolean isFullyPercentEncoded(String rawQuery, String key) {
        if (rawQuery == null) {
            return false;
        }
        for (String chunk : rawQuery.split("&")) {
            int cut = chunk.indexOf('=');
            if (cut <= 0 || !chunk.substring(0, cut).equals(key)) {
                continue;
            }
            String value = chunk.substring(cut + 1);
            if (value.isEmpty() || value.length() % 3 != 0) {
                return false;
            }
            for (int i = 0; i < value.length(); i += 3) {
                if (value.charAt(i) != '%') {
                    return false;
                }
                if (Character.digit(value.charAt(i + 1), 16) < 0 || Character.digit(value.charAt(i + 2), 16) < 0) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    static byte[] percentDecode(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length());
        for (int i = 0; i < text.length(); ) {
            char c = text.charAt(i);
            if (c == '%' && i + 2 < text.length()) {
                int hi = Character.digit(text.charAt(i + 1), 16);
                int lo = Character.digit(text.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 3;
                    continue;
                }
            }
            if (c == '+') {
                out.write(' ');
            } else {
                out.write((byte) c);
            }
            i++;
        }
        return out.toByteArray();
    }

    @Override
    public void close() {
        try {
            server.stop(0);
        } catch (Throwable ignored) {
            // 停不掉不影响结论
        }
        pool.shutdownNow();
    }
}
