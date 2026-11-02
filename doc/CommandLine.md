# ZombieBuddy Command-Line Parameters

ZombieBuddy accepts configuration parameters through the Java agent argument string. Parameters are passed as `key=value` pairs separated by commas.

---

## Usage

**macOS / Linux:**

```
-javaagent:ZombieBuddy.jar=param1=value1,param2=value2 --
```

**Windows:**

```
-agentlib:zbNative=param1=value1,param2=value2 --
```

> **Note:** The `--` at the end is mandatory.

---

## Parameters

### verbosity

Controls the amount of logging output.

| Value | Description |
|-------|-------------|
| `-2` | ERROR - log errors only |
| `-1` | WARN  - log warnings + ERROR |
| `0`  | (default) INFO - log informational messages + WARN |
| `1`  | DEBUG - log more debugging information, including the patches transformation progress info |
| `2`  | TRACE - log a vast amount of details |

**Example:**

```
-javaagent:ZombieBuddy.jar=verbosity=2 --
```

Can also be set via the `ZB_VERBOSITY` environment variable (overrides the command-line value).

---

### policy

Controls how ZombieBuddy handles unknown or changed Java mod JARs.

| Value | Description |
|-------|-------------|
| `prompt` | (default) Ask via a native dialog for each unknown/changed JAR |
| `deny-new` | Silently skip any JAR that isn't already approved. No dialogs shown |
| `allow-all` | Load every JAR without prompting. **Not recommended** - use only in controlled setups |

**Example:**

```
-javaagent:ZombieBuddy.jar=policy=deny-new --
```

The policy is locked during agent startup, before any Java mod is loaded. A later-loading Java mod cannot change it.

---

### allow_unsigned_mods

Controls whether mods without ZBS signatures are allowed.

| Value | Description |
|-------|-------------|
| `false` | (default) Treat missing `.zbs` sidecar as an invalid signature (blocked unless `policy=allow-all`) |
| `true` | Allow mods without `.zbs` signature files (explicit opt-in) |

**Example:**

```
-javaagent:ZombieBuddy.jar=allow_unsigned_mods=true --
```

> **Note:** Invalid signatures (present `.zbs` but verification fails) are always blocked when ZBS verification is enabled.
>
> **Security:** Preloaded Java mods must have a valid `.zbs` signature unless `policy=allow-all`. A preload entry is pinned to the approved JAR's SHA-256; replacing the JAR or deleting its `.zbs` requires re-approval.

---

### frontend

Selects the UI for Java mod approval dialogs.

| Value | Description |
|-------|-------------|
| `auto` | (default) Automatically select the best available frontend |
| `swing` | Use Swing batch dialog + TinyFileDialogs for per-mod prompts |
| `tinyfd` | Use TinyFileDialogs for all prompts |
| `console` | Use stdin/stdout (headless mode) |

**Example:**

```
-javaagent:ZombieBuddy.jar=frontend=console --
```

---

### http_client_timeout

Timeout in seconds for HTTP connections and requests. Applies to all outbound HTTP calls: Steam Workshop API, Steam profile pages, and the GitHub authors list.

| Value | Description |
|-------|-------------|
| `5` | (default) |
| `30` | Use 30 seconds as both the connect and request timeout |

**Example:**

```
-javaagent:ZombieBuddy.jar=http_client_timeout=30 --
```

---

### http_cache_ttl

Time-to-live in seconds for the in-memory HTTP response cache. Applies to all outbound HTTP calls: Steam Workshop API, Steam profile pages, and the GitHub authors list.

| Value | Description |
|-------|-------------|
| `3600` | (default) Cache responses for 1 hour |
| `0` | Disable caching entirely |

**Example:**

```
-javaagent:ZombieBuddy.jar=http_cache_ttl=0 --
```

---

### config_dir

Directory used for ZombieBuddy configuration and cache files. Defaults to `~/.zombie_buddy`.

**Example:**

```
-javaagent:ZombieBuddy.jar=config_dir=/path/to/zombie_buddy_config --
```

---

### prop_prefix

Import JVM system properties into ZombieBuddy agent arguments. When set, any system property whose name starts with this prefix plus `.` is read, the prefix and dot are stripped, and the remaining name becomes a ZombieBuddy argument.

If `prop_prefix` is not specified, ZombieBuddy does not read system properties into agent arguments.

**Example:**

```
-Dzb.verbosity=2 -Dzb.policy=deny-new -javaagent:ZombieBuddy.jar=prop_prefix=zb --
```

This is equivalent to:

```
-javaagent:ZombieBuddy.jar=verbosity=2,policy=deny-new --
```

---

### experimental

