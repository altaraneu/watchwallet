# Remote server list

Public blockchain servers die or start rate-limiting. So that the app can move to another server
without a new release, it reads a signed data file from this repository.

- `core/endpoints/.../BundledEndpoints.kt`: defaults built into the app, the single source of truth
  in code. They always work offline.
- `config/endpoints.source.json`: what you edit. Unsigned, public.
- `config/endpoints.json`: what the app downloads. Written by `scripts/sign_endpoints.py`.

## What the file can and cannot do

Each entry is data: a chain, an API type (protocol) and https URLs.

```json
{"chain": "bitcoin", "protocol": "esplora", "urls": ["https://mempool.space/api", "https://blockstream.info/api"]}
```

It can only point an API type the app already speaks at another host. It holds no code, scripts or
request mappings; a new API type or chain needs a new build. The app ignores, one entry at a time:
unknown chains, unknown protocols, a protocol that chain does not use, URLs that are not plain
https (credentials, query strings and fragments are refused too). An empty result leaves the
bundled entry in place, so the file cannot remove a server without naming a replacement.

An entry replaces the bundled URLs for that pair; pairs not in the file stay as bundled. A user's
custom Bitcoin server always wins. With "Update server list automatically" off (Settings → Network)
the app makes no request and uses bundled servers only.

| Protocol | Chains | URLs used |
|---|---|---|
| `esplora` | `bitcoin` | all, in order |
| `esplora` | `litecoin` | first |
| `blockbook` | `dogecoin`, `bitcoincash` | first |
| `evm-rpc`, `blockscout` | `evm:<chain id>` (21 networks; `blockscout` also on those without a default) | all, in order |
| `solana-rpc` | `solana` | all, in order |
| `xrpl` | `xrp` | all, in order |
| `near-rpc` | `near` | all, in order |
| `trongrid` | `tron` | first |
| `toncenter` | `ton` | first |
| `koios` | `cardano` | first |
| `cosmos-rest` | `cosmos` (one gateway for all Cosmos chains) | first |
| `substrate-rpc`, `substrate-asset-hub-rpc` | `polkadot`, `kusama` | all, in order |
| `horizon` | `stellar` | first |
| `sui-graphql` | `sui` | first |
| `aptos-rest` | `aptos` | first |
| `hedera-mirror` | `hedera` | first |
| `algorand-indexer` | `algorand` | first |
| `tzkt` | `tezos` | first |
| `kaspa-rest` | `kaspa` | first |

"First" means the adapter has one server without failover, so further URLs are not used yet.
"All, in order" means a failing host is left alone for a while and the next one takes over.

Fixed in the build, not in this file: price and market data (CoinGecko, Binance, Kraken, Frankfurter),
Trust Wallet logos, block explorer links and the two download URLs of the file itself.

## Change servers

1. Edit `config/endpoints.source.json` and raise `version`. Make the same change in
   `BundledEndpoints.kt`, so the next release ships it (a test fails while the two differ). A server
   you add sees the addresses of every user, so vet it first, and add its host to the tables in
   `PRIVACY.md` in the same commit (a test checks that too).
2. Sign on your own machine:
   ```sh
   python scripts/sign_endpoints.py
   ```
   The key is read from `~/.watchwallet-signing/endpoints-signing.key` (or `--key <path>`). The tool
   refuses a key that does not match the public key pinned in the app, and a `version` that is not
   above the published one.
3. Verify: `python scripts/sign_endpoints.py --check` (needs no key).
4. Commit `endpoints.source.json` and `endpoints.json` and push to `main`.

Apps fetch the file at most once a day at app start and apply it at the **next** app start. The
jsDelivr fallback may lag behind GitHub by some hours.

**Re-sign before expiry.** `expires_in_days` (180) sets the expiry when signing. Raise `version` and
sign again well before that date, even if nothing changed.

## How it is verified

The envelope is `{"payload":"<base64>","signature":"<hex>"}`. The signature is BIP-340 Schnorr over
secp256k1 of SHA-256 of the decoded payload bytes, so JSON formatting cannot affect it. The payload
is `{"format":1,"version":N,"expires":<epoch seconds>,"endpoints":[...]}` and is not parsed before
the signature is checked. The whole file may have at most 256 KB; a larger download is cut off at
that limit. A file failing any check is discarded and only noted in the debug log.

The verified file is stored as downloaded in the settings DataStore and verified again at each start.

## Replay protection and expiry

`version` must never go down: a lower version than the stored one is rejected, and so is an expired
file. The same version changes nothing.

**After expiry the app goes back to the bundled defaults instead of keeping the last valid list.**
Expiry exists for the case where updates stop reaching a device: a mirror or network blocks them, or
a list turned out to name a bad server. Keeping the last list would let whoever blocks updates hold
the device on it indefinitely, and that list decides who receives the user's addresses. The bundled
defaults went through a release and are the safer place to end up; the cost is at worst some dead
servers, which is visible and recoverable, while silently leaking addresses is neither. The expired
file stays stored, without being applied, so its `version` still rejects older files. As soon as a
newer valid file arrives it applies again.

Limits: expiry depends on the device clock, so someone who controls the clock can stretch it, and a
fresh install has no stored version, so it accepts any unexpired signed file. That is why expiry
stays short (180 days) and why old signed files should not name servers that later went bad: raise
`version` and re-sign promptly when one does.

## Key management

The private key must stay off this repository and off CI. Keep an offline backup. `*.key` is
git-ignored as a safety net. Only the x-only public key
(`EndpointFileVerifier.PINNED_PUBLIC_KEY` and `PINNED_PUBLIC_KEY` in the tool, kept equal by a test)
is public.

### Key lost

Nothing breaks at once: installed apps keep their list until it expires, then use their bundled
defaults, and they keep working.

1. Generate a new key on a trusted machine; store it offline with a backup.
2. Put the new public key in both places above and refresh `BundledEndpoints.kt`.
3. Release a new build and publish a file signed with the new key. Use a `version` above every
   earlier one.

Old builds cannot verify the new file and keep discarding it silently, so they stay on their last
list until expiry and then on their bundled defaults. A new build ignores a stored file signed with
the old key, which also resets its stored `version`.

### Key compromised

An attacker with the key can point the servers of every app with the toggle on at their own hosts
and so learn which addresses users track. They cannot run code, reach keys (the app has none) or
change balances beyond what a dishonest server can already report. They would still need GitHub or
jsDelivr to serve their file at the two URLs the app fetches, for example through access to this
repository.

1. Rotate at once as under "Key lost" and release. Old builds cannot be fixed remotely.
2. Check `git log config/` and revoke repository access if the repository was affected.
3. Publish a security advisory that tells users to update, and meanwhile to turn off "Update server
   list automatically" and restart the app, which goes back to bundled servers immediately.

A file signed with a stolen key can also carry a huge `version`, which would make old builds reject
any later file signed by you. New builds reset this through rotation.

## F-Droid

Not yet checked: whether F-Droid's review considers this list an issue. It is signed data, not code,
it can be turned off, and the default URLs are in the source. Ask before submitting, and consider
whether the toggle should default to off in that build.

## Tests

- `python scripts/test_sign_endpoints.py`: the tool, including all official BIP-340 vectors
  (`scripts/testdata/bip340-test-vectors.csv`, from the BIPs repository, BSD-2-Clause).
- `./gradlew :core:endpoints:jvmTest`: verification, overlay, updater, consistency of source file,
  defaults, pinned key and `PRIVACY.md`, and a file signed by the Python tool.

All of them use public test keys only.
