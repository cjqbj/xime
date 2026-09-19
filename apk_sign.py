#!/usr/bin/env python3

import importlib.util, os, subprocess, sys


def ensure_dependencies():
    packages = {
        "ecdsa": "ecdsa",
        "cryptography": "cryptography",
        "asn1crypto": "asn1crypto",  # [新增] 用于手动构造确定性 X.509 证书
    }
    missing = [package for module, package in packages.items()
               if importlib.util.find_spec(module) is None]
    if not missing:
        return

    index_url = "https://pypi.tuna.tsinghua.edu.cn/simple"
    print(f"[+] 正在使用清华源安装依赖: {', '.join(missing)}")
    command = [
        sys.executable,
        "-m",
        "pip",
        "install",
        "-i",
        index_url,
        "--trusted-host",
        "pypi.tuna.tsinghua.edu.cn",
        *missing,
    ]
    try:
        subprocess.check_call(command)
    except (OSError, subprocess.CalledProcessError) as error:
        print(f"[!] 依赖安装失败: {error}", file=sys.stderr)
        sys.exit(1)


ensure_dependencies()

"""Sign APK files with a NIST256p PEM key or a secret exponent.

修改说明：
  1. 移除 cryptography.x509.CertificateBuilder 生成证书的逻辑，
     因为它底层走 OpenSSL，ECDSA 使用随机 k（随机 nonce），每次输出都不同。
  2. 改为用 asn1crypto 手动拼装 TbsCertificate，然后用 RFC6979 确定性
     ECDSA（ecdsa 库的 sign_digest_deterministic）对其签名。
  3. 结果：相同私钥（PEM 或 secexp）=> 相同证书 DER 字节 => 相同签名指纹，
     在任何机器、任何时间都可复现。
"""

import argparse
import datetime
import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

import ecdsa

from asn1crypto import x509 as asn1x509
from asn1crypto import keys as asn1keys
from asn1crypto import algos as asn1algos

KEY_ALIAS = "apk_signer"
KEY_PASSWORD = "123456"
DEFAULT_PEM_PATH = Path.home() / ".ssh" / "NIST256p.pem"


def private_key_from_pem(pem_path: Path) -> ec.EllipticCurvePrivateKey:
    if not pem_path.is_file():
        raise FileNotFoundError(f"找不到 APK 签名私钥: {pem_path}")
    try:
        private_key = serialization.load_ssh_private_key(
            pem_path.read_bytes(), password=None
        )
    except ValueError:
        private_key = serialization.load_pem_private_key(
            pem_path.read_bytes(), password=None
        )
    if not isinstance(private_key, ec.EllipticCurvePrivateKey):
        raise ValueError("APK 签名私钥必须是 ECDSA 私钥")
    if not isinstance(private_key.curve, ec.SECP256R1):
        raise ValueError("APK 签名仅支持 NIST256p (secp256r1)")
    return private_key


def private_key_from_secexp(secexp: int) -> ec.EllipticCurvePrivateKey:
    curve = ec.SECP256R1()
    if not 1 <= secexp < 2**curve.key_size:
        raise ValueError(f"secexp 必须是 1 到 {2**curve.key_size - 1} 之间的整数")
    return ec.derive_private_key(secexp, curve)


def deterministic_ecdsa_sign_digest(
    private_key: ec.EllipticCurvePrivateKey, digest: bytes
) -> bytes:
    """使用 RFC6979 确定性 ECDSA 签名，确保相同 key+digest 必定得到相同签名。"""
    if ecdsa is None:
        raise RuntimeError(
            "缺少 ecdsa 依赖，无法进行确定性 ECDSA 签名。请执行: python3 -m pip install ecdsa"
        )
    if not isinstance(private_key.curve, ec.SECP256R1):
        raise ValueError("仅支持 secp256r1 / NIST256p 的确定性签名")

    private_value = private_key.private_numbers().private_value
    signing_key = ecdsa.SigningKey.from_secret_exponent(
        private_value, curve=ecdsa.NIST256p
    )
    return signing_key.sign_digest_deterministic(
        digest,
        hashfunc=hashlib.sha256,
        sigencode=ecdsa.util.sigencode_der,
    )


def deterministic_ecdsa_sign(
    private_key: ec.EllipticCurvePrivateKey, message: bytes
) -> bytes:
    return deterministic_ecdsa_sign_digest(
        private_key, hashlib.sha256(message).digest()
    )


