package io.dsh.sshd;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 手机侧 SSH 控制通道（dropbear 服务端）的核心逻辑。
 *
 * <p>设计约束与依据：
 * <ul>
 *   <li>二进制放在 {@code $DATA_ROOT/local/tmp/dsh-sshd/}（真机 = /data/local/tmp/dsh-sshd/），
 *       因为要以 root 运行：Android 的应用私有目录 /data/data/&lt;pkg&gt; 是 0700，root 的 SELinux
 *       域（su）访问会被拒；/data/local/tmp 是 shell_data_file，root 可读可执行。</li>
 *   <li>只用公钥认证（{@code -s}），禁用密码认证——Android bionic 没有 {@code crypt()}，
 *       编译期已把 DROPBEAR_SVR_PASSWORD_AUTH 置 0。</li>
 *   <li>主机密钥在**设备上用 dropbearkey 现生成**（首次运行），不做跨平台格式假设：
 *       dropbear 的密钥格式与 OpenSSH 不同，{@code -t ed25519} 是其默认类型。</li>
 *   <li>用 {@code -F} 前台运行 + {@code exec}，让父进程就是 dropbear 本体，
 *       这样 App 能可靠地 start/stop 并拿到退出码。</li>
 * </ul>
 *
 * <p>本类不引用 Android API，脚本生成部分可在 PC 上用普通 JVM 真跑测试。
 */
public final class SshdCore {

    public static final int PORT = 2222;
    public static final String SCRIPT_NAME = "dsh-sshd.sh";
    public static final String SU = "su";

    /**
     * 数据根：真机为 /data；测试时用环境变量覆盖成沙箱，脚本可逐字真跑。
     * 刻意**不用** shell 参数展开（如 ${X:-/data}）：root 的 su 未必经 POSIX shell
     * （Android 常见 mksh），参数展开在字面执行下会失效。所有变量由 Java 侧解析后内联。
     */
    private static String dataRoot() {
        String v = System.getenv("SSHD_ROOT");
        return (v == null || v.isEmpty()) ? "/data" : v;
    }

    /** 真机 = /data/local/tmp/dsh-sshd（root 可读可执行）。 */
    public static String base() {
        return dataRoot() + "/local/tmp/dsh-sshd";
    }


    /** 容器侧客户端公钥（与 keys/ssh_client.pub 一致），编译期写死便于免 root 自检。 */
    public static final String CLIENT_PUBKEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDtLCMwKhh+hivFO3/EIPPHAV879VzGD7O+9plqvh0Z/ dsh-client";

    private static final String[] ROOT_SHELLS = {SU + " -c", SU + " 0 sh -c"};

    /**
     * 启动 dropbear 时**实际使用**的短选项。自检直接从这份清单生成，
     * 避免「启动用的选项」与「自检的选项」两处漂移（-E 就是这么漏掉的：
     * case 'E' 被 #ifndef DISABLE_SYSLOG 包住，用 --disable-syslog 编出来就没有）。
     */
    private static final String START_OPTS = "F s p r c D";

    private static String[] serviceDirs() {
        String r = dataRoot();
        return new String[]{
                r + "/adb/service.d",
                r + "/adb/ksu/service.d",
                r + "/adb/modules/dsh_sshd/service.d",
        };
    }

    /**
     * authorized_keys 的候选目录，按优先级排列。
     *
     * <p>为什么不能放在 BASE（/data/local/tmp/dsh-sshd）：真机实测 /data/local/tmp 的权限是
     * **777**（世界可写），而 dropbear 的 {@code checkpubkeyperms()} 会从 authorized_keys
     * 逐级向上检查每个路径组件必须满足「属主是 root 或该用户」且「无组/其他写位」
     * （src/svr-authpubkey.c:610 checkfileperm），走到 /data/local/tmp 这层必然失败，
     * 而且它只记一条 INFO 日志、然后静默拒绝认证。
     *
     * <p>所以让 App 用 dropbear 同一套规则探测，选第一个全部组件合规的目录。
     */
    private static String[] authDirCandidates() {
        String r = dataRoot();
        return new String[]{
                r + "/adb/dsh-sshd",
                r + "/misc/dsh-sshd",
                r + "/system/dsh-sshd",
                r + "/local/dsh-sshd",
        };
    }

    /** 把候选目录拼成可直接放进 shell 的空格分隔实参串。 */
    private static String authDirArg() {
        StringBuilder sb = new StringBuilder();
        for (String d : authDirCandidates()) {
            sb.append(' ').append(d);
        }
        return sb.toString();
    }

