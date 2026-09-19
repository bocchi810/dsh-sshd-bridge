# adb-bridge 构建说明

无 Gradle 的手工构建链（容器为 aarch64 Debian sid，无 Android SDK 管理器）。

## 工具

| 工具 | 来源 | 说明 |
|---|---|---|
| JDK 17 | `apt install openjdk-17-jdk-headless` | 编译 / 签名 |
| aapt2 8.13.2 | `dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/8.13.2-14304508/aapt2-8.13.2-14304508-linux.jar` | 内含 ELF **x86-64** 的 `aapt2`，本机靠 qemu binfmt 用户态仿真运行 |
| r8 9.4.24 | `.../com/android/tools/r8/9.4.24/r8-9.4.24.jar` | 只用 `com.android.tools.r8.D8` |
| apksig 8.13.2 | `.../com/android/tools/build/apksig/8.13.2/apksig-8.13.2.jar` | `com.android.apksig.ApkSigner` |
| android.jar | `repo1.maven.org/.../com/google/android/android/4.1.1.4/android-4.1.1.4.jar` | 仅作编译期 stub；API 16 级别，故意只用最老的 API 以规避 stub 限制 |

## 步骤

```bash
bash build.sh
```

产物：`out/adb-bridge-debug.apk`（v1+v2 签名，debug keystore）。

## 密钥

- `keys/adbkey` / `keys/adbkey.pub`：本机 adb 客户端私钥与对应公钥（PKCS#8 PEM / adb 自定义 base64 格式）。
  公钥被内嵌进 App（`BridgeCore.PUBKEY`），由 App 用 root 写入手机 `/data/misc/adb/adb_keys`。
- `adbhome/`：作为 `ANDROID_USER_HOME` 交给容器里的 adb，使其使用这把私钥。
