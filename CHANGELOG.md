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
- Server list updates: public blockchain servers can change without a new release, through a data
  file signed with BIP-340 Schnorr in the public repository. Data only, https only, known API types
  only; bundled servers always work offline and a custom server always wins. Can be turned off in
  Settings → Network. See `docs/endpoints-config.md`.
- `scripts/sign_endpoints.py` to sign and verify that file.
- "Powered by CoinGecko" on the Markets list, coin details and watchlist search, only while something
  displayed there really came from CoinGecko, as its API terms require.

### Changed
- Much faster EVM refresh: only networks where the wallet is active are refreshed; all networks are
  searched on the first sync, once a day and on a manual refresh.
- Balances of each EVM network appear as soon as they are known, before its history, and a slow or
  failing network no longer delays the others. A network that fails keeps its last known data; a
  blocked token indexer no longer holds back the native balance.
- A refresh skips the history of an EVM network when its balances and counters did not change.
- Requests fail fast: 20 s deadline, one quick retry, no retry of client errors, `Retry-After`
  honoured up to 10 s. Failing hosts are remembered for the session. EVM networks can fall back to
  a second RPC host.
- Current prices appear from the local cache and refresh at app start without waiting for wallets;
  known assets update first, long-tail tokens as they arrive. Screens share price requests. Older
  chart history loads in the background.
- Zoom and pan light the matching range chip (none when no chip fits); tapping a chip goes to its
  range, also after moving away from the selected one.
- The chart header and line colour describe what is visible; the chart stays readable while prices
  arrive, without a progress bar.
- Removed the data source note under the candle chart; sources are listed in `PRIVACY.md`.

### Fixed
- Older history of EVM wallets and Cardano stake and payment addresses is now fetched in chunks after
  the first sync instead of staying cut off.
- Redirects are no longer followed, so addresses cannot reach hosts missing from the privacy policy;
  Optimism, Gnosis and Scroll now use the instances their old addresses redirected to.
- A failed CoinGecko token request no longer hides that token's price for 12 hours.
- Value charts no longer project balances without transactions (staked coins, rewards) over the
  time before they existed: stake moves count as a pair, and such balances start at the wallet's
  first transaction on that chain. Wallets with cut-off history keep the old projection.
- Extended keys: an address that newly enters the gap window has its whole history read, and stored
  transactions it took part in are corrected.
- Solana transactions that change no balance no longer hold back the loading of older history.
- Token lists are read to their end on Sui, Tezos and TON. Where a limit is still hit (also XRP trust
  lines, Algorand assets, Hedera tokens) known tokens stay and the wallet says so, instead of tokens disappearing.
- Short pages from Aptos and Kaspa no longer leave holes in history. Malformed answers on Algorand,
  XRP, Kaspa, NEAR, Polkadot, Aptos, Tezos and TON fail the sync instead of showing a zero balance.
- A failed Cosmos history request no longer marks the history as cut off forever.
- Wallets show "Some networks could not be updated" when only part of a sync failed, and say the coin
  balance is current when only tokens or history failed. Pull to refresh tries all failed hosts again.
- Failures no longer read as empty data: a Solana node error no longer drops staked SOL, a held Tron
  token without a recent transfer stays, and malformed answers fail the sync instead of showing a
  zero balance (JSON-RPC, Tron, Dogecoin, Bitcoin Cash, Litecoin). Algorand and Hedera holdings are
  not crowded out by empty token entries.
- Cut-off history is marked as such on Cosmos, and on paging ends of Stellar, Tezos and Blockbook.
- Prices no longer follow an exchange that halted trading or froze; such assets use the next source,
  and so do assets priced more than 10 % away from it. This holds for refined chart windows too.
- The crosshair stays on the selected point through pan, zoom and refreshes, and its line and axis
  label show that point's value.
- The value series ends at the present even when no price changed for a while.
- The chart no longer moves vertically when the deposits line or the candle details appear.
- Price axis ticks follow the visible range, so stablecoins and very cheap coins get readable ticks.
- Price changes of cheap coins show enough decimals instead of `-0.00`.
- The price in the Market section follows the live price.

### Security

Fixes from the review in `docs/security/audit-2026-11-02.md`.

- The Android HTTP engine really stops following redirects now; until this change it still followed
  them, despite the entry under Fixed. Every bundled and fixed endpoint was checked to answer without one.
- Cardano stake wallets: a payment address from the server is offered only if it carries the wallet's
  stake key, checked at sync and on every display; otherwise only the stake key is shown. The app cannot
  check the payment part of that address, so the screen now says to compare it with your wallet app.
  Such an address no longer counts as "contained" in the wallet when adding another one.
- A backup can no longer be written empty, or empty an older file, after the device rotated or the app
  was killed in the file picker. Without its content nothing is written, the file the picker created is
  removed if empty, and the failure is shown. Cancelling the picker no longer reports a failure.
- Server list: Settings → Network shows the version, dates and every host in use, and the app says once
  when the hosts change. A list valid for more than 200 days is ignored, each build has a version floor,
  and the verifier can pin an offline recovery key. The documents now state what a stolen signing key
  could do, and that updates stay on by default by the owner's decision.
  **Before release:** the owner must generate that recovery key and enter it; none is set.
- CSV exports: cells a spreadsheet could run as a formula are marked as text; import restores the
  original and negative amounts stay numbers.
- Export password: must be repeated and have at least 12 characters.
- Discreet mode also hides amounts in the add-wallet preview, hidden assets, the allocation shares,
  the "excluding deposits" line of charts and the hints of the cost dialog.
- Deleting a wallet also removes the tokens and prices only it used and the cached logos. Deleted
  database content is overwritten, the database is compacted and its log emptied. The remaining limits
  are in `PRIVACY.md`.
- `sign_endpoints.py` compares with the published file, not only the local one, and refuses an expiry
  apps would ignore.
- Large exports go to the share sheet as a file instead of intent text, and imported files are read
  with a limit rather than read whole and then measured.
- A cost reference in a backup is checked: its asset id must look like one, and neither part may hide a
  seed phrase.
- `PRIVACY.md` says that JSON backups hold cost basis with transaction references and subtract links.
- Addresses, token ids and cursors are encoded wherever they become part of a request URL, so a hostile
  value stored by an older backup import cannot change which path of a host is asked. Dot segments and
  empty values are refused, and a Hedera paging cursor can no longer name another host.

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