    private SshdCore() {
    }

    // ------------------------------------------------------------- 结果模型

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
        public boolean isRoot;
        public boolean listening;
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
              .append("   SSH 端口 ").append(PORT).append(" 监听=").append(listening)
              .append("\n手机 IP=").append(deviceIp).append('\n');
            if (listening) {
                sb.append("连接命令（在容器里）:\n")
                  .append("  ssh -i /root/harness/adb-bridge/keys/ssh_client -p ").append(PORT)
                  .append(" -o StrictHostKeyChecking=no root@").append(deviceIp).append('\n')
                  .append("  或 root@127.0.0.1（容器与手机同网络命名空间）\n");
            } else {
                sb.append("SSH 未监听：\n")
                  .append("  · 若第 1 步不是 uid=0 → 请在 root 管理器里授权本应用\n")
                  .append("  · 否则把上面完整输出发我\n");
            }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------- 脚本生成

    /** 每个脚本的公共前缀：直接内联 Java 侧解析好的 BASE 绝对路径。 */
    private static String prelude() {
        return "BASE='" + base() + "'\n";
    }

    /** 1. 建目录（已存在则不动）。 */
    public static String planPrepare() {
        return prelude()
                + "mkdir -p \"$BASE\" 2>/dev/null\n"
                + "chmod 755 \"$BASE\" 2>/dev/null\n"
                + "echo -n '目录: '; ls -ld \"$BASE\" 2>&1\n";
    }

