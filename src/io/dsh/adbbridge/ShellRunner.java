package io.dsh.adbbridge;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 与 Android 无关的 shell 执行封装，方便在 PC 上用普通 JVM 单测。
 */
public final class ShellRunner {

    /** 一条命令的执行结果。 */
    public static final class Result {
        public final int exit;
        public final String out;
        public final String err;

        public Result(int exit, String out, String err) {
            this.exit = exit;
            this.out = out == null ? "" : out;
            this.err = err == null ? "" : err;
        }

        public String all() {
            StringBuilder sb = new StringBuilder(out);
            if (!err.isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(err);
            }
            return sb.toString();
        }
    }

    private ShellRunner() {
    }

    /**
     * 依次尝试若干种 shell 前缀，返回第一个退出码为 0 的结果，失败则返回最后一次结果。
     * shellPrefix 形如 "su -c"、"sh -c"，playload 是脚本原文。
     */
    public static Result runFirstOk(String payload, String... shellPrefix) {
        Result last = null;
        for (String prefix : shellPrefix) {
            List<String> argv = new ArrayList<String>();
            String[] head = prefix.split(" ");
            for (String h : head) {
                if (!h.isEmpty()) argv.add(h);
            }
            argv.add(payload);
            Result r = exec(argv);
            if (r.exit == 0) return r;
            last = r;
        }
        return last != null ? last : new Result(-1, "", "no shell attempted");
    }

    /** 执行 argv，捕获 stdout/stderr。 */
    public static Result exec(List<String> argv) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(false);
            p = pb.start();
            Pump outPump = new Pump(p.getInputStream());
            Pump errPump = new Pump(p.getErrorStream());
            outPump.start();
            errPump.start();
            int code = p.waitFor();
            outPump.join(2000);
            errPump.join(2000);
            return new Result(code, outPump.text(), errPump.text());
        } catch (Exception e) {
            return new Result(-1, "", e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final class Pump extends Thread {
        private final InputStream in;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        Pump(InputStream in) {
            this.in = in;
            setDaemon(true);
        }

        @Override
        public void run() {
            byte[] tmp = new byte[4096];
            try {
                int n;
                while ((n = in.read(tmp)) > 0) {
                    buf.write(tmp, 0, n);
                }
            } catch (Exception ignored) {
            }
        }

        String text() {
            return new String(buf.toByteArray());
        }
    }
}
