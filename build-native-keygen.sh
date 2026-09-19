#!/usr/bin/env bash
# 在容器里原生编译 dropbearkey，用于**预生成架构无关的主机密钥**。
#
# 依据（dropbear 源码）：
#   - src/signkey.c: buf_put_priv_key → buf_put_ed25519_priv_key
#       = buf_putstring("ssh-ed25519") + buf_putint(64) + priv(32) + pub(32)
#   - src/buffer.c: buf_putint 用 STORE32H（大端）
#   → 密钥文件是 SSH 线格式、无额外头部、与 CPU 字节序无关，可在 x86_64 生成后给 aarch64 Android 用。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/dropbear-src"
NATIVE="$HERE/dropbear-native"
OUT="$HERE/keys"

if [ ! -f "$SRC/localoptions.h" ]; then
  echo "缺少 $SRC/localoptions.h（先跑过 build-dropbear.sh）"; exit 1
fi

# 独立复制一份给原生构建，避免污染交叉编译产物
if [ ! -f "$NATIVE/Makefile" ]; then
  echo "== 复制源码树用于原生构建 =="
  rm -rf "$NATIVE"
  mkdir -p "$NATIVE"
  tar -C "$SRC" --exclude=obj --exclude='*.o' --exclude='*.a' \
      -cf - . | tar -C "$NATIVE" -xf -
  cd "$NATIVE"
  ./configure --disable-zlib --disable-syslog --disable-lastlog --disable-utmp \
      --disable-utmpx --disable-wtmp --disable-wtmpx --disable-loginfunc \
      --disable-pututline --disable-pututxline --disable-shadow >/dev/null
fi
cd "$NATIVE"
echo "== 原生编译 dropbearkey =="
make -j"$(nproc)" dropbearkey 2>&1 | tail -3

mkdir -p "$OUT"
rm -f "$OUT/hostkey_ed25519" "$OUT/hostkey_ed25519.pub"
echo "== 生成 ed25519 主机密钥 =="
./dropbearkey -t ed25519 -f "$OUT/hostkey_ed25519" -C dsh-sshd-hostkey 2>&1 | tail -4
chmod 600 "$OUT/hostkey_ed25519"

echo "== 产物 =="
ls -la "$OUT/hostkey_ed25519" "$OUT/hostkey_ed25519.pub"
echo "--- 私钥前 16 字节（应为 00 00 00 0b 'ssh-ed25519'）---"
xxd -l 16 "$OUT/hostkey_ed25519"
echo "--- 公钥指纹 ---"
./dropbearkey -y -f "$OUT/hostkey_ed25519" 2>&1 | tail -3
