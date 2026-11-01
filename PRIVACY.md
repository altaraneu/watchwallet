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

| Version | Endpoint | Purpose | Data sent |
|---|---|---|---|
| 0.1.0 | – | No network access is made yet. | – |

Planned for upcoming milestones (all configurable in Settings):

- **Blockchain data** (Esplora / Electrum servers, Blockscout, public RPC nodes): individual
  addresses or script hashes you track, to fetch balances and history. Extended public keys
  are **never** sent; addresses are derived on the device.
- **Prices** (CoinGecko, with fallbacks): asset identifiers and the selected currency. No
  addresses.
- **Tor / SOCKS5**: all traffic can be routed through a proxy such as Orbot.

## Exports

Exports are created only on your request and delivered through the Android share sheet or the
system file picker. Optional password encryption will be documented here when it ships.

## Contact

Questions: open an issue on <https://github.com/altaraneu/watchwallet>.
