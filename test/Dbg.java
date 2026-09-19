import io.dsh.adbbridge.BridgeCore;
import java.io.*;

/** 带 xtrace 的调试器：把开机自愈脚本放到沙箱里逐行执行，看它到底做了什么。 */
public class Dbg {
    public static void main(String[] a) throws Exception {
        String sb = "/tmp/brk3";
        new ProcessBuilder("rm", "-rf", sb).start().waitFor();
        new File(sb + "/data/misc/adb").mkdirs();

        String body = BridgeCore.bootScriptBody().replace("/data", sb + "/data");
        System.out.println("--- 脚本内容 ---\n" + body + "--- 执行 (sh -x) ---");

        File f = new File("/tmp/boot_dbg.sh");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f))) {
            w.write(body);
        }
        Process p = new ProcessBuilder("sh", "-x", f.getAbsolutePath())
                .redirectErrorStream(true).start();
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String l;
        while ((l = br.readLine()) != null) System.out.println(l);
        System.out.println("exit=" + p.waitFor());
        File keys = new File(sb + "/data/misc/adb/adb_keys");
        System.out.println("adb_keys 存在=" + keys.exists());
        if (keys.exists()) {
            System.out.println("内容匹配=" + new String(java.nio.file.Files.readAllBytes(keys.toPath()))
                    .trim().equals(BridgeCore.PUBKEY));
        }
    }
}
