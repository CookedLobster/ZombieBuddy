# Mod Signing

Mod signing is ZombieBuddy's way to verify that a Java mod JAR was approved by the author key it claims to come from.

It is not a safety guarantee. It is an identity and integrity check.

---

## The Short Version

A signed Java mod has two files:

- `YourMod.jar` - the Java code that will be loaded.
- `YourMod.jar.zbs` - a small signature file next to the JAR.

ZombieBuddy checks that:

1. The `.zbs` file names a Steam author ID.
2. The JAR's SHA-256 hash matches the signed payload.
3. The signature was made by a public key associated with that Steam author ID.
4. If the mod is installed from Steam Workshop and the uploader ID is known, the signer Steam ID matches the Workshop item uploader.

If all checks pass, ZombieBuddy knows: this exact JAR was signed by a key associated with that author identity.

---

## What Signing Proves

Signing proves file integrity:

- The JAR has not changed since it was signed.
- A copied `.zbs` file cannot validate a different JAR.
- If someone edits, repacks, or injects code into the JAR, the signature stops matching.

Signing also proves key identity:

- The signature was made by the private key corresponding to an accepted public key.
- The public key is associated with the signer SteamID64 through either:
  - the known authors list, or
  - `JavaModZBS:<public key>` on the author's Steam profile.

Signing can support author trust:

- A user can approve one signed mod and choose to trust that author.
- Later JARs signed by the same trusted author key can be allowed without asking again.

---

## What Signing Does Not Prove

Signing does not prove that the mod is safe.

A malicious author can sign malicious code. A signature only says who signed the file, not whether the code is good.

Signing does not prove that the code was reviewed.

ZombieBuddy does not inspect source code, decompile JARs, or judge behavior.

Signing does not protect users if the author's private key is stolen.

If a private key leaks, someone else may be able to sign files as that author until the key is removed or replaced.

Signing does not automatically make unsigned mods bad.

Unsigned mods may be legitimate, especially during development or before an author has set up signing. ZombieBuddy can still ask users to approve unsigned mods depending on policy.

---

## Why Steam Identity Is Used

The public key alone is hard for users to understand. A SteamID64 gives ZombieBuddy a stable author identity to show and store trust against.

Steam identity is used for:

- displaying an author in the approval UI
- storing trusted-author decisions
- finding the author's public signing key
- checking that a signed Workshop mod was signed by the Workshop uploader, when that information is available

This prevents trust from being attached to a vague "some key". Instead, the trust decision is "trust signed Java mods from this Steam author".

Important: Steam identity is not magic security. It answers "is this the same author identity?" It does not answer "is this author trustworthy?"

---

## Known Authors And `authors.json`

ZombieBuddy can load a known authors list from `authors.json`. Entries look like:

```json
{
  "id": 76561198012345678,
  "name": "Author name",
  "keys": ["64 hex public key"]
}
```

`authors.json` is a generated, signed file. Its source of truth is the `authors/` directory, which holds one JSON file per author (e.g. `authors/author-name.json`). The `authors.json` file is rebuilt from `authors/*.json` and re-signed whenever the mod is built.

This list gives ZombieBuddy a stable mapping from SteamID64 to display name and public signing keys. ZombieBuddy syncs `authors.json` from GitHub and caches it in the ZombieBuddy config directory, so new author entries can be added without publishing a ZombieBuddy mod update, and verification can still work when the remote list is temporarily unavailable.

When an author has keys in `authors.json`, those keys are authoritative. ZombieBuddy tries them before falling back to reading `JavaModZBS:<key>` from the author's Steam profile.

Authors who want to keep their Steam profile private can submit a pull request to add their SteamID64, display name, and public key instead of publishing `JavaModZBS:<key>` on their profile. Do this by adding a new file to `authors/` (named after the author, e.g. `authors/author-name.json`), not by editing `authors.json` directly — that file is regenerated from `authors/` and any direct edits to it will be overwritten.

