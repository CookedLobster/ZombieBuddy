# ZombieBuddy Framework — Audit Findings and Fix Plan

**Audit date:** 2026-11-02  
**Audited revision:** `7794d9f974b509f9f800b266f76c15cd9eb6bfa6` (`master`, 2026-10-20)  
**Repository:** `https://github.com/zed-0xff/ZombieBuddy`  
**Fix fork:** `https://github.com/CookedLobster/ZombieBuddy` (`master`, 2026-11-02)  
**Scope:** static review of the full Git history, source, docs, scripts, historical binaries, images, signatures, network calls, and build files.

## Verdict

I found **no evidence of intentional malicious code, backdoor, credential theft, or hidden command-and-control/exfiltration**.

The project is a legitimate Java-agent modding framework. The issues below are security-hardening, fail-open behavior, trust-model, supply-chain, and reliability problems. Several are inherent to the framework’s purpose: Java mods and HTTP/experimental features can execute arbitrary code by design.

## Status summary

**Implementation update (2026-11-02):** the High and Medium findings were fixed in this fork
(`CookedLobster/ZombieBuddy`) together with the related ZB-16 UI consistency fix. The Low
findings (ZB-06, ZB-07, ZB-08, ZB-09, ZB-10, ZB-11, ZB-13, ZB-14) were subsequently fixed in the
same fork; Info items retain their original status unless explicitly noted below.

| ID | Severity | Issue | Status |
|---|---|---|---|
| ZB-01 | High | Unauthenticated experimental HTTP Lua API | **Fixed** |
| ZB-02 | Medium-High | Preloaded mods bypass hash pinning and Steam ban checks | **Fixed** |
| ZB-03 | Medium | Steam ban status fails open and is path-dependent | **Fixed** |
| ZB-04 | Medium-Low | TinyFD approval defaults to “Yes” on old game versions | **Fixed** |
| ZB-05 | Medium (design) | Permissive defaults and trusted-author auto-approval | **Fixed** |
| ZB-06 | Low | Dev-only launch args bypass approval/signature checks | **Fixed** |
| ZB-07 | Low | Stealth mode bypasses multiplayer Lua checksum | **Fixed** |
| ZB-08 | Low | Experimental reflection/debug API is exposed to Lua | **Fixed** |
| ZB-09 | Low | Self-updater hardening gaps | **Fixed** |
| ZB-10 | Low | `javaJarFile` path traversal | **Fixed** |
| ZB-11 | Low | Build/installer supply-chain hardening | **Fixed** |
| ZB-12 | Medium (process) | Author-list duplicate-ID/key-shadowing risk | **Fixed** |
| ZB-13 | Low | Installer modifies Steam config without backup | **Fixed** |
| ZB-14 | Low | Approval response validation is incomplete | **Fixed** |
| ZB-15 | Info | `http_client_timeout` is documented but not implemented | Open |
| ZB-16 | Info | Trust badge uses uploader ID instead of signer ID | **Fixed** (with ZB-05) |
| ZB-17 | Info | Historical build artifacts remain in Git | Open |
| ZB-18 | Info | No CI/release provenance in repo; released binaries are out of scope | Open |
| ZB-19 | Info | Intermediate `authors.json` commits were unsigned | Open |
| ZB-20 | Info | Experimental logging/debug features can expose sensitive data | Open |

---

# Findings

## ZB-01 — Unauthenticated experimental HTTP Lua API

**Severity:** High (opt-in, but enabled by the developer `rake run` task)  
**Status:** **Fixed in this fork** (2026-11-02). See “Implementation notes” below.

### Location

- `Rakefile:42-43` — `rake run` sets `experimental=true` and `lua_server_port=4444`.
- `java/src/main/java/me/zed_0xff/zombie_buddy/patches/experimental/PreMain.java:15-37` — starts the HTTP server from agent arguments.
- `.../experimental/HttpServer.java:249,260-263` — registers `/lua`; allows `0.0.0.0` and `*`.
- `.../experimental/http/LuaHandler.java:60-90,118-145` — accepts a POST body and runs it with `LuaCompiler.loadstring`.
- `.../experimental/http/LogHandler.java:17-20` — serves the game console log.
- `.../experimental/http/LuaHandler.java:73,130-136` — the `sandbox` option is not a security boundary; the sandbox table falls back to `LuaManager.env` via `__index`.

### Impact

When the server is running, any local process can POST arbitrary Lua to `/lua`. A malicious web page may also be able to send a cross-origin request because there is no token, no `Origin` check, and no CSRF protection. If `lua_server_host` is set to `0.0.0.0`, the endpoint is network-exposed.

`/log` exposes console contents. The server also has no request-body limit and uses an unbounded cached thread pool.

### Recommended fix

1. **Require a bearer token** for `/lua` and `/log`:
   - Accept `X-ZombieBuddy-Token: <token>` or `Authorization: Bearer <token>`.
   - Compare with `MessageDigest.isEqual`.
   - Never accept the token in the URL/query string.
   - Generate 32 random bytes (64 hex chars) when `lua_server_token` is absent or invalid.
   - Write the token to `zbLuaAPI.token` next to `zbLuaAPI.txt`, with `rw-------` where supported.
2. **Reject browser-originated requests** on sensitive endpoints:
   - Reject any request with an `Origin` header.
   - Requiring a custom token header also blocks normal cross-origin browser requests without a CORS preflight.
3. **Keep loopback as the default**:
   - Default to `127.0.0.1`.
   - Require `lua_server_allow_remote=true` for `0.0.0.0`, `*`, or any non-loopback host.
   - If remote binding is allowed, log that the token travels over cleartext HTTP and recommend VPN/SSH tunneling.
