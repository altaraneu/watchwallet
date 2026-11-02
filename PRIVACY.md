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
| `mempool.space/api` (Esplora), fallback `blockstream.info/api` when it fails | Bitcoin balances and history | Individual Bitcoin addresses. For an xpub, the addresses derived **on the device** are queried one by one with random timing; the xpub itself is never sent. |
| Public JSON-RPC nodes per EVM network (`*.publicnode.com`, `mainnet.era.zksync.io`, `rpc.linea.build`, `rpc.scroll.io`, `rpc.mantle.xyz`, `rpc.gnosischain.com`, `evm.cronos.org`, `rpc.soniclabs.com`, `forno.celo.org`, `etc.etcdesktop.com`, `rpc.hyperliquid.xyz`, `rpc.berachain.com`, `evm-rpc.sei-apis.com`, `mainnet.unichain.org`, `rpc.blast.io`) | Native balance and activity check on each EVM network | The EVM address |
| Blockscout instances (`eth.`, `base.`, `optimism.`, `arbitrum.`, `polygon.`, `gnosis.`, `zksync.`, `scroll.`, `celo.`, `etc.`, `unichain.blockscout.com`, `explorer.mantle.xyz`) | Token balances and transaction history | The EVM address |

| `litecoinspace.org/api` (Esplora) | Litecoin balances and history | Individual Litecoin addresses (xpub-derived addresses one by one) |
| `dogecoin.atomicwallet.io`, `blockbook.bch.zelcore.io` (public Blockbook instances, address endpoint only) | Dogecoin and Bitcoin Cash | Individual addresses; the xpub endpoint is never used |
| `api.mainnet-beta.solana.com` | Solana SOL and SPL/Token-2022 balances, history | The Solana address and transaction signatures |
| `xrplcluster.com` (fallback `s1.ripple.com`) | XRP balance, trust lines, history, reserve | The XRP account |
| `api.trongrid.io` | TRX, TRC-10, TRC-20 balances and history | The Tron address; TRC-10 token ids |
| `toncenter.com/api/v3` | TON balance, jettons, history | The TON address |
| `api.koios.rest` | Cardano balances, native tokens, history | The stake or payment address; transaction hashes |
| `rest.cosmos.directory` | Cosmos Hub, Osmosis, Celestia, Injective balances, staking, history | The bech32 address |
| `polkadot-asset-hub-rpc.polkadot.io`, `kusama-asset-hub-rpc.polkadot.io`, `rpc.polkadot.io`, `kusama-rpc.polkadot.io` | Polkadot / Kusama balances (Asset Hub plus relay-chain leftovers) | A storage key containing the account id |
| `horizon.stellar.org` | Stellar balances, trustlines, payments | The Stellar account |
| `graphql.mainnet.sui.io` | Sui balances and history (GraphQL) | The Sui address (only after you pick Sui explicitly) |
| `api.mainnet.aptoslabs.com` | Aptos balance and history | The Aptos address (only after you pick Aptos explicitly) |
| `rpc.mainnet.near.org` | NEAR balance | The NEAR account id |
| `mainnet-public.mirrornode.hedera.com` | Hedera balances, tokens, history | The Hedera account id; token ids |
| `mainnet-idx.algonode.cloud` | Algorand balances, ASAs, history | The Algorand address; asset ids |
| `api.tzkt.io` | Tezos balance, tokens, operations | The Tezos address |
| `api.kaspa.org` | Kaspa balance and history | The Kaspa address |

### Prices and market data (no addresses are ever sent)

| Service | Used for | Data sent |
|---|---|---|
| `api.coingecko.com` | Current prices, price history, market overview | Coin ids (for example `bitcoin`), token contract addresses (public token identifiers, not your address), selected currency |
| `raw.githubusercontent.com` (Trust Wallet assets repository) | Coin and token logos, cached on the device; can be turned off in Settings → Privacy | Which coin or token logo is needed (coin name or token contract), never your addresses |
| `api.binance.com`, fallback `data-api.binance.vision` (public market data, no account) | Current prices and price history of well-known coins and tokens (tried before CoinGecko) | Coin symbols only (for example `BTCUSDT`); candle charts also open a WebSocket to `stream.binance.com` for the viewed pair |
| `api.kraken.com`, `ws.kraken.com` | Candlestick charts and live price updates | Trading pair (for example `BTC/EUR`) and candle interval |
| `api.frankfurter.dev` (fallback `api.frankfurter.app`) | ECB reference exchange rates for currencies Kraken does not quote (CZK, PLN…) | Currency codes |

