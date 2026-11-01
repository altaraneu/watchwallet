# WatchWallet

**An open-source, watch-only crypto portfolio tracker for Android.**

WatchWallet tracks balances, transaction history and portfolio value using **public addresses
and extended public keys only**. It never asks for, accepts or stores a seed phrase or private
key: it cannot sign or send anything.

- **Private by design** – no accounts, no backend, no analytics, no ads, no Google Play Services.
  Everything stays on your device. Every network endpoint is listed in [PRIVACY.md](PRIVACY.md)
  and configurable in the app.
- **Multichain, one design** – Bitcoin, Ethereum and EVM networks, Solana, XRP and more in one
  consistent interface.
- **Paste and done** – paste an address, an xpub or a name; WatchWallet detects the chain.
- **Unlimited** – no limit on wallets, addresses, groups or chains.

> **Status:** early development (milestone 1 of 8: app skeleton). Wallet tracking arrives with
> milestone 2 (Bitcoin). See [CHANGELOG.md](CHANGELOG.md).

## Supported chains (planned)

| Milestone | Chains |
|---|---|
| 2 | Bitcoin (addresses, xpub/ypub/zpub, output descriptors) |
| 4 | Ethereum and EVM networks (Arbitrum, Base, Optimism, Polygon, BNB Chain, …) |
| 5 | Solana, XRP Ledger, Litecoin, Dogecoin, Bitcoin Cash, Tron |
| 6 | TON, Cardano, Cosmos ecosystem, Polkadot ecosystem |
| 7 | Stellar, Sui, Aptos, NEAR, Hedera, Algorand, Tezos, Kaspa |

Privacy coins (Monero, shielded Zcash) are intentionally not supported: watching them requires
a private view key.

## Building

Requirements: JDK 17 or newer (the JDK bundled with Android Studio works) and the Android SDK
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

- **BTC:** `<BTC_ADDRESS>`
- **ETH:** `<ETH_ADDRESS>`

## Contributing and translations

See [CONTRIBUTING.md](CONTRIBUTING.md). Security issues: see [SECURITY.md](SECURITY.md).

## License

[GNU General Public License v3.0](LICENSE).