4. **Add limits**:
   - `/lua` body limit: 1 MiB; return `413`.
   - `/log?lines=` limit: 5,000 lines (default 100).
   - Use a fixed HTTP worker pool (4 threads) instead of `newCachedThreadPool`.
5. **Fail closed**:
   - If a generated token cannot be written, make `/lua` and `/log` return `503`.
   - If the user supplied `lua_server_token`, keep it usable even if the token file cannot be written.
6. Keep `/status` and `/version` unauthenticated for health checks.
7. Add unit tests for token validation/extraction/comparison/generation and loopback detection.
8. Document the arguments, token file, headers, limits, and security warnings in `doc/CommandLine.md`.

### Implementation notes

The fix added `HttpAuth.java` and `HttpAuthTest.java`, and updated `HttpServer`, `PreMain`,
`LuaHandler`, `LogHandler`, and `RootHandler`:

- `/`, `/lua`, and `/log` require `X-ZombieBuddy-Token` or `Authorization: Bearer`.
- Tokens are compared with `MessageDigest.isEqual`; tokens in query strings are never read.
- Any request carrying an `Origin` header is rejected with `403`.
- `/status` and `/version` remain unauthenticated health checks.
- `lua_server_token` is validated (minimum 32 chars, no whitespace); otherwise a 32-byte
  random token is generated. Generated tokens are written to `zbLuaAPI.token` with `rw-------`
  where POSIX permissions are available.
- A generated token is unusable until persisted; if the write fails, sensitive endpoints return
  `503`. A user-supplied token remains usable even if its token file cannot be written.
- Non-loopback `lua_server_host` values are downgraded to `127.0.0.1` unless
  `lua_server_allow_remote=true`; remote binding logs a cleartext-HTTP warning.
- `/lua` is limited to 1 MiB and returns `413` above the limit. `/log?lines=` defaults to 100
  and is capped at 5,000. The server uses a fixed 4-thread worker pool.
- Docs were added to `doc/CommandLine.md`.

### Reference implementation

A working implementation was made locally in the closed PR #123. If the closed PR/fork is still available, the exact diff can be retrieved with:

```bash
gh pr diff 123 --repo zed-0xff/ZombieBuddy
```

Otherwise, implement the behavior above. The local implementation added:

- `java/src/main/java/me/zed_0xff/zombie_buddy/HttpAuth.java`
- `java/test/me/zed_0xff/zombie_buddy/HttpAuthTest.java`
- changes to `HttpServer`, `PreMain`, `LuaHandler`, `LogHandler`, `RootHandler`
- docs in `doc/CommandLine.md`

Suggested `HttpAuth` API:

```java
public final class HttpAuth {
    public static final int MIN_TOKEN_LENGTH = 32;

    public static boolean isValidToken(String token);
    public static String generateToken();                 // 32 random bytes -> 64 hex
    public static String extractToken(String headerToken, String authorization);
    public static boolean isAuthorized(String expected, String supplied);
    public static boolean isLoopbackHost(String host);    // 127.0.0.1, localhost, ::1, etc.
}
```

Suggested `HttpServer` additions:

```java
public static void setAuthToken(String token);
public static boolean requireAuth(HttpExchange exchange) throws IOException;
public static byte[] readBodyLimited(HttpExchange exchange, int maxBytes) throws IOException;
```

`requireAuth` should:

- return `503` if no valid token is configured;
- reject any `Origin` header with `403`;
- extract the token from `X-ZombieBuddy-Token` or `Authorization: Bearer`;
- return `401` with `WWW-Authenticate: Bearer realm="ZombieBuddy"` on mismatch.

`PreMain` should:

- read `lua_server_token` and `lua_server_allow_remote`;
- generate a token when needed;
- fall back to `127.0.0.1` for non-loopback hosts unless remote is explicitly allowed;
- write the token file at `onGameInitComplete` (the cache directory may not exist during `premain`);
- disable `/lua` and `/log` if a generated token cannot be persisted.

### Acceptance tests

- No token: `401`.
- Valid token: `200`.
- Wrong token: `401`.
- `Origin` header: `403`.
- Body > 1 MiB: `413`.
- `lua_server_host=0.0.0.0` without `lua_server_allow_remote=true`: warns and binds `127.0.0.1`.
- Generated token file is owner-only on POSIX.
- Token file missing/unwritable: `/lua` and `/log` do not run unauthenticated.

---

## ZB-02 — Preloaded mods bypass hash pinning and Steam ban checks

**Severity:** Medium-High  
**Status:** **Fixed in this fork** (2026-11-02)

### Implementation notes

- `Config.PreloadMod` now stores the approved `sha256`.
- `PreloadMods.add(...)` requires a non-blank approved hash.
- `Loader.preloadMods()` compares the current JAR hash against the pinned hash before loading;
  a mismatch or a legacy entry without a hash removes the preload and requires re-approval.
- Preloads require a valid `.zbs` signature when ZBS checks are enabled, even if
  `allow_unsigned_mods=true`.
- Preloads apply the same Workshop ban check as normal mods. A banned preload is removed and
  its uploader loses trusted-author status. An unknown ban status also removes the preload
  (fail closed).
- Existing `config.json` entries written before this change have no hash; they are intentionally
  not loaded until the user re-approves them.

### Location

- `Config.java:22-25` — `PreloadMod` stores only `infPath` and `jarPath`.
- `Loader.java:394` — `PreloadMods.add(id, infPath, jarPath)` stores no hash.
- `Loader.java:435-486` — `preloadMods()` computes the current SHA-256 and passes it to `zbsCheck()` and `loadJar()`.
- `Loader.java:122-133` — `zbsCheck()` does not check Steam ban status.
- `Loader.java:489-535` — `loadJar()` compares `approvedHash` with the current hash; in the preload path these are the same value.

