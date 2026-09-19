import io.dsh.sshd.SshdCore;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 对 SSH 通道的所有生成脚本做语法 + 沙箱真跑测试（不需手机）。 */
public class SshdPlanTest {
    static int fail = 0;
    static final String SB = "/tmp/sshd-sandbox";

    static void check(String label, boolean ok, String detail) {
        if (!ok) fail++;
        System.out.println((ok ? "  OK   " : "  FAIL ") + label + (detail.isEmpty() ? "" : "  " + detail));
    }

    static void syntax(String label, String script) throws Exception {
        File f = File.createTempFile("splan", ".sh");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f))) { w.write(script); }
        Process p = new ProcessBuilder("sh", "-n", f.getAbsolutePath()).redirectErrorStream(true).start();
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String l; StringBuilder sb = new StringBuilder();
        while ((l = br.readLine()) != null) sb.append(l).append(' ');
        check(label, p.waitFor() == 0, sb.toString().trim());
    }

    static String run(String script, Map<String, String> env) throws Exception {
        File f = File.createTempFile("srun", ".sh");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f))) { w.write(script); }
        ProcessBuilder pb = new ProcessBuilder("sh", f.getAbsolutePath());
        pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String l; StringBuilder all = new StringBuilder();
        while ((l = br.readLine()) != null) all.append(l).append('\n');
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); fail++; return "[超时]\n" + all; }
        StringBuilder out = new StringBuilder();
        for (String line : all.toString().split("\n")) out.append("    ").append(line).append('\n');
        return out.toString();
    }

    public static void main(String[] a) throws Exception {
        Map<String, String> env = new HashMap<String, String>();
        env.put("SSHD_ROOT", SB);
        String tmpSrc = SB + "/assets";

        System.out.println("== 1. 语法校验 (sh -n) ==");
        syntax("planPrepare", SshdCore.planPrepare());
        syntax("planInstallBinaries", SshdCore.planInstallBinaries(tmpSrc));
        syntax("planHostKey", SshdCore.planHostKey(tmpSrc));
        syntax("planAuthorizedKeys", SshdCore.planAuthorizedKeys());
        syntax("planStatus", SshdCore.planStatus());
        syntax("planInstallBootScript", SshdCore.planInstallBootScript());
        syntax("bootScriptBody", SshdCore.bootScriptBody());
        syntax("planStart", SshdCore.planStart());

        System.out.println("\n== 2. 准备目录 ==");
        new ProcessBuilder("rm", "-rf", SB).start().waitFor();
        new File(tmpSrc).mkdirs();
        // 用桩替身模拟 App 解出来的二进制，验证安装与权限逻辑
        for (String b : new String[]{"dropbear", "dropbearkey", "dropbear_dyn", "dropbearkey_dyn"}) {
            File f = new File(tmpSrc, b);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f))) {
                w.write("#!/bin/sh\n"
                        + "case \"$*\" in *\"-h\"*) exit 0;; esac\n"
                        + "echo \"stub " + b + " $*\"\n");
            }
            f.setExecutable(true);
        }
        System.out.println(run(SshdCore.planPrepare(), env));
        check("BASE 目录已建", new File(SB + "/local/tmp/dsh-sshd").isDirectory(), "");

        System.out.println("\n== 3. 安装二进制（cp + chmod 755）==");
        System.out.println(run(SshdCore.planInstallBinaries(tmpSrc), env));

        String out3 = run(SshdCore.planInstallBinaries(tmpSrc), env);
        check("dropbear(静态) 已就位且可执行",
                new File(SB + "/local/tmp/dsh-sshd/dropbear").canExecute(), "");
        check("dropbear_dyn(动态) 已就位且可执行",
                new File(SB + "/local/tmp/dsh-sshd/dropbear_dyn").canExecute(), "");
        check("探测后写入了变体选择", out3.contains("选定变体: ") && !out3.contains("(无可用变体)"), "");
        check("权限探测识破了世界可写目录（沙箱 /tmp 为 1777）",
                out3.contains("组可写") || out3.contains("其他可写"), "");
        check("无合规目录时回退并写入 authdir",
                out3.contains("回退用 BASE") && new File(SB + "/local/tmp/dsh-sshd/authdir").exists(), "");

        System.out.println("\n== 4. authorized_keys（幂等）==");
        System.out.println(run(SshdCore.planAuthorizedKeys(), env));
        System.out.println(run(SshdCore.planAuthorizedKeys(), env));
        File ak = new File(SB + "/local/tmp/dsh-sshd/authorized_keys");
        long lines = ak.exists() ? Files.readAllLines(ak.toPath()).size() : -1;
        check("authorized_keys 只有 1 行", lines == 1, "行数=" + lines);
        check("内容是客户端公钥", ak.exists() && new String(Files.readAllBytes(ak.toPath()))
                .trim().equals(SshdCore.CLIENT_PUBKEY), "");

        System.out.println("\n== 5. 主机密钥：设备端失败 → 内嵌密钥兜底（沙箱真跑）==");
        // 沙箱里的 dropbearkey 是打印后即退出的桩，不会产出密钥 → 正好模拟真机的段错误失败。
        // 把真密钥放到 srcDir 作为"内嵌密钥"，验证兜底能否恢复。
        Files.copy(java.nio.file.Paths.get("/root/harness/adb-bridge/keys/hostkey_ed25519"),
                java.nio.file.Paths.get(tmpSrc, "hostkey_ed25519"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        new File(SB + "/local/tmp/dsh-sshd/hostkey_ed25519").delete();
        String out5 = run(SshdCore.planHostKey(tmpSrc), env);
        System.out.println(out5);
        check("直接使用内嵌预生成密钥", out5.contains("使用内嵌的预生成密钥"), "");
        check("兜底后 hostkey 可用", out5.contains("hostkey 可用: yes"), "");
        File hk = new File(SB + "/local/tmp/dsh-sshd/hostkey_ed25519");
        check("主机密钥确已落盘且 >64 字节", hk.exists() && hk.length() > 64, "大小=" + hk.length());
        check("落盘内容 == 内嵌密钥", hk.exists() && java.util.Arrays.equals(
                Files.readAllBytes(hk.toPath()),
                Files.readAllBytes(java.nio.file.Paths.get("/root/harness/adb-bridge/keys/hostkey_ed25519"))), "");

        System.out.println("\n== 6. 状态脚本（沙箱真跑）==");
        System.out.println(run(SshdCore.planStatus(), env));

        System.out.println("\n== 7. 安装开机自启脚本（沙箱真跑）==");
        for (String d : new String[]{"/adb/service.d"}) new File(SB + d).mkdirs();
        System.out.println(run(SshdCore.planInstallBootScript(), env));
        File boot = new File(SB + "/adb/service.d/" + SshdCore.SCRIPT_NAME);
        check("自启脚本已安装", boot.exists(), boot.getPath());
        check("可执行", boot.canExecute(), "");
        String body = boot.exists() ? new String(Files.readAllBytes(boot.toPath())) : "";
        check("含 dropbear 启动命令", body.contains("-p " + SshdCore.PORT), "");
        check("BASE 已内联为沙箱绝对路径（无 shell 参数展开）",
                body.contains("BASE='" + SB + "/local/tmp/dsh-sshd'") && !body.contains("${"), "");

        System.out.println("\n== 8. planStart 的 argv 形状（不真跑，避免 exec 接管）==");
        String st = SshdCore.planStart();
        check("用 exec 让父进程即 dropbear", st.contains("exec "), "");
        check("仅公钥认证 -s（无密码认证）", st.contains(" -s "), "");
        check("禁密码：不含 -P/-w 之类口令选项", !st.contains(" -w "), "");
        check("端口正确", st.contains("-p " + SshdCore.PORT), "");
        check("指定 hostkey", st.contains("-r "), "");
        check("不含被 --disable-syslog 编掉的 -E", !st.contains(" -E "), "");
        check("含 -F 前台运行", st.contains(" -F "), "");
        check("含 -c 指定 shell", st.contains("-c /system/bin/sh"), "");
        // 依据 svr-authpubkey.c：路径 = <authorized_keys_dir>/authorized_keys，
        // 默认 "~/.ssh"（svr-runopts.c:186），Android 上 root 的 HOME="/" → 会去找 /.ssh/，
        // 必须显式 -D 指到我们放 authorized_keys 的目录。
        check("含 -D 且指向探测选定的合规目录（不是 BASE）",
                st.contains("-D \"$A\"") && !st.contains("-D \"$BASE\""), "");
        check("恢复了 authdir 选择", st.contains("$BASE/authdir"), "");
        String instX = SshdCore.planInstallBinaries("/tmp/x");
        check("包含 D 的自检", instX.contains("for o in F s p r c D"), "");
        // authorized_keys 必须落在通过权限探测的目录，而不是 777 的 /data/local/tmp
        check("安装阶段做 authorized_keys 目录权限探测",
                instX.contains("挑选 authorized_keys 目录")
                        && (instX.contains("组可写") || instX.contains("其他可写")), "");
        check("探测把结果写入 authdir", instX.contains("$BASE/authdir"), "");
        // 判据必须拆分 mode 的三位：旧写法 case "$M" in *[2367]) 只看最后一位，
        // 会把 771（组可写）误判为合规 —— 这条断言锁死修正后的写法。
        check("判据检查「组可写」位", instX.contains("组可写(mode $M)"), "");
        check("判据检查「其他可写」位", instX.contains("其他可写(mode $M)"), "");
        check("判据已拆成组/其他两位分别判断",
                instX.contains("GW=$(printf '%s' \"$M3\" | cut -c2)")
                        && instX.contains("OW=$(printf '%s' \"$M3\" | cut -c3)"), "");
        String probe = SshdCore.planFixPerms();
        check("权限探针用三种判据交叉验证",
                probe.contains("find_022=") && probe.contains("旧逻辑="), "");
        String inst = SshdCore.planInstallBinaries("/tmp/x");
        check("安装阶段做选项存在性自检（遍历实际使用的选项）",
                inst.contains("for o in F s p r c") && inst.contains(":$o") == false
                        && inst.contains("选项 -$o"), "");

        System.out.println(fail == 0 ? "\n全部通过（0 失败）" : "\n有 " + fail + " 项失败");
        if (fail > 0) System.exit(1);
    }
}