    /**
     * 2. 装二进制：静态版与动态版都装，并**逐个实测哪一种真能跑**。
     *
     * <p>为什么要探测：真机上静态版（static-PIE：ELF e_type=DYN、有 DYNAMIC 段但无 PT_INTERP）
     * 执行即 SIGSEGV；动态版用设备自带的 bionic libc。探针用
     * {@code dropbearkey -y -f <hostkey>}（打印公钥后退出）——它会完整走
     * crypto_init + seedrandom + 读私钥，正好覆盖崩掉的那段路径，又不会绑定端口。
     *
     * <p>同时刻意**不吞 stderr**：之前正是 `2>/dev/null` 把 SELinux 拒绝的原始报错盖掉，
     * 只剩一句 "FAILED cp" 无从定位。
     */
    public static String planInstallBinaries(String srcDir) {
        return prelude()
                + "SRC='" + srcDir + "'\n"
                + "echo '--- 源目录 ---'\n"
                + "ls -laZ \"$SRC\" 2>&1 || ls -la \"$SRC\" 2>&1\n"
                + "echo -n 'root 可读源目录: '; [ -r \"$SRC\" ] && echo yes || echo no\n"
                + "for f in dropbear dropbearkey dropbear_dyn dropbearkey_dyn; do\n"
                + "  echo \"--- 安装 $f ---\"\n"
                + "  if [ ! -f \"$SRC/$f\" ]; then echo \"源文件不存在: $SRC/$f\"; continue; fi\n"
                + "  if cp \"$SRC/$f\" \"$BASE/$f\" 2>&1; then\n"
                + "    chmod 755 \"$BASE/$f\" 2>&1\n"
                + "    chown 0:0 \"$BASE/$f\" 2>&1\n"
                + "    restorecon \"$BASE/$f\" 2>/dev/null\n"
                + "    echo \"OK: $f\"\n"
                + "  else\n"
                + "    echo \"FAILED cp $f（原始报错见上）\"\n"
                + "  fi\n"
                + "done\n"
                + "echo '--- 实测哪种变体能执行 ---'\n"
                + "for v in dyn static; do\n"
                + "  if [ \"$v\" = dyn ]; then K=\"$BASE/dropbearkey_dyn\"; D=\"$BASE/dropbear_dyn\";\n"
                + "  else K=\"$BASE/dropbearkey\"; D=\"$BASE/dropbear\"; fi\n"
                + "  if [ ! -x \"$K\" ]; then echo \"  $v: 二进制缺失，跳过\"; continue; fi\n"
                // 探针用 -h：源码 dropbearkey.c 中 crypto_init() + seedrandom() 在选项解析之前调用，
                // 而 case 'h' 直接 exit(EXIT_SUCCESS)。所以它恰好只覆盖「崩掉的那段路径」，
                // 又不依赖任何文件、不绑定端口、不需要已存在的主机密钥。
                + "  \"$K\" -h >/dev/null 2>&1; E=$?\n"
                + "  echo \"  $v: 探测 dropbearkey -h exit=$E\"\n"
                + "  if [ \"$E\" = 0 ]; then echo \"$D\" > \"$BASE/dropbear.active\"; echo \"  -> 选用 $v 版（$D）\"; break; fi\n"
                + "done\n"
                + "echo -n '选定变体: '; if [ -s \"$BASE/dropbear.active\" ]; then cat \"$BASE/dropbear.active\"; else echo '(无可用变体)'; fi\n"
                // 选项受编译门控：case 'E' 被 #ifndef DISABLE_SYSLOG 包住，用 --disable-syslog
                // 编出来就没有 -E（真机上正是这么踩到的：Invalid option -E）。
                // 所以启动前拿 -h 的 usage 文本，核对我们实际要用的选项是否真的存在。
                + "D=$(cat \"$BASE/dropbear.active\" 2>/dev/null)\n"
                + "if [ -n \"$D\" ]; then\n"
                + "  H=$(\"$D\" -h 2>&1)\n"
                + "  for o in " + START_OPTS + "; do\n"
                + "    if echo \"$H\" | grep -q \"^  -$o\"; then echo \"  选项 -$o: 存在\"; else echo \"  选项 -$o: **缺失**\"; fi\n"
                + "  done\n"
                + "fi\n"
                // 复刻 dropbear 的 checkpubkeyperms/checkfileperm 规则选 authorized_keys 目录：
                // 每个路径组件都必须「属主为 root 或该用户」且「无组/其他写位」。
                + "echo '--- 挑选 authorized_keys 目录（复刻 dropbear 权限规则）---'\n"
                + "rm -f \"$BASE/authdir\"\n"
                + "for A in" + authDirArg() + "; do\n"
                + "  if [ ! -d \"$A\" ]; then mkdir -p \"$A\" 2>/dev/null; chmod 700 \"$A\" 2>/dev/null; fi\n"
                + "  if [ ! -d \"$A\" ]; then echo \"  $A: 无法创建\"; continue; fi\n"
                + "  P=\"$A\"; BAD=\"\"\n"
                + "  while : ; do\n"
                + "    U=$(stat -c %u \"$P\" 2>/dev/null); M=$(stat -c %a \"$P\" 2>/dev/null)\n"
                // 判据必须看 mode 的「组」和「其他」两位。上一版写成 case "$M" in *[2367])
                // 只看最后一位，导致 771（组可写）被误判为合规 —— 三位要拆开分别判断。
                + "    M3=$(printf '%03d' \"$M\" 2>/dev/null)\n"
                + "    GW=$(printf '%s' \"$M3\" | cut -c2); OW=$(printf '%s' \"$M3\" | cut -c3)\n"
                + "    case \"$GW\" in *[2367]) BAD=\"$P 组可写(mode $M)\"; break;; esac\n"
                + "    case \"$OW\" in *[2367]) BAD=\"$P 其他可写(mode $M)\"; break;; esac\n"
                + "    [ \"$U\" = 0 ] || { BAD=\"$P 属主 uid=$U 非 root\"; break; }\n"
                + "    [ \"$P\" = \"/\" ] && break\n"
                + "    P=$(dirname \"$P\")\n"
                + "  done\n"
                + "  if [ -z \"$BAD\" ]; then echo \"  $A: 合规 ✓\"; echo \"$A\" > \"$BASE/authdir\"; break; fi\n"
                + "  echo \"  $A: 不合规（$BAD）\"\n"
                + "done\n"
                + "if [ ! -s \"$BASE/authdir\" ]; then echo '  没有任何候选目录合规，回退用 BASE（认证很可能失败）'; echo \"$BASE\" > \"$BASE/authdir\"; fi\n"
                + "echo -n 'authorized_keys 目录: '; cat \"$BASE/authdir\"; echo\n";
    }