### Impact

A preloaded mod is approved once (it must be signed and have `ZB-Preload: true` at that point), but subsequent launches trust whatever JAR is now at the same path. There is no stored approved hash and no ban check. If the JAR is replaced or its `.zbs` is removed, and `allow_unsigned_mods=true` (the default), the new unsigned JAR can auto-load without approval.

This requires write access to the mod path (local file write or a Workshop content update), but that is exactly the threat model the approval system is supposed to cover.

### Recommended fix

1. Add a hash to the preload record:

```java
record PreloadMod(Path infPath, Path jarPath, String sha256) {}
```

2. When adding a preload entry, store the approved JAR hash.
3. In `preloadMods()`, compute the current hash and compare it to the stored hash before calling `zbsCheck()`/`loadJar()`.
4. On mismatch, remove or re-prompt the preload entry; do not silently load.
5. Apply the same Steam ban check used by normal mods (`workshopDetailsById`, `BanInfo.status`) and remove trust for banned uploaders.
6. Prefer requiring a valid signature for preloads, or require `allow_unsigned_mods=false`.
7. Keep the existing `ZB-Preload` manifest check.

### Acceptance tests

- Replace a preloaded JAR with a different JAR: it does not load without re-approval.
- Delete the `.zbs` from a preloaded JAR: it does not load as unsigned.
- Mark a Workshop preload as banned: it does not load.
- Unchanged JAR: still loads.

---

## ZB-03 — Steam ban status fails open and is path-dependent

**Severity:** Medium  
**Status:** **Fixed in this fork** (2026-11-02)

### Implementation notes

- `BanInfo` now uses the explicit tri-state `BanStatus { BANNED, NOT_BANNED, UNKNOWN }`.
- Banned Workshop mods are blocked. Unknown status never auto-approves: it forces the approval
  dialog, is rendered as an amber **Unknown** (Swing, ImGui, and console), and disables
  trusted-author auto-approval.
- The approval protocol carries the tri-state status so the UI can distinguish banned from
  unknown.
- `JavaModInfo` now also derives a Workshop ID from `workshop.txt` metadata next to a local
  mod copy, reducing reliance on the Steam `content/108600/<id>` directory layout.
- Tests cover the tri-state model and metadata-derived Workshop IDs.

### Location

- `SteamWorkshop.java:214-221` — unknown/interrupted API results produce `BanInfo(null, reason)`.
- `Loader.java:801-803` — `steamBanned` is only true when `status` is `Boolean.TRUE`; `null` becomes “not banned”.
- `SwingApprovalMain.java:222,318,332` — `steamBanYes = e.steamBan != null`; unknown status is displayed as a green **“No”**.
- `JavaModInfo.java:39-41` — Workshop ID is derived from the filesystem path; local copies outside a Workshop path get no ID and no ban check.

### Impact

During a Steam API outage or for an unknown item, the loader treats the mod as not banned and the UI reports “No”. A banned Workshop mod copied outside the Workshop content path also bypasses the ban lookup entirely.

### Recommended fix

1. Use a tri-state ban model: `BANNED`, `NOT_BANNED`, `UNKNOWN`.
2. Treat `UNKNOWN` as blocked or force a prompt; do not display it as a green “No”.
3. Add the ban status to the approval dialog even when unknown.
4. Apply ban checks to preloaded mods too (see ZB-02).
5. Consider deriving the Workshop ID from metadata as well as the path; do not rely only on the directory layout.
6. Add tests for API failure, missing item, and local-copy cases.

---

## ZB-04 — TinyFD approval defaults to “Yes” on old game versions

**Severity:** Medium-Low  
**Status:** **Fixed in this fork** (2026-11-02)

### Implementation notes

`TinyfdModApprovalFrontend.tinyfdYesNo()` now returns `null` (deny/defer) whenever
`TinyFileDialogs` is unavailable, regardless of the game version. It logs an error instructing
the user to select `frontend=swing` or `frontend=console`. A unit test covers the missing-library
path.

### Location

- `TinyfdModApprovalFrontend.java:97-102`:

```java
if (Core.getInstance().getGameVersion().isGreaterThan(GameVersion.parse("42.14"))) {
    Logger.error("tinyfdYesNo(): game version > 42.14 but TinyFileDialogs missing; returning null");
    return null;
}
Logger.info("tinyfdYesNo(): pre-42.15 and TinyFileDialogs missing; defaulting YES");
return Boolean.TRUE;
```

### Impact

If a user selects `frontend=tinyfd` on a pre-42.15 game and `TinyFileDialogs` is unavailable, every pending Java mod is auto-approved. This is a fail-open approval path.

### Recommended fix

- Return `null` or `false` when the dialog cannot be shown; let the loader deny or defer.
- Never auto-approve a mod because a UI library is missing.
- Add a loud warning that the frontend is unavailable.
- Add a test for the missing-tinyfd path.

---

## ZB-05 — Permissive defaults and trusted-author auto-approval

**Severity:** Medium (design/policy)  
**Status:** **Fixed in this fork** (2026-11-02)

### Implementation notes

- `allow_unsigned_mods` now defaults to `false` (`Agent.java`, `Loader.java`).
- A stored per-JAR denial is checked before the trusted-author auto-approval branch and always
  wins. Unknown Steam ban status also disables trusted-author auto-approval.
- Trust badges now use the signer ID (`zbsResult.sid()`) consistently; this also resolves ZB-16.
- Swing and ImGui approval UIs warn that author trust auto-loads future signed updates and that
  a compromised signing key can bypass the prompt.
