#!/usr/bin/env python3
"""Regenerates the coin and token logos bundled with the app, so that showing them needs no request.

Every logo comes from one pinned commit of trustwallet/assets (MIT) and is scaled to 96x96. The set
is deliberately small: native coins of the supported chains and well-known tokens. Everything else
shows a letter, or a downloaded logo if the user turned that on in Settings.

    pip install "pillow==13.0.0"
    python scripts/fetch_logos.py           # download, verify, write PNGs and the lock file
    python scripts/fetch_logos.py --check   # offline: compare the PNGs with the lock file

Generation also checks the token table in CryptoIcon.kt against Trust Wallet's own metadata, so a
mistyped contract cannot put a well-known logo on the wrong token.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DRAWABLES = ROOT / "core/designsystem/src/commonMain/composeResources/drawable"
ICON_TABLE = ROOT / "core/designsystem/src/commonMain/kotlin/com/altaran/watchwallet/core/designsystem/component/CryptoIcon.kt"
LOCK = ROOT / "scripts/logos.lock.tsv"
LICENSE = ROOT / "core/designsystem/src/commonMain/composeResources/files/licenses/trustwallet-assets-LICENSE.txt"

# Change this and re-run to update the logos; review the resulting image diff.
COMMIT = "ff33272a831412ad1c88fc87e62b81ec207130c1"
BASE = f"https://raw.githubusercontent.com/trustwallet/assets/{COMMIT}"
SIZE = 96
ATTEMPTS = 6

ETHEREUM = "blockchains/ethereum/assets"

# Icon key (`chain_<key>.png`) -> file in trustwallet/assets.
LOGOS = {
    # Native coins and networks of the supported chains. Berachain, Unichain and Kaspa have no folder
    # at this commit, so they show a letter.
    "btc": "blockchains/bitcoin/info/logo.png",
    "eth": "blockchains/ethereum/info/logo.png",
    "bnb": "blockchains/smartchain/info/logo.png",
    "arbitrum": "blockchains/arbitrum/info/logo.png",
    "optimism": "blockchains/optimism/info/logo.png",
    "base": "blockchains/base/info/logo.png",
    "polygon": "blockchains/polygon/info/logo.png",
    "avax": "blockchains/avalanchec/info/logo.png",
    "zksync": "blockchains/zksync/info/logo.png",
    "linea": "blockchains/linea/info/logo.png",
    "scroll": "blockchains/scroll/info/logo.png",
    "mantle": "blockchains/mantle/info/logo.png",
    "gno": "blockchains/xdai/info/logo.png",
    "cronos": "blockchains/cronos/info/logo.png",
    "sonic": "blockchains/sonic/info/logo.png",
    "celo": "blockchains/celo/info/logo.png",
    "etc": "blockchains/classic/info/logo.png",
    "hyperevm": "blockchains/hyperevm/info/logo.png",
    "sei": "blockchains/sei/info/logo.png",
    "blast": "blockchains/blast/info/logo.png",
    "ltc": "blockchains/litecoin/info/logo.png",
    "doge": "blockchains/doge/info/logo.png",
    "bch": "blockchains/bitcoincash/info/logo.png",
    "sol": "blockchains/solana/info/logo.png",
    "xrp": "blockchains/ripple/info/logo.png",
    "trx": "blockchains/tron/info/logo.png",
    "ton": "blockchains/ton/info/logo.png",
    "ada": "blockchains/cardano/info/logo.png",
    "atom": "blockchains/cosmos/info/logo.png",
    "osmosis": "blockchains/osmosis/info/logo.png",
    "celestia": "blockchains/tia/info/logo.png",
    "injective": "blockchains/nativeinjective/info/logo.png",
    "dot": "blockchains/polkadot/info/logo.png",
    "ksm": "blockchains/kusama/info/logo.png",
    "xlm": "blockchains/stellar/info/logo.png",
    "sui": "blockchains/sui/info/logo.png",
    "aptos": "blockchains/aptos/info/logo.png",
    "near": "blockchains/near/info/logo.png",
    "hedera": "blockchains/hedera/info/logo.png",
    "algo": "blockchains/algorand/info/logo.png",
    "xtz": "blockchains/tezos/info/logo.png",
    # Well-known tokens.
    "usdt": f"{ETHEREUM}/0xdAC17F958D2ee523a2206206994597C13D831ec7/logo.png",
    "usdc": f"{ETHEREUM}/0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48/logo.png",
    "dai": f"{ETHEREUM}/0x6B175474E89094C44Da98b954EedeAC495271d0F/logo.png",
    "wbtc": f"{ETHEREUM}/0x2260FAC5E5542a773Aa44fBCfeDf7C193bc2C599/logo.png",
    "shib": f"{ETHEREUM}/0x95aD61b0a150d79219dCF64E1E6Cc01f0B64C4cE/logo.png",
    "chz": f"{ETHEREUM}/0x3506424F91fD33084466F402d5D97f05F8e3b4AF/logo.png",
    "ankr": f"{ETHEREUM}/0x8290333ceF9e6D528dD5618Fb97a76f268f3EDD4/logo.png",
    "link": f"{ETHEREUM}/0x514910771AF9Ca656af840dff83E8264EcF986CA/logo.png",
    "uni": f"{ETHEREUM}/0x1f9840a85d5aF5bf1D1762F925BDADdC4201F984/logo.png",
    "pepe": f"{ETHEREUM}/0x6982508145454Ce325dDbE47a25d4ec3d2311933/logo.png",
}

# CoinGecko platform id -> Trust Wallet folder, for checking the token table.
PLATFORMS = {
    "ethereum": "ethereum",
    "binance-smart-chain": "smartchain",
    "polygon-pos": "polygon",
    "arbitrum-one": "arbitrum",
    "optimistic-ethereum": "optimism",
    "base": "base",
    "avalanche": "avalanchec",
    "solana": "solana",
    "tron": "tron",
}

# Expected symbols per icon key; Trust Wallet spells bridged versions differently.
SYMBOLS = {
    "usdt": {"USDT", "USDt", "USDT.e", "USD₮0", "USDT0"},
    "usdc": {"USDC", "USDC.e", "USDbC"},
    "dai": {"DAI", "DAI.e"},
    "eth": {"WETH"},
    "wbtc": {"WBTC"},
    "shib": {"SHIB"},
    "chz": {"CHZ"},
    "ankr": {"ANKR"},
    "link": {"LINK"},
    "uni": {"UNI"},
    "pepe": {"PEPE"},
    "polygon": {"POL", "MATIC"},
    "arbitrum": {"ARB"},
    "optimism": {"OP"},
}


def download(path: str) -> bytes:
    request = urllib.request.Request(f"{BASE}/{path}", headers={"User-Agent": "watchwallet-fetch-logos"})
    for attempt in range(ATTEMPTS):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return response.read()
        except urllib.error.HTTPError:
            raise  # A missing file stays missing at a pinned commit.
        except OSError:
            if attempt == ATTEMPTS - 1:
                raise
            time.sleep(2**attempt)
    raise AssertionError("unreachable")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def keccak256(data: bytes) -> bytes:
    """Keccak-256 (not SHA3-256, whose padding differs), so that generation only needs Pillow."""
    rotations = [0, 1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39, 41, 45, 15, 21, 8, 18, 2, 61, 56, 14]
    mask = (1 << 64) - 1
    rate = 136
    padded = bytearray(data) + b"\x01" + bytes((rate - (len(data) + 1) % rate) % rate)
    padded[-1] |= 0x80
    state = [0] * 25
    for block in range(0, len(padded), rate):
        for i in range(rate // 8):
            state[i] ^= int.from_bytes(padded[block + i * 8 : block + i * 8 + 8], "little")
        lfsr = 1
        for _round in range(24):
            parity = [state[x] ^ state[x + 5] ^ state[x + 10] ^ state[x + 15] ^ state[x + 20] for x in range(5)]
            for i in range(25):
                left = parity[(i % 5 + 1) % 5]
                state[i] ^= parity[(i % 5 + 4) % 5] ^ ((left << 1 | left >> 63) & mask)
            moved = [0] * 25
            for x in range(5):
                for y in range(5):
                    value, shift = state[x + 5 * y], rotations[x + 5 * y]
                    moved[y + 5 * ((2 * x + 3 * y) % 5)] = ((value << shift) | (value >> (64 - shift))) & mask
            state = [moved[i] ^ (~moved[(i + 1) % 5 + i // 5 * 5] & moved[(i + 2) % 5 + i // 5 * 5] & mask) for i in range(25)]
            for j in range(7):
                lfsr = ((lfsr << 1) ^ ((lfsr >> 7) * 0x71)) & 0xFF
                if lfsr & 2:
                    state[0] ^= 1 << ((1 << j) - 1)
    return b"".join(v.to_bytes(8, "little") for v in state[:4])


def keccak_checksum(address: str) -> str:
    """EIP-55 spelling, which names the EVM token folders of Trust Wallet."""
    plain = address.lower().removeprefix("0x")
    digest = keccak256(plain.encode()).hex()
    return "0x" + "".join(c.upper() if int(digest[i], 16) >= 8 else c for i, c in enumerate(plain))


def render(source: bytes) -> bytes:
    from PIL import Image

    with Image.open(io.BytesIO(source)) as image:
        rgba = image.convert("RGBA").resize((SIZE, SIZE), Image.Resampling.LANCZOS)
        # A new image carries no metadata; compression settings fixed, so output is byte-identical.
        clean = Image.new("RGBA", rgba.size)
        clean.paste(rgba)
        out = io.BytesIO()
        clean.save(out, format="PNG", optimize=True)
        return out.getvalue()


def token_table() -> list[tuple[str, str, str]]:
    """(platform, contract, icon key) of every token in CryptoIcon.kt."""
    pairs = re.findall(r'"token:([a-z0-9-]+):([^"]+)" to "(\w+)"', ICON_TABLE.read_text(encoding="utf-8"))
    if not pairs:
        sys.exit(f"No token table found in {ICON_TABLE}")
    return pairs


def verify_tokens() -> None:
    for platform, contract, key in token_table():
        folder = PLATFORMS[platform]
        spelled = keccak_checksum(contract) if contract.startswith("0x") else contract
        info = json.loads(download(f"blockchains/{folder}/assets/{spelled}/info.json"))
        if info["symbol"] not in SYMBOLS[key]:
            sys.exit(f"{platform}:{contract} is {info['symbol']} at Trust Wallet, the table says {key}")
        print(f"ok {platform} {info['symbol']:8} {contract}")


def generate() -> None:
    verify_tokens()
    LICENSE.parent.mkdir(parents=True, exist_ok=True)
    LICENSE.write_bytes(download("LICENSE"))
    lines = [f"# trustwallet/assets@{COMMIT}, scaled to {SIZE}x{SIZE}. Regenerate: scripts/fetch_logos.py"]
    for key, path in sorted(LOGOS.items()):
        source = download(path)
        png = render(source)
        (DRAWABLES / f"chain_{key}.png").write_bytes(png)
        lines.append(f"{key}\t{path}\t{sha256(source)}\t{sha256(png)}")
        print(f"{key}: {len(source)} -> {len(png)} bytes")
    for stale in DRAWABLES.glob("chain_*.png"):
        if stale.stem.removeprefix("chain_") not in LOGOS:
            print(f"removed {stale.name}")
            stale.unlink()
    LOCK.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"{len(LOGOS)} logos, {sum(p.stat().st_size for p in DRAWABLES.glob('chain_*.png'))} bytes")


def check() -> None:
    locked = {}
    for line in LOCK.read_text(encoding="utf-8").splitlines():
        if not line.startswith("#"):
            key, _path, _source, output = line.split("\t")
            locked[f"chain_{key}.png"] = output
    found = {p.name: sha256(p.read_bytes()) for p in DRAWABLES.glob("chain_*.png")}
    if found != locked:
        different = sorted(n for n in found.keys() | locked.keys() if found.get(n) != locked.get(n))
        sys.exit(f"Logos differ from {LOCK.name}: {', '.join(different)}")
    print(f"{len(found)} logos match {LOCK.name}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="only compare the PNGs with the lock file")
    if parser.parse_args().check:
        check()
    else:
        generate()
