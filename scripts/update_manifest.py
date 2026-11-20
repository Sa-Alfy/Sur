#!/usr/bin/env python3
"""Create, sign and verify Sur's in-app update manifest (sur-update.json).

Signing happens offline on the maintainer's machine. The private key never enters the
repository, CI or the APK. Needs: Python 3.8+, OpenSSL 3 (`openssl` on PATH), and Android
build-tools (aapt2 + apksigner) found through ANDROID_HOME / ANDROID_SDK_ROOT or --build-tools.

  sign:    python scripts/update_manifest.py sign --apk Sur-v1.1.0.apk --tag v1.1.0 \
               --notes docs/releases/release-1.1.0.md --key D:/tools/keys/sur-update-ed25519.pem \
               --out sur-update.json
  verify:  python scripts/update_manifest.py verify sur-update.json [--apk Sur-v1.1.0.apk]

See docs/APP_UPDATE_SYSTEM.md for the format and docs/RELEASE_CHECKLIST.md for the procedure.
"""
import argparse
import base64
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PACKAGE = "io.github.saalfy.sur"
REPO_SLUG = "Sa-Alfy/Sur"
SCHEMA = 1
CHANNEL = "stable"
# SHA-256 of Sur's release signing certificate (D:/tools/keys/sur-release.jks), same since v0.3.x.
RELEASE_CERT_SHA256 = "dad26ae786b2526a36b681f33ff3be1c6c761a806cef6c0da36e6865d7756f42"
KEYS_SOURCE = REPO / "app/src/main/java/io/github/saalfy/sur/update/UpdateManifest.kt"
DEFAULT_KEY_ID = "sur-update-2026-1"
ED25519_SPKI_PREFIX = bytes.fromhex("302a300506032b6570032100")
MIN_APK, MAX_APK = 256 * 1024, 200 * 1024 * 1024
MAX_NOTES = 10_000


def die(msg):
    print(f"ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def run(cmd, **kw):
    try:
        return subprocess.run(cmd, check=True, capture_output=True, text=True, **kw).stdout
    except FileNotFoundError:
        die(f"not found: {cmd[0]}")
    except subprocess.CalledProcessError as e:
        die(f"{' '.join(map(str, cmd))} failed:\n{e.stderr or e.stdout}")


def build_tool(name, override):
    exe = {"aapt2": "aapt2.exe", "apksigner": "apksigner.bat"}[name] if os.name == "nt" else name
    roots = [override] if override else []
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env):
            bt = Path(os.environ[env]) / "build-tools"
            if bt.is_dir():
                versions = sorted(bt.iterdir(), key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.name)])
                roots += [str(v) for v in reversed(versions)]
    for r in roots:
        p = Path(r) / exe
        if p.is_file():
            return str(p)
    found = shutil.which(name)
    if found:
        return found
    die(f"{name} not found; set ANDROID_HOME or pass --build-tools")


def trusted_keys():
    """Public keys compiled into the app, read from the Kotlin source so they can't drift."""
    src = KEYS_SOURCE.read_text(encoding="utf-8")
    ids = dict(re.findall(r'const val (\w+) = "([^"]+)"', src))
    keys = {}
    for const, b64 in re.findall(r'(\w+) to "([A-Za-z0-9+/=]{44})"', src):
        if const in ids:
            keys[ids[const]] = b64
    if not keys:
        die(f"no trusted keys found in {KEYS_SOURCE}")
    return keys


def openssl(*args):
    return run(["openssl", *args])


def public_key_b64(private_key):
    with tempfile.TemporaryDirectory() as tmp:
        der = Path(tmp) / "pub.der"
        openssl("pkey", "-in", private_key, "-pubout", "-outform", "DER", "-out", str(der))
        raw = der.read_bytes()
    if len(raw) != 44 or not raw.startswith(ED25519_SPKI_PREFIX):
        die("key is not an Ed25519 key")
    return base64.b64encode(raw[-32:]).decode()


