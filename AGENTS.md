# AGENTS.md

Rules and context for coding agents working on this repository.

## Project

ZombieBuddy is a Java-agent modding framework for **Project Zomboid**. It patches game classes
at runtime, loads approved Java mod JARs, and ships a Lua mod that exposes its UI/options.

- Upstream: `https://github.com/zed-0xff/ZombieBuddy`
- This fork: `https://github.com/CookedLobster/ZombieBuddy`
- Primary branch: `master`
- License: see `LICENSE.txt`

## Repository layout

| Path | Purpose |
|---|---|
| `java/src/main/java/me/zed_0xff/zombie_buddy/` | Agent core, loader, approvals, ZBS verification, HTTP auth |
| `java/src/main/java/me/zed_0xff/zombie_buddy/patches/` | Game patches (bundled into `patches.jar`) |
| `java/src/main/java/me/zed_0xff/zombie_buddy/patches/experimental/` | Opt-in experimental patches and HTTP Lua API |
| `java/test/` | JUnit 5 unit tests |
| `java/test_vanilla/`, `java/test_patched/` | Integration test source sets |
| `42/`, `41/`, `common/` | Project Zomboid Lua mod content |
| `authors/`, `authors.json` | Signed known-author trust list |
| `installer/` | Go Windows installer |
| `c/` | Native Windows launcher |
| `doc/` | User and developer documentation |

## Environment (WSL)

The game is installed on the Windows host. From WSL, the default game path is:

```text
/mnt/c/Program Files (x86)/Steam/steamapps/common/ProjectZomboid
```

The main game classpath is:

```text
/mnt/c/Program Files (x86)/Steam/steamapps/common/ProjectZomboid/projectzomboid.jar
```

The project targets Java 25 by default (`java/build.gradle`). If only a newer JDK is installed,
pass `-PjavaVersion=<version>` explicitly. Do not change the default toolchain just to make a
local build pass.

## Build and test

Run from `java/`:

```bash
# Compile all source sets
gradle -PjavaVersion=27 \
  -PgameClasspath="/mnt/c/Program Files (x86)/Steam/steamapps/common/ProjectZomboid/projectzomboid.jar" \
  compileJava compilePatchesJava compileExperimentalJava

# Unit tests
gradle -PjavaVersion=27 \
  -PgameClasspath="/mnt/c/Program Files (x86)/Steam/steamapps/common/ProjectZomboid/projectzomboid.jar" \
  unitTest

# Vanilla integration tests
gradle -PjavaVersion=27 \
  -PgameClasspath="/mnt/c/Program Files (x86)/Steam/steamapps/common/ProjectZomboid/projectzomboid.jar" \
  test_vanilla
```

`test_patched` currently depends on the patched-test harness and a compatible JDK; failures there
must be checked against a clean build before assuming a regression.

Always report which commands were run and whether they passed.

## Security invariants

These are release-blocking. Do not weaken them without an explicit instruction from the user.

1. **HTTP API authentication**
   - `POST /lua` and `GET /log` must require a bearer token.
   - Accept only `X-ZombieBuddy-Token` or `Authorization: Bearer`. Never accept a token from the
     query string.
   - Reject any request with an `Origin` header.
   - Default to loopback. Non-loopback binds require `lua_server_allow_remote=true`.
   - Keep `/lua` limited to 1 MiB and `/log` capped at 5,000 lines.
   - A generated token must be usable only after it is persisted. Fail closed with `503`.
2. **Approval policy**
   - New/changed JARs must be hash-pinned before execution.
   - A stored denial always wins over trusted-author auto-approval.
   - Unknown Steam ban status must never auto-approve. It may prompt, but must not look like
     “not banned”.
3. **Preloads**
   - Preload records must include the approved SHA-256.
   - Verify the hash and Workshop ban status before every preload.
   - Preloads require a valid `.zbs` signature when ZBS checks are enabled.
4. **Signatures and authors**
   - Do not add trust bypasses, “temporary” unsigned paths, or debug flags that skip approval.
   - Author-list changes must go through `authors:sign`; never commit a hand-edited aggregate.
   - Reject duplicate author IDs and duplicate signing keys.
5. **Fail closed**
   - Missing UI, missing signature, unknown ban status, unreadable token, or failed persistence
     must deny or defer, not auto-approve.
6. **Sensitive data**
   - Never log tokens, approval secrets, or private keys.
   - Keep experimental logging features disabled by default.

## Coding conventions

- Java: 4 spaces, no tabs. Keep changes local and consistent with surrounding code.
- Prefer records for immutable data and existing helpers (`Utils.isBlank`, `Utils.sha256Hex`,
  `Utils.writeFileAtomic`, `Logger`).
- Do not commit build outputs: `*.jar`, `*.dll`, `*.exe`, `build/`, `.gradle/`.
- Add or update JUnit tests for security-sensitive behavior.
- Update `doc/CommandLine.md` / `doc/Installation.md` when changing agent arguments or defaults.
- Preserve backward compatibility for config JSON where possible. If unsafe legacy data cannot
  be migrated safely, fail closed and require re-approval.
- Use `MessageDigest.isEqual` for secret/hash comparison where timing matters.
- Write files atomically and set owner-only permissions for secrets on POSIX.

## Testing expectations

- New security logic needs a unit test.
- Run `unitTest` before committing.
- If a change affects patched Minecraft/PZ class loading, run `test_vanilla` and, when possible,
  `test_patched`.
- Do not mask failures by deleting tests. If an existing test is environment-dependent, make it
  hermetic (e.g., `@TempDir`) rather than relying on a developer’s home directory.

## Git workflow

- Work on a named branch, not directly on `master`.
- Use imperative commit subjects, e.g. `Harden experimental HTTP API`.
- Group related changes into one commit; do not mix formatting-only churn with security fixes.
- Push to the fork remote (`CookedLobster/ZombieBuddy`), not upstream.
- Do not force-push `master`.
- Mention the audit IDs (e.g. `ZB-01`, `ZB-02`) in commit messages or PR descriptions.

## Security audit

The current audit is maintained outside the repository at `/root/ZombieBuddy-Audit.md` in the
development environment. It records fixed and open findings. When implementing a finding, update
that document’s status table and add implementation notes.

## Scope discipline

- Do not implement Low/Info findings unless the user asks.
- Do not add new third-party dependencies without a clear need and a supply-chain review.
- Do not change release signing, trust anchors (`KnownAuthors.AUTHORS_PUBKEY_HEX`,
  `SelfUpdater` fingerprint), or the `authors.json` public key.
