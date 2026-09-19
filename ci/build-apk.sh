#!/usr/bin/env bash
# 在 CI 上组装 APK：解出预编译二进制与密钥 → 生成 debug keystore → 打包 → 签名。
# 依赖 assets/ 里已放好 dsh-* 文件（由 ci/build-dropbear.sh 产出）。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T="$REPO_ROOT/tools"
OUT="$REPO_ROOT/out"
ASSETS="$REPO_ROOT/assets"
CLASSES="$OUT/classes"; DEX="$OUT/dex"; RESC="$OUT/res-compiled"

VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"

rm -rf "$OUT"
mkdir -p "$CLASSES" "$DEX" "$RESC" "$T"

# ---------- 1. 构建工具（从 Google Maven 拉，不做缓存以免陈旧的工具链） ----------
AAPT2_VER="8.13.2-14304508"
R8_VER="9.4.24"
APKSIG_VER="8.13.2"
fetch() {  # fetch <url> <dest>
  [ -s "$2" ] || curl -fsSL --retry 3 -o "$2" "$1"
}
echo "== 拉取构建工具 =="
fetch "https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/${AAPT2_VER}/aapt2-${AAPT2_VER}-linux.jar" "$T/aapt2.jar"
fetch "https://dl.google.com/dl/android/maven2/com/android/tools/r8/${R8_VER}/r8-${R8_VER}.jar" "$T/r8.jar"
fetch "https://dl.google.com/dl/android/maven2/com/android/tools/build/apksig/${APKSIG_VER}/apksig-${APKSIG_VER}.jar" "$T/apksigner.jar"
fetch "https://repo1.maven.org/maven2/com/google/android/android/4.1.1.4/android-4.1.1.4.jar" "$T/android.jar"
mkdir -p "$T/x" && (cd "$T/x" && unzip -o -q ../aapt2.jar aapt2 && chmod +x aapt2)
AAPT2="$T/x/aapt2"
"$AAPT2" version

# ---------- 2. assets 检查（命名必须让「去 dsh- 前缀」== 设备端目标名） ----------
echo "== assets =="
ls -la "$ASSETS"
for f in dsh-dropbear dsh-dropbear_dyn dsh-dropbearkey dsh-dropbearkey_dyn dsh-client.pub dsh-hostkey_ed25519; do
  [ -s "$ASSETS/$f" ] || { echo "缺少 assets/$f" >&2; exit 1; }
  printf '  %-26s %9s 字节 -> 设备端 %s\n' "$f" "$(stat -c%s "$ASSETS/$f")" "${f#dsh-}"
done

# ---------- 3. 编译 Java ----------
echo "== 编译 Java =="
cd "$REPO_ROOT/src"
find . -name '*.java' > "$OUT/sources.txt"
javac -nowarn -Xlint:-options -source 8 -target 8 \
      -bootclasspath "$(dirname "$(dirname "$(readlink -f "$(which javac)")")")/lib/modules" \
      -classpath "$T/android.jar" -d "$CLASSES" @"$OUT/sources.txt"
echo "  类文件: $(find "$CLASSES" -name '*.class' | wc -l)"

# ---------- 4. dex ----------
echo "== dex (d8) =="
java -cp "$T/r8.jar" com.android.tools.r8.D8 --release --min-api 21 \
     --output "$DEX" $(find "$CLASSES" -name '*.class') 2>&1 | grep -vE '^Warning|^Type `' || true

# ---------- 5. 资源 + manifest（用 sed 注入版本号，避免额外模板文件） ----------
echo "== 资源与 manifest =="
"$AAPT2" compile --dir "$REPO_ROOT/res" -o "$RESC/res.zip"
sed -e "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VERSION_CODE\"/" \
    -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VERSION_NAME\"/" \
    "$REPO_ROOT/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
grep -oE 'versionCode="[0-9]+"|versionName="[^"]+"' "$OUT/AndroidManifest.xml"
"$AAPT2" link -o "$OUT/app-unsigned.apk" -I "$T/android.jar" \
    --manifest "$OUT/AndroidManifest.xml" \
    --min-sdk-version 21 --target-sdk-version 34 \
    -R "$RESC/res.zip" -A "$ASSETS" --auto-add-overlay

# ---------- 6. dex 入包 ----------
echo "== 打包 dex =="
(cd "$DEX" && zip -q -X "$OUT/app-unsigned.apk" classes.dex)

# ---------- 7. 签名（每次 CI 现生成 debug keystore；同一次运行内的两个产物一致） ----------
echo "== 签名 =="
KS="$REPO_ROOT/keys/debug.keystore"
mkdir -p "$REPO_ROOT/keys"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storetype PKCS12 -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
APK="$OUT/sshd-bridge-debug.apk"
javac -nowarn -cp "$T/apksigner.jar" -d "$OUT" "$REPO_ROOT/tools/SignApk.java"
java -cp "$T/apksigner.jar:$OUT" SignApk "$KS" android androiddebugkey android \
     "$OUT/app-unsigned.apk" "$APK"
rm -f "$OUT/app-unsigned.apk" "$OUT"/*.class

# ---------- 8. 校验：包内路径 + 命名映射（与 App 的读取逻辑一一对应） ----------
echo "== 校验 =="
ASSETS_DIR="$ASSETS" python3 - "$APK" <<'PY'
import sys, os, zipfile, hashlib
apk = sys.argv[1]
z = zipfile.ZipFile(apk)
data = open(apk, 'rb').read()
ok = True
print("  v2 签名块:", b"APK Sig Block 42" in data)
print("  v1 签名:", "META-INF/MANIFEST.MF" in z.namelist())

# App 侧契约（MainActivity.extractAssets）：am.list("") 取 assets 根下 "dsh-" 开头的项，
# 去掉前缀后作为落盘名；SshdCore 在设备端用的名字必须与「去前缀」的结果一致。
REQUIRED = {
    "dsh-dropbear": "dropbear",
    "dsh-dropbearkey": "dropbearkey",
    "dsh-dropbear_dyn": "dropbear_dyn",
    "dsh-dropbearkey_dyn": "dropbearkey_dyn",
    "dsh-hostkey_ed25519": "hostkey_ed25519",
    "dsh-client.pub": "client.pub",
}
src_dir = os.environ["ASSETS_DIR"]
names = z.namelist()
for asset, target in REQUIRED.items():
    inner = "assets/" + asset
    if inner not in names:
        print(f"  FAIL 包内缺少 {inner}"); ok = False; continue
    in_apk = z.read(inner)
    local = open(os.path.join(src_dir, asset), 'rb').read()
    same = hashlib.sha256(in_apk).hexdigest() == hashlib.sha256(local).hexdigest()
    if not same: ok = False
    print(f"  {'OK  ' if same else 'FAIL'} {inner:28s} -> 设备端 {target:18s} {len(in_apk):>9d} 字节")

for extra in sorted({n[len('assets/'):] for n in names if n.startswith('assets/dsh-')} - set(REQUIRED)):
    print(f"  WARN assets/{extra} 不在必需清单里（会被解出但无人使用）")
for stray in [n for n in names if n in REQUIRED]:
    print("  FAIL 包根残留:", stray); ok = False
if not ok:
    print("\n包内布局校验失败 —— 拒绝交付"); sys.exit(1)
print("\n  包内布局校验通过")
PY
"$AAPT2" dump badging "$APK" 2>/dev/null | grep -E '^package|launchable-activity|targetSdk' || true
ls -la "$APK"
sha256sum "$APK" | tee "$APK.sha256"
