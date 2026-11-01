# Privacy

WatchWallet has no servers, no accounts and no analytics. This document lists **every**
network endpoint the app contacts and exactly what it sends. It is updated with every change
that adds or modifies network access; a pull request that adds an endpoint without updating
this file will not be merged.

## What stays on your device

- Tracked addresses, extended public keys, labels, groups, notes and tags.
- Cached balances, transactions and prices (a local SQLite database).
- Settings.

Nothing is uploaded to any cloud backup: Android cloud backup is disabled for all app data.
Direct device-to-device transfer when you set up a new phone is allowed (Android 12+).

## Keys

The app never asks for, accepts or stores a seed phrase or private key. Input that looks like
one is rejected, is not stored anywhere (including logs) and is never sent over the network.

## Network endpoints

All requests are plain HTTPS GET/POST calls without cookies, accounts or tracking headers.
Responses are not cached on disk by the HTTP client.

### Blockchain data (sends the individual addresses you track)

| Service | Used for | Data sent |
|---|---|---|
| `mempool.space/api` (Esplora) | Bitcoin balances and history | Individual Bitcoin addresses. For an xpub, the addresses derived **on the device** are queried one by one with random timing; the xpub itself is never sent. |
| Public JSON-RPC nodes per EVM network (`*.publicnode.com`, `mainnet.era.zksync.io`, `rpc.linea.build`, `rpc.scroll.io`, `rpc.mantle.xyz`, `rpc.gnosischain.com`, `evm.cronos.org`, `rpc.soniclabs.com`, `forno.celo.org`, `etc.rivet.link`, `rpc.hyperliquid.xyz`, `rpc.berachain.com`, `evm-rpc.sei-apis.com`, `mainnet.unichain.org`, `rpc.blast.io`) | Native balance and activity check on each EVM network | The EVM address |
| Blockscout instances (`eth.`, `base.`, `optimism.`, `arbitrum.`, `polygon.`, `gnosis.`, `zksync.`, `scroll.`, `celo.`, `etc.`, `unichain.blockscout.com`, `explorer.mantle.xyz`) | Token balances and transaction history | The EVM address |

### Prices and market data (no addresses are ever sent)

| Service | Used for | Data sent |
|---|---|---|
| `api.coingecko.com` | Current prices, price history, market overview | Coin ids (for example `bitcoin`), token contract addresses (public token identifiers, not your address), selected currency |
| `api.kraken.com`, `ws.kraken.com` | Candlestick charts and live price updates | Trading pair (for example `BTC/EUR`) and candle interval |
| `api.frankfurter.dev` (fallback `api.frankfurter.app`) | ECB reference exchange rates for currencies Kraken does not quote (CZK, PLN…) | Currency codes |

Planned: configurable endpoints per chain (own Electrum/Esplora server, custom RPC) and a
Tor/SOCKS5 proxy option for all traffic.

## Charts

Charts are rendered by TradingView Lightweight Charts, bundled inside the app. The chart view
has network access blocked; it does not load anything from the internet.

## Exports

Exports are created only on your request and delivered through the Android share sheet or the
system file picker. Optional password encryption will be documented here when it ships.

## Contact

Questions: open an issue on <https://github.com/altaraneu/watchwallet>.
