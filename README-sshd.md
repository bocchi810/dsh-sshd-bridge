# SSH 控制通道（sshd-bridge）

手机侧 APK：内置交叉编译的 **dropbear**，以 root 身份监听 **TCP 2222**，**只允许公钥认证**。
装完并点一次按钮后，这个容器就能用 `ssh` 直接控制手机执行任意 `adb shell` 级命令——
相当于一条类 SSH 的远程 root 通道，不需要数据线、不需要无线调试配对码。

- 包名 `io.dsh.sshd`，versionName 2.0，debug 签名（v1+v2），minSdk 21 / targetSdk 34
- 体积 1950278 字节，md5 `6c32d145684acf530c4eaa5a0348a785`
- 共享存储路径：`/storage/emulated/0/Download/adb-bridge/sshd-bridge-debug.apk`

---

## 一、装 + 跑

1. 文件管理器进 `Download/adb-bridge/`，点 `sshd-bridge-debug.apk` 安装（允许一次未知来源）。
2. 打开 App「SSH 控制通道」→ 点 **① 启动 SSH 服务** → 弹出的 ROOT 授权框点**允许**。
3. 日志里出现连接命令后，把结论几行告诉我即可。

| 按钮 | 作用 |
|---|---|
| ① 启动 SSH 服务 | 解出二进制 → 装到 `/data/local/tmp/dsh-sshd/` → 生成主机密钥 → 写 authorized_keys → 前台启动 dropbear |
| ② 只自检 | 只读：文件、进程、监听状态、最近日志 |
| ③ 停止 SSH 服务 | `pkill` 掉 dropbear |

## 二、容器侧连接

```bash
chmod 600 /root/harness/adb-bridge/keys/ssh_client
ssh -i /root/harness/adb-bridge/keys/ssh_client -p 2222 \
    -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
    root@127.0.0.1          # 容器与手机同网络命名空间；也可用手机 wlan IP
```

连上后即可执行任意命令，例如：

```bash
ssh ... root@127.0.0.1 'settings get global assisted_gps_enabled; dumpsys location -a | head -50'
```

## 三、设计要点与依据（不是随手写的）

| 决策 | 原因 |
|---|---|
| 二进制放 `/data/local/tmp/dsh-sshd/`，不放应用私有目录 | 要以 root 运行；`/data/data/<pkg>` 是 0700，root 的 SELinux 域（su）访问会被拒；`/data/local/tmp` 是 `shell_data_file`，root 可读可执行 |
| **只开公钥认证**（`-s`），不编译密码认证 | Android bionic 没有 `crypt()`；编译期把 `DROPBEAR_SVR_PASSWORD_AUTH` 置 0。顺带消除了暴力破解面 |
| 主机密钥在**设备上用 `dropbearkey -t ed25519` 现生成** | dropbear 的密钥格式与 OpenSSH 不同，跨平台预生成有格式风险；`ed25519` 是其默认类型（源码 `dropbearkey.c:65`） |
| `-F` 前台运行 + shell `exec` | 让父进程就是 dropbear 本体，App 能可靠 start/stop 并拿到退出码，不用猜 PID |
| 脚本**不用** shell 参数展开（`${X:-/data}`） | root 的 `su` 未必经 POSIX shell（Android 常见 mksh），字面执行会让路径变成 `$X`；改为 Java 侧解析后内联绝对路径 |
| 认证凭据是**客户端公钥** | 私钥只存在容器 `/root/harness/adb-bridge/keys/ssh_client`（0600），手机侧只有公钥 |

## 四、风险（请自己权衡后再长期开着）

- 这是**手机的 root shell**。持有对应私钥、且能连到 2222 端口的机器即可拿到 root。
  端口在整个 Wi-Fi 网段可达，**别在不可信热点下长期开着**。
- 传输是 SSH 加密的（比明文 `adb tcpip 5555` 好），但**授权完全依赖那把私钥**——
  私钥泄露等于手机 root 泄露。别把 `keys/ssh_client` 复制到别处。
- 想关掉：App 点 ③，或 `ssh ... 'pkill -f dsh-sshd/dropbear'`；再删 `/data/local/tmp/dsh-sshd/`。

## 五、构建（可复现）

```bash
# 1) 交叉编译 dropbear（NDK r27c，静态 aarch64-android24）
bash build-dropbear.sh          # 产物在 out-native/，已 strip --strip-debug

# 2) 生成客户端密钥（已生成，勿覆盖）
ssh-keygen -t ed25519 -f keys/ssh_client -N ''

# 3) 打包 APK（无 Gradle：javac + d8 + aapt2 + apksig）
bash build-sshd-apk.sh          # 产物 out/sshd-bridge-debug.apk
```

测试：`SSHD_ROOT=/tmp/sshd-sandbox java -cp build-test SshdPlanTest`
—— 对 App 生成的全部 shell 脚本做 `sh -n` 语法校验 + 沙箱**逐字真跑**
（安装、权限、authorized_keys 幂等、状态输出、开机脚本安装）。

> 注：`dbclient` 未打进包（省 1.1MB），手机侧不需要往外 ssh。
> 需要的话 `out-native/dbclient` 是编好的，可单独推送。