- `doc/Installation.md` documents the secure defaults and recommends
  `policy=deny-new,allow_unsigned_mods=false` for servers/shared machines.

### Location

- `Loader.java:66` — `g_allowUnsignedMods = true` by default.
- `Loader.java:78` — default policy is `prompt`.
- `Loader.java:859-869` — a signed JAR from a trusted author is auto-approved before the stored-decision check.
- `Loader.java:872` — trusted-author flag is applied for the UI.
- `doc/Installation.md` — correctly warns that Java mods are unrestricted.

### Impact

- Unsigned mods are allowed by default; security depends on the user clicking “No” correctly.
- Once a user trusts an author, any future signed JAR from that author is auto-loaded. A per-JAR stored **denial** is overridden by the trusted-author branch (the `decision != null` check comes later).
- If the author’s signing key is compromised, malicious updates auto-load.

### Recommended fix

1. Consider making `allow_unsigned_mods=false` the default, or require an explicit opt-in.
2. Change trusted-author precedence so an explicit stored denial for a specific JAR wins over author trust.
3. Show a clear warning when trusting an author, including key-compromise and auto-update implications.
4. Document that “signed” only proves key possession, not code safety.
5. For servers, recommend `policy=deny-new` and `allow_unsigned_mods=false`.

---

## ZB-06 — Dev-only launch arguments bypass approval/signature checks

**Severity:** Low  
**Status:** **Fixed in this fork** (2026-11-02)

### Implementation notes

- `Agent.isDevMode()` requires an explicit `dev=true`. `patches_jar` and `expose_classes` are
  ignored with a warning unless dev mode is enabled.
- `Patch_LuaManager` gates `lua_init_script` on dev mode and only accepts an existing file that
  resolves inside the game working directory or the Project Zomboid cache directory.
- `rake run` and the `test_patched` javaagent arguments pass `dev=true` because they are
  development-only paths.
- `doc/CommandLine.md` documents `dev=true` and the restrictions.

### Location

- `Agent.java:96-132` — `patches_jar` loads JARs with `approvedHash = null`; `expose_classes` exposes arbitrary Java classes to Lua.
- `Patch_LuaManager.java:24-26` — `lua_init_script` runs an arbitrary Lua file at initialization.
- `doc/CommandLine.md` — documents `patches_jar` and `expose_classes` as development features.

### Impact

Anyone who can edit the game launch options can load arbitrary Java code or expose arbitrary classes to Lua without the normal approval flow. This is local-only and documented for development, but it is a foot-gun on shared machines and in packaged configurations.

### Recommended fix

- Gate these arguments behind an explicit `dev=true` or similar opt-in.
- Log a prominent warning when used.
- Require `patches_jar` paths to be signed/pinned if possible.
- Restrict `lua_init_script` to a path inside the mod/game directory.
- Do not enable them from the normal `rake run` task unless needed.

---

## ZB-07 — Stealth mode bypasses the multiplayer Lua checksum

**Severity:** Low  
**Status:** **Fixed in this fork** (2026-11-02)

### Implementation notes

- Stealth mode remains off by default.
- Enabling it logs a prominent warning about the server-integrity exception and possible
  violation of server rules or anti-cheat expectations. `Patch_NetChecksum` logs once when it
  actually hides files.
- The in-game option description and `doc/Installation.md` now document the warning.

### Location

- `Patch_NetChecksum.java:10-38` — hides ZombieBuddy’s own `ZombieBuddy.lua` and `ZombieBuddy_Options.lua` from `NetChecksum` when the server does not list ZombieBuddy.
- `42/media/lua/client/ZombieBuddy_Options.lua:11` — exposes the option.
- `common/media/lua/shared/Translate/EN/UI.json:15` — UI description.

### Impact

This intentionally bypasses the server’s Lua file checksum for ZombieBuddy’s own files. It is opt-in and limited to two files, but it may violate server rules or anti-cheat expectations. It is not a general-purpose checksum bypass, but it is still a server-integrity exception.

### Recommended fix

- Keep the default off (it already is).
- Document the server/ToS implications clearly.
- Consider requiring server-side opt-in or removing the feature from release builds.
- Add a warning in the UI and logs when enabled.

---

## ZB-08 — Experimental reflection/debug API is exposed to Lua

**Severity:** Low  
**Status:** **Fixed in this fork** (2026-11-02)

### Implementation notes

- Added `@Exposer.RequiresDevTools`; `zbUtils` and `WatchesAPI` are marked with it.
- `Exposer` refuses to expose marked classes unless both `experimental=true` and
  `devtools=true` are set. `Patch_KahluaThread` also gates its `tableget` interception, and
  `PreMain` only initializes `WatchesAPI` when devtools are enabled.
- `doc/DevDebugFunctions.md` and `doc/CommandLine.md` document the additional requirement and
  warn that the helpers allow arbitrary field writes and method invocation.

### Location

- `patches/experimental/zbUtils.java:229-255` — `zbget`, `zbset`, `zbcall` use reflection to read/write fields and invoke methods.
- `patches/experimental/Patch_KahluaThread.java:19-46` — installs these functions as Lua globals when the experimental JAR is loaded.
- `WatchesAPI.java` — can hook arbitrary Java methods and log arguments.

### Impact

When `experimental` is enabled, any Lua mod can use `zbcall`/`zbset` to invoke public methods and modify fields on Java objects. This is a broad reflection surface and can bypass assumptions about the Lua/Java boundary. It is opt-in, but `rake run` enables `experimental` by default for developers.

### Recommended fix

