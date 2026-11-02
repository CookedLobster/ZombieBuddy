# ZombieBuddy Dev/Debug Functions

Global Lua helpers exposed by ZombieBuddy for inspecting and manipulating Java/Lua objects. Use from the in-game Lua console or from mod scripts.

> **Note:** ZombieBuddy must be loaded with **experimental mode** and **devtools** enabled.
>
> macOS / Linux:
> ```
> -javaagent:ZombieBuddy.jar=experimental=true,devtools=true --
> ```
> Windows:
> ```
> -agentlib:zbNative=experimental=true,devtools=true --
> ```
> See [CommandLine.md](CommandLine.md) for full parameter reference.
>
> **Warning:** These helpers expose broad reflective access to Java objects, including field
> writes and method invocation. Enable them only in a development environment.

For other Lua APIs (Events, Watches, Status), see [LuaAPI.md](LuaAPI.md).

---

## Inspection

### `zbinspect(obj)` / `zbinspect(obj, includePrivate)`

Returns a table describing the object:

- **`type`** — Full Java class name (e.g. `"java.util.HashSet"`).
- **`element_types`** — (Collections only) 1-based array of runtime element class names; best-effort, capped.
- **`fields`** — Table of field names → values (see `zbfields`).
- **`methods`** — 1-based array of method signature strings, sorted by method name (see `zbmethods`).

`includePrivate` (boolean): if true, includes private and inherited fields/methods. Default false.

---

### `zbmethod(obj)`  
*Alias for `ZombieBuddy.getCallableInfo(obj)`.*

Returns metadata for a **callable** (Lua function or Java-exposed method):

- **Lua closure:** `kind = "lua"`, plus `file`, `filename`, `name`, `numParams`, `isVararg`, `line`, `fileLastModified`.
- **Java (MultiLuaJavaInvoker / LuaJavaInvoker):** `kind = "java"`, `className`, `simpleName`, and **`invokers`** — array of per-overload tables with `targetClass`, `targetSimpleClass`, `methodName`, `declaringClass`, `methodDebugData`. On error, `unwrapError` may be set.

---

### `zbmethods(obj)` / `zbmethods(obj, includePrivate)`

Returns a **1-based Lua array** of method signature strings, sorted alphabetically by method name.

- For **Java objects:** uses reflection; each signature is `ReturnType name(ArgType, ...)`. Overloads appear as multiple entries.
- For **Kahlua-exposed callables** (e.g. `getSoundManager().PlaySound`): uses `LuaUtils.addInvokersInfo`; array entries are the `methodDebugData` strings (e.g. `"Audio obj:PlaySound(String arg1, boolean arg2, float arg3)\n"`).

`includePrivate`: include private and inherited methods (reflection only).

---

### `zbfields(obj)` / `zbfields(obj, includePrivate)`

Returns a table of **field name → value** for the object (reflection). Inaccessible fields show `"[inaccessible]"`. `includePrivate` includes private and inherited fields.

---

## Get / set / call by name

### `zbget(obj, name)` / `zbget(obj, name, defaultValue)`

Gets the field or property `name` on `obj`. Returns `defaultValue` (or nil) if missing/inaccessible.

### `zbset(obj, name, value)`

Sets the field or property `name` on `obj` to `value`. Returns boolean success.

### `zbcall(obj, methodName, ...)`

Calls the method `methodName` on `obj` with the given arguments. Returns the method’s return value. Uses reflection (e.g. for calling Java methods by string name from Lua).

---

## Table/collection helpers

### `zbkeys(obj)`

Returns a 1-based array of keys. Supports `KahluaTable` and Java `Map`.

### `zbvalues(obj)`

Returns a 1-based array of values. Supports `KahluaTable` and Java `Map`.

---

### `zbmap(obj, closure)`  
**or** `zbmap(obj, methodName)`

- **With Lua function `closure`:** For each (key, value) in table/map or (index, value) in list, calls `closure(key, value)` (or `closure(index, value)` for list). Return 0 values to skip, 1 to set `out[key] = value`, 2 to set `out[newKey] = newVal`. Builds and returns a new table.
- **With string `methodName`:** For each value, calls the no-arg Java method `methodName` on that value and uses the result (or original value if call fails). Works on `KahluaTable`, `Map`, `List`.

---

### `zbgrep(obj, pattern)` / `zbgrep(obj, pattern, caseSensitive)`  
**or** `zbgrep(obj, predicate)`

**String pattern:** Keeps entries where the key or value string contains `pattern`. For lists, matches the element’s string. Case-insensitive by default; use `zbgrep(obj, pattern, true)` for case-sensitive. For non-table/list/map objects, runs on `zbinspect(obj)` and greps `fields` and `methods`.

**Callable predicate:** Keeps entries where the predicate returns truthy (non-nil, not false).

- **Table / Map:** predicate is called with `(key, value)`.
- **List:** predicate is called with `(item)`.
- **Other:** same as pattern mode: greps inspected `fields` and `methods` using the predicate.

Returns a new table (or list-like table for lists) with only the matching entries.

---

### `zbgreplog(substring)`

Reads the game console log file and returns a 1-based table of **lines** that contain `substring`. Returns nil if the file cannot be read or substring is null/empty.

---

## ZombieBuddy class (callable metadata)

Available as `ZombieBuddy` in Lua:

- **`ZombieBuddy.getVersion()`** — Mod version string.
- **`ZombieBuddy.getFullVersionString()`** — e.g. `"ZombieBuddy v1.0"`.
- **`ZombieBuddy.getClosureFilename(obj)`** — Filename of a Lua closure, or nil.
- **`ZombieBuddy.getClosureInfo(obj)`** — Table with `file`, `filename`, `name`, `numParams`, `isVararg`, `line`, `fileLastModified` for a Lua closure; nil otherwise.
- **`ZombieBuddy.getCallableInfo(obj)`** — Same as `zbmethod(obj)`: metadata for Lua closures and Java callables (including `invokers` for exposed Java methods).

---

## Summary table

| Function | Purpose |
|----------|---------|
| `zbinspect` | Type, element_types (collections), fields, methods |
| `zbmethod` | Callable metadata (closure or Java invoker) |
| `zbmethods` | Array of method signatures sorted by name |
| `zbfields` | Table of field names → values |
| `zbget` / `zbset` | Get/set field by name |
| `zbcall` | Call method by name with args |
| `zbkeys` / `zbvalues` | Keys or values as 1-based array |
| `zbmap` | Map table/list/map with closure or method name |
| `zbgrep` | Filter by string pattern or predicate |
| `zbgreplog` | Lines from console log containing substring |
