#!/usr/bin/env bash
# 交叉编译 dropbear 为 aarch64-android 静态二进制（给手机上的 SSH 服务端用）
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
NDK="${NDK:-/root/harness/mnn-nnapi-build/ndk/android-ndk-r27c}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=24
SRC="$HERE/dropbear-src"
VER="${DROPBEAR_VER:-dropbear-2026.94}"
TARBALL="$HERE/$VER.tar.bz2"
OUT="$HERE/out-native"

mkdir -p "$OUT"

if [ ! -f "$SRC/configure" ]; then
  if [ ! -f "$TARBALL" ]; then
    echo "== 下载 $VER =="
    curl -sSL --retry 3 -o "$TARBALL" \
      "https://matt.ucc.asn.au/dropbear/$VER.tar.bz2" \
      || curl -sSL --retry 3 -x http://127.0.0.1:7890 -o "$TARBALL" \
           "https://matt.ucc.asn.au/dropbear/$VER.tar.bz2"
  fi
  echo "== 解包 =="
  mkdir -p "$SRC"
  tar -xjf "$TARBALL" -C "$SRC" --strip-components=1
fi

cd "$SRC"
[ -f Makefile ] || {
  echo "== configure =="
  CC="$TC/aarch64-linux-android${API}-clang" \
  AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" STRIP="$TC/llvm-strip" \
  ./configure \
    --host=aarch64-linux-android \
    --prefix=/data/local/tmp/dropbear \
    --disable-zlib --disable-syslog --disable-lastlog --disable-utmp \
    --disable-utmpx --disable-wtmp --disable-wtmpx --disable-loginfunc \
    --disable-pututline --disable-pututxline --disable-shadow \
    CFLAGS="-O2 -static -fPIE -pie -DANDROID -Wno-implicit-function-declaration" \
    LDFLAGS="-static -pie"
}
echo "== 编译 =="
make -j"$(nproc)" PROGRAMS="dropbear dropbearkey dbclient" 2>&1 | tail -15

echo "== 产物（strip 调试段后）=="
STRIP="$TC/llvm-strip"
for b in dropbear dropbearkey dbclient; do
  if [ -f "$SRC/$b" ]; then
    cp "$SRC/$b" "$OUT/$b"
    [ -x "$STRIP" ] && "$STRIP" --strip-debug "$OUT/$b"
    printf '  %-12s %9s 字节  ' "$b" "$(stat -c%s "$OUT/$b")"
    file "$OUT/$b" | sed 's/.*ELF/ELF/; s/,.*built by//'
  fi
done
ls -la "$OUT"