- Keep these functions in a dev-only build/source set.
- Require an explicit `devtools=true` flag in addition to `experimental`.
- Warn in the UI/docs that enabling this gives Lua broad Java access.
- Consider removing `zbcall`/`zbset` from release builds.

---

## ZB-09 — Self-updater hardening gaps

**Severity:** Low  
**Status:** **Fixed in this fork** (2026-11-02)

### Implementation notes

- `verifyJarAndGetCerts` now requires every non-`META-INF` entry to be signed and requires all
  signed entries to share the same signer certificate.
- `verifyTrustedSigner` checks certificate validity, `digitalSignature` key usage, and compares
  the leaf certificate SHA-256 with the pinned `EXPECTED_FINGERPRINT`.
- `performUpdate` re-verifies the source, copies it to a staged temp file, verifies the staged
  copy's hash and signature, and only then replaces the live JAR. If replacement fails after the
  live JAR was moved aside, the `.bak` is restored.
- Deferred Windows updates write `ZombieBuddy.jar.new` plus `ZombieBuddy.jar.new.sha256`.
  `zbNative` computes the `.new` SHA-256 with BCrypt and refuses to apply it unless the sidecar
  matches. This is an integrity marker, not a signature; the next Java startup still re-verifies
  the JAR signature before loading.
- Tests cover unsigned JAR rejection and signer certificate mismatch.

### Location

- `SelfUpdater.java:28` — pinned SHA-256 certificate fingerprint.
- `SelfUpdater.java:66-90` — `performUpdate` moves the current JAR to `.bak` and copies the new JAR; failures can leave a `.new` file.
- `SelfUpdater.java:100-130` — verifies the JAR, then copies it; there is a verify-to-copy window.
- `SelfUpdater.java:137-170` — `verifyJarAndGetCerts` returns the certificates of the first signed entry; it does not enforce that every entry has the same signer.
- `c/windows/zbNative.c:39-53` — the native launcher applies a `.new` file with `MoveFileExA` and no signature verification.

### Impact

- A local attacker who can write the game directory can drop a `.new` file and have it applied at next launch.
- The verify/copy gap is a local TOCTOU risk.
- A multi-signer JAR could theoretically pass the “first signer” check; standard `jarsigner` manifests make this difficult, but the check should be stricter.
- Certificate validity dates and key usage are not checked.

The historical JAR’s certificate fingerprint matches the pinned value, so the current trust anchor is consistent.

### Recommended fix

- Verify the JAR again immediately before/after the copy, or copy to a temp file and verify the copy.
- Reject a JAR unless all signed entries share the expected signer certificate.
- Check certificate validity and key usage.
- Have the native launcher verify a signature/hash before applying `.new`, or only apply `.new` files written by a verified update path.
- Keep a recoverable `.bak` and avoid moving the live JAR before the replacement is ready.
- Add tests for unsigned/extra-signer/tampered update JARs.

---

## ZB-10 — `javaJarFile` path traversal

**Severity:** Low  
**Status:** **Fixed in this fork** (2026-11-02)

### Implementation notes

- `JavaModInfo.resolveJarPath` rejects absolute paths, lexical `..` escapes, and symlinks whose
  real target leaves the mod root.
- Containment is checked against the mod root. B42 `../libs` paths relative to the version
  directory remain valid when they stay inside the mod's root (`parseVersionDir` uses the parent
  of the version directory as the root).
- Unit tests cover parent traversal, absolute paths, symlink escapes, and valid in-root
  `../libs` resolution.

### Location

- `JavaModInfo.java:192` — `Path jarPath = jarDir.resolve(versionMatch.jarFilePath());`
- `JavaModInfo.java:240` — only checks that the value ends with `.jar`.

### Impact

A malicious `mod.info` can use `../` or an absolute path to make the loader read a JAR outside the mod directory. The approval dialog shows the resolved path, so it is visible, but the loader does not enforce containment.

### Recommended fix

- Normalize the resolved path.
- Require the result to stay inside the mod root (note: B42 mods legitimately use `../libs` relative to the version directory, so containment must be checked against the mod root, not the version directory).
- Reject absolute paths and paths that escape the mod root.
- Keep the `.jar` extension check.

---

## ZB-11 — Build/installer supply-chain hardening

**Severity:** Low  
**Status:** **Fixed in this fork** (2026-11-02)

### Implementation notes

- Added `distributionSha256Sum` to `java/gradle/wrapper/gradle-wrapper.properties`.
- Added `java/gradle/verification-metadata.xml` with SHA-256 checksums for plugin and Maven
  dependencies.
- Added `doc/Build.md` documenting the wrapper checksum, dependency verification, and the
  Gradle plugin trust boundary.
- The installer now verifies that every `META-INF/*.RSA`/`*.DSA`/`*.EC` signature block contains
  the pinned ZombieBuddy signing certificate before copying `ZombieBuddy.jar`.
- `go mod tidy` removed the unused `github.com/jslay88/vdf` dependency and made
  `github.com/andygrunwald/vdf` a direct dependency.
- A CI workflow was added for Go installer tests, Gradle plugin/dependency verification, and the
  wrapper checksum pin. A full Java compile/test job still requires the proprietary game JAR, so
  that release-provenance work remains tracked under ZB-18.

### Location

- `java/build.gradle:4-5` — third-party `com.gradleup.shadow` and `io.github.zed-0xff.zb-gradle-plugin`.
- `java/build.gradle:34-39` — dependency versions are pinned but there is no dependency verification/lockfile.
- `java/gradle/wrapper/gradle-wrapper.properties:3` — distribution URL has no `distributionSha256Sum`.
- `installer/installer.go:751-776` — copies `ZombieBuddy.jar` and `zbNative.dll` from Workshop content without verifying a signature or hash.
- `installer/go.mod` — `github.com/jslay88/vdf` is a direct dependency but not used by `installer.go` (the code imports `github.com/andygrunwald/vdf`).
- `.github/` — no CI workflow.

