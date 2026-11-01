# Contributing

Thanks for helping! Please read [CLAUDE.md](CLAUDE.md) first: it describes the architecture,
conventions and how to add a chain adapter.

## Ground rules

- **Watch-only.** Never add code that accepts, stores or transmits key material.
- **English only** in code, comments, commit messages, PRs and docs. User-facing text lives
  only in translation resources.
- **No proprietary dependencies.** No Google Play Services, Firebase, analytics or tracking
  SDKs. The app must stay buildable for F-Droid.
- **New network endpoints** must be documented in [PRIVACY.md](PRIVACY.md) in the same PR.

## Development

```sh
./gradlew check                 # tests + ktlint + detekt
./gradlew ktlintFormat          # auto-format
python scripts/check_strings.py # translation completeness and hardcoded text
scripts/compile-check.sh        # fast compile of all modules without building the APK
```

Keep commits focused, with an imperative subject line ("Add Esplora balance fetcher").

## Translations

English (`values/strings.xml`) is the source language. Translations live next to each module's
screens in `src/commonMain/composeResources/values-<language>/strings.xml`.

To add a language, create `values-<language>` folders with translated `strings.xml` files in
every module that has a `values` folder (start with `shared`). The in-app language picker and
Android's per-app language settings pick the new language up automatically; no code changes.

Rules for translators:

- Keep placeholders such as `%1$s` exactly; their order may change.
- Use `plurals` entries for counts; Czech and many other languages have several plural forms.
- Read the `<!-- comments -->` above strings for context.

We plan to host translations on [Weblate](https://weblate.org); the link will appear here once
the project is set up.

## Reporting bugs

Open an issue with steps to reproduce, the app version and your Android version. Never include
seed phrases or private keys; public addresses are fine but consider privacy.
