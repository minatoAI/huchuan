package app.huchuan.harness;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * 给本进程的每一行输出打上 run=<runId> 前缀（日志可重放问题的堵口）。
 *
 * runId 由 scripts/m1-headless.sh 每轮生成，经 -Dm1.runid 传进来；本类把它写在每一行的行首，
 * 连异常栈的每一行也带。断言只认本轮 runId 的行，历史日志留在同一个文件里也不会被误采信。
 */
public final class RunLog {

    private static volatile String runId = "";

    private RunLog() {
    }

    /** 装上前缀流并返回本轮 runId。幂等；缺少 -Dm1.runid 时退化为本机唯一 id。 */
    public static synchronized String install() {
        if (!runId.isEmpty()) {
            return runId;
        }
        String id = System.getProperty("m1.runid", "").trim();
        if (!valid(id)) {
            id = "local-" + Long.toString(System.currentTimeMillis(), 36)
                    + "-" + ProcessHandle.current().pid();
        }
        runId = id;
        byte[] prefix = ("run=" + id + " ").getBytes(StandardCharsets.UTF_8);
        System.setOut(new PrintStream(new LinePrefixStream(System.out, prefix), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new LinePrefixStream(System.err, prefix), true, StandardCharsets.UTF_8));
        return id;
    }

    public static String runId() {
        return runId;
    }

    /** 只允许字母数字与连字符：runId 会被拼进脚本的 grep 模式，不许有正则元字符。 */
    public static boolean valid(String id) {
        return id != null && id.matches("[0-9A-Za-z][0-9A-Za-z-]{7,63}");
    }

    /** 一次成行输出（行内不换行；多行请逐行调用）。 */
    public static void out(String line) {
        System.out.println(line);
        System.out.flush();
    }

    /** 只在行首补前缀；行内续写不补，保证一行只有一个标记。 */
    private static final class LinePrefixStream extends OutputStream {
        private final OutputStream sink;
        private final byte[] prefix;
        private boolean atLineStart = true;

        LinePrefixStream(OutputStream sink, byte[] prefix) {
            this.sink = sink;
            this.prefix = prefix;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            if (atLineStart && b != '\n') {
                sink.write(prefix);
                atLineStart = false;
            }
            sink.write(b);
            if (b == '\n') {
                atLineStart = true;
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            for (int i = off; i < off + len; i++) {
                write(b[i]);
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            sink.flush();
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
