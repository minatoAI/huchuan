package app.huchuan.harness;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SettingsPack;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.swig.settings_pack;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * 直读 libtorrent 会话/任务的真值：settings_pack 的 enable_dht、dht_running、torrent 的字节计数。
 *
 * 为什么用反射：M1Kernel.bt / BtSession.manager / BtSession.tasks 在 core 里是 internal/private，
 * core 尚未把「settings_pack 实际生效值」「torrent 的 payload 上下行字节」暴露成公开读口，
 * 而本轮不改 core（另一个 agent 在改并行目录）。core 补上公开读口后，这个类可以整体删掉，
 * 断言不用动（日志字段是 Probe 的对外契约）。待核心补的信号见 docs/reviews/m1-harness-hardening.md。
 *
 * 反射拿不到就返回 source=unavailable + error=...，脚本会把这当成 FAIL —— 不允许静默降级。
 */
final class Probe {

    private final Object bt;
    private final SessionManager manager;
    private final Object tasks;
    private final Object lock;
    private final String error;

    private Probe(Object bt, SessionManager manager, Object tasks, Object lock, String error) {
        this.bt = bt;
        this.manager = manager;
        this.tasks = tasks;
        this.lock = lock;
        this.error = error;
    }

    static Probe of(Object kernel) {
        return ofField(kernel, "bt");
    }

    /** 同一套读口，换到公开会话（M1Kernel.publicBt）：公开条目的字节计数只能从这里读。 */
    static Probe ofPublic(Object kernel) {
        return ofField(kernel, "publicBt");
    }

    private static Probe ofField(Object kernel, String fieldName) {
        try {
            Object bt = field(kernel, fieldName);
            if (bt == null) {
                return new Probe(null, null, null, null, "M1Kernel." + fieldName + " is null (kernel not started?)");
            }
            SessionManager manager = (SessionManager) field(bt, "manager");
            Object tasks = field(bt, "tasks");
            Object lock = field(bt, "lock");
            return new Probe(bt, manager, tasks, lock, "");
        } catch (Throwable t) {
            return new Probe(null, null, null, null, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    boolean ok() {
        return bt != null && manager != null && tasks != null && lock != null;
    }

    String error() {
        return error;
    }

    /** bt_probe 行：三个值都直接问 libtorrent 会话，不读 core 的自报日志。 */
    String sessionLine() {
        if (!ok()) {
            return "source=unavailable error=" + safe(error);
        }
        StringBuilder sb = new StringBuilder("source=libtorrent_session");
        try {
            SettingsPack settings = manager.settings();
            sb.append(" enable_dht=").append(flag(settings == null ? null : Boolean.valueOf(settings.isEnableDht())));
            sb.append(" enable_lsd=").append(flag(settings == null ? null : Boolean.valueOf(settings.isEnableLsd())));
            sb.append(" enable_upnp=").append(flag(bool(settings, settings_pack.bool_types.enable_upnp.swigValue())));
            sb.append(" enable_natpmp=").append(flag(bool(settings, settings_pack.bool_types.enable_natpmp.swigValue())));
            sb.append(" dht_running=").append(manager.isDhtRunning());
            sb.append(" dht_nodes=").append(manager.dhtNodes());
        } catch (Throwable t) {
            sb.append(" error=").append(safe(t.toString()));
        }
        return sb.toString();
    }

    /** 采样行：脚本要求每进程 ≥3 条且 dht_nodes 全 0、dht_running 全 false、enable_dht 全 false。 */
    String sampleLine() {
        if (!ok()) {
            return "source=unavailable error=" + safe(error);
        }
        try {
            SettingsPack settings = manager.settings();
            Boolean dht = settings == null ? null : Boolean.valueOf(settings.isEnableDht());
            return "dht_nodes=" + manager.dhtNodes()
                    + " dht_running=" + manager.isDhtRunning()
                    + " enable_dht=" + flag(dht);
        } catch (Throwable t) {
            return "source=unavailable error=" + safe(t.toString());
        }
    }

    /** 某个 fileId 在 libtorrent 里的字节计数（不是文件大小）。 */
    String torrentLine(String fileId) {
        if (!ok()) {
            return "source=unavailable error=" + safe(error);
        }
        TorrentStatus st;
        try {
            synchronized (lock) {
                Object task = ((Map<?, ?>) tasks).get(fileId);
                if (task == null) {
                    return "source=libtorrent status=missing file_id=" + fileId;
                }
                Object handle = field(task, "handle");
                st = ((TorrentHandle) handle).status(true);
            }
        } catch (Throwable t) {
            return "source=libtorrent status=error error=" + safe(t.toString()) + " file_id=" + fileId;
        }
        if (st == null) {
            return "source=libtorrent status=null file_id=" + fileId;
        }
        return "source=libtorrent file_id=" + fileId
                + " total_done=" + st.totalDone()
                + " payload_downloaded=" + st.totalPayloadDownload()
                + " payload_uploaded=" + st.totalPayloadUpload()
                + " total_download=" + st.totalDownload()
                + " total_upload=" + st.totalUpload()
                + " num_peers=" + st.numPeers()
                + " num_seeds=" + st.numSeeds()
                + " num_connections=" + st.numConnections()
                + " finished=" + st.isFinished();
    }

    /**
     * 每个 BT 对端的完成度（ppm，1000000 = 对端数据完整）。
     *
     * E0 里 S 侧用它确认两个下载者都真的到了 100%，而不是只看到「连上了」。
     * 直接读 TorrentHandle.peerInfo()，不经过 core 的读数。
     */
    String peerProgressLine(String fileId) {
        if (!ok()) {
            return "source=unavailable error=" + safe(error);
        }
        java.util.List<org.libtorrent4j.PeerInfo> peers;
        try {
            synchronized (lock) {
                Object task = ((Map<?, ?>) tasks).get(fileId);
                if (task == null) {
                    return "source=libtorrent status=missing file_id=" + fileId;
                }
                Object handle = field(task, "handle");
                peers = ((TorrentHandle) handle).peerInfo();
            }
        } catch (Throwable t) {
            return "source=libtorrent status=error error=" + safe(t.toString()) + " file_id=" + fileId;
        }
        StringBuilder sb = new StringBuilder("source=libtorrent file_id=").append(fileId).append(" n=").append(peers.size());
        for (int i = 0; i < peers.size(); i++) {
            org.libtorrent4j.PeerInfo p = peers.get(i);
            sb.append(" p").append(i).append('=').append(safe(p.ip())).append(':').append(p.progressPpm());
            sb.append(":0x").append(Integer.toHexString(p.flags()));
        }
        return sb.toString();
    }

    /** 变异注入：把会话的 enable_dht 打开。只给反向测试用，正常路径不调用。 */
    boolean turnDhtOn() {
        if (manager == null) {
            return false;
        }
        try {
            SettingsPack pack = new SettingsPack();
            pack.setEnableDht(true);
            manager.applySettings(pack);
            try {
                manager.startDht();
            } catch (Throwable ignored) {
                // 能开就开；开不了也有 settings_pack 的值可断言
            }
            SettingsPack now = manager.settings();
            return now != null && now.isEnableDht();
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 工具

    private static Boolean bool(SettingsPack settings, int key) {
        if (settings == null) {
            return null;
        }
        try {
            return Boolean.valueOf(settings.getBoolean(key));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String flag(Boolean value) {
        return value == null ? "unknown" : value.toString();
    }

    private static Object field(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(target.getClass().getName() + "." + name);
    }

    private static String safe(String s) {
        return s == null ? "" : s.replaceAll("[\\s]+", "_");
    }
}
