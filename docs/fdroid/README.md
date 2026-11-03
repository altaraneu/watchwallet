# F-Droid readiness

State of the app against the [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/). Nothing has
been submitted. `com.altaran.watchwallet.yml` is a draft for fdroiddata; this folder does not ship in
the app and nothing reads it.

## Verified against the build

Checked from `:app:dependencies --configuration releaseRuntimeClasspath`, the POM of each module and the
built APK. To repeat, see [`../reproducible-builds.md`](../reproducible-builds.md).

- **Licences:** 360 runtime modules, all Apache-2.0, BSD-3-Clause or MIT by their POMs.
- **Repositories:** only Google Maven, Maven Central and the Gradle Plugin Portal, all on the scanner's
  allow list.
- **No proprietary libraries:** no Google Play Services, Firebase, Play Core or analytics artifact in the
  dependency tree. R8 renames most classes, so a package scan of the APK proves less than the tree does.
- **Source tree:** the only binary the scanner's rules would flag is `gradle/wrapper/gradle-wrapper.jar`,
  which it removes by itself.
- **Store promotion:** the rating prompt only appears when the installer is Google Play.

## Native code in the APK

| Library | From | Licence | Source |
|---|---|---|---|
| `libsecp256k1-jni.so` | `fr.acinq.secp256k1:secp256k1-kmp-jni-android` (Maven Central) | Apache-2.0 (wrapper), MIT (libsecp256k1) | [ACINQ/secp256k1-kmp](https://github.com/ACINQ/secp256k1-kmp) |
| `libsqliteJni.so` | `androidx.sqlite:sqlite-bundled` (Google Maven) | Apache-2.0, SQLite public domain | AOSP |
| `libdatastore_shared_counter.so` | `androidx.datastore` (Google Maven) | Apache-2.0 | AOSP |
| `libandroidx.graphics.path.so` | `androidx.graphics:graphics-path` (Google Maven) | Apache-2.0 | AOSP |
| `libimage_processing_util_jni.so`, `libsurface_util_jni.so` | `androidx.camera:camera-core` (Google Maven) | Apache-2.0, BSD-3-Clause | AOSP |

The policy accepts "prebuilt FLOSS binaries" from trusted Maven repositories when they are freely
licensed, and the scanner does not open AARs. So these pass the rules as written, and no build flavour
is needed. A reviewer may still ask for native code to be built from source, the policy's stated
preference. The likeliest request is secp256k1, the one library outside the AndroidX stack. In that
case:

1. **Build it in the recipe:** add secp256k1-kmp as a `srclib` with an `ndk:` version and replace the
   AAR with the result. Untested, and it breaks byte-identical builds with the Maven `.so`.
2. **Remove the need:** public key derivation is all the app uses it for. A pure Kotlin secp256k1 for
   public keys has no secrets to protect, but bitcoin-kmp requires the native library, so its use
   would have to go too. This is real work and would need its own review.

The desktop builds of secp256k1 (Windows DLL, macOS dylib) that used to land in the APK are excluded in
`AndroidApplicationConventionPlugin.kt`.

## Assets

- **Logos:** MIT through trustwallet/assets (see `NOTICE.md`), but they are third-party marks, and the
  repository's licence cannot grant trademark rights. The policy forbids infringing trademarks.
  Identifying an asset by its logo is the ordinary use in this kind of app, but the policy does not
  address it; a reviewer may want `NonFreeAssets` or a note. If they refuse the logos, remove
  `chain_*.png`, the lock file and the tables in `CryptoIcon.kt`: every asset then shows its letter.
- **Charts:** `lightweight-charts.js` is TradingView's prebuilt, minified release, Apache-2.0, with the
  required attribution shown on every chart. The scanner does not flag JavaScript. A reviewer could ask
  for it to be built from source.

## Anti-features

| Flag | Applies? | Why |
|---|---|---|
| `NonFreeNet` | Yes, declared | "Promote or depend entirely on a proprietary network service". Balances use public servers on open protocols, but fiat values only come from CoinGecko, Binance and Kraken, whose APIs are proprietary. Frankfurter is the one open source. The strict reading could say "not entirely"; declaring is the honest choice. |
| `Tracking` | No | Logo downloads and the signed server list are both off by default and opt-in, which the definition exempts: without the user turning it on, nothing is requested from GitHub or jsDelivr (`EndpointUpdatesChoiceTest`, `EndpointConfigUpdaterTest.disabledMakesNoRequest`). The hint that offers the server list makes no request either. Once on, the daily check is what the user asked for, and new servers need their approval first. |
| `NonFreeAssets` | Reviewer's call | See logos above. |
| `TetheredNet` | No | Several replaceable sources; a failure of one does not end the app. |
| `NonFreeDep`, `Ads`, `KnownVuln`, `NonFreeAdd`, `NoSourceSince` | No | No proprietary software needed, no ads, source public, no known vulnerability flagged. Not checked with a vulnerability scanner. |

## Open points

- **Application id:** the policy asks for an id based on a domain the developer owns, and
  `com.altaran.watchwallet` implies `altaran.com`. The owner must confirm that. Play has already
  published this id, so changing it means a new app.
- **Signing:** by default F-Droid signs with its own key, so its users cannot update from the Play or
  GitHub version. To share a key, add the release certificate as `AllowedAPKSigningKeys`, publish signed
  APKs as `Binaries`, and F-Droid will ship yours only when its build matches. Google holds the Play
  signing key under Play App Signing, so this can only cover GitHub and F-Droid. It also requires the
  builds to match on F-Droid's server, which is untested.
- **Toolchain:** the app needs JDK 21, Gradle 9.8.2 and SDK 37. Whether F-Droid's builder has these by
  the time of submission is unverified, and the `sudo:` lines in the draft are a guess. The Kotlin
  Gradle plugin may also fetch `kotlin-native-prebuilt` (Apache-2.0, Maven Central) for the declared iOS
  targets, though `:app:assembleRelease` does not compile them.
- **Tools not run:** `fdroid scanner`, `lint` and `build` did not run here. `pip install fdroidserver`
  fails on this Windows machine because of path length limits. The rules were read from
  [`scanner.py`](https://gitlab.com/fdroid/fdroidserver/-/blob/master/fdroidserver/scanner.py) and applied
  by hand. The `AntiFeatures` map in the draft is from memory of current fdroiddata files; the reference
  page only documents a comma list. `fdroid rewritemeta` will show.
- **Metadata:** `python scripts/check_fastlane.py` checks the limits and the changelog of the current
  version. There are no screenshots yet (`fastlane/metadata/android/*/images/`); F-Droid does not
  require them, but the listing looks empty without them.
