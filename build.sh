#!/usr/bin/env bash
# 无 Gradle 手工构建 adb-bridge APK（aarch64 Debian + qemu binfmt 跑 x86-64 aapt2）
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

echo "== 1/6 编译 Java =="
cd "$HERE/src"
find . -name '*.java' > "$OUT/sources.txt"
javac -nowarn -Xlint:-options -source 8 -target 8 \
      -bootclasspath "$JAVABASE" -classpath "$T/android.jar" \
      -d "$CLASSES" @"$OUT/sources.txt"
echo "   类文件: $(find "$CLASSES" -name '*.class' | wc -l)"

echo "== 2/6 dex (d8) =="
java -cp "$T/r8.jar" com.android.tools.r8.D8 --release --min-api 21 \
      --output "$DEX" $(find "$CLASSES" -name '*.class')
ls -la "$DEX"

echo "== 3/6 编译资源 =="
"$AAPT2" compile --dir "$HERE/res" -o "$RES/res.zip"

echo "== 4/6 链接资源 + manifest =="
"$AAPT2" link \
    -o "$OUT/app-unsigned.apk" \
    -I "$T/android.jar" \
    --manifest "$HERE/AndroidManifest.xml" \
    --min-sdk-version 21 \
    --target-sdk-version 34 \
    -R "$RES/res.zip" \
    --auto-add-overlay

echo "== 5/6 打包 dex =="
cd "$DEX"
zip -q -X "$OUT/app-unsigned.apk" classes.dex

echo "== 6/6 签名 =="
KS="$HERE/keys/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storetype PKCS12 -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
APK="$OUT/adb-bridge-debug.apk"
javac -nowarn -cp "$T/apksigner.jar" -d "$OUT" "$T/SignApk.java"
java -cp "$T/apksigner.jar:$OUT" SignApk "$KS" android androiddebugkey android \
    "$OUT/app-unsigned.apk" "$APK"
rm -f "$OUT/app-unsigned.apk"

echo
echo "== 产物 =="
ls -la "$APK"
unzip -l "$APK" | tail -8
echo "--- 签名块 ---"
python3 - "$APK" <<'PY'
import sys
d = open(sys.argv[1],'rb').read()
print("size:", len(d))
print("v2 signing block (APK Sig Block 42):", b"APK Sig Block 42" in d)
print("v1 META-INF/MANIFEST.MF:", b"META-INF/MANIFEST.MF" in d)
PY
