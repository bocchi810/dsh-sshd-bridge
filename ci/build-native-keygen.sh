#!/usr/bin/env bash
# 原生（CI runner 架构）编译 dropbearkey，用于**生成架构无关的主机密钥**。
#
# 依据（dropbear 源码）：
#   - src/signkey.c: buf_put_priv_key → buf_put_ed25519_priv_key
#       = buf_putstring("ssh-ed25519") + buf_putint(64) + priv(32) + pub(32)
#   - src/buffer.c: buf_putint 用 STORE32H（**大端**）
#   → 密钥文件是 SSH 线格式、无额外头部、与 CPU 字节序无关，
#     所以 x86_64 上生成的密钥可以直接给 aarch64 Android 的 dropbear 使用。
#
# 自足性：本脚本**自己下载源码**（不依赖 ci/build-dropbear.sh 先跑过），
# 否则「dropbear 产物缓存命中 → 跳过交叉编译 → dropbear-src 不存在」时会失败。
set -euo pipefail

# 先算出本脚本所在目录的绝对路径：脚本中途会 cd 进 dropbear-native/，
# 之后再引用相对路径（./derive_pubkey.py）就会解析错位。
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
HERE="$(cd "$SCRIPT_DIR/.." && pwd)"
SRC="$HERE/dropbear-src"
NATIVE="$HERE/dropbear-native"
OUT="$HERE/keys"
DROPBEAR_VER="${DROPBEAR_VER:-2026.94}"

# ---------- 1. 源码（缺则自己取） ----------
if [ ! -f "$SRC/src/svr-authpubkey.c" ]; then
  echo "== 下载 dropbear-$DROPBEAR_VER =="
  curl -fsSL -o /tmp/dropbear-keygen.tar.bz2 \
    "https://matt.ucc.asn.au/dropbear/dropbear-$DROPBEAR_VER.tar.bz2"
  mkdir -p "$SRC"
  tar -xjf /tmp/dropbear-keygen.tar.bz2 -C "$SRC" --strip-components=1
fi

# native 构建只需要能编出 dropbearkey，密码认证相关选项不影响它；
# 但仍写一份 localoptions.h，保证与交叉编译版本在同一套配置下构建。
cat > "$SRC/localoptions.h" <<'EOF'
/* 仅公钥认证（bionic 无 crypt()）；本文件也供原生 keygen 构建使用 */
#define DROPBEAR_SVR_PASSWORD_AUTH 0
#define DROPBEAR_SVR_PAM_AUTH 0
#define DROPBEAR_SVR_PUBKEY_AUTH 1
#define DROPBEAR_CLI_PASSWORD_AUTH 0
#define DROPBEAR_CLI_PUBKEY_AUTH 1
EOF

# ---------- 2. 复制一份做原生构建，避免污染交叉编译产物 ----------
if [ ! -f "$NATIVE/Makefile" ]; then
  echo "== 复制源码树用于原生构建 =="
  rm -rf "$NATIVE"
  mkdir -p "$NATIVE"
  tar -C "$SRC" --exclude=obj --exclude='*.o' --exclude='*.a' -cf - . \
    | tar -C "$NATIVE" -xf -
  cd "$NATIVE"
  ./configure --disable-zlib --disable-syslog --disable-lastlog --disable-utmp \
      --disable-utmpx --disable-wtmp --disable-wtmpx --disable-loginfunc \
      --disable-pututline --disable-pututxline --disable-shadow >/dev/null
fi
cd "$NATIVE"
echo "== 原生编译 dropbearkey =="
make -j"$(nproc)" dropbearkey 2>&1 | tail -2

# ---------- 3. 生成密钥（幂等：已存在则保留，支持由外部注入） ----------
mkdir -p "$OUT"
if [ -s "$OUT/hostkey_ed25519" ]; then
  echo "== 主机密钥已存在，保留（不重新生成） =="
else
  rm -f "$OUT/hostkey_ed25519"
  echo "== 生成 ed25519 主机密钥 =="
  ./dropbearkey -t ed25519 -f "$OUT/hostkey_ed25519" -C dsh-sshd-hostkey 2>&1 | tail -3
fi
chmod 600 "$OUT/hostkey_ed25519"

# ---------- 4. 结构与指纹校验（不通过就让构建失败） ----------
echo "== 校验密钥文件结构（不通过则构建失败）=="
python3 "$SCRIPT_DIR/derive_pubkey.py" --check "$OUT/hostkey_ed25519"
# .pub 只供人工核对（App 不使用）；已存在就不覆盖
if [ ! -s "$OUT/hostkey_ed25519.pub" ]; then
  python3 "$SCRIPT_DIR/derive_pubkey.py" "$OUT/hostkey_ed25519" "$OUT/hostkey_ed25519.pub"
else
  python3 "$SCRIPT_DIR/derive_pubkey.py" --check "$OUT/hostkey_ed25519"
fi

echo "== 产物 =="
ls -la "$OUT/hostkey_ed25519" "$OUT/hostkey_ed25519.pub"
cat "$OUT/hostkey_ed25519.pub"
