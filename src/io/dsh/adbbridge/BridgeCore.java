package io.dsh.adbbridge;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * ADB 桥核心逻辑：用 root 把宿主 adb 公钥写进 adb_keys、打开 adbd 的 TCP 端口、并自检。
 *
 * <p>事实依据（AOSP / GrapheneOS platform_packages_modules_adb docs/dev/keystore.md）：
 * adbd 只从两个文件读取公钥 —— 只读分区上的 {@code /adb_keys}，与可写的
 * {@code /data/misc/adb/adb_keys}，由 {@code adbd_auth} 直接读取。
 *
 * <p>另一个关键约束（frameworks/base AdbDebuggingManager.writeKeys）：框架会以 AtomicFile
 * 整文件重写 {@code /data/misc/adb/adb_keys}，内容仅来自 {@code adb_temp_keys.xml}，
 * 因此手工注入的 key 会在框架 key 刷新时被覆盖掉。所以桥必须提供：
 * ① 开机自愈脚本（root 管理器 service.d）；② 随时可重新注入的入口。
 *
 * <p>本类不引用任何 Android API，便于在 PC 上用普通 JVM 直接跑测试。
 */
public final class BridgeCore {

    public static final int PORT = 5555;

    /**
     * 数据根目录。真机上就是 /data。做成变量是为了能在 PC 上用沙箱逐字真跑这些脚本
     * （测试时置 DATA_ROOT=/tmp/xxx，脚本里的 $DATA_ROOT 会原样展开成沙箱路径）。
     */
    public static final String DATA_ROOT = "$DATA_ROOT";

    /** adbd 读取的可写授权文件（AOSP 常量 ADB_KEYS_FILE = "adb_keys"，目录 /data/misc/adb）。 */
    public static final String ADB_KEYS = DATA_ROOT + "/misc/adb/adb_keys";

    /** 宿主 adb 客户端公钥（adb 自定义 base64 格式，与 keys/adbkey.pub 完全一致）。 */
    public static final String PUBKEY =
            "QUFBQQEAAcIPH+JTERIhGSEIJlDGQQM/O8d/FHmiXZdDLAaRA4BddtrvQR6Lj+AE/BAf8yhNZXjvskithk+MPQuEbeZqgNcKjCZl2AC3K4OxOFllNOZNAhfS02sz/bHD6+3WpUOTAM3HZWEGDnvrP5JTRgK3V4bnt5AdgrfQlLEDBvC6gAEVlol03mjx6VSxIAheutdSlpzYukgh1vD0Uygd8gz78pTK/7Kxtgtz53dbJvv7r8MdJwsB9dB83PA4dJ3ZkYUBneUXvkWrJmHDwWwnt/hcH1eLPMMH+kW84rkWgnU8LZxhL/93D0s7zGWjPhzJKRul/RBHHLrE1uPhC8zQ/gZ33k0=";

    /** root 通道的尝试顺序。 */
    private static final String[] ROOT_SHELLS = {"su -c", "su 0 sh -c"};

    /** root 管理器会在开机时执行这些目录下的脚本（存在哪个用哪个）。 */
    private static final String[] SERVICE_DIRS = {
            DATA_ROOT + "/adb/service.d",
            DATA_ROOT + "/adb/ksu/service.d",
            DATA_ROOT + "/adb/modules/dsh_adb_bridge/service.d",
    };

    /** 每个脚本的公共前缀：真机（未设 DATA_ROOT）时解析为 /data。 */
    private static String prelude() {
        return "DATA_ROOT=" + "${DATA_ROOT:-/data}" + "\n";
    }

    public static final String SCRIPT_NAME = "dsh-adb-bridge.sh";

    private BridgeCore() {
    }

    // ---------------------------------------------------------------- 结果模型

    public static final class Step {
        public final String name;
        public final String body;

        Step(String name, String body) {
            this.name = name;
            this.body = body;
        }
    }

    public static final class Result {
        public final List<Step> steps = new ArrayList<Step>();
        public boolean portListening;
        public boolean isRoot;
        public boolean bootScriptInstalled;
        public String bootScriptPath = "(未安装)";
        public String deviceIp = "?";

        public void add(String name, String body) {
            steps.add(new Step(name, body == null ? "" : body.trim()));
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Step s : steps) {
                sb.append("== ").append(s.name).append(" ==\n").append(s.body).append("\n\n");
            }
            sb.append("== 结论 ==\n")
              .append("root=").append(isRoot)
              .append("   adbd ").append(PORT).append(" 监听=").append(portListening)
              .append("   开机自愈=").append(bootScriptInstalled).append(' ').append(bootScriptPath)
              .append("\n手机 IP=").append(deviceIp).append('\n');
            if (portListening) {
                sb.append("可连接: adb connect ").append(deviceIp).append(':').append(PORT)
                  .append("   (本机同网络命名空间，也可 127.0.0.1:").append(PORT).append(")\n");
                sb.append("提示: 首次连接若手机弹出授权框，请勾选「一直允许」——这样 key 会被框架接管，\n"
                        + "      不再依赖手工注入，也不会被系统的 key 刷新覆盖。\n");
            } else {
                sb.append("adbd 未监听：\n")
                  .append("  · 若第 1 步不是 uid=0 → 请在 root 管理器里授权本应用\n")
                  .append("  · 否则把上面的完整输出发我\n");
            }
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- 脚本生成

