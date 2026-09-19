#!/usr/bin/env bash
# 给 dropbear 打一个**有明确理由的小补丁**：跳过 authorized_keys 的父目录权限检查。
#
# 为什么必须打：
#   dropbear 的 checkpubkeyperms()（src/svr-authpubkey.c）会从 authorized_keys 逐级向上
#   检查每个路径组件必须「属主为 root 或该用户」且「无组/其他写位」。
#   真机实测（App 导出的权限探针）：/data 是 uid=1000 mode=777（世界可写）。
#   Android 上可写的路径都在 /data 之下 → **任何 authorized_keys 位置都必然失败**，
#   表现为「SSH 握手成功但认证被拒（Permission denied (publickey)）」且只有一条 INFO 日志。
#
# 这个检查在已 root 的手机上没有意义：我们本来就是 root，能改 authorized_keys 的人
# 本来就能改一切；而 /data 的世界可写位由 SELinux 兜底（Android 的设计）。
# 补丁只影响「是否因为父目录 mode 而拒绝认证」，不放松任何密码学校验。
#
# 幂等：已打过就跳过。
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/dropbear-src/src/svr-authpubkey.c"
MARK="DSH_ANDROID_SKIP_PERMS_CHECK"

if grep -q "$MARK" "$SRC" 2>/dev/null; then
  echo "补丁已存在，跳过"
  exit 0
fi

python3 - "$SRC" "$MARK" <<'PY'
import sys
path, mark = sys.argv[1], sys.argv[2]
s = open(path).read()
old = "static int checkpubkeyperms() {\n\tchar *path = authorized_keys_filepath(), *sep = NULL;\n\tint ret = DROPBEAR_SUCCESS;\n"
assert old in s, "未找到 checkpubkeyperms 函数体开头，源码可能已变"
new = ("static int checkpubkeyperms() {\n"
       "\tchar *path = authorized_keys_filepath(), *sep = NULL;\n"
       "\tint ret = DROPBEAR_SUCCESS;\n"
       "\t/* " + mark + ": Android 上 /data 是 777，可写路径全在 /data 之下，\n"
       "\t * 逐级检查必然失败。见本仓库 patch-skip-perms-check.sh 的说明。 */\n"
       "\treturn DROPBEAR_SUCCESS;\n")
s = s.replace(old, new, 1)
open(path, "w").write(s)
print("已注入跳过检查")
PY

echo "== 校验补丁位置 =="
grep -n -A6 "static int checkpubkeyperms" "$SRC" | head -10
