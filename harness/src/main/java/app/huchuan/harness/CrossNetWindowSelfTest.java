package app.huchuan.harness;

import app.huchuan.core.m1.CrossNetWindow;
import app.huchuan.core.m1.KernelEvents;
import app.huchuan.core.m1.M1Kernel;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 跨网分享窗口（AGENTS §9 / docs/design/nat-traversal.md §7）的自检。
 *
 * 窗口的硬要求是三条不变量，每条都能被现实缺陷打红：
 *
 *   1. **开窗 = 换随机端口 + 开映射**。端口必须落在 49152..65535、不等于 51413 / 47100、
 *      且真的是会话正在监听的端口（从外部 TCP 连得进去）。打红方式：只开 setting 不换端口
 *      （port 恒为 btHint）、或者选端口时把 51413 放回候选。
 *   2. **关窗 = 撤映射 + 关 setting + 恢复原端口**。两个 setting 必须读回 false、status 必须
 *      回到 closed、监听端口必须回到窗口前的值。打红方式：把 close 里的 setPortMapping(false)
 *      注释掉（窗口关了但端口还映射着，正是本功能最怕的泄漏）。
 *   3. **到点自动关**。500 ms 的窗口睡 1 s 后必须已经撤销（调度任务 / 心跳），不是等人来关。
 *
 * 断言读的是**会话 live 真值**（kernel.btUpnpEnabled / btNatpmpEnabled 走 session.get_settings），
 * 不是 CrossNetWindow 自己的缓存；status 的 mappings 也来自 libtorrent 的 portmap_alert。
 */
final class CrossNetWindowSelfTest {

    private CrossNetWindowSelfTest() {
    }

    static void run(BiConsumer<String, Boolean> check) {
        failurePath(check);
        lifecycle(check);
        expiry(check);
    }

    // ---------------------------------------------------------------- 0. 失败路径：不抛异常、一句话

