# Releasing

No key, password or fingerprint belongs in this repository. Keep this list in order; steps marked
**owner** need the owner's keys or accounts and cannot be done by CI or a contributor.

## Before tagging

1. On `main`, with CI green. Set `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Move the `[Unreleased]` section of `CHANGELOG.md` to the new version.
3. Add `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`, at most 500 characters.
4. `python scripts/check_fastlane.py`, `python scripts/check_strings.py`,
   `python scripts/fetch_logos.py --check`.
5. Any new host: listed in `PRIVACY.md` in the same change.
6. **Owner**, if servers changed or the signed list expires within two months: sign it on your own
   machine and push, as in [`endpoints-config.md`](endpoints-config.md):
   ```sh
   python scripts/sign_endpoints.py
   python scripts/sign_endpoints.py --check
   ```

## Tag

**Owner.** The tag is what everyone else verifies, so sign it:

```sh
git config user.name "<public name>"
git config user.email "<public address>"
git config user.signingkey "<key id>"       # GPG, or SSH with gpg.format=ssh
git tag -s v1.1.0 -m "WatchWallet 1.1.0"
git tag -v v1.1.0
git push origin main v1.1.0
```

Commits so far are authored as `jan@example.com`, a placeholder. Set a real public address before the
next commit; it appears in every public commit and history need not be rewritten. Publish the public
part of the signing key (for example add it to the GitHub account, which then marks tags as verified).

## Build

The tag starts `.github/workflows/release-unsigned.yml`, which builds the release variant twice from
two paths, fails if they differ, and uploads `unsigned-release`: the unsigned APK, `mapping.txt`,
`apk-entries.sha256` and `SHA256SUMS`. No secret is available to it.

**Owner**, on your own machine with `keystore.properties` in place:

```sh
./gradlew --no-build-cache :app:bundleRelease :app:assembleRelease
```

Check your build against CI's before uploading anything:

```sh
python scripts/compare_apks.py app/build/outputs/apk/release/app-release.apk app-release-unsigned.apk
```

Keep each release's `mapping.txt`; crash traces cannot be read without it.

## Publish

**Owner.**

1. Google Play: upload the AAB to the intended track.
2. GitHub Releases, attached to the signed tag:
   - the changelog section of this version;
   - `app-release.apk`, signed with your release key;
   - `app-release-unsigned.apk`, `mapping.txt`, `apk-entries.sha256` and `SHA256SUMS` from CI;
   - the SHA-256 of `app-release.apk`;
   - the certificate fingerprints below.

## Signing fingerprints

Publish these so that people can check what they installed. They are public values, but do not paste
them from memory; read them each time:

```sh
# Certificate of an APK you signed (upload or release key):
apksigner verify --print-certs app-release.apk

# Play: Console → Test and release → App integrity → App signing → "App signing key certificate".
# Compare with an installed copy or a Play-delivered base APK:
apksigner verify --print-certs base.apk
```

Which key signs what:

| Channel | Signed by | Fingerprint to publish |
|---|---|---|
| Google Play with Play App Signing | Google's app signing key | Play's "App signing key certificate" |
| Upload to Play | Owner's upload key | Not for users; Google only accepts it for uploads |
| GitHub Releases | Owner's release key | Certificate of the published APK |
| F-Droid, if it builds and signs itself | F-Droid's key for this app | Shown by F-Droid |

Android refuses to update across different signing certificates, so users cannot move between these
channels without reinstalling, unless the keys are the same. Decide this before the first F-Droid
release: see [`fdroid/README.md`](fdroid/README.md). `README.md` still says the fingerprint "will be
published here"; replace that with the table's values on the first public release.

## After publishing

- Confirm the release's assets download and that `SHA256SUMS` matches.
- Reset the `[Unreleased]` section of `CHANGELOG.md`.
- Update `docs/fdroid/com.altaran.watchwallet.yml` if the build recipe had to change.
