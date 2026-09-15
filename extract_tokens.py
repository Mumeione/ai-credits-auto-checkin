#!/usr/bin/env python3
"""从电脑端提取 Trae / WorkBuddy 登录凭据，输出手机 App 可导入的 checkin_auth.json"""
import base64
import hashlib
import json
import os
import sys

# ---------- Trae 解密常量（来自 trae-check 项目） ----------
HEADER = bytes([116, 99, 5, 16, 0, 0])
LEFT_SECRET = bytes([82, 9, 106, 213, 48, 54, 165, 56, 191, 64, 163, 158, 129, 243, 215, 251, 124, 227, 57, 130, 155, 47, 255, 135, 52, 142, 67, 68, 196, 222, 233, 203, 84, 123, 148, 50, 166, 194, 35, 61, 238, 76, 149, 11, 66, 250, 195, 78, 8, 46, 161, 102, 40, 217, 36, 178, 118, 91, 162, 73, 109, 139, 209, 37])
RIGHT_SECRET = bytes([31, 221, 168, 51, 136, 7, 199, 49, 177, 18, 16, 89, 39, 128, 236, 95, 96, 81, 127, 169, 25, 181, 74, 13, 45, 229, 122, 159, 147, 201, 156, 239, 160, 224, 59, 77, 174, 42, 245, 176, 200, 235, 187, 60, 131, 83, 153, 97, 23, 43, 4, 126, 186, 119, 214, 38, 225, 105, 20, 99, 85, 33, 12, 125])

try:
    from Crypto.Cipher import AES  # pycryptodome
except ImportError:
    sys.exit("需要 pycryptodome: pip install pycryptodome")


def sha512(data: bytes) -> bytes:
    return hashlib.sha512(data).digest()


def decrypt_trae(encoded_b64: str) -> dict:
    envelope = base64.b64decode(encoded_b64)
    if len(envelope) <= 38 or envelope[:6] != HEADER:
        raise ValueError("无效的 Trae 凭据信封")
    random_key = envelope[6:38]
    secret = bytes(l ^ r for l, r in zip(LEFT_SECRET, RIGHT_SECRET))
    derived = sha512(sha512(random_key) + secret)
    key, iv = derived[:16], derived[16:32]
    plaintext = AES.new(key, AES.MODE_CBC, iv).decrypt(envelope[38:])
    plaintext = plaintext[:-plaintext[-1]]  # 去除 PKCS7 填充
    digest, payload = plaintext[:64], plaintext[64:]
    if digest != sha512(payload):
        raise ValueError("HMAC 校验失败")
    return json.loads(payload.decode("utf-8"))


def find_trae_storage() -> str:
    appdata = os.environ.get("APPDATA", "")
    candidates = [
        os.path.join(appdata, "Trae CN", "User", "globalStorage", "storage.json"),
        os.path.join(appdata, "TRAE SOLO CN", "User", "globalStorage", "storage.json"),
        os.path.join(appdata, "Trae", "User", "globalStorage", "storage.json"),
    ]
    for p in candidates:
        if os.path.exists(p):
            return p
    sys.exit("找不到 Trae storage.json，请确认已登录 Trae 桌面端")


def extract_trae() -> dict:
    with open(find_trae_storage(), encoding="utf-8") as f:
        storage = json.load(f)
    auth = decrypt_trae(storage["iCubeAuthInfo://icube.cloudide"])
    device_id = storage.get("telemetry.devDeviceId", "")
    if not device_id:
        sys.exit("storage.json 中缺少 telemetry.devDeviceId")
    return {
        "trae_access": auth.get("token") or auth.get("accessToken", ""),
        "trae_refresh": auth.get("refreshToken", ""),
        "trae_device": device_id,
        "trae_expired_at": auth.get("expiredAt", ""),
    }


def extract_workbuddy() -> dict:
    path = os.path.join(
        os.environ.get("LOCALAPPDATA", ""),
        "CodeBuddyExtension", "Data", "Public", "auth", "workbuddy-desktop.info",
    )
    if not os.path.exists(path):
        print("跳过 WorkBuddy：未找到 workbuddy-desktop.info")
        return {}
    with open(path, encoding="utf-8") as f:
        info = json.load(f)
    auth = info.get("auth", info)
    return {
        "wb_token": auth.get("accessToken", ""),
        "wb_domain": "https://" + (auth.get("domain") or "copilot.tencent.com"),
    }


def main():
    result = {}
    try:
        result.update(extract_trae())
        print("[OK] Trae 凭据提取成功")
    except SystemExit as e:
        print(e)
    except Exception as e:
        print(f"[SKIP] Trae 提取失败: {e}")
    try:
        wb = extract_workbuddy()
        result.update(wb)
        if wb:
            print("[OK] WorkBuddy 凭据提取成功")
    except Exception as e:
        print(f"[SKIP] WorkBuddy 提取失败: {e}")

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "checkin_auth.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
    print(f"已写入 {out}")
    print("字段:", ", ".join(result.keys()))


if __name__ == "__main__":
    main()
