#!/usr/bin/env bash
# 无 Gradle 手工构建：aarch64 Debian + qemu binfmt 跑 x86-64 aapt2
# 产物：out/sshd-bridge-debug.apk（内置 dropbear，root SSH 控制通道）
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
T="$HERE/tools"
OUT="$HERE/out"
CLASSES="$OUT/classes"
DEX="$OUT/dex"
RES="$OUT/res-compiled"

rm -rf "$OUT"
mkdir -p "$CLASSES" "$DEX" "$RES"

JAVABASE=$(dirname $(dirname $(readlink -f $(which javac))))/lib/modules

# aapt2.jar 里是原生 ELF（x86-64，本机靠 qemu binfmt 运行），需先解出来
if [ ! -x "$T/x/aapt2" ]; then
  mkdir -p "$T/x" && (cd "$T/x" && unzip -o -q ../aapt2.jar aapt2 && chmod +x aapt2)
fi
AAPT2="$T/x/aapt2"

echo "== 0/7 校验 assets =="
for f in dsh-dropbear dsh-dropbearkey dsh-dropbear_dyn dsh-dropbearkey_dyn dsh-client.pub dsh-hostkey_ed25519 dsh-hostkey_ed25519.pub; do
  [ -s "$HERE/assets/$f" ] || { echo "缺少 assets/$f"; exit 1; }
  printf '   %-20s %s 字节\n' "$f" "$(stat -c%s "$HERE/assets/$f")"
done

echo "== 1/7 编译 Java =="
cd "$HERE/src"
find . -name '*.java' > "$OUT/sources.txt"
javac -nowarn -Xlint:-options -source 8 -target 8 \
      -bootclasspath "$JAVABASE" -classpath "$T/android.jar" \
      -d "$CLASSES" @"$OUT/sources.txt"
echo "   类文件: $(find "$CLASSES" -name '*.class' | wc -l)"

echo "== 2/7 dex (d8) =="
java -cp "$T/r8.jar" com.android.tools.r8.D8 --release --min-api 21 \
      --output "$DEX" $(find "$CLASSES" -name '*.class') 2>&1 | grep -vE '^Warning|^Type `' || true
ls -la "$DEX"

echo "== 3/7 编译资源 =="
"$AAPT2" compile --dir "$HERE/res" -o "$RES/res.zip"

echo "== 4/7 链接资源 + manifest + assets =="
# -A 必须给：Android 的 AssetManager 只从 APK 内的 assets/ 目录读，
# 把文件 zip 到包根目录是读不到的（App 的 am.list("") 会返回空）。
"$AAPT2" link \
    -o "$OUT/app-unsigned.apk" \
    -I "$T/android.jar" \
    --manifest "$HERE/AndroidManifest.xml" \
    --min-sdk-version 21 \
    --target-sdk-version 34 \
    -R "$RES/res.zip" \
    -A "$HERE/assets" \
    --auto-add-overlay

echo "== 5/7 打包 dex =="
cd "$DEX"
zip -q -X "$OUT/app-unsigned.apk" classes.dex
echo "   包内清单:"
unzip -l "$OUT/app-unsigned.apk" | grep -E 'assets/|classes.dex|AndroidManifest' || true
unzip -l "$OUT/app-unsigned.apk" | tail -8

echo "== 6/7 签名 =="
KS="$HERE/keys/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storetype PKCS12 -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
APK="$OUT/sshd-bridge-debug.apk"
javac -nowarn -cp "$T/apksigner.jar" -d "$OUT" "$T/SignApk.java"
java -cp "$T/apksigner.jar:$OUT" SignApk "$KS" android androiddebugkey android \
    "$OUT/app-unsigned.apk" "$APK"
rm -f "$OUT/app-unsigned.apk"

echo "== 7/7 校验（包内路径必须与 App 读取路径一致，否则直接失败）=="
ls -la "$APK"
"$AAPT2" dump badging "$APK" 2>/dev/null | grep -E "^package|launchable-activity|targetSdk" || true
ASSETS_DIR="$HERE/assets" python3 - "$APK" <<'PY'
import sys, os, zipfile, hashlib

apk = sys.argv[1]
z = zipfile.ZipFile(apk)
data = open(apk, 'rb').read()
ok = True
print("  v2 签名块:", b"APK Sig Block 42" in data)
print("  v1 签名:", "META-INF/MANIFEST.MF" in z.namelist())

names = z.namelist()
src_dir = os.environ["ASSETS_DIR"]

# App 侧契约（MainActivity.extractAssets）：am.list("") 取 assets 根下以 "dsh-" 开头的项，
# 去掉 "dsh-" 前缀后作为落盘文件名。因此 asset 名必须让「去前缀」的结果正好等于
# SshdCore 在设备端使用的文件名 —— 这里把映射写成断言，命名不一致直接拒绝交付。
REQUIRED = {
    "dsh-dropbear": "dropbear",
    "dsh-dropbearkey": "dropbearkey",
    "dsh-dropbear_dyn": "dropbear_dyn",
    "dsh-dropbearkey_dyn": "dropbearkey_dyn",
    "dsh-hostkey_ed25519": "hostkey_ed25519",
    "dsh-hostkey_ed25519.pub": "hostkey_ed25519.pub",
    "dsh-client.pub": "client.pub",
}

for asset, target in REQUIRED.items():
    inner = "assets/" + asset
    if inner not in names:
        print(f"  FAIL 包内缺少 {inner}（AssetManager 读不到根目录下的文件！）")
        ok = False
        continue
    in_apk = z.read(inner)
    local = open(os.path.join(src_dir, asset), 'rb').read()
    same = hashlib.sha256(in_apk).hexdigest() == hashlib.sha256(local).hexdigest()
    if not same:
        ok = False
    print(f"  {'OK  ' if same else 'FAIL'} {inner:30s} -> 设备端 {target:18s} 包内 {len(in_apk):>9d} 字节")

claimed = set(REQUIRED)
present = {n[len("assets/"):] for n in names if n.startswith("assets/dsh-")}
for extra in sorted(present - claimed):
    print(f"  WARN assets/{extra} 不在必需清单里（会被解出但无人使用）")

stray = [n for n in names if n in REQUIRED]
if stray:
    print("  FAIL 包根目录还残留文件（App 读不到）:", stray)
    ok = False

if not ok:
    print("\n包内布局校验失败 —— 拒绝交付")
    sys.exit(1)
print("\n  包内布局校验通过：assets/ 路径与内容均正确")
PY