def build_certificate_deterministic(
    private_key: ec.EllipticCurvePrivateKey,
) -> bytes:
    """
    手工构造一个完全确定性的 X.509 v3 自签名证书（DER 字节）。

    为什么不用 cryptography.x509.CertificateBuilder？
      —— 它的 .sign() 走 OpenSSL，ECDSA 用随机 k，因此每次签出来的证书字节不同。
        我们要的是"任何机器、任何时间、相同私钥 => 相同证书"。

    这里改为：
      1. 用 cryptography 生成 SPKI（公钥部分，本来就是确定性的）。
      2. 用 asn1crypto 手工拼装 TbsCertificate。
      3. 用 RFC6979 确定性 ECDSA 对 TBS 签名。
      4. 组装成完整的 Certificate。
    """
    # 1) 公钥 SPKI（DER），由 cryptography 生成标准编码
    spki_der = private_key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    spki = asn1keys.PublicKeyInfo.load(spki_der)

    # 2) 序列号：由公钥哈希派生（去掉高位符号位，避免负数）
    serial_number = int.from_bytes(
        hashlib.sha256(spki_der).digest(), "big"
    ) % (2**63 - 1)
    if serial_number == 0:
        serial_number = 1

    # 3) Subject / Issuer 固定
    subject = asn1x509.Name.build({
        "country_name": "CN",
        "organization_name": "SELF",
        "common_name": "APK_SIGNER",
    })

    # 4) 固定有效期（2024-01-01 ~ 2099-12-31，都用 UTC）
    not_before = asn1x509.Time({
        "utc_time": datetime.datetime(
            2024, 1, 1, 0, 0, 0, tzinfo=datetime.timezone.utc
        ),
    })
    not_after = asn1x509.Time({
        "general_time": datetime.datetime(
            2099, 12, 31, 23, 59, 59, tzinfo=datetime.timezone.utc
        ),
    })

    signature_algorithm = asn1algos.SignedDigestAlgorithm({
        "algorithm": "sha256_ecdsa"
    })

    tbs = asn1x509.TbsCertificate({
        "version": "v3",
        "serial_number": serial_number,
        "signature": signature_algorithm,
        "issuer": subject,
        "validity": {
            "not_before": not_before,
            "not_after": not_after,
        },
        "subject": subject,
        "subject_public_key_info": spki,
    })

    tbs_der = tbs.dump()

    # 5) 关键：用 RFC6979 确定性 ECDSA 对 TBS 字节签名
    signature = deterministic_ecdsa_sign(private_key, tbs_der)

    cert = asn1x509.Certificate({
        "tbs_certificate": tbs,
        "signature_algorithm": signature_algorithm,
        "signature_value": signature,
    })

    return cert.dump()


def make_keystore(
    private_key: ec.EllipticCurvePrivateKey, output_dir: Path
) -> dict:
    cert_der = build_certificate_deterministic(private_key)

    output_dir.mkdir(parents=True, exist_ok=True)

    key_path = output_dir / "apk_sign_key_NIST256p.pk8"
    cert_path = output_dir / "apk_sign_cert_NIST256p.der"

    key_path.write_bytes(
        private_key.private_bytes(
            encoding=serialization.Encoding.DER,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )
    )
    cert_path.write_bytes(cert_der)

    return {
        "key_path": str(key_path),
        "cert_path": str(cert_path),
    }


def find_apksigner(sdk_path: str | None = None) -> Path:
    sdk = sdk_path or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        raise FileNotFoundError(
            "未找到 Android SDK 路径，请先通过 build.sh 设置 ANDROID_HOME。"
        )
    build_tools_dir = Path(sdk) / "build-tools"
    candidates = []
    if build_tools_dir.is_dir():
        candidates = [
            path / "apksigner"
            for path in build_tools_dir.iterdir()
            if path.is_dir() and (path / "apksigner").is_file()
        ]
    candidates.sort(
        key=lambda path: tuple(int(part) for part in re.findall(r"\d+", path.parent.name)),
        reverse=True,
    )
    if not candidates:
        raise FileNotFoundError(f"SDK 中未找到 apksigner: {build_tools_dir}")
    return candidates[0]


def signed_path(apk_path: Path) -> Path:
    if apk_path.name.endswith("-unsigned.apk"):
        return apk_path.with_name(
            apk_path.name[:-len("-unsigned.apk")] + "-signed.apk"
        )
    return apk_path.with_name(f"{apk_path.stem}-signed{apk_path.suffix}")