### Impact

Build and installation trust depend on external plugins, unverified downloads, and Workshop content. This is normal for many mod projects, but it is a supply-chain risk, especially because the installer copies executable code and modifies launch options.

### Recommended fix

- Add `distributionSha256Sum` to the Gradle wrapper properties.
- Enable Gradle dependency verification/locking for the build.
- Pin or vendor the custom Gradle plugin, or document its trust boundary.
- Verify the JAR certificate fingerprint/hash in the installer before copying.
- Remove unused Go dependencies (`go mod tidy`).
- Add a CI workflow that builds/tests with the pinned JDK and publishes checksums/signatures.

---

## ZB-12 — Author-list duplicate-ID/key-shadowing risk

**Severity:** Medium (process/trust root)  
**Status:** **Fixed in this fork** (2026-11-02)

### Implementation notes

- `authors:add` validates the 64-hex key and rejects duplicate IDs or keys before writing.
- `authors:sign` validates every per-author file, rejects duplicate IDs and cross-author
  signing keys, and rejects malformed keys before aggregating.
- `KnownAuthors.parseAuthorsJSON` fails closed: duplicate IDs, duplicate keys shared by
  different authors, or missing IDs cause the whole list to be rejected. Malformed keys are
  ignored and logged.
- `KnownAuthorsTest` covers duplicate-ID, duplicate-key, and malformed-key cases.

### Location

- `lib/tasks/authors.rake:25-28` — `authors:sign` aggregates every `authors/*.json` file without duplicate checks.
- `KnownAuthors.java:35` — fetches `authors.json` from the `master` branch at runtime.
- `KnownAuthors.java:40` — hardcoded Ed25519 public key.
- `KnownAuthors.java:195-205` — `parseAuthorsJSON` puts entries into a map keyed by Steam ID; later entries overwrite earlier ones.

### Impact

The current `authors.json` is validly signed and has no duplicate IDs or keys. However, a malicious PR that adds a duplicate Steam ID with a different key could shadow an existing author after the maintainer signs the aggregate. `authors:sign` does not reject duplicates, and the loader’s map is last-wins.

The runtime trust root is also `master`; compromise of Zed’s Ed25519 private key would allow arbitrary author/key injection.

### Recommended fix

- Add duplicate-ID and duplicate-key checks to `authors:add`, `authors:sign`, and `KnownAuthors.parseAuthorsJSON`.
- Reject or explicitly merge duplicate IDs; never silently overwrite.
- Validate that keys are 64 lowercase/uppercase hex characters.
- Document key rotation/revocation and the risk of private-key compromise.
- Consider caching/pinning the list with an expiry and a clear trust-root warning.

### Verification note

- Current `authors.json` signature verified against the hardcoded key.
- 33 authors; no duplicate IDs or keys.
- Every `authors/*.json` matches the aggregate.
- Historical intermediate `authors.json` commits include unsigned/invalid signatures from PRs; the final HEAD is valid. Do not treat intermediate commits as trusted.

---

## ZB-13 — Installer modifies Steam config without backup

**Severity:** Low  
**Status:** **Fixed in this fork** (2026-11-02)

### Implementation notes

- The installer creates a timestamped `.zb-backup-<timestamp>` copy before modifying or
  deleting `ProjectZomboid64.json`, `ProjectZomboid64.bat`, `localconfig.vdf`, and core files.
- Launcher/VDF writes use a temp file, `fsync`, and rename instead of truncating in place.
- `manualPatchVDF` validates brace balance and that the new launch options were actually applied
  before replacing the file, and prints the old/new launch-option diff.
- Tests cover VDF backup creation.

### Location

- `installer/installer.go:1067-1178` — updates/removes Steam launch options.
- `installer/installer.go:1317` — `manualPatchVDF` rewrites `localconfig.vdf`.
- `installer/installer.go:751-776` — copies core files.

### Impact

The installer modifies `ProjectZomboid64.json`, `ProjectZomboid64.bat`, and Steam `localconfig.vdf` files. It does not create backups or offer a rollback, and it can update every user profile under `steam/userdata`. A failed/interrupted write can corrupt Steam configuration.

### Recommended fix

- Create timestamped backups of every file before modification.
- Write atomically and validate the result.
- Limit launch-option changes to the selected Steam user where possible.
- Show a clear diff/preview and a rollback command.
- Verify the source JAR/DLL signature/hash before copying.
- Add tests for interrupted writes and malformed VDF.

---

## ZB-14 — Approval response validation is incomplete

**Severity:** Low (local-only)  
**Status:** **Fixed in this fork** (2026-11-02)

### Implementation notes

- The Swing approval subprocess now uses a private `0700` temporary directory with `0600`
  request/response files.
- Requests include a random nonce; the dialog echoes it in the response and the parent rejects a
  missing or mismatched nonce.
- `JarBatchApprovalProtocol.sanitizeResponse` validates mod ID, SHA-256, JAR/mod paths, Workshop
  ID, date, ZBS data, and Steam ban data against the request. It rejects path changes and strips
  `MF_PRELOAD`/`MF_TRUST_AUTHOR` unless the request permitted them.
- `Loader.applyBatchApprovalLines` now applies only the sanitized response and uses request paths
  for preload records.
- Tests cover nonce round-trip, path-change rejection, preload/trust flag constraints, and
  forced denial for banned mods.

### Location