    private static void failurePath(BiConsumer<String, Boolean> check) {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "huchuan-selftest-window-cold-" + System.nanoTime());
        dir.mkdirs();
        M1Kernel kernel = null;
        try {
            // 故意不 start()：窗口必须返回失败码，而不是 NPE / IllegalStateException。
            kernel = new M1Kernel(dir, "window-cold", 47870, 47970, new Recorder());
            CrossNetWindow.OpenResult r = kernel.crossNetWindowOpen(1000);
            check.accept("window.cold_open_fails_with_code (code=" + r.getCode() + ")", !r.getOk());
            check.accept("window.cold_open_message_sentence", !r.getMessage().isEmpty());
            check.accept("window.cold_open_port_zero", r.getPort() == 0);
            CrossNetWindow.Status st = kernel.crossNetWindowStatus();
            check.accept("window.cold_status_closed",
                    !st.getOpen() && st.getPort() == 0 && st.getRemainingMs() == 0
                            && st.getMappings().isEmpty());
            check.accept("window.cold_close_noop", !kernel.crossNetWindowClose());
        } catch (Throwable t) {
            check.accept("window.cold_exception (" + t + ")", false);
            t.printStackTrace(System.out);
        } finally {
            if (kernel != null) {
                try {
                    kernel.close();
                } catch (Throwable ignored) {
                    // 没起来的内核关不掉也不影响结论
                }
            }
            deleteTree(dir);
        }
    }

    // ---------------------------------------------------------------- 1/2. 开窗 / 关窗

    private static void lifecycle(BiConsumer<String, Boolean> check) {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "huchuan-selftest-window-" + System.nanoTime());
        dir.mkdirs();
        M1Kernel kernel = null;
        try {
            Recorder events = new Recorder();
            kernel = new M1Kernel(dir, "window-selftest", 47860, 47960, events);
            kernel.start();

            int before = kernel.localBtPort();
            check.accept("window.baseline_listening (port=" + before + ")", before > 0);
            check.accept("window.baseline_upnp_off", !kernel.btUpnpEnabled());
            check.accept("window.baseline_natpmp_off", !kernel.btNatpmpEnabled());

            long t0 = System.currentTimeMillis();
            CrossNetWindow.OpenResult opened = kernel.crossNetWindowOpen(60_000);
            check.accept("window.open_ok (code=" + opened.getCode() + " " + opened.getMessage() + ")",
                    opened.getOk() && CrossNetWindow.CODE_OK.equals(opened.getCode()));
            check.accept("window.open_message_sentence", !opened.getMessage().isEmpty());

            CrossNetWindow.Status st = kernel.crossNetWindowStatus();
            int port = st.getPort();
            check.accept("window.status_open", st.getOpen());
            check.accept("window.port_in_dynamic_range (port=" + port + ")",
                    port >= CrossNetWindow.PORT_MIN && port <= CrossNetWindow.PORT_MAX);
            check.accept("window.port_avoids_fixed_ports (port=" + port + ")",
                    port != 51413 && port != 47100);
            check.accept("window.port_replaced_bt_port", port != before);
            check.accept("window.status_port_is_listen_port",
                    kernel.localBtPort() == port);
            check.accept("window.remaining_within_window (" + st.getRemainingMs() + "ms)",
                    st.getRemainingMs() > 0 && st.getRemainingMs() <= 60_000);
            check.accept("window.open_result_matches_status",
                    opened.getPort() == port && opened.getExpiresAt() > t0);

            // 会话 setting 的 live 真值：开了两个，就是映射窗口开的证据。
            check.accept("window.settings_upnp_on", kernel.btUpnpEnabled());
            check.accept("window.settings_natpmp_on", kernel.btNatpmpEnabled());

            // 端口真的在监听：从外部连一下比读字段更硬（字段可以骗人，accept 不会）。
            check.accept("window.port_accepts_tcp (127.0.0.1:" + port + ")", accepts(port));

            // mappings 来自 portmap_alert：至少两个传输各一条 TCP；有路由器时状态会走到 mapped。
            List<CrossNetWindow.Mapping> mappings = st.getMappings();
            boolean allInternal = !mappings.isEmpty();
            boolean closedStates = true;
            for (CrossNetWindow.Mapping m : mappings) {
                allInternal &= m.getInternalPort() == port;
                closedStates &= CrossNetWindow.STATE_REQUESTED.equals(m.getState())
                        || CrossNetWindow.STATE_MAPPED.equals(m.getState())
                        || CrossNetWindow.STATE_FAILED.equals(m.getState());
            }
            check.accept("window.mappings_present (" + mappings.size() + ")", mappings.size() >= 2);
            check.accept("window.mappings_internal_port", allInternal);
            check.accept("window.mappings_state_known", closedStates);

            boolean openLogged = events.lines().stream().anyMatch(line ->
                    line.startsWith("window_open ")
                            && line.contains(" port=" + port + " ")
                            && line.contains(" expires=")
                            && line.contains(" prev_port=" + before + " "));
            check.accept("window.open_log_has_port_and_expires (port=" + port + ")", openLogged);
            check.accept("window.open_refresh_session",
                    events.lines().stream().anyMatch(line ->
                            line.startsWith("window_refresh ") && line.contains(" open=true")
                                    && line.contains(" port=" + port + " ")));

            // 开着的窗口续期：不换端口、只推迟到期。
            CrossNetWindow.OpenResult extended = kernel.crossNetWindowOpen(120_000);
            CrossNetWindow.Status extendedStatus = kernel.crossNetWindowStatus();
            check.accept("window.extend_ok", extended.getOk()
                    && CrossNetWindow.CODE_ALREADY_OPEN.equals(extended.getCode()));
            check.accept("window.extend_keeps_port", extendedStatus.getPort() == port);
            check.accept("window.extend_pushes_expiry", extendedStatus.getRemainingMs() > 60_000);

            // ---- 关窗
            boolean closed = kernel.crossNetWindowClose();
            CrossNetWindow.Status after = kernel.crossNetWindowStatus();
            check.accept("window.close_reported", closed);
            check.accept("window.close_status_closed",
                    !after.getOpen() && after.getPort() == 0 && after.getRemainingMs() == 0);
            check.accept("window.close_mappings_cleared", after.getMappings().isEmpty());
            // 这一条是反向变异的靶子：把 close() 里的 setPortMapping(false) 注释掉就红。
            check.accept("window.close_settings_upnp_off", !kernel.btUpnpEnabled());
            check.accept("window.close_settings_natpmp_off", !kernel.btNatpmpEnabled());
            check.accept("window.close_restores_listen_port (port=" + kernel.localBtPort() + ")",
                    kernel.localBtPort() == before);
            check.accept("window.close_log_reason_manual", events.lines().stream().anyMatch(line ->
                    line.startsWith("window_close ") && line.contains(" reason=manual ")
                            && line.endsWith(" was_open=true")));
            check.accept("window.close_refresh_session", events.lines().stream().anyMatch(line ->
                    line.startsWith("window_refresh ") && line.endsWith(" open=false")));
            check.accept("window.close_is_idempotent", !kernel.crossNetWindowClose());
        } catch (Throwable t) {
            check.accept("window.lifecycle_exception (" + t + ")", false);
            t.printStackTrace(System.out);
        } finally {
            if (kernel != null) {
                try {
                    kernel.close();
                } catch (Throwable ignored) {
                    // 端口与临时目录在 finally 里收
                }
            }
            deleteTree(dir);
        }
    }

    // ---------------------------------------------------------------- 3. 到期自动关

    private static void expiry(BiConsumer<String, Boolean> check) {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "huchuan-selftest-window-expiry-" + System.nanoTime());
        dir.mkdirs();
        M1Kernel kernel = null;
        try {
            Recorder events = new Recorder();
            kernel = new M1Kernel(dir, "window-expiry", 47861, 47961, events);
            kernel.start();
            int before = kernel.localBtPort();

            CrossNetWindow.OpenResult opened = kernel.crossNetWindowOpen(500);
            check.accept("window.expiry_open_ok (port=" + opened.getPort() + ")", opened.getOk());
            CrossNetWindow.Status during = kernel.crossNetWindowStatus();
            check.accept("window.expiry_open_before_deadline", during.getOpen());

            // 窗口 500 ms：睡 1 s 必须已经自动撤销（调度任务 / 200 ms 心跳兜底都够）。
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            CrossNetWindow.Status after = kernel.crossNetWindowStatus();
            check.accept("window.expired_auto_closed", !after.getOpen());
            check.accept("window.expired_port_zero", after.getPort() == 0 && after.getRemainingMs() == 0);
            // 到期路径与手动路径走同一个 close()，所以 setting 与监听端口同样必须复原。
            check.accept("window.expired_settings_off",
                    !kernel.btUpnpEnabled() && !kernel.btNatpmpEnabled());
            check.accept("window.expired_restores_listen_port (port=" + kernel.localBtPort() + ")",
                    kernel.localBtPort() == before);
            check.accept("window.expired_log_reason_expired", events.lines().stream().anyMatch(line ->
                    line.startsWith("window_close ") && line.contains(" reason=expired ")
                            && line.endsWith(" was_open=true")));
        } catch (Throwable t) {
            check.accept("window.expiry_exception (" + t + ")", false);
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

    // ---------------------------------------------------------------- 工具

    /** 从外部连一下窗口端口：能连上 = libtorrent 真的在这个端口上监听。 */
    private static boolean accepts(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 事件出口：把内核日志拼成一行，供日志断言（window_open / window_close / window_refresh）。 */
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
        // Windows 上刚停掉的会话可能还占着句柄：删不掉就算了，临时目录不参与断言。
        file.delete();
    }
}
