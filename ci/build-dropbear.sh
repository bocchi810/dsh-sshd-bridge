#!/usr/bin/env bash
# 在 CI（Ubuntu x86_64）上交叉编译 dropbear 的两种变体，输出到 out-native/。
#
# 为什么两种都要：
#   - 静态版是 static-PIE（ELF e_type=DYN、无 PT_INTERP）。真机实测在 Android 15 +
#     6.6.118 内核上**执行即 SIGSEGV**（连一行输出都没有），因为设备内核未启用
#     CONFIG_BINFMT_ELF_STATIC_PIE 自举。
#   - 动态版用设备自带的 bionic libc（有 PT_INTERP），实测可正常执行。
#   两者都发，由 App 启动时用 `dropbearkey -h` 实测选一种（该探针覆盖
#   crypto_init + seedrandom 这段会崩的路径，见 dropbearkey.c 的调用顺序）。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$REPO_ROOT/dropbear-src"
OUT="$REPO_ROOT/out-native"
ASSETS="$REPO_ROOT/assets"
NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
API="${API:-35}"

if [ -z "$NDK_DIR" ] || [ ! -d "$NDK_DIR" ]; then
  echo "未找到 NDK（设置 ANDROID_NDK_HOME）" >&2
  exit 1
fi
TC="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -x "$TC/aarch64-linux-android${API}-clang" ] || { echo "缺少 $TC/aarch64-linux-android${API}-clang" >&2; exit 1; }

DROPBEAR_VER="${DROPBEAR_VER:-2026.94}"

# ---------- 1. 取源码 ----------
if [ ! -f "$SRC/src/svr-authpubkey.c" ]; then
  echo "== 下载 dropbear-$DROPBEAR_VER =="
  curl -fsSL -o /tmp/dropbear.tar.bz2 \
    "https://matt.ucc.asn.au/dropbear/dropbear-$DROPBEAR_VER.tar.bz2"
  mkdir -p "$SRC"
  tar -xjf /tmp/dropbear.tar.bz2 -C "$SRC" --strip-components=1
fi
cd "$SRC"

# ---------- 2. 应用两个必要补丁 ----------
# 2a. 只用公钥认证：Android bionic 没有 crypt()，且我们本来就不需要密码认证。
cat > localoptions.h <<'EOF'
/* Android / dsh-sshd-bridge：仅公钥认证（bionic 无 crypt()） */
#define DROPBEAR_SVR_PASSWORD_AUTH 0
#define DROPBEAR_SVR_PAM_AUTH 0
#define DROPBEAR_SVR_PUBKEY_AUTH 1
#define DROPBEAR_CLI_PASSWORD_AUTH 0
#define DROPBEAR_CLI_PUBKEY_AUTH 1
#define DROPBEAR_SVR_REMOTEPORT_FORWARD 0
#define DROPBEAR_SVR_LOCALTCPFWD 1
#define DROPBEAR_SVR_REMOTETCPFWD 0
EOF

# 2b. 跳过 authorized_keys 的父目录权限检查。
#     checkpubkeyperms() 要求每个路径组件「属主 root/该用户」且「无组/其他写位」，
#     而真机实测 Android 的 /data 是 uid=1000 mode=777 —— 可写路径全在 /data 之下，
#     于是任何位置都过不了，表现为「握手成功但认证被拒」且只有一条 INFO 日志。
#     已 root 的手机上这个检查没有意义（能改该文件的人本来就能改一切），
#     世界可写位由 SELinux 兜底。**不放松任何密码学校验。**
MARK="DSH_ANDROID_SKIP_PERMS_CHECK"
if ! grep -q "$MARK" src/svr-authpubkey.c; then
  python3 - <<'PY'
mark = "DSH_ANDROID_SKIP_PERMS_CHECK"
p = "src/svr-authpubkey.c"
s = open(p).read()
old = ("static int checkpubkeyperms() {\n"
       "\tchar *path = authorized_keys_filepath(), *sep = NULL;\n"
       "\tint ret = DROPBEAR_SUCCESS;\n")
assert old in s, "未找到 checkpubkeyperms 函数体开头，dropbear 源码结构可能已变"
new = old + ("\t/* " + mark + ": Android 的 /data 是 777，可写路径全在 /data 之下，\n"
             "\t * 逐级权限检查必然失败。理由与影响见 ci/build-dropbear.sh。 */\n"
             "\treturn DROPBEAR_SUCCESS;\n")
open(p, "w").write(s.replace(old, new, 1))
print("已注入跳过权限检查")
PY
fi
grep -q "$MARK" src/svr-authpubkey.c || { echo "补丁未生效" >&2; exit 1; }

# 2c. 版本串加标记：SSH banner 会带上它，便于**远程确认跑的是打过补丁的二进制**。
if ! grep -q 'dsh-nopermcheck' src/sysoptions.h; then
  sed -i 's/#define DROPBEAR_VERSION "\([^"]*\)"/#define DROPBEAR_VERSION "\1-dsh-nopermcheck"/' src/sysoptions.h
fi
grep -n 'define DROPBEAR_VERSION' src/sysoptions.h

# ---------- 3. 编译两种变体 ----------
build_variant() {  # $1 = dyn|static
  local mode="$1" cflags ldflags suffix=""
  if [ "$mode" = dyn ]; then
    cflags="-O2 -fPIE -pie -DANDROID -Wno-implicit-function-declaration"; ldflags="-pie"
  else
    cflags="-O2 -static -fPIE -pie -DANDROID -Wno-implicit-function-declaration"; ldflags="-static -pie"
    suffix=""
  fi
  [ "$mode" = dyn ] && suffix="_dyn"

  make clean >/dev/null 2>&1 || true
  CC="$TC/aarch64-linux-android${API}-clang" AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" \
  ./configure --host=aarch64-linux-android \
      --disable-zlib --disable-syslog --disable-lastlog --disable-utmp --disable-utmpx \
      --disable-wtmp --disable-wtmpx --disable-loginfunc --disable-pututline \
      --disable-pututxline --disable-shadow \
      CFLAGS="$cflags" LDFLAGS="$ldflags" >/dev/null
  make -j"$(nproc)" PROGRAMS="dropbear dropbearkey" >/dev/null

  mkdir -p "$OUT" "$ASSETS"
  for b in dropbear dropbearkey; do
    cp "$SRC/$b" "$OUT/${b}${suffix}"
    "$TC/llvm-strip" --strip-debug "$OUT/${b}${suffix}"
    cp "$OUT/${b}${suffix}" "$ASSETS/dsh-${b}${suffix}"
  done
  printf '  %-7s dropbear=%-9s dropbearkey=%-9s\n' "$mode" \
    "$(stat -c%s "$ASSETS/dsh-dropbear${suffix}")" "$(stat -c%s "$ASSETS/dsh-dropbearkey${suffix}")"
}

echo "== 编译（NDK API $API）=="
build_variant dyn
build_variant static

# ---------- 4. 校验：补丁标记必须在两种变体里 ----------
echo "== 校验 =="
for f in "$ASSETS/dsh-dropbear_dyn" "$ASSETS/dsh-dropbear"; do
  if strings -a "$f" | grep -q 'dsh-nopermcheck'; then
    echo "  OK   $(basename "$f") 含版本标记"
  else
    echo "  FAIL $(basename "$f") 缺版本标记，补丁可能未生效" >&2
    exit 1
  fi
  printf '       ELF: '; file -b "$f" | cut -c1-90
done
echo "== 产物 =="
ls -la "$OUT"
