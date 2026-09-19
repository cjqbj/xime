#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
纯 Python 复现 apksigner --print-certs 的 MD5/SHA-1/SHA-256 指纹。
只依赖标准库：struct / hashlib / sys。
"""
import struct, hashlib, sys

APK_SIG_BLOCK_MAGIC       = b'APK Sig Block 42'
V2_BLOCK_ID               = 0x7109871a
V3_BLOCK_ID               = 0xf05368c0
V3_1_BLOCK_ID             = 0x1b93ad61  # Android 13+

def _u32(d, o): return struct.unpack_from('<I', d, o)[0]
def _u64(d, o): return struct.unpack_from('<Q', d, o)[0]

# ---------- 1. 定位 APK Signing Block ----------
def find_sig_block(data: bytes):
    eocd_sig = b'PK\x05\x06'
    i = data.rfind(eocd_sig)
    if i < 0:
        raise ValueError("EOCD not found")
    cd_off = _u32(data, i + 16)                 # EOCD 中的 CD 偏移
    if data[cd_off - 16: cd_off] != APK_SIG_BLOCK_MAGIC:
        raise ValueError("APK Signing Block magic not found")
    block_size  = _u64(data, cd_off - 24)       # 尾部 size 字段
    block_start = cd_off - block_size - 8
    if _u64(data, block_start) != block_size:
        raise ValueError("block size fields mismatch")
    return block_start + 8, cd_off - 24         # 内容区间 [start, end)

# ---------- 2. 遍历 ID-value 对 ----------
def iter_pairs(data, start, end):
    off = start
    while off < end:
        pair_size = _u64(data, off); off += 8
        pair_end  = off + pair_size
        pair_id   = _u32(data, off); off += 4
        yield pair_id, off, pair_end
        off = pair_end

# ---------- 3. 解析 v2 / v3 ----------
def parse_signed_data_certs(data, off, end):
    # signed_data = digests | certificates | additional_attrs （全长度前缀）
    digests_size = _u32(data, off); off += 4 + digests_size
    certs_size   = _u32(data, off); off += 4
    certs_end    = off + certs_size
    certs, p = [], off
    while p < certs_end:
        csize = _u32(data, p); p += 4
        certs.append(data[p:p + csize])         # <-- DER 原字节
        p += csize
    return certs

def parse_signer_certs(data, off, end):
    # signer = signed_data | signatures | public_key
    sd_size = _u32(data, off); off += 4
    sd_start, sd_end = off, off + sd_size
    off = sd_end
    sigs_size = _u32(data, off); off += 4 + sigs_size
    pk_size   = _u32(data, off); off += 4 + pk_size
    return parse_signed_data_certs(data, sd_start, sd_end)

def parse_signers_certs(data, off, end):
    certs = []
    while off < end:
        size = _u32(data, off); off += 4
        s_end = off + size
        certs.extend(parse_signer_certs(data, off, s_end))
        off = s_end
    return certs

# ---------- 4. 总入口 ----------
def extract_all_certs(apk_path):
    with open(apk_path, 'rb') as f:
        data = f.read()

    certs = []
    try:
        block_start, block_end = find_sig_block(data)
        for pair_id, vstart, vend in iter_pairs(data, block_start, block_end):
            if pair_id in (V2_BLOCK_ID, V3_BLOCK_ID, V3_1_BLOCK_ID):
                signers_size = _u32(data, vstart)
                signers_start = vstart + 4
                signers_end   = signers_start + signers_size
                certs.extend(parse_signers_certs(data, signers_start, signers_end))
    except ValueError as e:
        print(f"[!] v2/v3 解析失败: {e}", file=sys.stderr)

    return certs

def fingerprints(cert_der: bytes):
    return (
        hashlib.md5(cert_der).hexdigest(),
        hashlib.sha1(cert_der).hexdigest(),
        hashlib.sha256(cert_der).hexdigest(),
    )

if __name__ == '__main__':
    apk = sys.argv[1] if len(sys.argv) > 1 else \
        '/workspaces/build_xime_home/Xime_rpc/out/debug-secexp/Xime-20260914-arm64-v8a.apk'

    certs = extract_all_certs(apk)
    print(f"共提取 {len(certs)} 张证书（含签名者+链）\n")

    seen = set()
    for i, cert_der in enumerate(certs):
        md5, sha1, sha256 = fingerprints(cert_der)
        if sha256 in seen:                # 去重
            continue
        seen.add(sha256)
        print(f"[#{i}] \n {cert_der} \ncert length = {len(cert_der)} bytes")
        print(f"     MD5     : {md5}")
        print(f"     SHA-1   : {sha1}")
        print(f"     SHA-256 : {sha256}")
        print()

'''
 length = 346 bytes
     MD5     : f49befc123a1c32125e0887c1fa8fe6d
     SHA-1   : 774fe4151dce8f6a60aadbbc6cfe1a8a2ece6927
     SHA-256 : 45dae96c7df223eb1c554dc95d23d40f1e5c5802110e8731a29148d8661da329


30 82 01 56                          SEQUENCE, len=0x0156=342   ← Certificate
  30 81 fe                           SEQUENCE, len=0xfe=254      ← tbsCertificate
    a0 03                            [0] EXPLICIT, len=3         ← version（v3 才带）
      02 01 02                       INTEGER = 2                  → v3
    02 08 56 74 2b 81 2f 47 0f c6    INTEGER (8 字节序列号)
    30 0a                            SEQUENCE, len=10            ← signature AlgorithmIdentifier
      06 08 2a 86 48 ce 3d 04 03 02  OID 1.2.840.10045.4.3.2      → ecdsa-with-SHA256
    30 31                            SEQUENCE, len=49            ← Issuer（颁发者）
      31 0b                          SET, len=11                 ← RDN
        30 09                        SEQUENCE, len=9
          06 03 55 04 06             OID 2.5.4.6                 → countryName
          13 02 43 4e                PrintableString "CN"
      31 0d                          SET, len=13
        30 0b                        SEQUENCE, len=11
          06 03 55 04 0a             OID 2.5.4.10                → organizationName
          0c 04 53 45 4c 46          UTF8String "SELF"
      31 13                          SET, len=19
        30 11                        SEQUENCE, len=17
          06 03 55 04 03             OID 2.5.4.3                 → commonName
          0c 0a 41 50 4b 5f 53 49 47 4e 45 52
                                      UTF8String "APK_SIGNER"
    30 20                            SEQUENCE, len=32            ← Validity
      17 0d 32 34 30 31 30 31 30 30 30 30 30 30 5a
                                      UTCTime "240101000000Z"      → notBefore
      18 0f 32 30 39 39 31 32 33 31 32 33 35 39 35 39 5a
                                      GeneralizedTime "20991231235959Z" → notAfter
    30 31                            SEQUENCE, len=49            ← Subject（与 Issuer 完全相同 → 自签名）
      31 0b ... (同上三个 RDN：C=CN / O=SELF / CN=APK_SIGNER)
    30 59                            SEQUENCE, len=89            ← SubjectPublicKeyInfo
      30 13                          SEQUENCE, len=19            ← AlgorithmIdentifier
        06 07 2a 86 48 ce 3d 02 01   OID 1.2.840.10045.2.1       → id-ecPublicKey
        06 08 2a 86 48 ce 3d 03 01 07
                                      OID 1.2.840.10045.3.1.7     → prime256v1 (P-256)
      03 42                          BIT STRING, len=0x42=66
        00                           未使用位 = 0
        04                           EC 点未压缩前缀 0x04
        6b 17 d1 f2 e1 2c 42 47 f8 bc e6 e5 63 a4 40 f2
        77 03 7d 81 2d eb 33 a0 f4 a1 39 45 d8 98 c2 96
                                      → X 坐标 (32 字节)
        4f e3 42 e2 fe 1a 7f 9b 8e e7 eb 4a 7c 0f 9e 16
        2b ce 33 57 6b 31 5e ce cb b6 40 68 37 bf 51 f5
                                      → Y 坐标 (32 字节)
    （tbsCertificate 内无 extensions，因为 signature 算法是 ecdsa-with-SHA256 且无 v3 扩展）
  30 0a                              SEQUENCE, len=10            ← 外层 signatureAlgorithm（与 tbs 里一致）
    06 08 2a 86 48 ce 3d 04 03 02    OID 1.2.840.10045.4.3.2
  03 47                              BIT STRING, len=0x47=71     ← signatureValue
    00                               未使用位 = 0
    30 44                            SEQUENCE, len=68            ← ECDSA-Sig-Value
      02 20 12 90 2f fd 9c f1 2a ff 8a 5e c2 cf 8b ec d4
      47 db 7c 96 6c 81 f3 02 49 df 5c e7 f2 ba ab d3 c1
                                      INTEGER r (32 字节)
      02 20 01 8e 5f e7 96 d8 51 f4 61 43 d7 c2 84 4a dd
      b3 3d 2a 60 d3 7c 97 34 05 21 0c 01 d5 57 c6 d9 88
                                      INTEGER s (32 字节)
'''