- `SwingModApprovalFrontend.java:41-78` — writes a JSON request to a temp file, launches a child JVM, reads a JSON response.
- `JarBatchApprovalProtocol.java:30-40` — response entries contain paths, flags, ZBS data, and decisions.
- `Loader.java:330-351` — `applyBatchApprovalLines` trusts `MF_PRELOAD`, `MF_TRUST_AUTHOR`, and paths from the response.

### Impact

The parent validates only `modId` and `sha256` for each row. A local attacker who can modify the temp response file or the user config could set `MF_PRELOAD`, `MF_TRUST_AUTHOR`, or arbitrary preload paths. This is local-only and requires file access, but it can bypass the approval UI’s intended constraints.

### Recommended fix

- Create the temp files in a private directory with 0700/0600 permissions.
- Validate every security-relevant field against the request: paths, flags, ZBS author, decision.
- Do not accept path changes from the response.
- Only allow `MF_PRELOAD`/`MF_TRUST_AUTHOR` if the request entry permitted them.
- Consider a random nonce/shared secret in the request/response.

---

## ZB-15 — `http_client_timeout` is documented but not implemented

**Severity:** Info / functional bug  
**Status:** Open

### Location

- `SteamWorkshop.java:100` — `Agent.getArgInt("http.client.timeout", DEFAULT_TIMEOUT)`.
- `doc/CommandLine.md:107-119` — documents `http_client_timeout`.
- `doc/Migrating2xTo3x.md:188` — documents `http_client_timeout`.

### Impact

Setting the documented `http_client_timeout=30` has no effect. The code reads the undocumented `http.client.timeout` key instead.

### Recommended fix

- Read `http_client_timeout` (the documented key), or support both:

```java
int timeout = Agent.getArgInt("http_client_timeout",
        Agent.getArgInt("http.client.timeout", DEFAULT_TIMEOUT));
```

- Update docs/tests to match.

---

## ZB-16 — Trust badge uses uploader ID instead of signer ID

**Severity:** Info / UI inconsistency  
**Status:** **Fixed in this fork** (2026-11-02, with ZB-05)

### Implementation notes

The trusted-author badge/flag in `Loader.loadMods()` now uses `zbsResult.sid()` (signer ID)
instead of `uploaderID`, matching the auto-approval decision.

### Location

- `Loader.java:859` — auto-approval uses `authorID = ctx.zbsResult.sid()`.
- `Loader.java:943` — UI flag uses `isAuthorTrusted(ctx.zbsResult.uploaderID())`.
- `Loader.java:872` — batch entry uses the signer ID.

### Impact

For a signed mod that is not installed under a Workshop path, `uploaderID` is null, so the trusted-author flag may not be applied to the UI even though the signer ID is trusted and the mod was auto-approved. This is a display/consistency issue, not a direct security bypass.

### Recommended fix

- Use the signer ID (`sid`) consistently for trust checks and badges.
- If Workshop uploader binding is required for trust, enforce it explicitly and explain it in the UI.

---

## ZB-17 — Historical build artifacts remain in Git

**Severity:** Info  
**Status:** Open

### Location

- Commit `7fcbd2d` and `e5b2de0` contain `java/build/libs/ZombieBuddy.jar` (v1.6.4).
- History also contains `java/build/libs/zbNative.dll`.

### Impact

The old JAR is JAR-signed and its certificate SHA-256 matches the pinned `SelfUpdater` fingerprint. The old DLL imports only `KERNEL32.dll` and `USER32.dll`. No malicious behavior was found.

However, old binaries in Git history can confuse users, be mistaken for current builds, or be run accidentally. The old JAR also contains an older experimental HTTP server.

### Recommended fix

- Remove build artifacts from the repository history if feasible.
- Never distribute or run the historical binaries.
- Add a release policy that only signed artifacts from the release pipeline are supported.
- Keep `.gitignore` rules for build outputs.

---

## ZB-18 — No CI/release provenance in the repo

**Severity:** Info  
**Status:** Open

### Location

- `.github/` contains only `FUNDING.yml`; there are no workflows.
- Tags are lightweight.
- The README points to `ZombieBuddyInstaller.exe` releases and the Workshop page; those binaries are not in the repo.

### Impact

The repo does not contain a reproducible build/release pipeline, and the released installer/JAR were not part of this audit. Users must trust GitHub Releases and Steam Workshop. The JAR is signed, which helps, but the EXE was not audited here.

### Recommended fix

- Add a CI workflow that builds with the pinned JDK, runs tests, signs the JAR, and publishes checksums/signatures.
- Publish SHA-256 hashes for release artifacts.
- Document how to verify the JAR certificate fingerprint:
  `A7:75:10:1B:FB:6C:33:A9:2C:DF:25:20:AC:8D:02:95:CE:BF:89:0C:84:05:97:37:7F:D0:9B:17:D0:EA:DD:97`.
- Consider signing the Windows installer and publishing provenance.

---

## ZB-19 — Intermediate `authors.json` commits were unsigned

**Severity:** Info / process  
**Status:** Open

### Location

- Git history contains intermediate `authors.json` updates that are not validly signed (for example commits from PR branches). The final HEAD is validly signed.

### Impact

Anyone inspecting or building from an intermediate commit may see an unsigned author list. The maintainer’s final signing step restores validity, but the history is noisy and could confuse review.

### Recommended fix

- Require `authors:sign` before merging author-list changes.
- Add a CI check that verifies the `authors.json` signature at HEAD.
- Reject PRs that modify `authors.json` directly without the generated signature.
- Keep per-author files as the source of truth and regenerate the aggregate.

---

## ZB-20 — Experimental logging/debug features can expose sensitive data

**Severity:** Info  
**Status:** Open

### Location

