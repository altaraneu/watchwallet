#!/usr/bin/env python3
"""Signs and verifies the remote server list (config/endpoints.json).

Reads config/endpoints.source.json, signs it with BIP-340 Schnorr over secp256k1 and writes the
envelope the app downloads. Python standard library only, so the signing machine needs nothing
installed. See docs/endpoints-config.md.

    python scripts/sign_endpoints.py            # sign with the default key file
    python scripts/sign_endpoints.py --check    # verify config/endpoints.json, needs no key

Signing first downloads the published file, because apps reject a version that is not above the one
they already have, and the copy in a working tree can be older (--offline skips this).

The private key is never printed, logged or written anywhere by this tool. Error messages do not
quote the key file. This is a plain big-integer implementation without side-channel protection:
run it only on a trusted machine, never on a shared server or in CI.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

# x-only public key pinned in the app (EndpointFileVerifier.PINNED_PUBLIC_KEY).
PINNED_PUBLIC_KEY = "d518716d260dec9a323611396e9917f219a8ef02824b877975638af91e0f8866"
DEFAULT_KEY_FILE = Path.home() / ".watchwallet-signing" / "endpoints-signing.key"
DEFAULT_SOURCE = REPO / "config" / "endpoints.source.json"
DEFAULT_OUTPUT = REPO / "config" / "endpoints.json"

PAYLOAD_FORMAT = 1
# Same limit as the app (EndpointFileVerifier.MAX_ENVELOPE_BYTES).
MAX_ENVELOPE_BYTES = 256 * 1024
# Same limit as the app (EndpointFile.MAX_VALIDITY_SECONDS): it ignores a file expiring later.
MAX_VALIDITY_DAYS = 200
SECONDS_PER_DAY = 86_400
# The two URLs apps download from (EndpointConfigUpdater.DEFAULT_URLS).
PUBLISHED_URLS = (
    "https://raw.githubusercontent.com/altaraneu/watchwallet/main/config/endpoints.json",
    "https://cdn.jsdelivr.net/gh/altaraneu/watchwallet@main/config/endpoints.json",
)
DOWNLOAD_TIMEOUT_SECONDS = 20
URL_PATTERN = re.compile(r"https://[A-Za-z0-9.-]+(:[0-9]{1,5})?(/[A-Za-z0-9._~/%-]*)?")

# secp256k1 domain parameters.
P = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F
N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G = (
    0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
    0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8,
)


class EndpointsError(Exception):
    """A problem to report to the operator. Messages never contain key material."""


def tagged_hash(tag: str, data: bytes) -> bytes:
    tag_hash = hashlib.sha256(tag.encode()).digest()
    return hashlib.sha256(tag_hash + tag_hash + data).digest()


def point_add(a, b):
    if a is None:
        return b
    if b is None:
        return a
    if a[0] == b[0] and a[1] != b[1]:
        return None
    if a == b:
        slope = 3 * a[0] * a[0] * pow(2 * a[1], P - 2, P) % P
    else:
        slope = (b[1] - a[1]) * pow(b[0] - a[0], P - 2, P) % P
    x = (slope * slope - a[0] - b[0]) % P
    return (x, (slope * (a[0] - x) - a[1]) % P)


def point_mul(point, scalar: int):
    result = None
    for bit in range(256):
        if (scalar >> bit) & 1:
            result = point_add(result, point)
        point = point_add(point, point)
    return result


def lift_x(x: int):
    if x >= P:
        return None
    square = (pow(x, 3, P) + 7) % P
    y = pow(square, (P + 1) // 4, P)
    if pow(y, 2, P) != square:
        return None
    return (x, y if y % 2 == 0 else P - y)


def public_key(secret: bytes) -> bytes:
    d = int.from_bytes(secret, "big")
    if not 1 <= d < N:
        raise EndpointsError("The private key is out of range.")
    return point_mul(G, d)[0].to_bytes(32, "big")


def schnorr_sign(message: bytes, secret: bytes, aux_rand: bytes) -> bytes:
    """BIP-340 signing, step by step as in the specification."""
    d0 = int.from_bytes(secret, "big")
    if not 1 <= d0 < N:
        raise EndpointsError("The private key is out of range.")
    if len(aux_rand) != 32:
        raise ValueError("aux_rand must be 32 bytes")
    point = point_mul(G, d0)
    d = d0 if point[1] % 2 == 0 else N - d0
    pub = point[0].to_bytes(32, "big")
    masked = d ^ int.from_bytes(tagged_hash("BIP0340/aux", aux_rand), "big")
    k0 = int.from_bytes(tagged_hash("BIP0340/nonce", masked.to_bytes(32, "big") + pub + message), "big") % N
    if k0 == 0:
        raise EndpointsError("Could not derive a nonce; try again.")
    nonce_point = point_mul(G, k0)
    k = k0 if nonce_point[1] % 2 == 0 else N - k0
    r = nonce_point[0].to_bytes(32, "big")
    e = int.from_bytes(tagged_hash("BIP0340/challenge", r + pub + message), "big") % N
    signature = r + ((k + e * d) % N).to_bytes(32, "big")
    if not schnorr_verify(message, pub, signature):
        raise EndpointsError("The created signature did not verify; nothing was written.")
    return signature


def schnorr_verify(message: bytes, pub: bytes, signature: bytes) -> bool:
    if len(pub) != 32 or len(signature) != 64:
        return False
    point = lift_x(int.from_bytes(pub, "big"))
    r = int.from_bytes(signature[:32], "big")
    s = int.from_bytes(signature[32:], "big")
    if point is None or r >= P or s >= N:
        return False
    e = int.from_bytes(tagged_hash("BIP0340/challenge", signature[:32] + pub + message), "big") % N
    result = point_add(point_mul(G, s), point_mul(point, N - e))
    return result is not None and result[1] % 2 == 0 and result[0] == r


def load_secret(path: Path, expected_public_key: str) -> bytes:
    """Finds the key in [path]: 32 raw bytes, or hex text possibly among other text.

    Only the key that matches [expected_public_key] is accepted, so a wrong key file is reported
    before anything gets signed.
    """
    try:
        raw = path.read_bytes()
    except OSError:
        raise EndpointsError(f"Cannot read the key file {path}.") from None
    candidates = [raw] if len(raw) == 32 else []
    text = raw.decode("ascii", errors="ignore")
    candidates += [bytes.fromhex(token) for token in re.findall(r"(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])", text)]
    if not candidates:
        raise EndpointsError(f"The key file {path} holds no 32-byte key (raw or hex).")
    for candidate in candidates:
        if 1 <= int.from_bytes(candidate, "big") < N and public_key(candidate).hex() == expected_public_key:
            return candidate
    raise EndpointsError(f"The key in {path} does not match the expected public key {expected_public_key}.")


def validate_endpoints(endpoints) -> None:
    """The app ignores bad entries one by one; the signer refuses them, as they are mistakes."""
    if not isinstance(endpoints, list) or not endpoints:
        raise EndpointsError("'endpoints' must be a non-empty list.")
    seen = set()
    for entry in endpoints:
        if not isinstance(entry, dict) or not all(isinstance(entry.get(field), str) for field in ("chain", "protocol")):
            raise EndpointsError(f"Entry needs 'chain' and 'protocol': {entry}")
        key = (entry["chain"], entry["protocol"])
        if key in seen:
            raise EndpointsError(f"Duplicate entry: {key}")
        seen.add(key)
        urls = entry.get("urls")
        if not isinstance(urls, list) or not urls:
            raise EndpointsError(f"Entry {key} needs a non-empty 'urls' list.")
        for url in urls:
            if not isinstance(url, str) or not URL_PATTERN.fullmatch(url):
                raise EndpointsError(f"Entry {key}: URL must be plain https without query or credentials: {url}")


def build_payload(source: dict, now: int) -> bytes:
    """The exact bytes that get signed."""
    version = source.get("version")
    days = source.get("expires_in_days")
    if not isinstance(version, int) or version < 1:
        raise EndpointsError("'version' must be a positive integer.")
    if not isinstance(days, int) or days < 1:
        raise EndpointsError("'expires_in_days' must be a positive integer.")
    if days > MAX_VALIDITY_DAYS:
        raise EndpointsError(f"'expires_in_days' is {days}, but apps ignore a file expiring more than {MAX_VALIDITY_DAYS} days ahead.")
    validate_endpoints(source.get("endpoints"))
    payload = {
        "format": PAYLOAD_FORMAT,
        "version": version,
        "signed": now,
        "expires": now + days * SECONDS_PER_DAY,
        "endpoints": source["endpoints"],
    }
    return json.dumps(payload, indent=1, ensure_ascii=True).encode("ascii")


def build_envelope(payload: bytes, secret: bytes, aux_rand: bytes) -> bytes:
    signature = schnorr_sign(hashlib.sha256(payload).digest(), secret, aux_rand)
    envelope = {"payload": base64.b64encode(payload).decode("ascii"), "signature": signature.hex()}
    data = (json.dumps(envelope, indent=1) + "\n").encode("ascii")
    if len(data) > MAX_ENVELOPE_BYTES:
        raise EndpointsError(f"The file would be {len(data)} bytes, the app accepts {MAX_ENVELOPE_BYTES}.")
    return data


def open_envelope(data: bytes, pub_hex: str) -> dict:
    """Verifies an envelope like the app does and returns the decoded payload."""
    if len(data) > MAX_ENVELOPE_BYTES:
        raise EndpointsError(f"The file has {len(data)} bytes, the app accepts {MAX_ENVELOPE_BYTES}.")
    try:
        envelope = json.loads(data)
        payload = base64.b64decode(envelope["payload"], validate=True)
        signature = bytes.fromhex(envelope["signature"])
    except (ValueError, KeyError, TypeError):
        raise EndpointsError("Not a signed endpoints envelope.") from None
    if not schnorr_verify(hashlib.sha256(payload).digest(), bytes.fromhex(pub_hex), signature):
        raise EndpointsError("Bad signature.")
    return json.loads(payload)


def read_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise EndpointsError(f"Cannot read {path}: {error}") from None


def trusted_version(data: bytes, pub_hex: str) -> int:
    """Version in a signed file; only a signature we trust counts."""
    try:
        return int(open_envelope(data, pub_hex).get("version", 0))
    except EndpointsError:
        return 0


def existing_version(path: Path, pub_hex: str) -> int:
    """Version of the signed file in this working tree."""
    return trusted_version(path.read_bytes(), pub_hex) if path.exists() else 0


def download(url: str) -> bytes | None:
    """The body of [url], at most one byte over the limit; `None` when it does not exist yet."""
    request = urllib.request.Request(url, headers={"User-Agent": "watchwallet-sign-endpoints"})
    try:
        with urllib.request.urlopen(request, timeout=DOWNLOAD_TIMEOUT_SECONDS) as response:
            return response.read(MAX_ENVELOPE_BYTES + 1)
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise EndpointsError(f"{url} answered HTTP {error.code}.") from None
    except OSError as error:
        raise EndpointsError(f"Cannot reach {url}: {error}") from None


def published_version(fetch, urls, pub_hex: str) -> int:
    """The highest version apps can already have, over all mirrors.

    One mirror answering is enough, as they serve the same repository. With none answering the
    version is unknown, and guessing could publish a file every app rejects.
    """
    highest = 0
    reached = 0
    for url in urls:
        try:
            data = fetch(url)
        except EndpointsError as error:
            print(f"Warning: {error}", file=sys.stderr)
            continue
        reached += 1
        if data is None:
            continue
        version = trusted_version(data, pub_hex)
        if version == 0:
            print(f"Warning: {url} holds no file signed with the expected key.", file=sys.stderr)
        highest = max(highest, version)
    if reached == 0:
        raise EndpointsError("Could not read the published file to compare versions. Try again, or use --offline if you know it.")
    return highest


def sign(args: argparse.Namespace) -> int:
    source = read_json(args.source)
    previous = existing_version(args.output, args.pubkey)
    if not args.offline:
        previous = max(previous, published_version(args.fetch, PUBLISHED_URLS, args.pubkey))
    if source.get("version", 0) <= previous:
        raise EndpointsError(f"Bump 'version' in {args.source}: apps reject anything not above {previous}.")
    secret = load_secret(args.key, args.pubkey)
    data = build_envelope(build_payload(source, int(time.time())), secret, os.urandom(32))
    payload = open_envelope(data, args.pubkey)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(data)
    print(f"Signed {args.output}: version {payload['version']}, {len(payload['endpoints'])} entries, expires {format_time(payload['expires'])}.")
    return 0


def check(args: argparse.Namespace) -> int:
    try:
        data = args.output.read_bytes()
    except OSError:
        raise EndpointsError(f"Cannot read {args.output}.") from None
    payload = open_envelope(data, args.pubkey)
    validate_endpoints(payload.get("endpoints"))
    if payload.get("format") != PAYLOAD_FORMAT:
        raise EndpointsError(f"Unknown format {payload.get('format')}.")
    if payload.get("expires", 0) <= time.time():
        raise EndpointsError(f"Valid signature, but it expired on {format_time(payload['expires'])}.")
    if payload["expires"] - time.time() > MAX_VALIDITY_DAYS * SECONDS_PER_DAY:
        raise EndpointsError(f"Valid signature, but apps ignore a file expiring more than {MAX_VALIDITY_DAYS} days ahead.")
    source = read_json(args.source)
    if source.get("endpoints") != payload["endpoints"] or source.get("version") != payload["version"]:
        raise EndpointsError(f"Valid signature, but {args.output} does not match {args.source}. Sign again.")
    print(f"OK: version {payload['version']}, {len(payload['endpoints'])} entries, expires {format_time(payload['expires'])}.")
    return 0


def format_time(seconds: int) -> str:
    return time.strftime("%Y-%m-%d", time.gmtime(seconds))


def main(argv=None, fetch=download) -> int:
    parser = argparse.ArgumentParser(description="Sign or verify the remote server list.")
    parser.add_argument("--check", action="store_true", help="verify the output file instead of signing; needs no key")
    parser.add_argument("--key", type=Path, default=DEFAULT_KEY_FILE, help="private key file (default: %(default)s)")
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE, help="unsigned source (default: %(default)s)")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="signed file (default: %(default)s)")
    parser.add_argument("--pubkey", default=PINNED_PUBLIC_KEY, help="expected x-only public key in hex (default: the one pinned in the app)")
    parser.add_argument("--offline", action="store_true", help="do not compare with the published file; only if you know its version")
    args = parser.parse_args(argv)
    args.fetch = fetch
    if not re.fullmatch(r"[0-9a-f]{64}", args.pubkey):
        print("--pubkey must be 64 lowercase hex characters.", file=sys.stderr)
        return 2
    try:
        return check(args) if args.check else sign(args)
    except EndpointsError as error:
        print(f"Error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
