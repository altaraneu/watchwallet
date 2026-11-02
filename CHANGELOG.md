# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Value charts refine like a map: they draw at once from stored prices, then load better prices
  only for the visible window (one bar per minute for views up to 8 hours, per hour up to 14 days,
  per day beyond). Zoom in as far as single minutes, also far in the past. New 1H range.
- Candle charts follow the zoom with the candle width, and load older candles when panning back.
  Kraken pairs (for example Tether) serve 720 candles per interval: at that limit the chart moves
  to a coarser interval, which reaches further back.
- While a past point is selected, the header also shows the current value.

### Changed
- Zoom and pan light the matching range chip (none when no chip fits); tapping a chip goes to its
  range, also after moving away from the selected one.
- The chart header and line colour describe what is visible; the chart stays readable while prices
  arrive, without a progress bar.
- Removed the data source note under the candle chart; sources are listed in `PRIVACY.md`.

### Fixed
- The crosshair stays on the selected point through pan, zoom and refreshes, and its line and axis
  label show that point's value.
- The value series ends at the present even when no price changed for a while.
- The chart no longer moves vertically when the deposits line or the candle details appear.
- Price axis ticks follow the visible range, so stablecoins and very cheap coins get readable ticks.
- Price changes of cheap coins show enough decimals instead of `-0.00`.
- The price in the Market section follows the live price.

## [1.0.0] - 2026-11-02

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
- Coin detail as the one place for a coin: price and value charts, holdings across wallets,
  activity, cost basis and unrealized profit/loss.
- Cost basis per transaction (filled from the historical price, editable as total or per coin).
- Performance excluding deposits and withdrawals under the value change.
- Full value history with range windows; charts keep pan and zoom across refreshes.
- Coin and token icons (optional, Trust Wallet assets repository, cached on the device).
- Binance prices and candles first, CoinGecko and Kraken as fallbacks.
- Staking: Solana stake accounts, delegated POL, Cosmos rewards.
- Group field when adding a wallet; Add wallet in the Portfolio top bar.
- Wallet detail shows the address with copy and QR code, for example to receive a payment.
- JSON backups include notes and the cost basis entered by the user.
- Settings links to the privacy policy; failed price history requests wait before retrying and the chart loading hint stops after 15 seconds.
- An address that already belongs to an extended key wallet can be subtracted from it, so it is
  not counted twice while both wallets can be followed separately.
- Google Play installs ask for a rating every 20 to 30 launches, until "I already did".
- Languages: Arabic, Bengali, Chinese (Simplified), Czech, English, French, German, Hindi,
  Indonesian, Japanese, Portuguese, Russian and Spanish.

### Fixed
- Charts were invisible (zero-height WebView viewport).
- Polkadot/Kusama balances now come from Asset Hub (relay chain showed almost nothing).
- Sui uses the GraphQL service; Dogecoin/Bitcoin Cash use working Blockbook instances;
  Bitcoin falls back to blockstream.info; one failing EVM network no longer fails the wallet.
- Live candles reconnect after sleep and fill the gap; chart times use the local time zone.
- Markets no longer spins forever while chart history loads.
