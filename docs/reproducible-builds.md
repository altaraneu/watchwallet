# Reproducible builds

Anyone can rebuild a tagged release and check that the APK matches.

## Build and compare

You need JDK 21, the Android SDK (`ANDROID_HOME`) and Python 3. Do not provide `keystore.properties`:
without it the release build is simply unsigned.

```sh
git clone https://github.com/altaraneu/watchwallet
cd watchwallet
git tag -v v1.1.0        # once tags are signed, see release.md
git checkout v1.1.0
./gradlew --no-build-cache --no-configuration-cache :app:assembleRelease
sha256sum app/build/outputs/apk/release/app-release-unsigned.apk
```

Compare with `SHA256SUMS` of the `unsigned-release` artifact of the tag's run of the
[Unsigned release build](../.github/workflows/release-unsigned.yml) workflow, attached to the GitHub
release. If the hash differs, find out where:

```sh
python scripts/compare_apks.py app-release-unsigned.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

To compare with a signed APK from GitHub Releases, use the same script. It ignores the v1 signature files
under `META-INF`, and the v2/v3 signature block is not a zip entry. Equal entries mean equal code and
resources. The certificate is a separate check: `apksigner verify --print-certs`.

## Comparing with Google Play

A Play install is not one APK. Play builds split APKs from the uploaded bundle, so the files will
differ even for an honest build:

- native libraries, density resources and languages move to `split_config.*.apk`;
- `AndroidManifest.xml` and `resources.arsc` are rewritten, with split attributes and
  `com.android.vending.derived.apk.id`;
- Google signs with the Play app signing key, and may add `META-INF/BNDLTOOL.*`, a source stamp and
  other metadata.

This project does not add a code transparency file to its bundles, so nothing signed by the owner
vouches for the delivered code.

What can be compared are the dex files, assets and `.so` files, one by one:

```sh
adb shell pm path com.altaran.watchwallet        # lists base.apk and split_config.*.apk
adb pull <each path>
python scripts/compare_apks.py --list base.apk > play.txt
python scripts/compare_apks.py --list app-release-unsigned.apk > own.txt
grep -E ' classes[0-9]*\.dex$' play.txt own.txt
```

Equal dex hashes show the delivered code is what the tag builds. Different hashes prove nothing either
way: Google can change the code it delivers, and without code transparency nobody can check that from
the outside. This procedure has **not** been run against a real Play download.

## What was made deterministic

| Possible source | Finding |
|---|---|
| Version code from time | No: `versionCode` and `versionName` are literals in `app/build.gradle.kts`. |
| Timestamps | None embedded. Zip entries carry AGP's fixed 1981-01-01 date. |
| Build paths | None in the result: two paths gave the same APK and the same `mapping.txt`. |
| `META-INF/version-control-info.textproto` | **Fixed.** Its content depended on the kind of checkout (a commit hash in a clone, an error in a worktree). `vcsInfo.include = false` in the release type. |
| Generated `locales_config.xml`, `SUPPORTED_LANGUAGES` | Sorted list of folders, `\n` line ends. Same result. |
| Compose resources | Same result. |
| R8 output and `mapping.txt` | Same result. Keep `mapping.txt` of each release. |
| Baseline profile (`assets/dexopt/baseline.prof`) | Known elsewhere to vary; same result here. Remove it first if it ever differs. |
| Dependency metadata block | Already off (`dependenciesInfo`). |
| Line endings | `.gitattributes` forces LF, so Windows and Linux checkouts hold the same text. |
| Prebuilt `.so` files | Taken unchanged from Maven, so they do not vary. |

## What was tested, and the limits

Tested on one Windows 11 machine with Android Studio's JetBrains Runtime 21: three builds of the same
commit, each from a fresh export in its own directory without any build cache, at different times. The
third also used two workers, and a different time zone and language for the Gradle process (the Kotlin
daemon may not have picked those up). All three APKs and `mapping.txt` files were byte-identical.

**Not tested:**

- **Other JDKs, and other machines building on Linux.** Use Linux with Temurin 21 as the reference.
- **Why stripping differs between systems.** For `v1.1.0` the workflow (Linux, Temurin 21) passed, its two
  builds being identical. Compared with a Windows build by `scripts/compare_apks.py`, 1604 of 1612 entries
  are identical, including all `classes*.dex`. The 8 that differ are `libsqliteJni.so` and
  `libdatastore_shared_counter.so` for each of the four ABIs. Their loaded segments are the same apart from
  the ELF header fields describing the section table; the files differ in stripped debug data. Why the
  strip step gives different output on the two systems was not looked into.
- **The APKs Play sends to a device.** Only the universal APK from Play Console was compared, see below.
- **Bundles.** The AAB and Play's processing of it are outside this check.

## Comparing with Google Play

Play rewrites resources and the manifest, so a whole-file or per-entry comparison fails. Compare what runs:

```sh
adb shell pm path com.altaran.watchwallet       # then adb pull each path listed
python scripts/compare_play_apk.py app-release-unsigned.apk base.apk split_config.*.apk
aapt2 dump permissions base.apk
```

A device only gets the native libraries and languages of its own configuration, so entries are reported as
missing there. Results for `v1.1.0`, using the universal APK from Play Console against the CI build:

- 221 entries identical: the dex file, 204 assets and 16 native libraries.
- 8 native libraries with the same loaded code, stripped differently.
- Same permissions. Play's manifest adds four `meta-data` entries: `com.android.dynamic.apk.fused.modules`,
  `com.android.stamp.source`, `com.android.stamp.type` and `com.android.vending.splits`.
- Not compared: resources.

Only the owner can download that universal APK, so an outsider has to use a device. A match shows what Play
delivered then; Play could deliver something else to someone else.

A match shows the APK came from this source and these dependencies. It says nothing about whether the
dependencies are honest, and the prebuilt native libraries in particular are not rebuilt.
[Dependency verification](#dependency-verification) pins what they are, not what they do.

## Dependency verification

`gradle/verification-metadata.xml` is **not committed yet.** To record SHA-256 of every dependency it
has to be written from the platforms that build:

- Some artifacts exist per OS, for example `kotlin-native-prebuilt-<version>-windows-x86_64.zip`, so a
  file written on Windows fails on Linux CI. iOS compilation resolves more that only macOS requests.
  `--dry-run` skips tasks and misses artifacts they fetch, such as `aapt2`, so run the real tasks.
- Dependencies are also being removed on another branch, which would leave stale entries.

Once that branch is merged, run these in order and commit the result. Gradle merges into an existing
file, so each run adds only what the platform needs:

```sh
# On Linux, then again on macOS:
./gradlew --write-verification-metadata sha256 \
  :app:assembleDebug :app:assembleRelease check \
  :shared:linkDebugFrameworkIosSimulatorArm64 :core:model:iosSimulatorArm64Test

# On Windows, if you build there:
./gradlew --write-verification-metadata sha256 :app:assembleDebug :app:assembleRelease check
```

Review the diff before committing: new entries are whatever was downloaded at that moment, so run this
only from a machine and network you trust. Regenerate the same way after every dependency change, and
remove stale entries by deleting the `<components>` section first. Dependabot pull requests will fail
verification until someone does this, which is the point, but expect that work on each update.