def sign_apk(apk_path: Path, keystore: dict, sdk_path: str | None = None) -> Path:
    if not apk_path.is_file():
        raise FileNotFoundError(f"找不到需要签名的 APK: {apk_path}")
    output_path = signed_path(apk_path)
    command = [
        str(find_apksigner(sdk_path)), "sign",
        "--key", keystore["key_path"],
        "--cert", keystore["cert_path"],
        "--out", str(output_path), str(apk_path),
    ]
    print(f"使用 apksigner: {command[0]}")
    subprocess.run(command, check=True)
    print(f"签名成功: {output_path.resolve()}")
    return output_path


def print_cert_fingerprints(cert_path: Path) -> None:
    """打印证书 DER 的 SHA-256 / SHA-1，用于核对每次构建是否稳定。"""
    der = cert_path.read_bytes()
    print(f"证书 SHA-256: {hashlib.sha256(der).hexdigest()}")
    print(f"证书 SHA-1:   {hashlib.sha1(der).hexdigest()}")


def parse_secexp(value: str) -> int:
    if not value or not isinstance(value, str):
        raise ValueError("secexp 不能为空")
    allowed = {
        "__builtins__": {},
        "abs": abs,
        "bin": bin,
        "hex": hex,
        "int": int,
        "oct": oct,
        "pow": pow,
    }
    try:
        result = eval(value, allowed, {})
    except Exception as exc:  # pragma: no cover
        raise ValueError(f"secexp 表达式无效: {value!r} ({exc})") from exc
    if isinstance(result, bool) or not isinstance(result, int):
        raise ValueError(f"secexp 必须求值为整数，当前值为 {result!r}")
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="使用 NIST256p 私钥确定性签名 APK")
    parser.add_argument("apk", type=Path, nargs="?", help="待签名 APK 路径")
    parser.add_argument("--mode", choices=("pem", "secexp"), default=None)
    parser.add_argument("--pem", type=Path, default=DEFAULT_PEM_PATH)
    parser.add_argument("--secexp", help="Python 整数表达式，例如 2**64")
    parser.add_argument("--sdk", help="Android SDK 路径，默认读取 ANDROID_HOME")
    parser.add_argument(
        "--test-deterministic",
        action="store_true",
        help="验证同一 key + message 的确定性 ECDSA 输出是否完全一致",
    )
    parser.add_argument(
        "--message",
        default="deterministic-ecdsa-test-message",
        help="用于确定性签名测试的消息内容",
    )
    args = parser.parse_args()
    if args.secexp is not None and args.mode is None:
        args.mode = "secexp"
    if args.mode is None:
        args.mode = "pem"
    return args


def main() -> int:
    args = parse_args()
    if args.mode == "pem":
        private_key = private_key_from_pem(args.pem)
    else:
        if args.secexp is None:
            raise SystemExit("--mode secexp 必须同时提供 --secexp")
        private_key = private_key_from_secexp(parse_secexp(args.secexp))

    if args.test_deterministic:
        # 1) 消息签名确定性
        payload = args.message.encode("utf-8")
        sig1 = deterministic_ecdsa_sign(private_key, payload)
        sig2 = deterministic_ecdsa_sign(private_key, payload)
        print(f"message={args.message!r}")
        print(f"sig1={sig1.hex()}")
        print(f"sig2={sig2.hex()}")
        print(f"same={sig1 == sig2}")

        # 2) 证书确定性
        cert1 = build_certificate_deterministic(private_key)
        cert2 = build_certificate_deterministic(private_key)
        print(f"证书 SHA-256 #1: {hashlib.sha256(cert1).hexdigest()}")
        print(f"证书 SHA-256 #2: {hashlib.sha256(cert2).hexdigest()}")
        print(f"证书完全一致: {cert1 == cert2}")
        return 0

    if args.apk is None:
        raise SystemExit("必须提供 APK 路径，或使用 --test-deterministic 仅做确定性签名测试")

    keystore = make_keystore(private_key, Path.home() / ".ssh")
    print_cert_fingerprints(Path(keystore["cert_path"]))
    sign_apk(args.apk, keystore, args.sdk)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (FileNotFoundError, ValueError) as error:
        print(f"错误: {error}", file=sys.stderr)
        raise SystemExit(1)