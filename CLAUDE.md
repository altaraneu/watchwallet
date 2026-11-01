# WatchWallet – guide for contributors and AI assistants

Watch-only multichain crypto portfolio tracker. Android app today, Kotlin Multiplatform
throughout so an iOS app can be added cheaply later. Read this before changing code.

## Non-negotiable principles

1. **Watch-only.** Never accept, store, log or transmit seed phrases or private keys. The
   secret-detection guard (`:core:security`, milestone 2) runs before any classification,
   network request or persistence of user input.
2. **Private.** No analytics, ads, tracking, Firebase or Google Play Services. No backend.
   Every network endpoint must be listed in `PRIVACY.md` in the same change that adds it.
   Never log addresses or balances in release builds.
3. **English only** in code, comments, KDoc, commits, PRs, docs and logs. User-facing text
   exists only in translation resources.
4. **F-Droid compatible.** Only open-source dependencies, no proprietary blobs.

## Architecture

Clean Architecture with unidirectional data flow: Composable ← ViewModel (immutable
`UiState` in a `StateFlow`) ← repository ← Room/DataStore (single source of truth) ← sync /
chain adapters ← network. Screens never call the network directly.

```
:app                 Android shell: Application (Koin start), MainActivity, manifest, icon,
                     per-app language (AppCompat), generated locales_config
:shared              Root composable WatchWalletApp, navigation, AppViewModel, Koin module list.
                     Becomes the iOS framework ("Shared") later.
:core:common         Pure Kotlin helpers (dispatchers, locale facts)          [jvm + ios]
:core:model          Domain models: QuoteCurrency, UserSettings, Wallet…      [jvm + ios]
:core:datastore      Settings (Preferences DataStore)                         [android + ios]
:core:database       Room (KMP, bundled SQLite, WAL)                          [android + ios]
:core:data           Repositories + platform service interfaces               [android + ios]
:core:designsystem   Theme, colors, icons (WwIcon), shared components         [android + ios]
:core:ui             Locale-aware formatting, clipboard helpers               [android + ios]
:feature:*           One module per screen/flow                               [android + ios]
build-logic/         Convention plugins (watchwallet.*) – all module config lives here
```

Planned: `:core:security` (secret guard), `:core:classifier` (offline address classifier),
`:core:crypto`, `:core:network`, `:core:pricing`, `:core:timeline`, `:chain:*`.

### Multiplatform rules

- Write code in `commonMain`. Platform code goes into `androidMain` / `iosMain` behind an
  `expect` declaration or an interface provided through Koin.
- Pure logic modules use `watchwallet.kmp.library` (jvm + iOS, fast JVM tests). Modules that need
  Android APIs or resources use `watchwallet.kmp.android.library`.
- iOS targets are declared everywhere and compiled by CI on macOS. Local Windows builds skip
  them, so check that you only use multiplatform libraries in `commonMain`.
- Android-only capabilities (WorkManager, Glance widget, CameraX, biometrics, per-app language,
  FLAG_SECURE) sit behind interfaces defined in common code.

### Dependency injection

Koin. Each feature exposes a `xxxModule` and a `NavGraphBuilder.xxxScreen()` function; `:shared`
lists all modules in `sharedModules`. Platform services (`AppLanguageController`, `AppInfo`)
come from the platform shell (`WatchWalletApplication`).

### Navigation

Type-safe Navigation Compose (`@Serializable` route objects in each feature). Top-level
destinations: Portfolio, Wallets, Markets, Settings.

## Database

Room is the source of truth. Rules:

- WAL journaling, all writes of one sync in a single transaction.
- Never use destructive migration. User data (wallets, labels, groups, notes) is irreplaceable.
  Every schema change bumps the version, ships an explicit migration and a migration test;
  schemas are committed in `core/database/schemas/`.
- Cached chain data (transactions, balances, prices) may be dropped and re-fetched.
- Cloud backup is disabled (`data_extraction_rules.xml`); device-to-device transfer is allowed.

## UI conventions

- Material 3 with dynamic color; brand palette fallback in `core/designsystem/theme/Color.kt`.
- All amounts go through `AmountText` (discreet mode, tabular figures, accessibility).
- All formatting goes through `MoneyFormatter` / `DisplayNames` (locale-aware, never manual).
- One text edge: list rows have no decorative leading icons; use `SettingsItem`,
  `SectionHeader`, `EmptyState`, `SkeletonBlock` from the design system.
- Icons only from `WwIcon` (Material Symbols vectors in `core/designsystem/composeResources`).
- Gains/losses never by color alone: always a sign (`+` / `−`) or arrow.

## Strings and translations

- Strings live in each module's `src/commonMain/composeResources/values/strings.xml`
  (English source) and `values-cs/` (Czech). Access via the module's generated internal `Res`.
- No hardcoded user-facing text, no concatenated sentences, positional placeholders (`%1$s`),
  `plurals` for counts, translator comments for ambiguous strings.
- Adding a language = adding `values-<lang>` folders. `:app` generates the language list
  (picker + `locales_config.xml`) from `shared/src/commonMain/composeResources/values-*`.
- `python scripts/check_strings.py` verifies completeness and hardcoded text (CI runs it).

## Adding a chain adapter (from milestone 2)

1. Create `:chain:<name>` with `watchwallet.kmp.library` (or `.android.library` if needed).
2. Implement `ChainAdapter` (validation/detection, derivation if applicable, balances, tokens,
   history, balance timeline) using only multiplatform libraries.
3. Add detection rules and table-driven tests (valid, invalid, wrong checksum, wrong network,
   overlapping formats) to `:core:classifier`.
4. Add secret formats of that chain to `:core:security` with tests.
5. Register the adapter in Koin; document new endpoints in `PRIVACY.md`.
6. Mocked HTTP responses in tests; no live network in unit tests.

## Build, test, verify

Builds are slow on Windows: batch changes and build rarely.

```sh
./gradlew :app:assembleDebug                 # debug APK (applicationId com.altaran.watchwallet.debug)
./gradlew :core:ui:testAndroidHostTest       # one module's tests (android modules)
./gradlew :core:model:jvmTest                # one module's tests (pure modules)
./gradlew check                              # everything (CI)
./gradlew ktlintFormat                       # auto-format
```

On-device verification over ADB: interact only with this app's package, keep screenshots and
UI dumps outside the repository, restore any changed system setting (font scale, locale…).

## Commits

Imperative English subject ≤ 72 chars, body explains why. Keep the build green.
