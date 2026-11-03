# Security policy

WatchWallet is watch-only: it has no use for private keys and cannot sign or send anything. It does
handle information that reveals a user's holdings. We take reports seriously.

## What the secret check does, and what it cannot do

`SecretGuard` runs before input is classified, sent or stored, on every change of a text field, for QR
codes and for imports. It recognises seed phrases (all official BIP-39 languages, numbered, run
together or with typos; SeedQR), extended private keys by their content, WIF, hex, base64 and chain
specific key formats, and key files. A rejected value is cleared and is kept in no state, log or message.
A name, note or group is checked again when it is stored.

It recognises formats, so it has limits that no check can remove:

- **A 32-byte Solana secret in base58 cannot be told from a Solana address.** Pasted by mistake, it is
  treated as an address and sent to the Solana server. The byte array form is rejected.
- **`0x` and 64 hexadecimal characters is both a Sui or Aptos address and the private key of Ethereum
  and many other chains.** It is rejected by default. The separate route for those two chains accepts it
  after the user picks the chain and accepts a warning, and a backup entry needs the same confirmation.
  **A private key entered through that route reaches that chain's public server and is stored.** This is
  a deliberate trade-off to support those chains, and it is documented in [PRIVACY.md](PRIVACY.md).
- A base64 key made only of characters that are also base58, and decoding to 32 bytes as base58, has the
  form of a Solana address and passes as one.
- Fewer than 11 words pass, as does an unknown word list that reads like a sentence. Text that is
  really a list of 12 simple words on a BIP-39 list is rejected even if it is not a seed.
- **11 or more words that are mostly misspelled pass.** A phrase is recognised when three quarters of
  its words are exact words of a list, or all but one match a word of that list in their first four
  letters. Below both limits it looks like ordinary text.
- **A key split across two fields passes.** Each field is checked on its own, and half of a hexadecimal
  key (32 characters) is too short to count as a key. Nothing joins the name and the note of a wallet.
- The keyboard and the clipboard are outside the app. The field asks the keyboard not to learn, which a
  keyboard can ignore. The clipboard is cleared only if it holds the rejected text.
- Passwords for encrypted backups and the numeric cost basis field are not checked; neither is stored
  as text or sent.

## App lock

With the lock on, the app shows nothing until the settings are loaded and authentication has passed,
after every cold start and after 30 seconds in the background. Turning the lock off requires
authentication. The lock also forces screenshot and task switcher protection on and hides amounts in
the widget. If the device has no biometrics or screen lock left, nobody could ever authenticate, so the
app opens without asking; removing a screen lock already takes the credential. The lock protects the
view of the app, not the data at rest: the database is not encrypted by it.

## Build integrity

What is shown so far is limited. On one Windows 11 machine with one JDK (Android Studio's JetBrains
Runtime 21), three local builds of the same commit, from fresh exports, were byte-identical
([details](docs/reproducible-builds.md)). For v1.1.0 the workflow building tagged releases ran on Linux with
Temurin 21: two builds from two paths matched each other, and against a Windows build 1604 of 1612 APK
entries are identical, including all `classes*.dex`. The other 8 are the native libraries in `lib/`.
Those 8 have the same loaded code, and differ only in stripped debug data and the ELF header fields
describing it.

The universal APK that Play Console generates for v1.1.0 matches that CI build in the same way: same dex
and assets, same native code, same permissions (`scripts/compare_play_apk.py`). **Not shown:** that the APKs
Play sends to a device match too, as they are split differently and were not checked, and that anyone
independent has repeated this. Resources were not compared, because Play rewrites them. Play re-signs and processes what it receives, and that APK has
never been compared with a local build.

Dependency checksums are not pinned: `gradle/verification-metadata.xml` is **not committed yet**, so a
build does not verify that libraries downloaded from Maven are the expected ones.

## Reporting a vulnerability

Please **do not** open a public issue. Report privately via GitHub's
[private vulnerability reporting](https://github.com/altaraneu/watchwallet/security/advisories/new).

Include a description, affected version, steps to reproduce and the impact you expect.
You will receive an acknowledgement within 7 days. Please allow up to 90 days for a fix before
public disclosure; we will credit you unless you prefer otherwise.

## In scope

- Any way the app could accept, store, log or transmit a seed phrase or private key, other than the
  limits listed above.
- Any way to see portfolio data past the app lock.
- Leaks of tracked addresses or balances (logs, backups, clipboard, network) beyond what
  [PRIVACY.md](PRIVACY.md) documents.
- Network requests to endpoints not listed in PRIVACY.md.
- Weaknesses in export encryption.

## Supported versions

Only the latest release receives security fixes.