    /**
     * 3. 主机密钥：先试设备端 dropbearkey 现生成；**失败则落回 App 内嵌的预生成密钥**。
     *
     * <p>为什么要有兜底：真机实测 dropbearkey 段错误（SIGSEGV），主机密钥生成不出来，
     * 后续 dropbear -r 找不到 hostkey 直接退出（exit=139）。内嵌密钥由容器原生编译的
     * dropbearkey 生成——格式为 SSH 线格式、长度字段大端（src/buffer.c buf_putint 用 STORE32H），
     * 与 CPU 字节序无关，故 x86_64 生成的密钥可直接给 aarch64 使用。
     *
     * <p>并且**必须校验结果**：上一版生成失败后照样往下跑，最后只报「SSH 未监听」，
     * 把真正的原因（密钥没生成）埋掉了。
     */
    public static String planHostKey(String srcDir) {
        return prelude()
                + "SRC='" + srcDir + "'\n"
                + "if [ ! -s \"$BASE/hostkey_ed25519\" ]; then\n"
                + "  echo '设备端没有可用主机密钥：直接使用内嵌的预生成密钥'\n"
                + "  if cp \"$SRC/hostkey_ed25519\" \"$BASE/hostkey_ed25519\" 2>&1; then\n"
                + "    echo '已写入内嵌密钥'\n"
                + "  else\n"
                + "    echo 'FAILED 内嵌密钥写入失败'\n"
                + "  fi\n"
                + "else\n"
                + "  echo '主机密钥已存在，跳过'\n"
                + "fi\n"
                + "chmod 600 \"$BASE/hostkey_ed25519\" 2>/dev/null\n"
                + "chown 0:0 \"$BASE/hostkey_ed25519\" 2>/dev/null\n"
                + "restorecon \"$BASE/hostkey_ed25519\" 2>/dev/null\n"
                + "SZ=$(wc -c < \"$BASE/hostkey_ed25519\" 2>/dev/null || echo 0)\n"
                + "echo \"hostkey 大小: $SZ\"\n"
                + "if [ \"$SZ\" -gt 64 ] 2>/dev/null; then echo 'hostkey 可用: yes'; else echo 'hostkey 可用: no'; fi\n";
    }

    /**
     * 4. 写入 authorized_keys（幂等）。
     *
     * <p>关键决定：**同时写进所有候选目录**，而不是只写探测选中的那一个。
     * 理由——探测依赖 mode 字符串解析，而我在 PC 上推演过两次都得到错误结论；
     * dropbear 只会读它启动参数 {@code -D} 指定的那一个目录，所以只要每个候选目录里
     * 都有这把公钥，无论探测选中哪个（甚至探测判错、回退到 BASE），认证都能通过。
     * 写入内容只有一行公钥（92 字节），幂等，完全可回滚。
     */
    public static String planAuthorizedKeys() {
        StringBuilder sb = new StringBuilder(prelude());
        sb.append("A=$(cat \"$BASE/authdir\" 2>/dev/null); [ -n \"$A\" ] || A=\"$BASE\"\n")
          .append("K='").append(CLIENT_PUBKEY).append("'\n")
          .append("echo \"探针选定: $A\"\n")
          .append("for D in \"$A\" \"$BASE\"")
          .append(authDirArg())
          .append("; do\n")
          .append("  [ -n \"$D\" ] || continue\n")
          .append("  mkdir -p \"$D\" 2>/dev/null\n")
          .append("  F=\"$D/authorized_keys\"\n")
          .append("  touch \"$F\" 2>/dev/null || { echo \"  $D: 不可写\"; continue; }\n")
          .append("  grep -qF \"$K\" \"$F\" 2>/dev/null || echo \"$K\" >> \"$F\"\n")
          .append("  chmod 600 \"$F\" 2>/dev/null\n")
          .append("  chown 0:0 \"$F\" 2>/dev/null\n")
          .append("  echo \"  已写: $F  ($(grep -c . \"$F\" 2>/dev/null) 行)\"\n")
          .append("done\n")
          .append("echo -n '含客户端公钥(选定目录): '; grep -qF \"$K\" \"$A/authorized_keys\" 2>/dev/null && echo yes || echo no\n")
          .append("echo '--- 各候选目录最终状态 ---'\n")
          .append("for D in \"$A\" \"$BASE\"").append(authDirArg()).append("; do\n")
          .append("  [ -n \"$D\" ] || continue\n")
          .append("  [ -f \"$D/authorized_keys\" ] && ls -laZ \"$D/authorized_keys\" 2>&1\n")
          .append("done\n");
        return sb.toString();
    }

    /**
     * 5. 启动 SSH 服务端（幂等：已在跑就先停）。
     * 用探测阶段选定的变体（$BASE/dropbear.active），失败则依次回退到其它变体；
     * exec 让父进程就是 dropbear，App 能可靠拿到退出码。
     */
    public static String planStart() {
        return prelude()
                + "pkill -f \"$BASE/dropbear\" 2>/dev/null; sleep 1\n"
                + "A=$(cat \"$BASE/authdir\" 2>/dev/null); [ -n \"$A\" ] || A=\"$BASE\"\n"
                + "echo \"authorized_keys 目录: $A\"\n"
                + "for C in \"$(cat \"$BASE/dropbear.active\" 2>/dev/null)\" \"$BASE/dropbear_dyn\" \"$BASE/dropbear\"; do\n"
                + "  [ -n \"$C\" ] || continue\n"
                + "  [ -x \"$C\" ] || continue\n"
                + "  echo \"启动: $C\"\n"
                + "  exec \"$C\" -F -s -p " + PORT + " -r \"$BASE/hostkey_ed25519\" -D \"$A\" -c /system/bin/sh\n"
                + "  echo \"$C 退出 exit=$?，尝试下一个变体\"\n"
                + "done\n"
                + "echo '所有变体都无法启动'\n"
                + "exit 1\n";
    }