    /** 注入公钥、确保权限与 SELinux 上下文。 */
    public static String planInstallKey() {
        return prelude() + "KEY='" + PUBKEY + "'\n"
                + "F=" + ADB_KEYS + "\n"
                + "mkdir -p \"$DATA_ROOT/misc/adb\" 2>/dev/null\n"
                + "touch \"$F\" 2>/dev/null\n"
                + "grep -qF \"$KEY\" \"$F\" 2>/dev/null || echo \"$KEY\" >> \"$F\"\n"
                + "chmod 640 \"$F\" 2>/dev/null\n"
                + "chown root:shell \"$F\" 2>/dev/null || chown root:adb \"$F\" 2>/dev/null || chown root:root \"$F\" 2>/dev/null\n"
                + "chcon u:object_r:adb_keys_file:s0 \"$F\" 2>/dev/null\n"
                + "ls -laZ \"$F\" 2>/dev/null\n"
                + "echo -n 'adb_keys 行数: '; grep -c . \"$F\" 2>/dev/null\n"
                + "echo -n '本桥 key 已存在: '; grep -qF \"$KEY\" \"$F\" 2>/dev/null && echo yes || echo no";
    }

    /** 打开 adbd TCP 端口并重启 adbd（带回退）。 */
    public static String planOpenPort() {
        String hexPort = Integer.toHexString(PORT).toUpperCase();
        return "setprop service.adb.tcp.port " + PORT + "\n"
                + "echo -n 'service.adb.tcp.port = '; getprop service.adb.tcp.port\n"
                + "setprop ctl.restart adbd 2>/dev/null\n"
                + "sleep 1\n"
                + "if command -v pgrep >/dev/null 2>&1; then\n"
                + "  pgrep -x adbd >/dev/null 2>&1 || pkill -x adbd 2>/dev/null\n"
                + "else\n"
                + "  ps -A 2>/dev/null | grep -qw adbd || pkill adbd 2>/dev/null\n"
                + "fi\n"
                + "sleep 2\n"
                + "echo -n 'adbd pid: '; (pgrep -x adbd 2>/dev/null || ps -A 2>/dev/null | awk '/adbd/{print $2}') | tr '\\n' ' '; echo\n"
                + "echo '--- /proc/net/tcp 中端口 " + hexPort + " 的监听项 ---'\n"
                + "{ cat /proc/net/tcp /proc/net/tcp6; } 2>/dev/null | awk '$2 ~ /:" + hexPort + "$/ {print $0}'\n"
                + "echo '--- 进程身份（是否为 root adbd）---'\n"
                + "ps -A -o USER,PID,NAME 2>/dev/null | awk '$3==\"adbd\"'";
    }

    /** 开机自愈脚本正文：框架整文件重写 adb_keys 后，由它在下次开机把 key 补回来并重开端口。 */
    public static String bootScriptBody() {
        return prelude() + "#!/system/bin/sh\n"
                + "# dsh-adb-bridge: 开机自愈（App 生成，可安全删除）\n"
                + "KEY='" + PUBKEY + "'\n"
                + "F=" + ADB_KEYS + "\n"
                + "log -t dsh_adb_bridge \"booting: ensuring adb key + tcp " + PORT + "\" 2>/dev/null\n"
                + "mkdir -p \"$DATA_ROOT/misc/adb\" 2>/dev/null\n"
                + "touch \"$F\" 2>/dev/null\n"
                + "grep -qF \"$KEY\" \"$F\" 2>/dev/null || echo \"$KEY\" >> \"$F\"\n"
                + "chmod 640 \"$F\" 2>/dev/null\n"
                + "chown root:shell \"$F\" 2>/dev/null || chown root:adb \"$F\" 2>/dev/null\n"
                + "chcon u:object_r:adb_keys_file:s0 \"$F\" 2>/dev/null\n"
                + "# 等开机完成，但必须有上限：绝不无限等待，避免卡住 root 管理器的启动执行链\n"
                + "i=0\n"
                + "if command -v getprop >/dev/null 2>&1; then\n"
                + "  while [ \"$(getprop sys.boot_completed 2>/dev/null)\" != \"1\" ] && [ $i -lt 60 ]; do\n"
                + "    sleep 2\n"
                + "    i=$((i + 1))\n"
                + "  done\n"
                + "fi\n"
                + "if [ \"$i\" -ge 60 ]; then\n"
                + "  log -t dsh_adb_bridge \"wait boot_completed timeout(120s), continue\" 2>/dev/null\n"
                + "fi\n"
                + "setprop service.adb.tcp.port " + PORT + "\n"
                + "setprop ctl.restart adbd 2>/dev/null\n"
                + "log -t dsh_adb_bridge \"done: adb tcp " + PORT + " requested\" 2>/dev/null\n";
    }

