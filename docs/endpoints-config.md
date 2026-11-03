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

## Off by default, and consent before use

**The toggle is off by default.** The key holder could otherwise redirect every address lookup, and the
user would only learn of it afterwards, so a dead server now waits for a release unless the user opted
in. New installs and everyone who never used the switch make no request to GitHub or jsDelivr.

- **The choice is stored explicitly** (`endpoints_updates_on`), only by the switch or the hint, never as
  a side effect of saving another network setting. No key means no choice, which is off.
- **Who is affected on updating.** Version 1.0 has neither the feature nor a key, so 1.0 users start off.
  Test builds of 1.1 had the key `endpoints_auto_update`, defaulted to on and wrote it with *any* saved
  network setting, so its value proves nothing. It is ignored and removed at start, with the list and the
  seen hosts stored under the old keys: those test users are off too, and must turn it on again if they
  want it. An old file was never approved, so it must not apply.
- **The hint.** When a chain failed in three sync rounds in a row, at least six hours apart, and the user
  has not decided, Settings → Network offers to turn updates on; **Not now** stores "off" and ends it. A
  round counts only if something else was reached (another chain or a price host), so being offline does
  not count; a chain whose native balance arrived does not count; and the user's own server does not
  count, because a list could not replace it. Counting is local and makes no request.
- **Consent before use.** A verified list that adds a server waits, without effect, for a dialog listing
  what it adds. "Adds" means a host with port that does not serve that pair of chain and API type yet: a
  host known from another chain still asks, as it would learn other addresses. Hosts the build ships for
  that pair never ask, so going back to them needs no approval. Until the user answers, the current hosts
  stay in force, also after restarts. Closing the dialog without an answer asks again at the next start.
- **Declined** lists are kept, without effect, so that their version is not offered again and older files
  stay rejected. Only a higher version can ask again.
- **No prompt:** a list that only removes hosts or changes none applies directly at the next start.
- **An answer belongs to what was shown.** If a newer list arrived behind the dialog, the answer does
  nothing and the newer list asks for itself.

Settings → Network shows the list in force (version, signing date, expiry) and every host in use.

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

Apps with updates on fetch the file at most once a day at app start and apply it at the **next** app
start, after the user's approval if it adds a server. The jsDelivr fallback may lag behind GitHub by
some hours.

**A list that adds a server reaches only those who say yes.** Anyone who declines stays on what they
have, so plan for both, and do not rely on a new host for a fix to reach everyone.

**Re-sign before expiry.** `expires_in_days` (180, at most 200) sets the expiry when signing. Raise
`version` and sign again well before that date, even if nothing changed.

## How it is verified

The envelope is `{"payload":"<base64>","signature":"<hex>"}`. The signature is BIP-340 Schnorr over
secp256k1 of SHA-256 of the decoded payload bytes, so JSON formatting cannot affect it. The payload
is `{"format":1,"version":N,"signed":<epoch seconds>,"expires":<epoch seconds>,"endpoints":[...]}` and
is not parsed before the signature is checked. The whole file may have at most 256 KB; a larger download is cut off at
that limit. A file failing any check is discarded and only noted in the debug log.

The verified file is stored as downloaded in the settings DataStore, one key each for applied, waiting
and declined, and verified again at each start. Only the applied one reaches `EndpointCatalog`.

## Replay protection and expiry

`version` must never go down: a lower version than any stored one, whether applied, waiting or declined,
is rejected, and so is an expired file, or one expiring more than 200 days ahead (`EndpointFile.MAX_VALIDITY_SECONDS`). The same version
changes nothing. `EndpointFileVerifier.MIN_ACCEPTED_VERSION` is a floor per build: raise it in a
release to drop every older file, including one stored on the device.

**After expiry the app goes back to the bundled defaults instead of keeping the last valid list.**
Expiry exists for the case where updates stop reaching a device: a mirror or network blocks them, or
a list turned out to name a bad server. Keeping the last list would let whoever blocks updates hold
the device on it indefinitely, and that list decides who receives the user's addresses. The bundled
defaults went through a release and are the safer place to end up; the cost is at worst some dead
servers, which is visible and recoverable, while silently leaking addresses is neither. The expired
file stays stored, without being applied, so its `version` still rejects older files. A newer valid
file is compared with what is then in force, the bundled defaults, so hosts of the expired list need
approval again. A list that expires while waiting for an answer is dropped from the question and kept
as declined, for its version.

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

An attacker with the key can offer their own hosts to every app with the toggle on, which is not the
default. They would still need GitHub or jsDelivr to serve their file at the two URLs the app fetches,
for example through access to this repository, and each user has to approve the hosts in the dialog,
which names them but cannot say who runs them. From the next app start after approval their servers:

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

Without approval they can still publish a list that adds nothing: it can drop bundled hosts or move a
chain to a fallback already in use, which can make that chain fail, but sends no address anywhere new.

Prices, logos and the two download URLs are not in the list. The hosts in force are visible in
Settings → Network. The dialog is prevention, unlike the notice it replaced: nothing is sent to a new
host before the user agrees. It protects only as far as users read it, so a convincing host name is
still a risk.

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

Not yet checked with F-Droid's reviewers. It is signed data, not code, off by default so opt-in, and the
default URLs are in the source, so `docs/fdroid/README.md` does not expect the `Tracking` flag.

## Tests

- `python scripts/test_sign_endpoints.py`: the tool, including all official BIP-340 vectors
  (`scripts/testdata/bip340-test-vectors.csv`, from the BIPs repository, BSD-2-Clause).
- `./gradlew :core:endpoints:jvmTest`: verification, overlay, updater, consent (`EndpointListApprovalTest`:
  waiting, approving, declining, expiry, restart, a newer list behind the dialog), consistency of source
  file, defaults, pinned key and `PRIVACY.md`, and a file signed by the Python tool.
- `:core:datastore:testAndroidHostTest` (`EndpointUpdatesChoiceTest`) and `:core:data:testAndroidHostTest`
  (`ServerFailureCountsTest`, `SyncEngineTest`): the stored choice and the hint.

All of them use public test keys only.
