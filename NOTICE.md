# Third-party notices

## Icons

- **App icon**: original work of the WatchWallet project, GPL-3.0.
- **UI icons** (`core/designsystem/.../composeResources/drawable/ic_*.xml`):
  [Material Symbols](https://github.com/google/material-design-icons), Apache License 2.0.
- **Coin and network logos** (`chain_*.png`), scaled to 96×96 by `scripts/fetch_logos.py` from one
  pinned commit of [trustwallet/assets](https://github.com/trustwallet/assets) (see
  `scripts/logos.lock.tsv`), MIT License, Copyright (c) 2019-2023 Trust Wallet; the licence ships in
  the app as `core/designsystem/.../composeResources/files/licenses/trustwallet-assets-LICENSE.txt`.

  That licence covers the repository; the marks themselves remain trademarks of their respective
  owners. They are used only to identify the corresponding network or asset, and their use does not
  imply endorsement.

## TradingView Lightweight Charts

Charts use [TradingView Lightweight Charts](https://www.tradingview.com/lightweight-charts/)
v5.2.1, Apache License 2.0 (`core/chart/.../files/chart/LIGHTWEIGHT-CHARTS-LICENSE.txt`).

> TradingView Lightweight Charts™
> Copyright (с) 2025 TradingView, Inc. https://www.tradingview.com/

The TradingView attribution logo with a link to tradingview.com is shown on every chart.

## Libraries

See `gradle/libs.versions.toml` for all dependencies; each is distributed under its own
open-source license (Apache 2.0, MIT or compatible).
