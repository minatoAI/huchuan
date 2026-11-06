package app.huchuan.harness;

import app.huchuan.core.link.Transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试/桌面临时用的 Transport 实现：java.net.http.WebSocket。
 *
 * 生产上桌面与 Android 各自实现 Transport（Android 用 OkHttp 或平台能力），core 不引这个类。
 * 这里只服务 harness：把 JDK 的 WebSocket 回调翻译成 core 的 open/send/close/onMessage 语义，
 * 握手失败时把 HTTP 状态码（例如撤销后的 410）放进 onClosed.code。
 */
final class JavaWsTransport implements Transport {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final AtomicBoolean opened = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private volatile Listener listener;

    @Override
    public void open(String url, Listener listener) {
        this.listener = listener;
        CLIENT.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create(url), new WebSocket.Listener() {
                    private final StringBuilder partial = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        socket.compareAndSet(null, webSocket);
                        webSocket.request(1);
                        if (opened.compareAndSet(false, true)) {
                            listener.onOpen();
                        }
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        synchronized (partial) {
                            partial.append(data);
                            if (last) {
                                String text = partial.toString();
                                partial.setLength(0);
                                listener.onMessage(text);
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        finish(statusCode, reason);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        finish(-1, describe(error));
                    }
                })
                .whenComplete((webSocket, error) -> {
                    if (error != null) {
                        Throwable cause = unwrap(error);
                        int code = -1;
                        String reason;
                        if (cause instanceof WebSocketHandshakeException) {
                            WebSocketHandshakeException handshake = (WebSocketHandshakeException) cause;
                            code = handshake.getResponse().statusCode();
                            reason = "handshake:" + code;
                        } else {
                            reason = "connect:" + describe(cause);
                        }
                        finish(code, reason);
                    } else {
                        socket.compareAndSet(null, webSocket);
                    }
                });
    }

    @Override
    public boolean send(String text) {
        WebSocket current = socket.get();
        if (current == null || finished.get()) {
            return false;
        }
        try {
            current.sendText(text, true).get(2, TimeUnit.SECONDS);
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    @Override
    public void close(int code, String reason) {
        WebSocket current = socket.get();
        try {
            if (current != null) {
                current.sendClose(code, reason == null ? "" : reason).get(1, TimeUnit.SECONDS);
                current.abort();
            }
        } catch (Throwable error) {
            if (current != null) {
                current.abort();
            }
        }
        finish(code, reason);
    }

    private void finish(int code, String reason) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        Listener target = listener;
        if (target != null) {
            target.onClosed(code, clean(reason));
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "";
        }
        String message = error.getMessage();
        return clean(error.getClass().getSimpleName() + (message == null ? "" : ":" + message));
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() <= 160 ? text : text.substring(0, 160);
    }
}
