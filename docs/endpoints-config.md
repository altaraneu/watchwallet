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

**The toggle is on by default.** This is the project owner's decision: a dead or misbehaving public
server should not need a new release to be replaced. What it costs is described under "Key
compromised". Settings → Network shows the list in force (version, signing date, expiry) and every
host in use, and the app says once when the hosts change.

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
   `BundledEndpoints.kt`, including `VERSION`, so the next release ships it (a test fails while the
   two differ). A server
   you add sees the addresses of every user, so vet it first, and add its host to the tables in
   `PRIVACY.md` in the same commit (a test checks that too).
2. Sign on your own machine:
   ```sh
   python scripts/sign_endpoints.py
   ```
   The key is read from `~/.watchwallet-signing/endpoints-signing.key` (or `--key <path>`). The tool
   refuses a key that does not match the public key pinned in the app, and a `version` that is not
   above the one in the working tree or at either download URL. With neither URL reachable it stops;
   `--offline` skips the comparison, for when you know the published version.
3. Verify: `python scripts/sign_endpoints.py --check` (needs no key).
4. Commit `endpoints.source.json` and `endpoints.json` and push to `main`.

Apps fetch the file at most once a day at app start and apply it at the **next** app start. The
jsDelivr fallback may lag behind GitHub by some hours.

**Re-sign before expiry.** `expires_in_days` (180, at most 200) sets the expiry when signing. Raise
`version` and sign again well before that date, even if nothing changed.

## How it is verified

The envelope is `{"payload":"<base64>","signature":"<hex>"}`. The signature is BIP-340 Schnorr over
secp256k1 of SHA-256 of the decoded payload bytes, so JSON formatting cannot affect it. The payload
is `{"format":1,"version":N,"signed":<epoch seconds>,"expires":<epoch seconds>,"endpoints":[...]}` and
is not parsed before the signature is checked. The whole file may have at most 256 KB; a larger download is cut off at
that limit. A file failing any check is discarded and only noted in the debug log.

The verified file is stored as downloaded in the settings DataStore and verified again at each start.

## Replay protection and expiry

`version` must never go down: a lower version than the stored one is rejected, and so is an expired
file, or one expiring more than 200 days ahead (`EndpointFile.MAX_VALIDITY_SECONDS`). The same version
changes nothing. `EndpointFileVerifier.MIN_ACCEPTED_VERSION` is a floor per build: raise it in a
release to drop every older file, including one stored on the device.

**After expiry the app goes back to the bundled defaults instead of keeping the last valid list.**
Expiry exists for the case where updates stop reaching a device: a mirror or network blocks them, or
a list turned out to name a bad server. Keeping the last list would let whoever blocks updates hold
the device on it indefinitely, and that list decides who receives the user's addresses. The bundled
defaults went through a release and are the safer place to end up; the cost is at worst some dead
servers, which is visible and recoverable, while silently leaking addresses is neither. The expired
file stays stored, without being applied, so its `version` still rejects older files. As soon as a
newer valid file arrives it applies again.

Limits: expiry depends on the device clock, so someone who controls the clock can stretch it, and a
fresh install only has the floor of its build, so it accepts any unexpired signed file above that.
That is why expiry stays short (180 days) and why old signed files should not name servers that later went bad: raise
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

An attacker with the key can point every server of every app with the toggle on, which is the
default, at their own hosts. They would still need GitHub or jsDelivr to serve their file at the two
URLs the app fetches, for example through access to this repository. From the next app start their
servers:

- learn every tracked address, with the IP address of each device (or its proxy), and can link the
  addresses of one extended key, because the app asks about them from one IP address close together;
- can show false balances, transactions and token names, so the portfolio can show anything. Do not
  rely on it for a decision, for example to confirm a payment arrived, while this may be going on;
- can answer wrongly or not at all, which looks like a dead server.

They cannot run code, reach keys (the app has none) or sign anything. They cannot change a receive
address either: the app offers only what the user entered or what it derived on the device. A Cardano
stake wallet shows its stake key alone, with a note that it cannot receive payments. The app no longer
asks a server for a payment address, because it could check only the stake part: a dishonest server
could name an address with its own payment key and the wallet's stake part.

Prices, logos and the two download URLs are not in the list. The hosts in force are visible in
Settings → Network and the app announces a change once, which is detection, not prevention: the
addresses have already been sent by the time it appears.

1. Rotate at once as under "Key lost" and release. If a recovery key is pinned, use it first, see
   below. Old builds without one cannot be fixed remotely.
2. Check `git log config/` and revoke repository access if the repository was affected.
3. Publish a security advisory that tells users to update, and meanwhile to turn off "Update server
   list automatically" and restart the app, which goes back to bundled servers immediately.

A file signed with a stolen key can also carry a huge `version`, which would make old builds reject
any later file signed by you. New builds reset this through rotation.

### Recovery key

**Not set up yet. The project owner has to do this before a release:** generate a second key on an
offline machine, keep it offline and apart from the normal key, and enter its x-only public key as
`EndpointFileVerifier.RECOVERY_PUBLIC_KEY`. Only builds containing it benefit, so it has to ship
before it is ever needed.

A file signed with the recovery key beats any file of the normal key whatever the version, so a
stolen key's huge `version` cannot block it. Once a device has stored such a file, it refuses files
of the normal key.

1. Sign with `python scripts/sign_endpoints.py --key <recovery key> --pubkey <its public key> --offline`
   and publish it. Use low `version` numbers for recovery files; they are compared among themselves.
2. Rotate the normal key and release a build with the new key and a `MIN_ACCEPTED_VERSION` above the
   recovery file. That drops the recovery file and hands over to the new key.

The recovery file is subject to the same 200-day limit. The tool's version comparison uses the key
given in `--pubkey`, so `--offline` avoids a warning about the file signed by the other key.

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
