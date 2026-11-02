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

The app never asks for a seed phrase or private key and has no use for one. Everything you type,
paste, scan or import is checked first, on every change of the field. Input that looks like key
material is rejected: the field is cleared, a warning explains why, the text is not saved, logged or
sent, and the clipboard is cleared if it holds the same text. Fields that take such text tell the
keyboard not to learn from them (Android; a keyboard can ignore this).

This check recognises formats. It cannot recognise everything, and it is not a guarantee:

- **A Solana secret of 32 bytes in base58 looks exactly like a Solana address.** The app cannot tell
  them apart. Pasted by mistake, such a key is treated as an address and sent to the Solana server.
- **Sui and Aptos addresses have the same form as the private key of Ethereum and many other chains**
  (`0x` and 64 hexadecimal characters). That form is rejected everywhere, except in the separate
  **Add a Sui or Aptos address** route, which you open deliberately: you pick the chain, read a warning
  and confirm, and only then is such a value accepted. A backup entry that names Sui or Aptos needs
  the same confirmation, one by one. **A private key entered through that route would reach the public
  server of that chain, and would be stored.** Never enter a private key there.
- Word lists are found through the official BIP-39 lists and, for other lists, by their shape. Fewer
  than 11 words, or an unknown word list written like a sentence, can pass.
- Passwords for encrypted backups are not checked. They are never stored or sent.
- Your keyboard, clipboard manager or another app may already have seen what you pasted before the
  app could react.

## Network endpoints