    /** 把自愈脚本写入所有可见的 service.d 目录。 */
    public static String planInstallBootScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("TMP=").append(DATA_ROOT).append("/local/tmp\n")
          .append("mkdir -p \"$TMP\" 2>/dev/null\n")
          .append("cat > \"$TMP/").append(SCRIPT_NAME).append("\" <<'DSHEOF'\n")
          .append(bootScriptBody())
          .append("DSHEOF\n")
          .append("echo -n '暂存脚本行数: '; wc -l < \"$TMP/").append(SCRIPT_NAME).append("\" 2>/dev/null\n");
        for (String d : SERVICE_DIRS) {
            sb.append("if [ -d ").append(d).append(" ]; then\n")
              .append("  if cp \"$TMP/").append(SCRIPT_NAME).append("\" ").append(d).append('/').append(SCRIPT_NAME).append(" 2>/dev/null; then\n")
              .append("    chmod 755 ").append(d).append('/').append(SCRIPT_NAME).append(" 2>/dev/null\n")
              .append("    chown 0:0 ").append(d).append('/').append(SCRIPT_NAME).append(" 2>/dev/null\n")
              .append("    restorecon ").append(d).append('/').append(SCRIPT_NAME).append(" 2>/dev/null\n")
              .append("    echo 'installed -> ").append(d).append('/').append(SCRIPT_NAME).append("'\n")
              .append("  else\n")
              .append("    echo 'FAILED(写入失败) -> ").append(d).append("'\n")
              .append("  fi\n")
              .append("else\n")
              .append("  echo 'skipped (目录不存在): ").append(d).append("'\n")
              .append("fi\n");
        }
        sb.append("echo '--- 安装结果 ---'\n")
          .append("ls -laZ ").append(ADB_KEYS).append(" 2>/dev/null\n");
        for (String d : SERVICE_DIRS) {
            sb.append("ls -laZ ").append(d).append('/').append(SCRIPT_NAME).append(" 2>/dev/null\n");
        }
        return sb.toString();
    }

    /** 把 SERVICE_DIRS 拼成可直接放进 shell 的实参串。 */
    private static String dirsArg() {
        StringBuilder sb = new StringBuilder();
        for (String d : SERVICE_DIRS) {
            sb.append(' ').append(d);
        }
        return sb.toString();
    }

    /** 当前状态快照，用于「只自检」。 */
    public static String planHealthCheck() {
        return prelude() + "echo -n 'service.adb.tcp.port='; getprop service.adb.tcp.port\n"
                + "echo -n 'init.svc.adbd='; getprop init.svc.adbd\n"
                + "ps -A -o USER,PID,NAME 2>/dev/null | awk '$3==\"adbd\"'\n"
                + "echo -n 'adb_keys 行数: '; grep -c . " + ADB_KEYS + " 2>/dev/null\n"
                + "echo -n '本桥 key 是否在 adb_keys 中: '; grep -qF '" + PUBKEY + "' " + ADB_KEYS + " 2>/dev/null && echo yes || echo no\n"
                + "echo '--- service.d 自愈脚本 ---'\n"
                + "for d in" + dirsArg() + "; do\n"
                + "  [ -f \"$d/" + SCRIPT_NAME + "\" ] && ls -laZ \"$d/" + SCRIPT_NAME + "\"\n"
                + "done";
    }

    // ---------------------------------------------------------------- 流程编排

