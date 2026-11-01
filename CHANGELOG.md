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