Enables experimental patches, including the HTTP Lua API. This is a flag parameter (no value needed).

**Example:**

```
-javaagent:ZombieBuddy.jar=experimental --
```

> **Warning:** Experimental features can expose game state and arbitrary Java/Lua capabilities. Keep them disabled outside development.

---

## Experimental HTTP Lua API

The HTTP API is only available when `experimental` is enabled **and** `lua_server_port` is set. `POST /lua` executes Lua in the game process, so it is protected by a bearer token.

### lua_server_host

Bind address for the HTTP server. Default: `127.0.0.1`.

Loopback addresses (`127.0.0.0/8`, `::1`, `localhost`) are always accepted. Any other address (`0.0.0.0`, `*`, a LAN IP, etc.) is refused and replaced with `127.0.0.1` unless `lua_server_allow_remote=true` is also set.

### lua_server_allow_remote

| Value | Description |
|-------|-------------|
| `false` | (default) Force loopback binding for non-loopback `lua_server_host` values |
| `true` | Permit remote binding. The token is sent over cleartext HTTP; use a VPN or SSH tunnel |

**Example:**

```
-javaagent:ZombieBuddy.jar=experimental,lua_server_host=0.0.0.0,lua_server_allow_remote=true --
```

### lua_server_port

Port for the HTTP server. Use `random` or `0` for an ephemeral port. When using a random port, the chosen port is written to `zbLuaAPI.txt` in the game cache directory once the game finishes initializing.

### lua_server_token

Bearer token for `/`, `/lua`, and `/log`. It must be at least 32 characters and contain no whitespace.

When omitted or invalid, ZombieBuddy generates a random 64-character hex token and writes it to `zbLuaAPI.token` next to `zbLuaAPI.txt` when the game finishes initializing. The file is created owner-only (`rw-------`) on POSIX systems.

A generated token is not usable until it has been persisted. If the token file cannot be written, `/lua` and `/log` return `503`; `/status` and `/version` remain available for health checks.

**Example:**

```
-javaagent:ZombieBuddy.jar=experimental,lua_server_port=4444,lua_server_token=<at-least-32-characters> --
```

### Authentication and limits

Send exactly one of these headers:

```
X-ZombieBuddy-Token: <token>
Authorization: Bearer <token>
```

- Tokens in the URL/query string are never accepted.
- Requests with an `Origin` header are rejected with `403` to block browser-originated requests.
- Missing or wrong tokens return `401` with `WWW-Authenticate: Bearer realm="ZombieBuddy"`.
- `POST /lua` body limit: 1 MiB (`413` above the limit).
- `GET /log?lines=N`: default 100, maximum 5000 lines.
- The HTTP server uses a fixed pool of 4 worker threads.
- `/status` and `/version` do not require a token.

**Example request:**

```
curl -X POST "http://127.0.0.1:4444/lua" \
  -H "X-ZombieBuddy-Token: $(cat ~/Zomboid/zbLuaAPI.token)" \
  --data-binary 'print("hello")'
```

> **Security:** Any process that can read the token file can execute Lua in the game process. On shared machines, protect the game cache directory and prefer loopback binding. Remote binding exposes the token to network sniffing unless tunnelled.

### lua_task_timeout

Timeout in milliseconds for queued Lua tasks. Default: `5000`.

---

### patches_jar

Load additional patch JARs at startup. Useful for development or testing patches without packaging them as a mod.

**Format:** `path:package_name` - multiple entries separated by semicolons.

**Example:**

```
-javaagent:ZombieBuddy.jar=patches_jar=/path/to/MyPatches.jar:com.example.patches --
```

Multiple JARs:

```
-javaagent:ZombieBuddy.jar=patches_jar=/path/to/First.jar:com.first;/path/to/Second.jar:com.second --
```

---

### expose_classes

Expose Java classes to Lua at startup. Comma-separated list of fully-qualified class names.

**Example:**

```
-javaagent:ZombieBuddy.jar=expose_classes=com.example.MyClass,com.example.OtherClass --
```

---

### exit_after_game_init

Exit the game immediately after initialization completes. Useful for testing or CI pipelines. This is a flag parameter (no value needed).

**Example:**

```
-javaagent:ZombieBuddy.jar=exit_after_game_init --
```

---

## Combining Parameters

Multiple parameters can be combined with commas:

```
-javaagent:ZombieBuddy.jar=verbosity=1,policy=deny-new,allow_unsigned_mods=false --
```

Windows example:

```
-agentlib:zbNative=verbosity=2,experimental --
```

---

## Environment Variables

| Variable | Description |
|----------|-------------|
| `ZB_VERBOSITY` | Sets verbosity level (overrides command-line `verbosity` parameter) |
