# ADB 桥（adb-bridge）

一个装在手机上的极小 App：**用 root 让 `adbd` 监听 TCP 5555，并把本容器 adb 客户端的公钥写进
`/data/misc/adb/adb_keys` 完成预授权**。装完之后，这个容器里的 `adb` 就能直接连上你的手机，
后续所有定位诊断/改配置/复测都由我来跑，不需要你在手机上复制粘贴命令。

- 包名：`io.dsh.adbbridge`，版本 1.0（versionCode 1）
- 体积：20883 字节，debug 签名（v1+v2），minSdk 21 / targetSdk 34
- md5（容器内与共享存储一致）：`6330d888b014e2bd044590d53071e591`

---

## 一、安装

APK 已放到手机的共享存储：

```
/storage/emulated/0/Download/adb-bridge/adb-bridge-debug.apk
```

在手机文件管理器里进入 `Download/adb-bridge/`，点这个 APK 安装（会提示"未知来源"，允许一次即可）。

## 二、使用

1. 打开 App「ADB 桥」。启动时会自动做一次**只读自检**，此时会弹出 ROOT 授权框 —— 点**允许**。
2. 点 **① 建立 / 重建 ADB 桥**。它依次做四件事：
   - 把宿主公钥写入 `/data/misc/adb/adb_keys`（`640 root:shell`，SELinux 上下文 `adb_keys_file`）
   - `setprop service.adb.tcp.port 5555` 并重启 `adbd`
   - 安装**开机自愈脚本**到 root 管理器的 `service.d`
   - 自检端口是否已在监听，并在结论区打印手机 IP 与可用的 `adb connect` 命令
3. 把结论区那几行截图或念给我即可（一般会显示 `adbd 5555 监听=true`）。

按钮说明：

| 按钮 | 作用 |
|---|---|
| ① 建立 / 重建 ADB 桥 | 完整的写入 + 开端口 + 装自愈脚本（需要 root） |
| ② 只自检 | 只读，不改任何东西；显示 adbd 身份、端口属性、adb_keys 内容与自愈脚本状态 |
| ③ 兜底：重新注入 key | 只重写 key + 重开端口，用于 key 被系统清掉时 |

## 三、它到底改了什么（可完整回滚）

| 改动 | 位置 | 回滚方式 |
|---|---|---|
| 追加一行公钥 | `/data/misc/adb/adb_keys` | 删掉那一行（`grep -v dsh` 或直接编辑） |
| 设置属性 | `service.adb.tcp.port=5555` | 每次开机后由系统重置，无需处理 |
| 开机自愈脚本 | `/data/adb/service.d/dsh-adb-bridge.sh`（或 `/data/adb/ksu/service.d/`） | 删掉该文件即可 |

没有改动 `/system`、`/vendor`，没有装 Magisk 模块，没有常驻进程，没有联网权限。

## 四、为什么要开机自愈脚本（这不是画蛇添足）

两条来自一手资料的硬约束：

1. **`adbd` 只认两个文件**：只读分区的 `/adb_keys`，和可写的 `/data/misc/adb/adb_keys`，
   由 `adbd_auth` 直接读取
   （[GrapheneOS platform_packages_modules_adb/docs/dev/keystore.md](https://github.com/GrapheneOS/platform_packages_modules_adb/blob/17/docs/dev/keystore.md)）。
2. **框架会整文件重写 `adb_keys`**：`frameworks/base` 的 `AdbDebuggingManager.writeKeys()` 用
   `AtomicFile` 把文件内容重写成"它自己记着的 key 列表"（来自 `adb_temp_keys.xml`），
   因此**手工注入的 key 会在系统做 key 刷新时被冲掉**。

所以桥提供两条冗余路径：

- **首选**：等你手机首次弹出 ADB 授权框时，**勾选「一直允许」**。这样 key 会被框架接管，
  写进 `adb_temp_keys.xml`，以后系统自己维护，不依赖手工注入。
- **兜底**：`service.d` 里的自愈脚本在每次开机时把 key 补回来并重开端口。

## 五、安全提示

- 5555 端口是**明文、无加密**的 ADB，任何能连到该端口且持有配对私钥的机器都能拿到 shell。
  本机是同 Wi-Fi 网段（容器 `wlan0` = 192.168.1.15），所以**别在不可信热点下长期开着**。
- 干完活想关掉：`setprop service.adb.tcp.port -1` 再重启 adbd，并删掉自愈脚本。

## 六、构建（可复现）

见 `BUILD.md`。无 Gradle：`javac` + `d8` + `aapt2` + `apksig` 手工链，`bash build.sh` 出包。
测试：`test/PlanTest.java` 在 PC 上对 App 生成的全部 shell 脚本做语法校验与沙箱真跑
（脚本用 `DATA_ROOT` 参数化，所以能逐字执行而不做字符串替换）。
