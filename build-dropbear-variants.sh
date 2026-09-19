#!/usr/bin/env bash
# 一次性构建 dropbear 的两种变体（静态 static-PIE + 动态链接），打到 assets/ 供 APK 内嵌。
#
# 为什么两种都要：
#   - 真机上静态版（static-PIE）执行即 SIGSEGV（设备内核不支持 static-PIE 自举），
#     动态版可用；但不同设备内核可能相反，所以两种都发，由 App 启动时实测选一种。
#
# 前置：先跑过 patch-skip-perms-check.sh（跳过 authorized_keys 父目录权限检查），
#       否则在 Android 上认证必然失败（/data 是 777）。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
NDK="${NDK:-/root/harness/mnn-nnapi-build/ndk/android-ndk-r27c}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
API="${API:-35}"
SRC="$HERE/dropbear-src"
OUT="$HERE/out-native"
ASSETS="$HERE/assets"

mkdir -p "$OUT" "$ASSETS"

[ -f "$SRC/localoptions.h" ] || { echo "缺少 localoptions.h"; exit 1; }
grep -q "DSH_ANDROID_SKIP_PERMS_CHECK" "$SRC/src/svr-authpubkey.c" \
  || { echo "请先运行 patch-skip-perms-check.sh"; exit 1; }

build_variant() {  # build_variant <dyn|static>
  local mode="$1" cflags ldflags
  if [ "$mode" = dyn ]; then
    cflags="-O2 -fPIE -pie -DANDROID -Wno-implicit-function-declaration"; ldflags="-pie"
  else
    cflags="-O2 -static -fPIE -pie -DANDROID -Wno-implicit-function-declaration"; ldflags="-static -pie"
  fi
  cd "$SRC"
  make clean >/dev/null 2>&1
  CC="$TC/aarch64-linux-android${API}-clang" AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" \
  ./configure --host=aarch64-linux-android \
      --disable-zlib --disable-syslog --disable-lastlog --disable-utmp --disable-utmpx \
      --disable-wtmp --disable-wtmpx --disable-loginfunc --disable-pututline \
      --disable-pututxline --disable-shadow \
      CFLAGS="$cflags" LDFLAGS="$ldflags" >/dev/null 2>&1
  make -j"$(nproc)" PROGRAMS="dropbear dropbearkey" >/dev/null 2>&1

  local suffix=""
  [ "$mode" = dyn ] && suffix="_dyn"
  for b in dropbear dropbearkey; do
    cp "$SRC/$b" "$OUT/${b}${suffix}"
    "$TC/llvm-strip" --strip-debug "$OUT/${b}${suffix}"
    cp "$OUT/${b}${suffix}" "$ASSETS/dsh-${b}${suffix}"
  done
  printf '  %-8s -> %s / %s\n' "$mode" \
      "$(stat -c%s "$ASSETS/dsh-dropbear${suffix}")" "$(stat -c%s "$ASSETS/dsh-dropbearkey${suffix}")"
}

echo "== 构建两种变体（API $API）=="
build_variant dyn
build_variant static

echo "== 校验补丁标记存在于两种变体 =="
for f in "$ASSETS/dsh-dropbear_dyn" "$ASSETS/dsh-dropbear"; do
  if strings -a "$f" | grep -q 'dsh-nopermcheck'; then
    echo "  OK   $(basename "$f") 含版本标记"
  else
    echo "  FAIL $(basename "$f") 缺版本标记 —— 补丁可能没生效"; exit 1
  fi
done

echo "== assets =="
ls -la "$ASSETS"
