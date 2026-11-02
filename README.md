# WatchWallet

**An open-source, watch-only crypto portfolio tracker for Android.**

WatchWallet tracks balances, transaction history and portfolio value using **public addresses
and extended public keys only**. It never asks for a seed phrase or private key and cannot sign or
send anything; input that looks like one is rejected ([limits](SECURITY.md)).

- **Private by design** – no accounts, no backend, no analytics, no ads, no Google Play Services.
  Everything stays on your device. Every network endpoint is listed in [PRIVACY.md](PRIVACY.md).
  You can use your own Bitcoin server and a SOCKS5 proxy, and turn off server list updates.
- **Multichain, one design** – Bitcoin, Ethereum and EVM networks, Solana, XRP and more in one
  consistent interface.
- **Paste and done** – paste an address, an xpub or a name; WatchWallet detects the chain.
- **Unlimited** – no limit on wallets, addresses, groups or chains.

> **Status:** pre-release (not yet 1.0). Bitcoin and EVM networks work end to end; more chains
> are being added. See [CHANGELOG.md](CHANGELOG.md).

## Features

- **Paste, scan or import**: addresses, xpub/ypub/zpub, output descriptors; QR scanner; batch
  import from text, CSV or a WatchWallet backup. Input that looks like a seed phrase or private key
  is rejected and not stored or sent; [SECURITY.md](SECURITY.md) lists what cannot be told from an address.
- **Bitcoin**: single addresses and extended public keys with local derivation and gap limit.
- **Ethereum and EVM networks**: one `0x` address scanned across 21 networks (Ethereum, Base,
  Arbitrum, OP, Polygon, BNB Chain, Avalanche, zkSync, Linea, Scroll, …), ERC-20 tokens, spam
  filter, history.
- **Portfolio**: total value and its history, allocation, per-wallet and per-group views (for
  example one portfolio per family member).
- **Markets**: prices of held assets and a watchlist; live candlestick charts (1m–1W) with pan,
  pinch zoom and crosshair, powered by TradingView Lightweight Charts.
- **Privacy and security**: discreet mode, app lock (biometrics / screen lock), screenshot
  blocking, Tor/SOCKS5 proxy, your own Esplora server, no cloud backup.
- **Export**: JSON backup (optionally AES-256-GCM encrypted), CSV, plain addresses.
- **Languages**: English and Czech; currencies CZK, EUR, USD, GBP, CHF, PLN and more.

## Supported chains

| Status | Chains |
|---|---|
| Available | Bitcoin, Ethereum and 20 more EVM networks |
| In progress | Litecoin, Dogecoin, Bitcoin Cash, Solana, XRP Ledger, Tron, TON, Cardano, Cosmos ecosystem, Polkadot, Stellar, Sui, Aptos, NEAR, Hedera, Algorand, Tezos, Kaspa |

Privacy coins (Monero, shielded Zcash) are intentionally not supported: watching them requires
a private view key.

## Building

Requirements: JDK 21 or newer (the JDK bundled with Android Studio works) and the Android SDK
with platform 37.

```sh
./gradlew :app:assembleDebug        # debug APK in app/build/outputs/apk/debug/
./gradlew :app:installDebug         # install on a connected device
./gradlew check                     # unit tests, ktlint, detekt
```

The project is Kotlin Multiplatform with Compose Multiplatform. Only the Android app is built
today; iOS targets are compiled in CI on macOS so that a future iOS app stays cheap. See
[CLAUDE.md](CLAUDE.md) for the architecture.

## Verifying release builds

Release APKs are published on GitHub Releases together with their SHA-256 checksums.
Verify a download with:

```sh
sha256sum WatchWallet-<version>.apk
apksigner verify --print-certs WatchWallet-<version>.apk
```

The signing certificate fingerprint will be published here with the first release.
Reproducible builds for F-Droid are a project goal.

## Support the project

WatchWallet is free and has no ads or tracking. If you find it useful, you can donate. These
are the **only** donation addresses; the app shows exactly the same ones
(`feature/donate/.../DonationAddresses.kt`):

- **BTC:** `bc1q474j9j3uc4kk48uphhvwd284v9jzmkkpf0e583`
- **ETH:** `0x45A92396902ED0F49ae39f2F94c3f8f084501656`

## Contributing and translations

See [CONTRIBUTING.md](CONTRIBUTING.md). Security issues: see [SECURITY.md](SECURITY.md).

## License

[GNU General Public License v3.0](LICENSE).