    /**
     * 以 root 采集完整诊断并**写到共享存储**，供容器侧直接读取（不必再让用户截图）。
     *
     * <p>输出路径是 App 的外部私有目录：/storage/emulated/0/Android/data/io.dsh.sshd/files/，
     * 容器已挂载 /storage/emulated/0，可直接读。
     */
    public static String planDiag(String outPath) {
        return prelude()
                + "O='" + outPath + "'\n"
                + "rm -f \"$O\" 2>/dev/null\n"
                + "P() { echo \"$@\" >> \"$O\"; }\n"
                + "P '=== dsh-sshd 诊断 ==='\n"
                + "P \"--- uname ---\"; uname -a >> \"$O\" 2>&1\n"
                + "P '--- 选定变体 ---'; cat \"$BASE/dropbear.active\" >> \"$O\" 2>&1\n"
                + "P '--- authdir ---'; cat \"$BASE/authdir\" >> \"$O\" 2>&1\n"
                + "A=$(cat \"$BASE/authdir\" 2>/dev/null); [ -n \"$A\" ] || A=\"$BASE\"\n"
                + "P '--- authorized_keys ---'; ls -laZ \"$A/authorized_keys\" >> \"$O\" 2>&1\n"
                + "P '--- authorized_keys 前 40 字符 ---'; cut -c1-40 \"$A/authorized_keys\" >> \"$O\" 2>&1\n"
                + "P '--- 逐级权限链（dropbear checkpubkeyperms 的判定依据）---'\n"
                + "Q=\"$A\"\n"
                + "while : ; do\n"
                + "  printf '%-34s uid=%s gid=%s mode=%s ctx=%s\\n' \"$Q\" \"$(stat -c %u \"$Q\" 2>/dev/null)\" \"$(stat -c %g \"$Q\" 2>/dev/null)\" \"$(stat -c %a \"$Q\" 2>/dev/null)\" \"$(stat -c %C \"$Q\" 2>/dev/null)\" >> \"$O\" 2>&1\n"
                + "  [ \"$Q\" = \"/\" ] && break\n"
                + "  Q=$(dirname \"$Q\")\n"
                + "done\n"
                + "P '--- BASE ---'; ls -laZ \"$BASE\" >> \"$O\" 2>&1\n"
                + "P '--- dropbear 进程 ---'; ps -A -o USER,PID,LABEL,NAME 2>/dev/null | grep -w dropbear >> \"$O\" 2>&1\n"
                + "P '--- dropbear.log 末尾 ---'; tail -40 \"$BASE/dropbear.log\" >> \"$O\" 2>&1\n"
                + "P '--- 候选目录判定 ---'\n"
                + "for C in" + authDirArg() + "; do\n"
                + "  echo \"  $C\" >> \"$O\" 2>&1\n"
                + "  ls -ldZ \"$C\" >> \"$O\" 2>&1\n"
                + "done\n"
                + "P '--- /data 下候选父目录 ---'\n"
                + "for D in /data/adb /data/misc /data/system /data/local /data/local/tmp; do\n"
                + "  ls -ldZ \"$D\" >> \"$O\" 2>&1\n"
                + "done\n"
                // 把权限探针的结果直接并进这份诊断，容器侧一次就能读全，不必再让用户点别的按钮。
                + "P ''\n"
                + "P '=== 权限探针（三种判据交叉验证）==='\n"
                + "PR=\"$BASE/perm-probe.txt\"\n"
                + "rm -f \"$PR\" 2>/dev/null\n"
                + "for A in" + authDirArg() + "; do\n"
                + "  P \"--- $A ---\"\n"
                + "  mkdir -p \"$A\" 2>/dev/null; chmod 700 \"$A\" 2>/dev/null\n"
                + "  P \"  ls: $(ls -ld \"$A\" 2>&1)\"\n"
                + "  Q=\"$A\"\n"
                + "  while : ; do\n"
                + "    U=$(stat -c %u \"$Q\" 2>/dev/null); M=$(stat -c %a \"$Q\" 2>/dev/null)\n"
                + "    M3=$(printf '%03d' \"$M\" 2>/dev/null)\n"
                + "    GW=$(printf '%s' \"$M3\" | cut -c2); OW=$(printf '%s' \"$M3\" | cut -c3)\n"
                + "    GW_W=0; case \"$GW\" in *[2367]) GW_W=1;; esac\n"
                + "    OW_W=0; case \"$OW\" in *[2367]) OW_W=1;; esac\n"
                + "    F_W=0; find \"$Q\" -maxdepth 0 -perm /022 >/dev/null 2>&1 && F_W=1\n"
                + "    P \"  $Q uid=[$U] mode=[$M] 组写=$GW_W 其他写=$OW_W find022=$F_W\"\n"
                + "    [ \"$Q\" = \"/\" ] && break\n"
                + "    Q=$(dirname \"$Q\")\n"
                + "  done\n"
                + "done\n"
                + "chmod 644 \"$O\" 2>/dev/null\n"
                + "echo \"诊断已写入 $O（$(wc -c < \"$O\" 2>/dev/null) 字节）\"\n";
    }