- `ZBPacketLog.java` — can log packet descriptions, which may include player/chat/world data.
- `ZBEventLog.java` — can log event arguments.
- `WatchesAPI.java` — can log arbitrary method arguments.
- `JavaStateDumper.java` — dumps all Java thread stacks on Ctrl+T/SIGINFO.
- `LogOverlay.java` — displays logs on screen.

### Impact

These features are disabled by default and mostly experimental, but enabling them can write sensitive game/server data to logs or the screen. The HTTP `/log` endpoint also exposes these logs (see ZB-01).

### Recommended fix

- Keep disabled by default.
- Add prominent warnings when enabling.
- Avoid logging player identifiers, chat, tokens, or file paths by default.
- Consider redaction/filtering for packet/event logs.
- Ensure `/log` is authenticated and access-controlled.

---

# Recommended fix order

1. **ZB-01** — lock down the HTTP API first; it is the only remote/local code-execution path with no authentication.
2. **ZB-02** — pin preload hashes and apply ban checks.
3. **ZB-03** — make ban status fail closed and visible.
4. **ZB-04** — make TinyFD fail closed.
5. **ZB-05** — review secure defaults and trusted-author precedence.
6. **ZB-06 / ZB-07 / ZB-08 / ZB-20** — gate dev/experimental features and warn.
7. **ZB-09 / ZB-10** — harden self-update and JAR path resolution.
8. **ZB-11 / ZB-12 / ZB-13 / ZB-14** — supply chain, author-list, installer, and approval-protocol hardening.
9. **ZB-15 / ZB-16** — functional/UI bugs.
10. **ZB-17 / ZB-18 / ZB-19** — history/release/process cleanup.

---

# Positive controls verified

These are not issues; they are reasons the project is not obviously malicious or insecure by default:

- **No malicious code found** in HEAD or full history: no unknown C2, credential readers, browser/Steam token access, hidden payloads, or shell download/exec.
- **`authors.json` signature is valid** against the hardcoded Ed25519 key; no duplicate IDs/keys; per-author files match.
- **Normal mod approval is hash-pinned**: `Loader.loadJar()` re-hashes the JAR and aborts on mismatch.
- **Invalid `.zbs` signatures are blocked** when ZBS checks are enabled.
- **Policy is locked at startup**, preventing a later mod from changing it via `Loader.setPolicy`.
- **Historical JAR is signed** with the certificate fingerprint pinned in `SelfUpdater`.
- **Historical DLL imports only** `KERNEL32.dll` and `USER32.dll`.
- **Images are clean**: no appended payloads, no script metadata, no hidden archives.
- **Git hygiene**: no hooks, submodules, alternates, replace refs, or unreachable objects; `git fsck` clean.
- **Network destinations are limited** to official GitHub/Steam/Patreon/Gradle/ByteBuddy docs plus loopback.

---

# Audit limitations

- Static source/history audit; no full runtime or fuzzing.
- The current release `ZombieBuddyInstaller.exe`, current Workshop JAR/DLL, and any release artifacts were not in the repository and were not audited here.
- Release dependencies/plugins fetched by Gradle/Go were not individually reviewed.
- Author identities and private-key custody were not verified.
- No guarantee against a compromised signing key, compromised dependency, or compromised maintainer account.

---

# Quick reapplication checklist

Use this when re-cloning upstream:

- [x] Implement ZB-01 HTTP token/auth/limits.
- [x] Add preload hash + ban checks (ZB-02).
- [x] Fix Steam ban tri-state (ZB-03).
- [x] Fix TinyFD fail-open (ZB-04).
- [x] Review `allow_unsigned_mods`, trusted-author precedence, and server/session defaults (ZB-05).
- [x] Gate `patches_jar`, `expose_classes`, `lua_init_script`, and experimental reflection (ZB-06/ZB-08).
- [x] Review stealth mode and document/limit it (ZB-07).
- [x] Harden self-updater and native `.new` handling (ZB-09).
- [x] Enforce module-root containment for `javaJarFile` (ZB-10).
- [x] Add Gradle checksum/dependency verification and installer signature checks (ZB-11).
- [x] Add duplicate-ID/key checks to the author-list pipeline (ZB-12).
- [x] Back up Steam config before installer changes (ZB-13).
- [x] Validate all approval-response fields (ZB-14).
- [ ] Fix `http_client_timeout` (ZB-15).
- [x] Use signer ID for trust badges (ZB-16).
- [ ] Remove historical binaries and add release provenance (ZB-17/ZB-18).
- [ ] Add a `authors.json` signature check to CI (ZB-19).
- [ ] Add warnings/redaction for experimental logging (ZB-20).

### Validation performed

- `compileJava`, `compilePatchesJava`, and `compileExperimentalJava` — successful (Java 27).
- `unitTest` — 209/209 passed (includes new dev-mode, `javaJarFile` containment,
  approval-response sanitization, and self-updater tests).
- `test_vanilla` — 27/27 passed.
- `test_patched` — 33/48 passed, 7 failed, 8 skipped. The same 7 failures reproduce on a clean
  `7794d9f` worktree with Java 27 (patched-test harness class-loading / `NoClassDefFoundError`),
  so they are not caused by these changes. Re-run with the project’s pinned JDK 25 before release.
- Installer: `go vet ./...` and `go test ./...` — passed; `go mod tidy` completed.
- Native launcher: `make` in `c/windows/` — successful with the MinGW cross-compiler.
- Gradle dependency verification metadata generated and a subsequent `unitTest` run passed with
  verification enabled.
- The installer JAR-signature check and `SelfUpdater.verifyTrustedSigner` were smoke-tested
  against the historical signed JAR in Git history; both accepted the pinned certificate.
