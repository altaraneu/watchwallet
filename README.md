# WatchWallet

**An open-source, watch-only crypto portfolio tracker for Android.**

WatchWallet tracks balances, transaction history and portfolio value using **public addresses
and extended public keys only**. It never asks for a seed phrase or private key and cannot sign or
send anything; input that looks like one is rejected ([limits](SECURITY.md)).

- **Private by design** – no accounts, no backend, no analytics, no ads, no Google Play Services.
  Wallets, labels and settings stay on your device. To read balances and prices the app contacts
  public blockchain and price services directly, and they can see the addresses or coins requested
  and your IP address. Every endpoint is listed in [PRIVACY.md](PRIVACY.md). You can use your own
  Bitcoin server and a SOCKS5 proxy. Server list updates are off unless you turn them on, and new
  servers need your approval first.
- **Multichain, one design** – Bitcoin, Ethereum and EVM networks, Solana, XRP and more in one
  consistent interface.
- **Paste and done** – paste an address, an xpub or a name; WatchWallet detects the chain.
- **Unlimited** – no limit on wallets, addresses, groups or chains.

> **Status:** alpha. Version 1.1.0 is in a closed test on Google Play. Expect bugs, and do not rely
> on it as your only record of your funds. See [CHANGELOG.md](CHANGELOG.md).

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
- **Languages**: Arabic, Bengali, Chinese (Simplified), Czech, English, French, German, Hindi,
  Indonesian, Japanese, Portuguese, Russian and Spanish. Translations other than English and Czech
  are machine-made and await human review. Currencies CZK, EUR, USD, GBP, CHF, PLN and more.

## Supported chains

- Bitcoin, Ethereum and 20 more EVM networks
- Litecoin, Dogecoin, Bitcoin Cash, Solana, XRP Ledger, Tron, TON, Cardano
- Cosmos Hub, Osmosis, Celestia, Injective, Polkadot, Kusama
- Stellar, Sui, Aptos, NEAR, Hedera, Algorand, Tezos, Kaspa

All of these are in the app, but they are not equally well tested in this alpha. Some networks have
no public history service, so they show balances without full history.

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

The Google Play version is distributed as App Bundles, signed by Google with this certificate:

```
SHA-256: AD:53:D1:37:FC:94:B5:7F:13:5E:12:6F:35:38:D9:61:B5:47:91:9D:86:24:C9:22:E1:F4:34:3E:AB:BC:F2:01
```

Check it with `apksigner verify --print-certs` on the APK from your device. Play builds also carry a
post-quantum signature; on an older Java, `apksigner` stops with "ML-DSA KeyFactory not available".

**What this does and does not prove.** The fingerprint shows who signed the app. It does not prove
that the app was built from this source code, and today nobody else can confirm that. Three local
builds of one commit were byte-identical, but on one Windows machine with one JDK. Still unproven:
whether another operating system or JDK gives the same bytes, and whether the APK on Play matches a
local build, which has never been compared. Dependency checksums are not pinned either:
`gradle/verification-metadata.xml` is not committed yet. See
[docs/reproducible-builds.md](docs/reproducible-builds.md). No APK is published on GitHub Releases yet;
signed APKs with SHA-256 checksums, a build verified across machines and F-Droid are the planned ways to
close this gap.

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