    /** 执行完整流程并返回可展示的报告。 */
    public static Result runAll() {
        Result r = new Result();

        ShellRunner.Result id = ShellRunner.runFirstOk("id", ROOT_SHELLS);
        r.add("1. 提权自检（期望 uid=0）", id.all());
        r.isRoot = id.out.contains("uid=0");
        if (!r.isRoot) {
            r.add("中止", "未能取得 root，后续步骤无法进行。请在本机 root 管理器（KernelSU/APatch/Magisk）里授权本应用后重试。");
            return r;
        }

        StringBuilder dirs = new StringBuilder();
        for (String d : SERVICE_DIRS) {
            dirs.append(d).append(' ');
        }
        ShellRunner.Result env = ShellRunner.runFirstOk(
                "echo -n 'model='; getprop ro.product.model\n"
                        + "echo -n 'android='; getprop ro.build.version.release\n"
                        + "echo -n 'build='; getprop ro.build.type\n"
                        + "echo -n 'ro.adb.secure='; getprop ro.adb.secure\n"
                        + "echo -n 'ro.debuggable='; getprop ro.debuggable\n"
                        + "echo -n 'selinux='; getenforce 2>/dev/null\n"
                        + "echo -n 'adbd 身份/上下文: '; ps -A -o USER,LABEL,NAME 2>/dev/null | awk '$3==\"adbd\"'; echo\n"
                        + "echo -n 'sd 目录: '; for d in " + dirs.toString().trim() + "; do [ -d \"$d\" ] && printf '%s ' \"$d\"; done; echo",
                ROOT_SHELLS);
        r.add("2. 环境", env.all());

        r.add("3. 注入宿主公钥到 " + ADB_KEYS,
                ShellRunner.runFirstOk(planInstallKey(), ROOT_SHELLS).all());

        r.add("4. 打开 adbd TCP " + PORT + " 并重启 adbd",
                ShellRunner.runFirstOk(planOpenPort(), ROOT_SHELLS).all());

        ShellRunner.Result boot = ShellRunner.runFirstOk(planInstallBootScript(), ROOT_SHELLS);
        r.add("5. 安装开机自愈脚本", boot.all());
        r.bootScriptInstalled = boot.out.contains("installed ->");
        for (String d : SERVICE_DIRS) {
            if (boot.out.contains("installed -> " + d)) {
                r.bootScriptPath = d + "/" + SCRIPT_NAME;
                break;
            }
        }

        r.portListening = detectPortListening();
        r.deviceIp = detectIpv4();
        r.add("6. 自检", "端口 " + PORT + " 监听=" + r.portListening + "\n本机 IPv4=" + r.deviceIp);
        return r;
    }

    /** 健康检查（只读，不修改任何东西）。 */
    public static Result health() {
        Result r = new Result();
        ShellRunner.Result id = ShellRunner.runFirstOk("id", ROOT_SHELLS);
        r.isRoot = id.out.contains("uid=0");
        r.add("root", id.all());
        r.add("状态", ShellRunner.runFirstOk(planHealthCheck(), ROOT_SHELLS).all());
        r.portListening = detectPortListening();
        r.deviceIp = detectIpv4();
        r.add("自检", "端口 " + PORT + " 监听=" + r.portListening + "  IP=" + r.deviceIp);
        return r;
    }

    /** 只重新注入 key + 重开端口（被框架覆盖后续命）。 */
    public static Result reInject() {
        Result r = new Result();
        ShellRunner.Result id = ShellRunner.runFirstOk("id", ROOT_SHELLS);
        r.isRoot = id.out.contains("uid=0");
        if (!r.isRoot) {
            r.add("中止", "没有 root。");
            return r;
        }
        r.add("重新注入公钥", ShellRunner.runFirstOk(planInstallKey(), ROOT_SHELLS).all());
        r.add("重开端口", ShellRunner.runFirstOk(planOpenPort(), ROOT_SHELLS).all());
        r.portListening = detectPortListening();
        r.deviceIp = detectIpv4();
        return r;
    }

    // ---------------------------------------------------------------- 本机探测

    /** 端口是否处于 LISTEN：先真连回环，再回落解析 /proc/net/tcp{,6}。 */
    public static boolean detectPortListening() {
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), 1200);
            return true;
        } catch (Throwable ignored) {
            // 回落到 /proc 解析
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
        for (String path : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            java.io.BufferedReader br = null;
            try {
                br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(path)));
                String line;
                while ((line = br.readLine()) != null) {
                    String[] f = line.trim().split("\\s+");
                    if (f.length < 4) continue;
                    if (!f[3].equalsIgnoreCase("0A")) continue;
                    String ipPort = f[1];
                    int colon = ipPort.lastIndexOf(':');
                    if (colon < 0) continue;
                    if (Integer.parseInt(ipPort.substring(colon + 1), 16) == PORT) return true;
                }
            } catch (Throwable ignored) {
            } finally {
                if (br != null) {
                    try {
                        br.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return false;
    }

    /** 取第一个非回环 IPv4，优先 wlan 接口。 */
    public static String detectIpv4() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            if (nis == null) return "?";
            for (NetworkInterface ni : Collections.list(nis)) {
                String name = ni.getName() == null ? "" : ni.getName();
                if (ni.isLoopback()) continue;
                for (InetAddress ia : Collections.list(ni.getInetAddresses())) {
                    if (!(ia instanceof Inet4Address)) continue;
                    if (ia.isLoopbackAddress()) continue;
                    String ip = ia.getHostAddress();
                    if (ip == null || ip.startsWith("169.254.")) continue;
                    if (name.startsWith("wlan")) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback != null ? fallback : "?";
    }
}
