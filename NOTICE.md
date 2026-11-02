# Third-party notices

## Icons

- **App icon**: original work of the WatchWallet project, GPL-3.0.
- **UI icons** (`core/designsystem/.../composeResources/drawable/ic_*.xml`):
  [Material Symbols](https://github.com/google/material-design-icons), Apache License 2.0.
- **Coin and network icons** (`chain_*.png`), resized to 96×96:
  - `chain_{btc,eth,bnb,avax,gno,etc,ltc,doge,bch,sol,xrp,trx,ada,atom,dot,ksm,xlm,algo,xtz,usdc,usdt,dai,chz,ankr,link,uni,wbtc}.png`:
    [spothq/cryptocurrency-icons](https://github.com/spothq/cryptocurrency-icons), CC0 1.0.
  - `chain_{arbitrum,optimism,base,zksync,linea,scroll,mantle,cronos,sonic,celo,hyperevm,sei,blast,ton,osmosis,aptos,near,hedera,polygon,shib,pepe,sui}.png`:
    [trustwallet/assets](https://github.com/trustwallet/assets), MIT License, Copyright (c) 2019-2023 Trust Wallet.

  Coin and network logos are trademarks of their respective owners and are used only to
  identify the corresponding network or asset. Their use does not imply endorsement.

## TradingView Lightweight Charts

Charts use [TradingView Lightweight Charts](https://www.tradingview.com/lightweight-charts/)
v5.2.1, Apache License 2.0 (`core/chart/.../files/chart/LIGHTWEIGHT-CHARTS-LICENSE.txt`).

> TradingView Lightweight Charts™
> Copyright (с) 2025 TradingView, Inc. https://www.tradingview.com/

The TradingView attribution logo with a link to tradingview.com is shown on every chart.

## BIP-39 word lists

`core/security/.../Bip39Wordlists.kt` holds the official word lists of
[BIP-39](https://github.com/bitcoin/bips/tree/master/bip-0039), MIT License, Copyright (c) 2013 Marek
Palatinus, Pavol Rusnak, Aaron Voisine and Sean Bowe. They are used only to recognise and reject
seed phrases.

## Libraries

See `gradle/libs.versions.toml` for all dependencies; each is distributed under its own
open-source license (Apache 2.0, MIT or compatible).
