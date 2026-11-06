package app.huchuan.harness;

import app.huchuan.core.m1.BtSession;
import org.libtorrent4j.LibTorrent;
import org.libtorrent4j.TorrentFlags;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

/**
 * super-seeding（BEP 16）能力探针：只回答「这份 libtorrent4j / 这份 native 库到底能不能开」。
 *
 * 三条证据分开打，不合并成一句「支持」：
 *   flags    —— TorrentFlags.SUPER_SEEDING 的位值（jar 里有没有这个符号，是 build 的事实）
 *   settings —— settings_pack 的布尔表里有没有 super_seeding（决定「会话级开关」存不存在）
 *   readback —— 真起一个会话、挂一个种、把 flag 设进去，再从 `torrent::flags()` 读回来
 *
 * readback 是唯一能证明「native 库把这个特性编译进来了」的一条：libtorrent 源码里那一位位于
 * `#ifndef TORRENT_DISABLE_SUPERSEEDING` 之内，编译掉的库读回来恒为 0。
 *
 * 用法：java ... app.huchuan.harness.SuperSeedProbe <workDir>
 * 退出码：0 = 三条证据都拿到了（readback 必须为 true），1 = 有缺失
 */
public final class SuperSeedProbe {

    static final long FILE_BYTES = 4L * 1024 * 1024;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: SuperSeedProbe <workDir>");
            System.exit(2);
        }
        Path work = Paths.get(args[0]).toAbsolutePath();
        Files.createDirectories(work);
        boolean ok = true;

        System.out.println("PROBE libtorrent version=" + LibTorrent.version()
                + " revision=" + LibTorrent.revision()
                + " libtorrent4j=" + LibTorrent.libtorrent4jVersion()
                + " java=" + System.getProperty("java.version"));

        // 1) flags：jar 里的符号 + 位值
        int bit;
        try {
            bit = TorrentFlags.SUPER_SEEDING.to_int();
            System.out.println("PROBE flags SUPER_SEEDING=int " + bit
                    + " hex=0x" + Integer.toHexString(bit)
                    + " symbol=org.libtorrent4j.TorrentFlags.SUPER_SEEDING");
        } catch (Throwable t) {
            bit = -1;
            ok = false;
            System.out.println("PROBE flags SUPER_SEEDING=absent error=" + clean(t.toString()));
        }

        // 2) settings_pack 的布尔表：有没有 super_seeding
        try {
            Class<?> boolTypes = Class.forName("org.libtorrent4j.swig.settings_pack$bool_types");
            int n = 0;
            StringBuilder hits = new StringBuilder();
            for (Field f : boolTypes.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (!f.getType().equals(boolTypes)) {
                    continue;
                }
                n++;
                if (f.getName().toLowerCase().contains("super")) {
                    hits.append(f.getName()).append(',');
                }
            }
            System.out.println("PROBE settings bool_count=" + n
                    + " super_seeding=" + (hits.length() == 0 ? "absent" : hits));
        } catch (Throwable t) {
            ok = false;
            System.out.println("PROBE settings scan=error " + clean(t.toString()));
        }

        // 3) readback：真挂一个种，把 flag 设进去再读回来
        File payload = work.resolve("probe-" + (FILE_BYTES >> 20) + "m.bin").toFile();
        writePayload(payload, FILE_BYTES);
        BtSession bt = new BtSession(0, work.resolve("bt").toFile(), true);
        try {
            int port = bt.start();
            System.out.println("PROBE session listen_port=" + port);
            BtSession.SeededTorrent seeded = bt.createPrivateTorrent(payload);
            bt.addSeed(seeded, work.toFile(), true);
            boolean finished = waitFinished(bt, seeded.getFileId(), 60_000L);
            System.out.println("PROBE seed finished=" + finished
                    + " file_id=" + seeded.getFileId()
                    + " size=" + seeded.getSize());
            boolean on = bt.superSeeding(seeded.getFileId());
            System.out.println("PROBE readback add_time_flag_on=" + on);
            bt.setSuperSeeding(seeded.getFileId(), false);
            boolean off = bt.superSeeding(seeded.getFileId());
            bt.setSuperSeeding(seeded.getFileId(), true);
            boolean back = bt.superSeeding(seeded.getFileId());
            System.out.println("PROBE readback toggle off=" + off + " on_again=" + back);
            if (!finished || !on || off || !back) {
                ok = false;
            }
        } finally {
            try {
                bt.stop();
            } catch (Throwable ignored) {
                // 探针退出路径
            }
        }
        System.out.println(ok ? "RESULT=PASS" : "RESULT=FAIL");
        System.exit(ok ? 0 : 1);
    }

    private static boolean waitFinished(BtSession bt, String fileId, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if ("finished".equals(bt.seedState(fileId))) {
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    /** 4 MiB 重复块（同一装置的口径，见 E0Team.createRepeatingPayload）。 */
    private static void writePayload(File f, long bytes) throws Exception {
        int piece = 256 * 1024;
        byte[] template = new byte[piece];
        new Random(20261106L).nextBytes(template);
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(bytes);
            raf.seek(0);
            raf.write(template, 0, (int) Math.min(template.length, bytes));
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\s]+", "_");
    }
}