### Your own server and proxy

- **Custom Esplora server (Settings → Network).** You can replace the default Bitcoin Esplora
  servers with your own (for example a self-hosted mempool or Esplora instance). The app then
  sends the Bitcoin addresses described above to that server instead of `mempool.space` /
  `blockstream.info`.
- **SOCKS5 proxy (Settings → Network, Android).** All HTTP and WebSocket traffic of the app can be
  routed through a SOCKS5 proxy, for example Orbot on `127.0.0.1:9050` for Tor. Host names are
  resolved by the proxy, not on the device. The option is not offered on platforms whose network
  stack cannot honour it yet.

## Block explorer links

Wallet and transaction screens offer links to public block explorers. They are opened **only when
you tap them**, in your own browser, outside the app; the app itself never contacts these sites.
The opened URL contains the address or transaction hash, so the explorer (and your browser) learns
which address or transaction you looked at. Explorers used:

- Bitcoin `mempool.space`; Litecoin `litecoinspace.org`; Dogecoin and Bitcoin Cash `blockchair.com`
- EVM networks: `etherscan.io`, `bscscan.com`, `arbiscan.io`, `optimistic.etherscan.io`,
  `basescan.org`, `polygonscan.com`, `snowtrace.io`, `explorer.zksync.io`, `lineascan.build`,
  `scrollscan.com`, `explorer.optimism.io`, `explorer.mantle.xyz`, `gnosisscan.io`, `cronoscan.com`, `sonicscan.org`,
  `celoscan.io`, `etc.blockscout.com`, `hyperevmscan.io`, `berascan.com`, `seitrace.com`,
  `uniscan.xyz`, `blastscan.io`
- Solana `solscan.io`; XRP `livenet.xrpl.org`; Tron `tronscan.org`; TON `tonviewer.com`;
  Cardano `cardanoscan.io`; Cosmos chains `www.mintscan.io`; Polkadot / Kusama
  `assethub-polkadot.subscan.io`, `assethub-kusama.subscan.io`; Stellar `stellar.expert`;
  Sui `suiscan.xyz`; Aptos `explorer.aptoslabs.com`; NEAR `nearblocks.io`; Hedera `hashscan.io`;
  Algorand `allo.info`; Tezos `tzkt.io`; Kaspa `explorer.kaspa.org`

The About section in Settings links to `www.tradingview.com` (chart library attribution) and the
project repository on `github.com`; these, too, open only on tap in your browser.

## Rating prompt

Apps installed from Google Play occasionally offer to rate WatchWallet. Only a tap opens
`play.google.com`, in the Play Store app or your browser. The app uses no Google Play Services
and learns nothing about your rating.

## Charts

Charts are rendered by TradingView Lightweight Charts, bundled inside the app. The chart view
has network access blocked; it does not load anything from the internet.

## Exports

Exports are created only on your request and delivered through the Android share sheet or the
system file picker; the app never uploads them anywhere. Formats:

- **Backup (JSON):** wallets (addresses or extended public keys), names, groups, tags and notes.
  Never balances or key material.
- **CSV / addresses only:** the same wallets as a table or plain list; CSV can optionally include
  current values.
- **Transaction history (CSV):** every stored transaction of the selected wallets (date, asset,
  amount, fee, transaction hash). This reveals your financial history: share it carefully.

**Password encryption (backup only).** With a password the JSON backup is encrypted with
AES-256-GCM. The key is derived from the password with PBKDF2-HMAC-SHA256, 600 000 iterations and
a random 16-byte salt; each export uses a random 12-byte IV, and GCM's authentication tag detects a
wrong password or a modified file. Salt, IV and parameters are stored in the file next to the
ciphertext (format `watchwallet-encrypted`, version 1). The password is not stored anywhere; a
lost password cannot be recovered. CSV and text exports are not encrypted.

## Deleting your data

WatchWallet (developer: Altaran) has no accounts and no server. The developer holds no data about you. Everything the app stores is on your device.

To delete your data:

- **One wallet:** open the wallet, tap the menu at the top, choose **Delete**.
- **Everything:** **Settings → Delete all wallets**. This removes all wallets, their history, groups and cost basis. Settings and the watchlist stay.
- **All app data:** uninstall the app, or clear its storage in Android settings.

What stays elsewhere: backups you exported are your own files, delete them yourself. Public blockchain data can't be deleted by anyone. The third-party services listed in this policy receive your addresses when the app loads balances. The developer doesn't control their logs, so ask them directly if you need to.

## Contact

Questions: open an issue on <https://github.com/altaraneu/watchwallet>.