    /** 6. 状态与监听检查（只读）。 */
    public static String planStatus() {
        String hex = Integer.toHexString(PORT).toUpperCase();
        return prelude()
                + "echo -n '选定变体: '; cat \"$BASE/dropbear.active\" 2>/dev/null || echo '(未选定)'\n"
                + "echo -n 'authdir: '; cat \"$BASE/authdir\" 2>/dev/null || echo '(未选定)'\n"
                + "A=$(cat \"$BASE/authdir\" 2>/dev/null); [ -n \"$A\" ] && ls -la \"$A/authorized_keys\" 2>&1\n"
                + "echo '--- 文件 ---'\n"
                + "ls -la \"$BASE\" 2>&1\n"
                + "echo '--- 进程 ---'\n"
                + "ps -A -o USER,PID,NAME 2>/dev/null | grep -w dropbear || echo '(dropbear 未运行)'\n"
                + "echo '--- 监听（/proc/net/tcp 端口 " + hex + "）---'\n"
                + "{ cat /proc/net/tcp /proc/net/tcp6; } 2>/dev/null | awk '$2 ~ /:" + hex + "$/ {print $0}' || true\n"
                + "echo '--- 最近日志 ---'\n"
                + "tail -20 \"$BASE/dropbear.log\" 2>/dev/null || echo '(无日志)'\n";
    }

    /** 7. 开机自启脚本正文（用户确认后安装）。用探测阶段选定的变体，并记录到日志。 */
    public static String bootScriptBody() {
        return "#!/system/bin/sh\n"
                + "# dsh-sshd: 开机自启 SSH 控制通道（App 生成，可安全删除）\n"
                + "BASE='" + base() + "'\n"
                + "log -t dsh_sshd \"booting: starting sshd on " + PORT + "\" 2>/dev/null\n"
                + "[ -s \"$BASE/hostkey_ed25519\" ] || exit 0\n"
                + "C=$(cat \"$BASE/dropbear.active\" 2>/dev/null)\n"
                + "A=$(cat \"$BASE/authdir\" 2>/dev/null); [ -n \"$A\" ] || A=\"$BASE\"\n"
                + "[ -x \"$C\" ] || C=\"$BASE/dropbear_dyn\"\n"
                + "[ -x \"$C\" ] || C=\"$BASE/dropbear\"\n"
                + "[ -x \"$C\" ] || exit 0\n"
                + "pkill -f \"$BASE/dropbear\" 2>/dev/null\n"
                + "sleep 1\n"
                + "\"$C\" -F -s -p " + PORT
                + " -r \"$BASE/hostkey_ed25519\" -D \"$A\" -c /system/bin/sh >>\"$BASE/dropbear.log\" 2>&1 &\n"
                + "log -t dsh_sshd \"sshd started: $C (pid $!)\" 2>/dev/null\n";
    }

