# Security policy

WatchWallet is watch-only and never handles private keys, but it does handle information that
reveals a user's holdings. We take reports seriously.

## Reporting a vulnerability

Please **do not** open a public issue. Report privately via GitHub's
[private vulnerability reporting](https://github.com/altaraneu/watchwallet/security/advisories/new).

Include a description, affected version, steps to reproduce and the impact you expect.
You will receive an acknowledgement within 7 days. Please allow up to 90 days for a fix before
public disclosure; we will credit you unless you prefer otherwise.

## In scope

- Any way the app could accept, store, log or transmit a seed phrase or private key.
- Leaks of tracked addresses or balances (logs, backups, clipboard, network) beyond what
  [PRIVACY.md](PRIVACY.md) documents.
- Network requests to endpoints not listed in PRIVACY.md.
- Weaknesses in export encryption.

## Supported versions

Only the latest release receives security fixes.