def ed25519_verify(public_b64, payload, signature):
    with tempfile.TemporaryDirectory() as tmp:
        pub = Path(tmp) / "pub.der"
        pub.write_bytes(ED25519_SPKI_PREFIX + base64.b64decode(public_b64))
        msg = Path(tmp) / "payload.bin"
        msg.write_bytes(payload)
        sig = Path(tmp) / "sig.bin"
        sig.write_bytes(signature)
        r = subprocess.run(
            ["openssl", "pkeyutl", "-verify", "-pubin", "-keyform", "DER", "-inkey", str(pub),
             "-rawin", "-in", str(msg), "-sigfile", str(sig)],
            capture_output=True, text=True,
        )
        return r.returncode == 0


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def inspect_apk(apk, build_tools, expect_cert):
    badging = run([build_tool("aapt2", build_tools), "dump", "badging", str(apk)])
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not m:
        die("could not read package info from APK")
    sdk = re.search(r"(?:minSdkVersion|sdkVersion):'(\d+)'", badging)
    certs = run([build_tool("apksigner", build_tools), "verify", "--print-certs", str(apk)])
    digests = set(re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", certs))
    if expect_cert and digests != {expect_cert}:
        die(f"APK is not signed with Sur's release certificate (found {sorted(digests)})")
    if "application-debuggable" in badging:
        die("APK is debuggable; only release builds may be published")
    return {
        "package": m.group(1),
        "versionCode": int(m.group(2)),
        "versionName": m.group(3),
        "minSdk": int(sdk.group(1)) if sdk else 26,
        "certs": sorted(digests),
    }


def cmd_sign(a):
    apk = Path(a.apk)
    if not apk.is_file():
        die(f"APK not found: {apk}")
    if not re.fullmatch(r"v\d+\.\d+\.\d+", a.tag):
        die("tag must look like v1.2.3 (stable releases only)")
    info = inspect_apk(apk, a.build_tools, None if a.skip_cert_check else RELEASE_CERT_SHA256)
    if info["package"] != PACKAGE:
        die(f"package is {info['package']}, expected {PACKAGE}")
    if info["versionName"] != a.tag[1:]:
        die(f"APK versionName {info['versionName']} does not match tag {a.tag}")
    size = apk.stat().st_size
    if not MIN_APK <= size <= MAX_APK:
        die(f"unreasonable APK size {size}")
    notes = Path(a.notes).read_text(encoding="utf-8").strip() if a.notes else ""
    if len(notes) > MAX_NOTES:
        die(f"release notes are {len(notes)} characters (max {MAX_NOTES})")
    min_updatable = a.min_updatable or 1
    if not 1 <= min_updatable <= info["versionCode"]:
        die("--min-updatable must be between 1 and the new versionCode")

    keys = trusted_keys()
    public_b64 = public_key_b64(a.key)
    if a.trusted_public_key:
        keys = {a.key_id: a.trusted_public_key}
    if keys.get(a.key_id) != public_b64:
        die(f"private key does not match the app's trusted key '{a.key_id}' (see UpdateKeys in {KEYS_SOURCE.name})")

    payload_obj = {
        "schema": SCHEMA,
        "packageName": PACKAGE,
        "channel": CHANNEL,
        "versionCode": info["versionCode"],
        "versionName": info["versionName"],
        "releaseDate": a.date or datetime.date.today().isoformat(),
        "minUpdatableVersionCode": min_updatable,
        "minSdk": info["minSdk"],
        "mandatory": bool(a.mandatory),
        "releaseNotes": notes,
        "apk": {
            "url": f"https://github.com/{REPO_SLUG}/releases/download/{a.tag}/Sur-{a.tag}.apk",
            "sha256": sha256_file(apk),
            "size": size,
        },
    }
    payload = json.dumps(payload_obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    with tempfile.TemporaryDirectory() as tmp:
        msg = Path(tmp) / "payload.bin"
        msg.write_bytes(payload)
        sig_path = Path(tmp) / "sig.bin"
        openssl("pkeyutl", "-sign", "-inkey", a.key, "-rawin", "-in", str(msg), "-out", str(sig_path))
        signature = sig_path.read_bytes()
    if len(signature) != 64 or not ed25519_verify(public_b64, payload, signature):
        die("signature self-check failed")
    envelope = {
        "keyId": a.key_id,
        "payload": base64.b64encode(payload).decode(),
        "signature": base64.b64encode(signature).decode(),
    }
    Path(a.out).write_text(json.dumps(envelope, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {a.out}")
    print(json.dumps({k: v for k, v in payload_obj.items() if k != "releaseNotes"}, indent=2))


def cmd_verify(a):
    raw = Path(a.manifest).read_bytes()
    if len(raw) > 64 * 1024:
        die("manifest larger than 64 KiB")
    env = json.loads(raw)
    keys = {env["keyId"]: a.trusted_public_key} if a.trusted_public_key else trusted_keys()
    if env["keyId"] not in keys:
        die(f"unknown key id {env['keyId']}")
    payload = base64.b64decode(env["payload"], validate=True)
    if not ed25519_verify(keys[env["keyId"]], payload, base64.b64decode(env["signature"], validate=True)):
        die("SIGNATURE INVALID")
    m = json.loads(payload)
    problems = []
    if m.get("schema") != SCHEMA: problems.append("schema")
    if m.get("packageName") != PACKAGE: problems.append("packageName")
    if m.get("channel") != CHANNEL: problems.append("channel")
    url = m.get("apk", {}).get("url", "")
    if not url.startswith(f"https://github.com/{REPO_SLUG}/releases/download/"): problems.append("apk.url")
    if not re.fullmatch(r"[0-9a-f]{64}", m.get("apk", {}).get("sha256", "")): problems.append("apk.sha256")
    if problems:
        die(f"invalid fields: {problems}")
    if a.apk:
        if sha256_file(a.apk) != m["apk"]["sha256"]: die("APK SHA-256 does not match the manifest")
        if Path(a.apk).stat().st_size != m["apk"]["size"]: die("APK size does not match the manifest")
        print("APK matches the manifest.")
    print("Signature OK.")
    print(json.dumps({k: v for k, v in m.items() if k != "releaseNotes"}, indent=2))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("sign", help="create and sign sur-update.json for a release APK")
    s.add_argument("--apk", required=True, help="the exact APK attached to the GitHub release")
    s.add_argument("--tag", required=True, help="release tag, e.g. v1.1.0")
    s.add_argument("--key", required=True, help="Ed25519 private key PEM (offline)")
    s.add_argument("--notes", help="release notes file (plain text / markdown)")
    s.add_argument("--out", default="sur-update.json")
    s.add_argument("--date", help="release date YYYY-MM-DD (default today)")
    s.add_argument("--mandatory", action="store_true")
    s.add_argument("--min-updatable", type=int, help="lowest installed versionCode allowed to update in-app")
    s.add_argument("--key-id", default=DEFAULT_KEY_ID)
    s.add_argument("--build-tools", help="Android build-tools directory")
    s.add_argument("--trusted-public-key", help=argparse.SUPPRESS)  # tests only
    s.add_argument("--skip-cert-check", action="store_true", help=argparse.SUPPRESS)  # tests only
    v = sub.add_parser("verify", help="verify a manifest (and optionally the APK it names)")
    v.add_argument("manifest")
    v.add_argument("--apk")
    v.add_argument("--trusted-public-key", help=argparse.SUPPRESS)
    a = p.parse_args()
    {"sign": cmd_sign, "verify": cmd_verify}[a.cmd](a)


if __name__ == "__main__":
    main()