    /** 8. 安装开机自启脚本到所有可见的 service.d。 */
    public static String planInstallBootScript() {
        StringBuilder sb = new StringBuilder(prelude());
        sb.append("TMP=\"$BASE/" + SCRIPT_NAME + "\"\n")
          .append("cat > \"$TMP\" <<'DSHEOF'\n")
          .append(bootScriptBody())
          .append("DSHEOF\n")
          .append("echo -n '暂存脚本行数: '; wc -l < \"$TMP\" 2>/dev/null\n");
        for (String d : serviceDirs()) {
            sb.append("if [ -d ").append(d).append(" ]; then\n")
              .append("  if cp \"$TMP\" ").append(d).append('/').append(SCRIPT_NAME).append(" 2>/dev/null; then\n")
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
        return sb.toString();
    }

    // ------------------------------------------------------------- 流程编排

    /** 完整安装：目录 → 二进制 → 主机密钥 → authorized_keys。返回 false 表示中途失败。 */
    public static boolean install(String assetsTmpDir, String diagOutPath, Result r) {
        ShellRunner.Result prep = ShellRunner.runFirstOk(planPrepare(), ROOT_SHELLS);
        r.add("1. 准备目录 " + base(), prep.all());
        if (prep.exit != 0) return false;

        ShellRunner.Result bins = ShellRunner.runFirstOk(planInstallBinaries(assetsTmpDir), ROOT_SHELLS);
        r.add("2. 安装二进制（root 拥有、755）", bins.all());
        if (!bins.out.contains("选定变体: ")) return false;
        if (bins.out.contains("选定变体: (无可用变体)")) return false;

        ShellRunner.Result key = ShellRunner.runFirstOk(planHostKey(assetsTmpDir), ROOT_SHELLS);
        r.add("3. 主机密钥（设备端生成 → 失败则用内嵌预生成密钥）", key.all());
        if (!key.out.contains("hostkey 可用: yes")) {
            r.add("中止", "主机密钥仍然不可用，dropbear 无法启动。请把上面完整输出发我。");
            return false;
        }

        ShellRunner.Result auth = ShellRunner.runFirstOk(planAuthorizedKeys(), ROOT_SHELLS);
        r.add("4. 写入客户端公钥 authorized_keys（到探测选定的合规目录）", auth.all());
        if (!auth.out.contains("含客户端公钥: yes")) {
            r.add("警告", "authorized_keys 未写入成功，认证会失败。");
        }

        ShellRunner.Result diag = ShellRunner.runFirstOk(planDiag(diagOutPath), ROOT_SHELLS);
        r.add("5. 导出诊断到共享存储", diag.all());
        return true;
    }

    /** 启动服务端（阻塞式：调用方在独立线程里跑）。 */
    public static ShellRunner.Result startServer() {
        return ShellRunner.runFirstOk(planStart(), ROOT_SHELLS);
    }

    /**
     * 权限探针：对候选目录逐个用**多种判据**检查，并把每一步原始取值打到文件里。
     *
     * <p>为什么需要它：上一版的判定写成 {@code case "$M" in *[2367])}，只看 mode 的**最后一位**，
     * 而 mode 是三位（属主/组/其他）——771 这种「组可写」会被误判为合规。我在 PC 上想当然的
     * 判定规则并不可靠，所以让设备把原始值打出来，用三种判据交叉验证。
     *
     * <p>dropbear 的真实要求（src/svr-authpubkey.c:610 checkfileperm）：
     * 属主 ∈ {root, 该用户}，且 (mode &amp; (S_IWGRP|S_IWOTH)) == 0。
     */
    public static String planFixPerms() {
        String[] cans = authDirCandidates();
        StringBuilder sb = new StringBuilder(prelude());
        sb.append("O=\"$BASE/perm-probe.txt\"\n")
          .append("rm -f \"$O\" 2>/dev/null\n")
          .append("P() { echo \"$@\" >> \"$O\"; }\n")
          .append("P \"date=$(date)\"\n")
          .append("P '--- stat 支持情况 ---'\n")
          .append("stat -c '%a' /data >> \"$O\" 2>&1 || P 'stat -c %a 失败'\n")
          .append("stat -c '%04a' /data >> \"$O\" 2>&1 || P 'stat -c %04a 失败'\n")
          .append("P '--- find -perm 支持情况 ---'\n")
          .append("find /data -maxdepth 0 -perm /022 -print >> \"$O\" 2>&1 || P 'find -perm 失败'\n");

        for (String d : cans) {
            sb.append("A='").append(d).append("'\n")
              .append("P ''\n")
              .append("P \"===== ").append(d).append(" =====\"\n")
              .append("mkdir -p \"$A\" 2>/dev/null; chmod 700 \"$A\" 2>/dev/null\n")
              .append("P \"ls: $(ls -ldZ \"$A\" 2>&1)\"\n")
              .append("PATHS=\"$A\"\n")
              .append("Q=\"$A\"\n")
              .append("while [ \"$Q\" != \"/\" ]; do Q=$(dirname \"$Q\"); PATHS=\"$PATHS $Q\"; done\n")
              .append("for Q in $PATHS; do\n")
              .append("  U=$(stat -c %u \"$Q\" 2>/dev/null); M=$(stat -c %a \"$Q\" 2>/dev/null)\n")
              .append("  P \"  $Q uid=[$U] mode=[$M]\"\n")
              .append("  M3=$(printf '%03d' \"$M\" 2>/dev/null)\n")
              .append("  GW=$(printf '%s' \"$M3\" | cut -c2); OW=$(printf '%s' \"$M3\" | cut -c3)\n")
              .append("  GW_W=0; case \"$GW\" in *[2367]) GW_W=1;; esac\n")
              .append("  OW_W=0; case \"$OW\" in *[2367]) OW_W=1;; esac\n")
              .append("  F_W=0; find \"$Q\" -maxdepth 0 -perm /022 >/dev/null 2>&1 && F_W=1\n")
              .append("  OLD_W=0; case \"$M\" in *[2367]) OLD_W=1;; esac\n")
              .append("  P \"      组写=$GW_W 其他写=$OW_W find_022=$F_W 旧逻辑=$OLD_W\"\n")
              .append("done\n")
              .append("OK=1\n")
              .append("for Q in $PATHS; do\n")
              .append("  U=$(stat -c %u \"$Q\" 2>/dev/null); M=$(stat -c %a \"$Q\" 2>/dev/null)\n")
              .append("  [ \"$U\" = 0 ] || OK=0\n")
              .append("  M3=$(printf '%03d' \"$M\" 2>/dev/null)\n")
              .append("  GW=$(printf '%s' \"$M3\" | cut -c2); OW=$(printf '%s' \"$M3\" | cut -c3)\n")
              .append("  case \"$GW\" in *[2367]) OK=0;; esac\n")
              .append("  case \"$OW\" in *[2367]) OK=0;; esac\n")
              .append("done\n")
              .append("if [ \"$OK\" = 1 ]; then P \"  => 合规\"; else P \"  => 不合规\"; fi\n");
        }

        sb.append("P ''\n")
          .append("BEST=''\n");
        for (String d : cans) {
            sb.append("A='").append(d).append("'\n")
              .append("OK=1\n")
              .append("Q=\"$A\"\n")
              .append("while [ \"$Q\" != \"/\" ]; do\n")
              .append("  U=$(stat -c %u \"$Q\" 2>/dev/null); M=$(stat -c %a \"$Q\" 2>/dev/null)\n")
              .append("  [ \"$U\" = 0 ] || OK=0\n")
              .append("  M3=$(printf '%03d' \"$M\" 2>/dev/null)\n")
              .append("  GW=$(printf '%s' \"$M3\" | cut -c2); OW=$(printf '%s' \"$M3\" | cut -c3)\n")
              .append("  case \"$GW\" in *[2367]) OK=0;; esac\n")
              .append("  case \"$OW\" in *[2367]) OK=0;; esac\n")
              .append("  Q=$(dirname \"$Q\")\n")
              .append("done\n")
              .append("if [ \"$OK\" = 1 ] && [ -z \"$BEST\" ]; then BEST=\"$A\"; fi\n");
        }
        sb.append("P \"选定=${BEST:-无}\"\n")
          .append("if [ -n \"$BEST\" ]; then echo \"$BEST\" > \"$BASE/authdir\"; fi\n")
          .append("chmod 644 \"$O\" 2>/dev/null\n")
          .append("echo \"权限探针已写入 $O（$(wc -c < \"$O\" 2>/dev/null) 字节），选定=${BEST:-无}\"\n");
        return sb.toString();
    }

    /** 独立跑权限探针（供按钮使用）。 */
    public static ShellRunner.Result probePerms() {
        return ShellRunner.runFirstOk(planFixPerms(), ROOT_SHELLS);
    }

    /** 独立导出诊断（供「导出诊断」按钮使用）。 */
    public static ShellRunner.Result exportDiag(String outPath) {
        return ShellRunner.runFirstOk(planDiag(outPath), ROOT_SHELLS);
    }

    /** 只读自检。 */
    public static Result status() {
        Result r = new Result();
        ShellRunner.Result id = ShellRunner.runFirstOk("id", ROOT_SHELLS);
        r.isRoot = id.out.contains("uid=0");
        r.add("root", id.all());
        r.add("状态", ShellRunner.runFirstOk(planStatus(), ROOT_SHELLS).all());
        r.listening = isListening();
        r.deviceIp = detectIpv4();
        return r;
    }

    // ------------------------------------------------------------- 本机探测

    /** 端口是否 LISTEN：真连回环。 */
    public static boolean isListening() {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 1200);
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 取第一个非回环 IPv4，优先 wlan。 */
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
