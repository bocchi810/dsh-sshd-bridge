import io.dsh.adbbridge.BridgeCore;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 在 PC 上对 App 生成的所有 shell 脚本做语法 + 功能测试（不需要手机）。
 * 脚本被设计成 DATA_ROOT 参数化，所以这里可以做「逐字真跑」——不做任何字符串替换。
 */
public class PlanTest {
    static int fail = 0;
    static final String SANDBOX = "/tmp/brk-sandbox";

    static void check(String label, boolean ok, String detail) {
        if (!ok) fail++;
        System.out.println((ok ? "  OK   " : "  FAIL ") + label + (detail.isEmpty() ? "" : "  " + detail));
    }

    static void syntax(String label, String script) throws Exception {
        File f = File.createTempFile("plan", ".sh");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f))) { w.write(script); }
        Process p = new ProcessBuilder("sh", "-n", f.getAbsolutePath()).redirectErrorStream(true).start();
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String l; StringBuilder sb = new StringBuilder();
        while ((l = br.readLine()) != null) sb.append(l).append(' ');
        check(label, p.waitFor() == 0, sb.toString().trim());
    }

    /** 在沙箱里执行脚本；DATA_ROOT 通过环境变量注入，脚本内容一字不改。 */
    static String run(String script, int timeoutSec, Map<String, String> env) throws Exception {
        File f = File.createTempFile("run", ".sh");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f))) { w.write(script); }
        ProcessBuilder pb = new ProcessBuilder("sh", f.getAbsolutePath());
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder all = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String l;
        while ((l = br.readLine()) != null) all.append(l).append('\n');
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            fail++;
            return "[超时 " + timeoutSec + "s，已强杀]\n" + all;
        }
        StringBuilder out = new StringBuilder();
        String prev = null; int n = 0;
        for (String line : all.toString().split("\n")) {
            if (line.equals(prev)) { n++; continue; }
            if (n > 0) out.append("    ... (上一行重复 ").append(n).append(" 次)\n");
            prev = line; n = 0;
            out.append("    ").append(line).append('\n');
        }
        return out.toString();
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = new HashMap<String, String>();
        env.put("DATA_ROOT", SANDBOX);

        System.out.println("== 1. 语法校验 (sh -n) ==");
        syntax("planInstallKey", BridgeCore.planInstallKey());
        syntax("planOpenPort", BridgeCore.planOpenPort());
        syntax("planInstallBootScript", BridgeCore.planInstallBootScript());
        syntax("planHealthCheck", BridgeCore.planHealthCheck());
        syntax("bootScriptBody", BridgeCore.bootScriptBody());

        System.out.println("\n== 2. 注入公钥（沙箱真跑）==");
        new ProcessBuilder("rm", "-rf", SANDBOX).start().waitFor();
        new File(SANDBOX + "/misc/adb").mkdirs();
        System.out.println(run(BridgeCore.planInstallKey(), 20, env));
        File keys = new File(SANDBOX + "/misc/adb/adb_keys");
        check("adb_keys 已创建", keys.exists(), keys.getPath());
        String content = keys.exists() ? new String(Files.readAllBytes(keys.toPath())).trim() : "";
        check("内容 == 内嵌公钥", content.equals(BridgeCore.PUBKEY), "len=" + content.length());

        System.out.println("\n== 3. 幂等：再注入一次 ==");
        System.out.println(run(BridgeCore.planInstallKey(), 20, env));
        long lines = keys.exists() ? Files.readAllLines(keys.toPath()).size() : -1;
        check("adb_keys 仍只有 1 行", lines == 1, "行数=" + lines);

        System.out.println("\n== 4. 安装开机自愈脚本（沙箱真跑）==");
        for (String d : new String[]{"/adb/service.d", "/adb/ksu/service.d"}) new File(SANDBOX + d).mkdirs();
        System.out.println(run(BridgeCore.planInstallBootScript(), 20, env));
        File inst = new File(SANDBOX + "/adb/service.d/" + BridgeCore.SCRIPT_NAME);
        check("脚本已安装", inst.exists(), inst.getPath());
        check("脚本可执行", inst.canExecute(), "");
        String body = inst.exists() ? new String(Files.readAllBytes(inst.toPath())) : "";
        check("脚本内嵌公钥", body.contains(BridgeCore.PUBKEY), "");

        System.out.println("\n== 5. 执行开机自愈脚本（逐字真跑；先删 key 模拟被系统覆盖）==");
        Files.deleteIfExists(keys.toPath());
        File f = File.createTempFile("boot", ".sh");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f))) { w.write(body); }
        ProcessBuilder pb = new ProcessBuilder("sh", f.getAbsolutePath());
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String l;
        while ((l = br.readLine()) != null) sb.append("    ").append(l).append('\n');
        boolean done = p.waitFor(150, TimeUnit.SECONDS);
        if (!done) p.destroyForcibly();
        System.out.print(sb);
        check("脚本在超时上限内自行退出", done, "exit=" + (done ? String.valueOf(p.exitValue()) : "killed"));
        check("被删的 key 已被自愈补回", keys.exists(), keys.getPath());
        if (keys.exists()) {
            check("补回内容正确", new String(Files.readAllBytes(keys.toPath())).trim().equals(BridgeCore.PUBKEY), "");
        }

        System.out.println("\n== 6. 健康检查脚本（沙箱真跑）==");
        System.out.println(run(BridgeCore.planHealthCheck(), 20, env));

        System.out.println(fail == 0 ? "\n全部通过（0 失败）" : "\n有 " + fail + " 项失败");
        if (fail > 0) System.exit(1);
    }
}
