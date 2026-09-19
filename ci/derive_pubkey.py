#!/usr/bin/env python3
"""从 dropbear 私钥推导 OpenSSH 格式公钥（供人工核对用；App 端不使用 .pub）。

依据 dropbear 源码：
  src/signkey.c   buf_put_priv_key → buf_put_ed25519_priv_key
      = buf_putstring("ssh-ed25519") + buf_putint(64) + priv(32) + pub(32)
  src/buffer.c    buf_putint 用 STORE32H → 长度字段是**大端**
所以文件布局为：
  4 字节(大端) 类型串长度 | 类型串 | 4 字节(大端) 主体长度 | priv|pub
推 OpenSSH 公钥时把这些字段按同样的大端长度前缀重新拼成一整个 blob，再 base64。

用法：
    derive_pubkey.py <dropbear私钥> <输出.pub>     推导并写 .pub，同时做结构校验
    derive_pubkey.py --check <dropbear私钥>        只做结构校验（供构建脚本当断言用）
"""
import base64
import struct
import sys


def main() -> int:
    argv = sys.argv[1:]
    check_only = False
    if argv and argv[0] == "--check":
        check_only = True
        argv = argv[1:]
    if check_only:
        if len(argv) != 1:
            print(__doc__, file=sys.stderr)
            return 2
    elif len(argv) not in (1, 2):
        print(__doc__, file=sys.stderr)
        return 2
    src = argv[0]
    dst = None if (check_only or len(argv) == 1) else argv[1]

    data = open(src, "rb").read()
    if len(data) < 8:
        print(f"文件太短（{len(data)} 字节），不是 dropbear 私钥", file=sys.stderr)
        return 1

    type_len = struct.unpack(">I", data[:4])[0]
    key_type = data[4:4 + type_len].decode("ascii", "replace")
    body_off = 4 + type_len
    body_len = struct.unpack(">I", data[body_off:body_off + 4])[0]
    body = data[body_off + 4:body_off + 4 + body_len]

    print(f"类型={key_type} 主体长度={body_len} 文件长度={len(data)}")
    if key_type != "ssh-ed25519":
        print(f"只处理 ssh-ed25519，实际是 {key_type}", file=sys.stderr)
        return 1
    if body_len != 64:
        print(f"ed25519 主体应为 64 字节，实际 {body_len}", file=sys.stderr)
        return 1
    total = 4 + type_len + 4 + body_len
    if total != len(data):
        print(f"文件长度不符：期望 {total}，实际 {len(data)}", file=sys.stderr)
        return 1
    print(f"结构校验通过（4 + {type_len} + 4 + {body_len} = {len(data)}）")
    if check_only or dst is None:
        return 0

    # dropbear 文件的主体是 priv(32) + pub(32)；OpenSSH 公钥的 blob 只含**后 32 字节的公钥**，
    # 不是整个 64 字节主体（早先版本把 64 字节整段塞进去，得到的是 142 字节的畸形公钥）。
    assert body_len == 64, body_len
    pubkey = body[32:]
    blob = (struct.pack(">I", type_len) + key_type.encode()
            + struct.pack(">I", len(pubkey)) + pubkey)
    with open(dst, "w") as fh:
        fh.write(f"{key_type} {base64.b64encode(blob).decode()} dsh-sshd-hostkey\n")
    print(f"已写入 {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
