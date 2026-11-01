# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Project skeleton (milestone 1): Kotlin Multiplatform + Compose Multiplatform, Android app.
- Bottom navigation: Portfolio, Wallets, Markets, Settings.
- Settings: display currency (15 fiat and crypto currencies, default from the device region),
  theme (system, light, dark), dynamic color, app language (English, Czech), discreet mode.
- Discreet mode hides every amount in the app.
- "Support the project" screen with BTC and ETH donation addresses and copy buttons.
- Local storage: Room (SQLite, WAL) for wallets and groups, DataStore for settings.
- Cloud backup disabled for all app data; device-to-device transfer allowed.
- App icon.
- Bitcoin (addresses, xpub/ypub/zpub and descriptors with local derivation) and 21 EVM networks
  with tokens and spam filtering.
- 18 more chains: Litecoin, Dogecoin, Bitcoin Cash, Solana, XRP, Tron, TON, Cardano, Cosmos Hub,
  Osmosis, Celestia, Injective, Polkadot, Kusama, Stellar, Sui, Aptos, NEAR, Hedera, Algorand,
  Tezos and Kaspa. Sui/Aptos addresses require choosing the chain explicitly.
- Secret guard: seed phrases and private keys in any supported format are rejected before
  anything else happens.
- Portfolio with groups, allocation and value history; Markets with watchlist, live Kraken
  candles (1m to 1W), tap for max/min/average.
- Charts by TradingView Lightweight Charts, bundled offline.
- Import (JSON, CSV, plain lists, QR) and export (encrypted JSON backup, CSV, addresses,
  transaction history CSV).
- App lock, screenshot blocking, Tor/SOCKS5 proxy, custom Bitcoin server, background refresh,
  home screen widget.
- Older Bitcoin history is backfilled in chunks after the first sync.
- Adding a Cosmos account also adds its active sister-chain accounts.
- Screen reader summaries for charts.

### Fixed
- Charts were invisible (zero-height WebView viewport).
- Polkadot/Kusama balances now come from Asset Hub (relay chain showed almost nothing).
- Sui uses the GraphQL service; Dogecoin/Bitcoin Cash use working Blockbook instances;
  Bitcoin falls back to blockstream.info; one failing EVM network no longer fails the wallet.
- Live candles reconnect after sleep and fill the gap; chart times use the local time zone.
- Markets no longer spins forever while chart history loads.
