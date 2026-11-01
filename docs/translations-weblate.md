# Translations on Weblate

WatchWallet's strings are Compose Multiplatform resources in Android `strings.xml` format, one
file per module. Weblate is set up once the repository is public (planned for version 1.0); this
page is the checklist for the maintainer.

## Project settings

- Version control: Git, repository `https://github.com/altaraneu/watchwallet`, branch `main`.
- Push: through pull requests from a Weblate fork (never direct pushes to `main`).
- Source language: English. License: GPL-3.0-only (same as the code).
- Add-ons "Cleanup translation files" and "Squash Git commits" (one commit per language and day).
- "Manage strings" only for maintainers; translators edit translations only.

## Components

Create one component per row, file format **Android String Resource**, monolingual
(base file = the English file). New translations start from the base file; Weblate creates the
`values-<lang>` folder.

| Component | File mask | Monolingual base file |
|---|---|---|
| `core-designsystem` | `core/designsystem/src/commonMain/composeResources/values-*/strings.xml` | `core/designsystem/src/commonMain/composeResources/values/strings.xml` |
| `core-ui` | `core/ui/src/commonMain/composeResources/values-*/strings.xml` | `core/ui/src/commonMain/composeResources/values/strings.xml` |
| `feature-addwallet` | `feature/addwallet/src/commonMain/composeResources/values-*/strings.xml` | `feature/addwallet/src/commonMain/composeResources/values/strings.xml` |
| `feature-donate` | `feature/donate/src/commonMain/composeResources/values-*/strings.xml` | `feature/donate/src/commonMain/composeResources/values/strings.xml` |
| `feature-importexport` | `feature/importexport/src/commonMain/composeResources/values-*/strings.xml` | `feature/importexport/src/commonMain/composeResources/values/strings.xml` |
| `feature-markets` | `feature/markets/src/commonMain/composeResources/values-*/strings.xml` | `feature/markets/src/commonMain/composeResources/values/strings.xml` |
| `feature-portfolio` | `feature/portfolio/src/commonMain/composeResources/values-*/strings.xml` | `feature/portfolio/src/commonMain/composeResources/values/strings.xml` |
| `feature-settings` | `feature/settings/src/commonMain/composeResources/values-*/strings.xml` | `feature/settings/src/commonMain/composeResources/values/strings.xml` |
| `feature-wallets` | `feature/wallets/src/commonMain/composeResources/values-*/strings.xml` | `feature/wallets/src/commonMain/composeResources/values/strings.xml` |
| `shared` | `shared/src/commonMain/composeResources/values-*/strings.xml` | `shared/src/commonMain/composeResources/values/strings.xml` |

Adding a language in Weblate adds `values-<lang>/strings.xml` to every component. The app builds
its language list (picker and `locales_config.xml`) from the folders in `shared`, so **translate
the `shared` component first**: a language shows up in the app only once that file exists.

## Checks before merging a Weblate pull request

- CI runs `python scripts/check_strings.py` (completeness, placeholders, hardcoded text).
- Placeholders (`%1$s`) must be kept; plural forms must be complete for the language.
- Do not translate product names, ticker symbols or "TradingView".
