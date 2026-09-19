# dsh-sshd-bridge

在 Android 手机上跑一个 **root SSH 控制通道**：交叉编译 [dropbear](https://matt.ucc.asn.au/dropbear/)，
配一个极简 APK 做安装器（写入主机密钥与 `authorized_keys`、启动 `dropbear`、开机自启）。

用途：从开发机通过 SSH 远程执行手机上的命令，无需数据线、无需无线调试配对码。

## 产物

每次构建把以下文件发到 Release（默认 tag `apk-latest`，同名则覆盖更新）：

| 文件 | 说明 |
|---|---|
| `sshd-bridge-debug.apk` | 安装器 APK（内嵌 dropbear 两种链接方式 + 主机密钥） |
| `sshd-bridge-debug.apk.sha256` | 校验值 |
| `hostkey_ed25519` / `.pub` | 服务端主机密钥（**幂等复用**，不会每次重建导致指纹漂移） |

## 为什么打包两种链接方式

| 变体 | 说明 |
|---|---|
| `dropbear_dyn` | **动态链接**设备自带的 bionic libc（有 `PT_INTERP`）——实测在 Android 15 / 6.6.118 内核上可执行 |
| `dropbear` | 静态 static-PIE（`e_type=DYN`、无 `PT_INTERP`）——同一设备上**执行即 SIGSEGV**（内核未启用 static-PIE 自举） |

App 启动时用 `dropbearkey -h` 做探针实测选一种（该探针会走 `crypto_init() + seedrandom()`，
正是崩溃所在的那段路径，且不绑定端口、不依赖任何文件 —— 见 `src/dropbearkey.c` 的调用顺序）。

## 对 dropbear 打的两个补丁

见 `ci/build-dropbear.sh`，两处都写在脚本里且可复现：

1. **仅公钥认证**：Android bionic 没有 `crypt()`，且本用途不需要密码认证
   （`DROPBEAR_SVR_PASSWORD_AUTH=0`）。
2. **跳过 `authorized_keys` 的父目录权限检查**：dropbear 的 `checkpubkeyperms()`
   要求路径的每个组件「属主 root/该用户」且「无组/其他写位」，而 Android 的 `/data`
   是 `uid=1000 mode=777`，可写路径全在 `/data` 之下 → 任何位置都过不了，
   表现为「SSH 握手成功但认证被拒」且只有一条 INFO 日志。
   已 root 的手机上这个检查没有实际意义，世界可写位由 SELinux 兜底。
   **不放松任何密码学校验。**

补丁会往版本串追加 `-dsh-nopermcheck`，所以从 SSH banner 就能确认跑的是打过补丁的二进制。

## 安全边界（请自行权衡）

- 这是手机的 **root shell**。持有对应私钥、且能连到 2222 端口的机器即为 root。
- 传输是 SSH 加密的，但授权完全依赖那把客户端私钥 —— 别泄露，别在不可信热点下长期开着。
- 只监听 TCP 2222，公钥认证（`-s`），无密码认证。

## 构建

GitHub Actions：[`.github/workflows/build-sshd-bridge.yml`](.github/workflows/build-sshd-bridge.yml)

- NDK 按版本缓存；dropbear 产物按「构建脚本 + 源码版本 + API」哈希缓存，命中即跳过编译
- 工作流手动触发可指定 `version_code` / `version_name` / `tag`