All requests are HTTPS GET/POST calls without cookies, accounts or tracking headers. The one
exception is your own server, which may use plain HTTP on `.onion`, localhost or your local network
(see [Your own server and proxy](#your-own-server-and-proxy)); the app refuses plain HTTP to any other
host. Responses are not cached on disk by the HTTP client. Redirects are not followed, so a service
moving elsewhere cannot lead the app to a host that is not listed here.

The servers listed below are those built into the app. A signed server list can replace them
without an app update (see [Server list updates](#server-list-updates)); its current content is
public in [`config/endpoints.source.json`](config/endpoints.source.json).

### Blockchain data (sends the individual addresses you track)

| Service | Used for | Data sent |
|---|---|---|
| `mempool.space/api` (Esplora), fallback `blockstream.info/api` when it fails | Bitcoin balances and history | Individual Bitcoin addresses. For an xpub, the addresses are derived **on the device** and the xpub itself is never sent. They are queried in bursts, up to 4 at a time and 80 to 230 ms apart, all from the same IP address, so **the server can link them to each other and work out that they belong to one wallet**. A proxy or Tor hides your IP address, not this link. |
| Public JSON-RPC nodes per EVM network (`*.publicnode.com`, `mainnet.era.zksync.io`, `rpc.linea.build`, `rpc.scroll.io`, `rpc.mantle.xyz`, `rpc.gnosischain.com`, `evm.cronos.org`, `rpc.soniclabs.com`, `forno.celo.org`, `etc.etcdesktop.com`, `rpc.hyperliquid.xyz`, `rpc.berachain.com`, `evm-rpc.sei-apis.com`, `mainnet.unichain.org`, `rpc.blast.io`); when one fails, the alternative of that network (`eth.drpc.org`, `polygon.drpc.org`, `zksync.drpc.org`, `hyperliquid.drpc.org`, `bsc-dataseed.binance.org`, `arb1.arbitrum.io`, `mainnet.optimism.io`, `mainnet.base.org`, `api.avax.network`, or `*.publicnode.com`) | Native balance and activity check on the EVM networks where the wallet is active; all networks on the first sync, once a day and on a manual refresh | The EVM address |
| Blockscout instances (`eth.`, `base.`, `arbitrum.`, `polygon.`, `zksync.`, `celo.`, `etc.`, `unichain.blockscout.com`, `explorer.optimism.io`, `gnosisscan.io`, `scrollscan.com`, `explorer.mantle.xyz`) | Token balances and transaction history | The EVM address |
| `litecoinspace.org/api` (Esplora) | Litecoin balances and history | Individual Litecoin addresses. Xpub-derived addresses go out up to 4 at a time from the same IP address, so the server can link them, as with Bitcoin. |
| `dogecoin.atomicwallet.io`, `blockbook.bch.zelcore.io` (public Blockbook instances, address endpoint only) | Dogecoin and Bitcoin Cash | Individual addresses; the xpub endpoint is never used. Xpub-derived addresses go out up to 3 at a time from the same IP address, so the server can link them, as with Bitcoin. |
| `api.mainnet-beta.solana.com` | Solana SOL and SPL/Token-2022 balances, history | The Solana address and transaction signatures |
| `xrplcluster.com` (fallback `s1.ripple.com`) | XRP balance, trust lines, history, reserve | The XRP account |
| `api.trongrid.io` | TRX, TRC-10, TRC-20 balances and history | The Tron address; TRC-10 token ids |
| `toncenter.com/api/v3` | TON balance, jettons, history | The TON address |
| `api.koios.rest` | Cardano balances, native tokens, history | The stake or payment address; transaction hashes |
| `rest.cosmos.directory` | Cosmos Hub, Osmosis, Celestia, Injective balances, staking, history | The bech32 address |
| `polkadot-asset-hub-rpc.polkadot.io`, `kusama-asset-hub-rpc.polkadot.io`, `rpc.polkadot.io`, `kusama-rpc.polkadot.io` | Polkadot / Kusama balances (Asset Hub plus relay-chain leftovers) | A storage key containing the account id |
| `horizon.stellar.org` | Stellar balances, trustlines, payments | The Stellar account |
| `graphql.mainnet.sui.io` | Sui balances and history (GraphQL) | The Sui address, only through the separate route or a confirmed backup entry (see [Keys](#keys)) |
| `api.mainnet.aptoslabs.com` | Aptos balance and history | The Aptos address, only through the separate route or a confirmed backup entry (see [Keys](#keys)) |
| `rpc.mainnet.near.org` | NEAR balance | The NEAR account id |
| `mainnet-public.mirrornode.hedera.com` | Hedera balances, tokens, history | The Hedera account id; token ids |
| `mainnet-idx.algonode.cloud` | Algorand balances, ASAs, history | The Algorand address; asset ids |
| `api.tzkt.io` | Tezos balance, tokens, operations | The Tezos address |
| `api.kaspa.org` | Kaspa balance and history | The Kaspa address |

### Prices and market data (no addresses are ever sent)

These services do not get your addresses, but **they see which coins you hold or watch, together with
your IP address**, because the app asks them for exactly those prices. The same goes for the logo host
below, and **logos are loaded from it by default**: turn them off in Settings → Privacy if you do not
want that. A proxy or Tor hides your IP address from them.

| Service | Used for | Data sent |
|---|---|---|
| `api.coingecko.com` | Current prices, price history, market overview | Coin ids (for example `bitcoin`), token contract addresses (public token identifiers, not your address), selected currency |
| `api.coingecko.com/api/v3/search` | Watchlist search in Markets | What you type in the search field, once it has passed a check: nothing that looks like a seed phrase, key or address, and at most 5 words and 40 characters |
| `api.coingecko.com` with your own API key | Higher rate limits, only if you enter an optional demo key in Settings → Network | The key, in a request header, with every request to CoinGecko. It is stored on the device like other settings. |
| `raw.githubusercontent.com` (Trust Wallet assets repository) | Coin and token logos, cached on the device; can be turned off in Settings → Privacy | Which coin or token logo is needed (coin name or token contract), never your addresses |
| `api.binance.com`, fallback `data-api.binance.vision` (public market data, no account) | Current prices and price history of well-known coins and tokens (tried before CoinGecko), minute and hourly prices for the part of a value chart on screen, candle charts of pairs listed there | Coin symbols only (for example `BTCUSDT`) and the requested time span, which shows which period you are looking at, not what you hold beyond the coins asked for; candle charts also open a WebSocket to `stream.binance.com` for the viewed pair |
| `api.kraken.com`, `ws.kraken.com` | Candlestick charts and live price updates of pairs Binance does not list (for example Tether) or quoted in BTC or ETH | Trading pair (for example `BTC/EUR`) and candle interval |
| `api.frankfurter.dev` | ECB reference exchange rates for currencies the exchanges do not quote (CZK, PLN…) | Currency codes |

### Server list updates

| Service | Used for | Data sent |
|---|---|---|
| `raw.githubusercontent.com` (`altaraneu/watchwallet`), fallback `cdn.jsdelivr.net` when it fails | Signed list of public blockchain servers, at most once a day at app start | Nothing about you or your wallets: a plain download of a public file. As with any download, GitHub or jsDelivr see your IP address (or your proxy's) and the time. |

The list lets the project replace a public server that died or went bad without an app update. It
contains only chain names, API types and https addresses of servers, never code. The app accepts it
only with a valid signature of the project's key, and only for API types it already knows. Once
applied, the addresses you track go to the servers it names instead of those in the table above,
for the same purpose and with the same data. The history of the list is public in this repository.
If the list expires, the app returns to its built-in servers. Details:
[`docs/endpoints-config.md`](docs/endpoints-config.md).

To turn this off, disable **Update server list automatically** in Settings → Network and restart the
app: it then makes no such request and uses only the built-in servers.

### Your own server and proxy

- **Custom Esplora server (Settings → Network).** You can replace the default Bitcoin Esplora
  servers with your own (for example a self-hosted mempool or Esplora instance). The app then
  sends the Bitcoin addresses described above to that server instead of `mempool.space` /
  `blockstream.info`. Your own server always takes precedence over the server list. It must use
  HTTPS; plain HTTP is accepted only for `.onion` addresses, localhost and private network addresses
  (`10.x`, `172.16-31.x`, `192.168.x`, `169.254.x`, `.local`, `fc00::/7`, `fe80::/10`). On a local
  network without HTTPS, others on that network can read the addresses.
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

## Screen and home screen

Screenshots and the task switcher preview are blocked by default; you can allow them in Settings.
While the app lock is on they stay blocked regardless of that switch, and the home-screen widget shows
dots instead of amounts.

## Exports

Exports are created only on your request and delivered through the Android share sheet or the
system file picker; the app never uploads them anywhere. Formats:

- **Backup (JSON):** wallets (addresses or extended public keys), names, groups, tags and notes.
  Never balances or key material.
- **CSV / addresses only:** the same wallets as a table or plain list; CSV can optionally include
  current values. A CSV or list with Sui or Aptos addresses cannot be imported again, because those
  addresses look like private keys there; use the JSON backup for them.
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
