# Security

## Supported versions

Security fixes are applied to the current `master` branch and the latest published release line.

## Reporting a vulnerability

Please do **not** open a public issue for an unfixed vulnerability. Report it privately to the
maintainer of this fork, or use GitHub’s private vulnerability reporting for
`CookedLobster/ZombieBuddy` once enabled.

Include:

- affected version/commit,
- reproduction steps or a proof of concept,
- impact (local code execution, approval bypass, data exposure, etc.),
- whether the issue requires a malicious mod, a local attacker, or network access.

## Security model

ZombieBuddy is a Java agent. Java mods are **not sandboxed** and can execute arbitrary code with
the user’s permissions. The approval system reduces accidental or remote-code risks; it does not
make Java mods safe.

Current hardening in this fork:

- New/changed JARs are approved by SHA-256 and re-hashed immediately before loading.
- Unsigned mods are denied by default (`allow_unsigned_mods=false`).
- A stored JAR denial always wins over trusted-author auto-approval.
- Steam ban status is tri-state: `BANNED`, `NOT_BANNED`, `UNKNOWN`. Unknown status forces a
  prompt and is shown as **Unknown**, never as a green **No**.
- Preloaded mods are pinned to the approved JAR hash, must carry a valid `.zbs` signature when
  ZBS checks are enabled, and are re-checked against Steam bans before every preload.
- The experimental HTTP Lua API requires a bearer token, rejects browser-originated requests,
  defaults to loopback, limits request/response sizes, and uses a fixed worker pool.
- Author-list generation and runtime parsing reject duplicate author IDs and duplicate signing
  keys. Malformed keys are ignored.
- The `authors.json` list is Ed25519-signed and verified against a key baked into the JAR.

## Verifying release artifacts

The JAR is expected to be signed. The certificate SHA-256 fingerprint pinned by the self-updater
is:

```text
A7:75:10:1B:FB:6C:33:A9:2C:DF:25:20:AC:8D:02:95:CE:BF:89:0C:84:05:97:37:7F:D0:9B:17:D0:EA:DD:97
```

Check a downloaded JAR with:

```bash
jarsigner -verify -verbose -certs ZombieBuddy.jar
keytool -printcert -jarfile ZombieBuddy.jar
```

Only install artifacts from the official releases/Workshop page or builds you made yourself.
Historical binaries in Git history are not release artifacts and must not be run.

## Fixed audit findings

The 2026-11-02 audit’s High and Medium findings were fixed:

| ID | Finding | Status |
|---|---|---|
| ZB-01 | Unauthenticated experimental HTTP Lua API | Fixed |
| ZB-02 | Preloaded mods bypass hash pinning and ban checks | Fixed |
| ZB-03 | Steam ban status fails open / path-dependent | Fixed |
| ZB-04 | TinyFD approval defaults to Yes | Fixed |
| ZB-05 | Permissive defaults / trusted-author precedence | Fixed |
| ZB-12 | Author-list duplicate-ID/key shadowing | Fixed |
| ZB-16 | Trust badge used uploader ID | Fixed with ZB-05 |

Low and Info findings remain tracked in the audit document. See `AGENTS.md` for the project’s
security invariants.