### Why not just use the Steam profile name?

Steam display names are not reliable author identities.

They can change at any time, may contain formatting or confusing impersonation text, and may be unavailable due to privacy settings, network failures, rate limits, or Steam pages returning different content. A live profile name is therefore not a good thing to store as a trust label.

The stable identity is the SteamID64. `authors.json` provides a curated human-readable name for that stable ID. If no known name is available, ZombieBuddy can still show the raw SteamID64.

---

## What Is Signed

ZombieBuddy signs a canonical payload based on:

```text
ZBS:<SteamID64>:<JAR_SHA256>
```

The same author may need to use the same signed JAR in local installs, test uploads, mirrors, or multiple Workshop items. When a Workshop uploader ID is available, ZombieBuddy checks it separately against the signer SteamID64.

---

## The `.zbs` File

A `.zbs` file is stored next to the JAR:

```text
YourMod.jar
YourMod.jar.zbs
```

The sidecar contains:

```text
ZBS
SteamID64:<author steam id>
Signature:<ed25519 signature hex>
```

Users and mod packs should distribute the `.zbs` file together with the JAR. If the `.zbs` file is missing, the mod is treated as unsigned.

---

## Public And Private Keys

The author keeps a private key locally. This key should not be shared or committed to a repository.

The public key is published so ZombieBuddy can verify signatures. ZombieBuddy can read it from:

- the known authors list, or
- the author's Steam profile summary as `JavaModZBS:<64 hex public key>`

The private key signs mod JARs. The public key verifies them.

---

## Trust Author

When ZombieBuddy shows a signed mod, the user may choose to trust the author.

If the user trusts the author:

- ZombieBuddy records trust for that author's SteamID64.
- Future JARs signed by that author can be allowed automatically.
- The JAR still has to verify cryptographically.

Trust does not mean "allow any file with this SteamID64 written in it". The signature must still match the JAR and the author's public key.

---

## Key Rotation, Revocation, And Compromise

There is no online revocation service. The trust root is the signed `authors.json` file and the
keys it lists:

- To **rotate** a key, add the new public key to your author entry, sign a short transition period
  with both keys if needed, then remove the old key.
- To **revoke** a compromised key, remove it from your `authors/` entry and regenerate + sign
  `authors.json`. Old JARs signed with that key stop validating once the updated list is fetched.
- If your private key is **compromised**, assume an attacker can sign arbitrary JARs as you until
  the key is removed from the signed list. Remove the key immediately, publish a warning, and
  rotate to a new key.
- A user who has already marked your SteamID64 as trusted will auto-approve future valid
  signatures from any currently listed key for that author. Removing the compromised key from
  `authors.json` is therefore essential.

Per-author files in `authors/` are the source of truth. Never hand-edit `authors.json`; run
`rake authors:sign` so duplicate IDs and keys are rejected before the aggregate is signed.

## Failure Cases

Common signature failure cases:

- `YourMod.jar.zbs` is missing.
- The JAR was rebuilt after signing, but the `.zbs` file was not regenerated.
- The `.zbs` file belongs to another JAR.
- The public key is missing from the known authors list and the Steam profile.
- The Steam profile key is malformed.
- The signer SteamID64 does not match the Workshop item uploader.
- The author's private key or configured SteamID64 is wrong.

---

## Advice For Mod Authors

- Keep your private key private.
- Sign the final JAR you distribute.
- Regenerate the `.zbs` file after every JAR rebuild.
- Distribute `YourMod.jar.zbs` alongside `YourMod.jar`.
- Publish your public key once using `JavaModZBS:<key>` or the known authors list.
- Do not promise that signing means your mod is "safe". Say that signing lets users verify the JAR came from your author key and was not changed after signing.

For setup commands and Gradle configuration, see [Modding Guide](ModdingGuide.md#signing-your-mod-optional).
