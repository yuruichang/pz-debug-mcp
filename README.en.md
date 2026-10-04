# PZ Debug MCP 0.3.1

[简体中文](README.md) | **English**

Connect an MCP-compatible client to Project Zomboid. The ZombieBuddy Java core collects object fields, JVM and Java mod information, handles file communication, and writes records in the background. A Lua adapter provides access to built-in Debug globals and tables, vehicle sampling, registered tests, and controlled reloads. Data is archived locally and retrieved on demand.

The original file bridge protocol and all existing tools are retained, with additional tools for Java runtime queries, field inspection, and bounded method tracing. The Java diagnostic mailbox runs independently of game requests: recorded data, cached errors, and JVM state remain accessible while Lua is paused or a game request is waiting. See the [Java bridge guide](docs/JAVA_BRIDGE.md) for detailed limits.

Personal private repository: [yuruichang/pz-debug-mcp](https://github.com/yuruichang/pz-debug-mcp). Each completed and verified batch of code changes is committed and pushed. Runtime data, saves, game files, and machine-specific configuration are excluded.

Targets **B42.21.0, Java 25, and ZombieBuddy 2.3.2**, including the optimized build installed locally. The renamed APIs in ZombieBuddy 3.x are not yet supported. The older Lua version has been tested in a real save. The 0.3.1 Java version passes JVM, MCP, and the game's Kahlua checks; actual save startup validation is documented separately in the [validation log](VALIDATION.md). B42.20 and B41 have not been verified. Supporting documents linked from this README are currently in Chinese.

## Installation and connection

Requires Python 3.11+ and ZombieBuddy installed and enabled in the game. Building from source also requires JDK 25:

```powershell
.\setup.ps1 -Development
.\build.ps1
.\install-mod.ps1
```

The installer prepares a staging package at `%USERPROFILE%\Zomboid\Workshop\PZDebugMCP`, with the mod inside `Contents\mods\PZDebugMCP`. An existing local `mods\PZDebugMCP` is moved into staging to avoid loading the same Mod ID twice. Existing staged mod files are backed up before an update; the Workshop ID, visibility, description, and custom preview are preserved. Enable **ZombieBuddy** and **PZDebugMCP**, fully restart the game with `-debug`, approve the generated Java JAR, and enter a test save. Outside Debug mode, only status queries are accepted.

On entering the game, the mod disables pause on focus loss through the built-in `getOptionPauseOnFocusloss/setOptionPauseOnFocusloss` APIs and checks the setting every second. The game can keep running while you switch to your AI client. `focus_pause.disabled=true` in `pz_status` confirms that the setting is disabled. Manual pauses, Lua breakpoint pauses, and JVM suspension are unaffected.

`setup.ps1` creates `mcp-config.json` with absolute paths for your machine. Add its `pz_debug` entry to your AI client's MCP configuration. The file uses the standard `mcpServers` JSON format. Clients with a different configuration format can use the same command, arguments, and environment variable:

```text
Command: <project directory>\.venv\Scripts\python.exe
Arguments: -m pz_debug_mcp.server --zomboid-dir C:\Users\<your-user>\Zomboid
Environment: PYTHONUTF8=1
Transport: stdio
```

Your client should launch the server directly. `run-mcp.cmd` is the manual entry point; waiting for MCP input during a normal run is expected. To check the connection:

```powershell
.\run-mcp.cmd --doctor
```

`--zomboid-dir` points to the **game cache directory**, not the Steam installation. If the game uses `-cachedir`, use that same directory for installation, configuration generation, and the MCP server:

```powershell
.\setup.ps1 -ZomboidDir D:\PZTest
.\install-mod.ps1 -ZomboidDir D:\PZTest
.\run-mcp.cmd --zomboid-dir D:\PZTest --doctor
```

The mod and Debug mode must also be enabled on the server. The client and server use separate `client` and `server` mailboxes; the tools' `endpoint` parameter selects one. Server vehicle queries require an explicit vehicle ID. For a remote server, run the MCP server on that machine and give it access to the local cache. This version does not forward requests over a network.

## Tools

| Tool | Purpose |
| --- | --- |
| `pz_status` | Version, heartbeat, Debug state, endpoint, capabilities, registered tests and modules; offline diagnostics |
| `pz_read_errors` | Bounded incremental reads of built-in Lua Debug errors and bridge events |
| `pz_inspect_vehicle` | Loaded vehicle position, speed, engine and towing relationships, with optional part snapshots |
| `pz_capture_vehicle_trace` | Continuous in-game sampling with `start/read/stop` and paginated reads |
| `pz_run_test` | Run a registered predefined test |
| `pz_reload_mod_lua` | Clean up, reload through the built-in API, and initialize a registered module |
| `pz_read_console` | Incremental reads of local `console.txt`, including while the game is offline |
| `pz_list_debug_interfaces` | Built-in global API and object field/method catalogs, with categories and pagination |
| `pz_query_debug` | Global, object method, reflection field, and Lua table queries; results are recorded automatically |
| `pz_read_recorded_data` | Paginated archive queries by target and record type, including historical sessions and offline reads |
| `pz_configure_recorder` | Sampling interval, execution budget, object graph depth, handle capacity, and refresh period |
| `pz_watch_debug` | Add, list, or remove recurring queries that require arguments |
| `pz_java_runtime` | JVM metrics, thread stacks, loaded classes, Java mod/patch metadata, and object roots |
| `pz_inspect_java` | Java private/static fields, arrays, and method metadata, with explicit access limits |
| `pz_trace_java` | Bounded sampling of a selected Java method's arguments, return values, exceptions, and duration |

## General collection and on-demand retrieval

The package includes catalogs generated from the local 42.21.0 signatures: **764 global APIs** and methods for **1152 types and their parent classes/interfaces**. Currently, only **16 global reader signatures reviewed against bytecode** and explicitly reviewed object methods are enabled. Other APIs remain in the catalog but are disabled by default. Names starting with get/is/has are not taken as evidence that a method has no side effects. See the [reader review](docs/READER_REVIEW.md).

Collection starts without waiting for AI requests. The bridge polls reviewed roots and selected methods, recording health, coordinates, weather, time, version, settings, and other data. SandboxVars is read through raw table access. Automatic traversal of `_G` is disabled. The Java core traverses fields of existing objects within depth and capacity limits; it does not execute unknown getters. Inherited methods can be browsed through Java metadata, and only reviewed signatures can be invoked. Unexposed members are not probed through speculative calls. Lua table getClass/__index hooks and functions stored in tables are not executed.

Each record contains a session, increasing sequence number, timestamp, target, and plain data. Objects are linked through session-local handles. Java reflection reads object fields, including accessible private application fields. Static fields require confirmed class initialization. Access denied by JDK module encapsulation is reported with a reason. Lua tables retain raw access; the fallback backend still uses the built-in reflection APIs.

Handles identify objects by reference, not by the content equality implemented by Java equals/hashCode. Two lists with equal contents receive different handles. Identity hash collisions are distinguished by reference equality, and changing an object's contents does not change its handle. Collection accessors such as get/size still require separate review; otherwise, only field/method metadata is available.

For example, query the player and health:

```json
{"tool":"pz_query_debug","arguments":{"target":"getPlayer"}}
{"tool":"pz_list_debug_interfaces","arguments":{"scope":"object","handle":"handle from the previous result","limit":50}}
{"tool":"pz_query_debug","arguments":{"target":"player handle","member":"getHealth"}}
{"tool":"pz_read_recorded_data","arguments":{"target":"getPlayer","limit":50}}
```

Query weather or a Lua table:

```json
{"tool":"pz_query_debug","arguments":{"target":"getClimateManager"}}
{"tool":"pz_query_debug","arguments":{"target":"climate manager handle","member":"getTemperature"}}
{"tool":"pz_query_debug","arguments":{"target":"root:SandboxVars"}}
{"tool":"pz_query_debug","arguments":{"target":"SandboxVars table handle","action":"table","member":"DayLength"}}
```

APIs that require arguments accept scalars or `{"handle":"object handle"}`, such as the object argument for `getNumClassFields`. Use `action=field` with `member` or `field_index` to read a field. Use `action=inspect` with `offset/limit` to browse an object in pages. Handles expire when the session changes, capacity evicts them, or handle capacity is changed. Previously recorded data remains readable.

For queries with coordinates, indices, or object arguments, first confirm the arguments and then register recurring collection:

```json
{"tool":"pz_watch_debug","arguments":{"query":{"target":"getNumClassFields","arguments":[{"handle":"player handle"}]},"interval_ms":1000}}
{"tool":"pz_watch_debug","arguments":{"action":"list"}}
{"tool":"pz_read_recorded_data","arguments":{"kind":"query","target":"getNumClassFields"}}
```

Up to 128 recurring queries are supported, with intervals of 100–60000 ms. Game callbacks collect them. They reset in a new session; expired object arguments are reported as errors in the watch list.

## Storage and coverage limits

The game cache at `Lua/PZDebugMCP/{client,server}/records/` contains 16 rotating buffer files, each holding up to 128 records or approximately 256 KiB. An index publishes records only after their writes have closed. While running, the MCP server archives new records to `recordings.sqlite3` for the same endpoint every 0.5 seconds, regardless of whether the AI makes a query. The archive retains all received history when game-side buffers rotate; its size grows with debugging time.

`pz_read_recorded_data` returns `available_sessions`. Set `recording_session` to query an older session recorded under a reviewed policy. For pagination, pass the last `cursor` as `after`; `session` identifies a cursor crossing a session boundary. Older records created under an unreviewed policy are not opened, archived, or returned, and unreviewed database sessions are isolated by default. `gaps` identifies records lost to buffer rotation. Keep the MCP server running from the start of debugging to retain the full received history.

A complete catalog does not mean every API has been reviewed or can be executed. By default, the Java core and Lua adapter each schedule up to four jobs every 100 ms, each with an approximately 2 ms cooperative budget, a depth limit of four levels, and up to 4096 handles. `pz_configure_recorder` changes these limits but does not enable unreviewed getters. The budget cannot forcibly interrupt a single call.

`pz_status.live.backend=zombiebuddy_java` and `read_policy=java_fields_reviewed_lua_v1` confirm that the Java backend and new recording policy are active. `automatic_object_graph=true` enables field graph collection, not unknown method execution. `lua_fallback` and the older policy mean the Java backend was not loaded. Enabling additional APIs requires implementation review, an allowlist entry, and verification. Unloaded areas, unbounded argument domains, and paused execution paths remain subject to the built-in APIs' limits. Current records are not a complete snapshot of the world.

Recommended first calls:

```json
{"tool":"pz_status","arguments":{"endpoint":"client"}}
{"tool":"pz_run_test","arguments":{"name":"bridge_self_test"}}
{"tool":"pz_inspect_vehicle","arguments":{"include_parts":true}}
{"tool":"pz_run_test","arguments":{"name":"vehicle_relationships","arguments":{"vehicle_id":1}}}
```

The outer `tool/arguments` structure in these examples describes the intended call. Clients convert it to standard MCP `tools/call` requests. Vehicle IDs must come from actual snapshots. If omitted, the tool uses the vehicle occupied by the local player.

Vehicle sampling workflow:

1. Call `pz_capture_vehicle_trace` with `action=start`, `duration_seconds=10`, and `interval_ms=100`. Save the returned `trace_id`.
2. Reproduce the driving or towing issue in the game.
3. Read with `action=read` and that `trace_id`. For subsequent pages, pass the returned `cursor` as `after`; continue while `has_more=true`.
4. Stop early with `action=stop` or wait for `done=true`. Inspect `relationship_changed`, trailer snapshots, and timestamps. Once detached, new state for the old trailer may no longer be available, but earlier samples are retained.

Sampling intervals range from 50–1000 ms and captures last 1–60 seconds. At most four captures are kept, each retaining the latest 600 samples, with up to 100 samples per response. `gap=true` means older samples have been overwritten. Starting a fifth capture evicts the oldest completed capture; a new capture is rejected if all four are still running. Sampling depends on actual game callback frequency and does not guarantee a fixed frame rate or synchronization with the physics thread.

Error reads also use `after/cursor` and `session`. `reset=true` identifies a new session, and `gap=true` reports overwritten events. The Lua adapter retains up to 256 events of up to 4096 string units each. The Java diagnostic mailbox exposes the latest 64 events with messages limited to 1024 string units, and marks truncated messages with `message_truncated`. Built-in Debug errors and `console.txt` are separate sources. This version does not intercept global `print` or scan every log file.

## Registering tests and reloads for your own mod

Built-in tests: `bridge_self_test` checks the bridge and JSON codec; `vehicle_relationships` checks reciprocal towing relationships; `example_counter` verifies that reloads do not duplicate events. The first two do not modify vehicles.

A mod can call:

```lua
local B = require 'PZDebugMCP/Bridge'
B.registerTest('my_vehicle_check', function(args)
    return { passed = true, vehicle_id = args.vehicle_id }
end, 'Vehicle check')
B.recordEvent('correction', 'Trailer correction was applied')
```

Test results must be bounded tables containing only plain data. Use `B.Json.array({...})` for arrays, `{}` for empty objects, and `B.Json.null` for JSON null. Do not return Java objects, functions, cyclic tables, or the entire game environment. Custom tests run synchronously in game callbacks and must limit their own execution time to avoid blocking the game.

Reloads require a fixed path registered inside the game; external callers can invoke only the registered name. See `Contents/mods/PZDebugMCP/42/media/lua/shared/PZDebugMCP/Example.lua`:

```lua
local B = require 'PZDebugMCP/Bridge'
local function onTick() end
local function cleanup()
    Events.OnTick.Remove(onTick)
    B.tests.my_check = nil
end
local function init()
    Events.OnTick.Remove(onTick)
    Events.OnTick.Add(onTick)
    B.registerTest('my_check', function() return { passed = true } end)
end
B.registerReloadable('my_module', {
    path = 'media/lua/shared/MyMod/Module.lua',
    cleanup = cleanup,
    init = init,
})
if not B.reloading then init() end
```

A reloaded file must register the same module name again. Once registration succeeds, the bridge calls the new `init`. Cleanup, reload, or initialization failure disables the module so it cannot be called in a partially initialized state. Fix the code and restart the game to restore it. Modules must manage old references, object migration, and other resources themselves; reloading does not update every existing object.

Mod files on both client and server use the built-in `reloadLuaFile`. In the local B42.21 build, `reloadServerLuaFile` prepends the server cache path, so it is not used for mod files. The bridge core and JSON module cannot reload themselves through this tool.

## Protocol and troubleshooting

Mailboxes live at `Zomboid/Lua/PZDebugMCP/{client,server}/` and use protocol version 1:

- `heartbeat.json`: the Java backend updates the session, version, and Debug state approximately every 500 ms. A heartbeat older than three seconds is considered offline.
- `request.json`: atomically replaced by Python; contains a random request ID, increasing sequence number, session, endpoint, and absolute expiry time. The game rejects previously processed sequence numbers, so delayed retransmissions cannot run again.
- `claim.json`: persists the claimed request ID before execution. The same ID is never executed again.
- `response.json` and `response.ready.txt`: the game closes the response file before writing the completion marker. The server accepts only complete results whose ID and session match.
- `mailbox.lock`: an operating-system process lock allows one request per mailbox at a time. It is released when the process exits. Java diagnostic requests use the separate `runtime/` mailbox.

Only bounded, predefined debugging operations are accepted. File contents are not executed as Lua code. JSON payloads are limited to 512 KiB and 24 nesting levels. If a heartbeat or response is being written, the reader waits for the next read. Multiple MCP instances can use the same mailbox serially. Do not share one endpoint cache directory between two game instances.

`TIMEOUT` means a response was not received in time. **It does not mean execution was canceled or never occurred.** A claimed request without a completed result causes further requests on that mailbox to return `INDETERMINATE`; the independent diagnostic mailbox remains usable. Wait for a late result or restart the game endpoint to create a new session. Do not automatically retry operations that modify state, or clear `claim.json` while the game is running to bypass this protection.

During a normal pause, the bridge also attempts polling through `OnTickEvenPaused`. While Lua is paused, the independent Java heartbeat, JVM queries, cached data, and offline archive remain accessible; game-object queries require callbacks to resume. Suspending the entire JVM blocks the bridge. Coroutine, call-frame, and local-variable APIs are currently cataloged only; unreviewed APIs are not invoked. This version does not provide full breakpoint/stepping control, JDWP, or arbitrary Lua execution.

## Development, validation, and packaging

See the [coverage report](docs/INTERFACE_COVERAGE.md) for the full API inventory. A status was assigned to each of the 764 global APIs and 24834 public method entries. In the tested single-player context, 573 new signatures passed read-only invocation checks with no new Lua errors. Non-data commands, state writes, insufficiently proven readers, methods with unverified transformed implementations, and missing-context cases remain disabled.

Reproduction tools are in `tools/BytecodeAudit.java`, `audit_interfaces.py`, `prepare_live_validation.py`, and `run_live_validation.py`. They read effect metadata using the actual game classpath, generate a review ledger, and temporarily load a validator into the existing reloadable example module for budgeted checks. After validation, the original example module is restored and temporary object references are released. Coverage reports contain no game values.

A successful call proves only that it returned for the recorded arguments and current object context. It does not establish coverage of every branch, multiplayer/breakpoint context, or argument combination. States such as `requires_review` are not passes and cannot be used to enable unknown getters.

```powershell
.\setup.ps1 -Development
.\build.ps1 -GameDir 'E:\Steam\steamapps\common\ProjectZomboid'
```

Building requires JDK 25's `javac` and an installed ZombieBuddy, and uses the selected game's Java/Kahlua runtime. Checks cover the standard MCP handshake, actual mailbox files, concurrency and timeouts, Lua 5.1 behavior, and the built-in Kahlua protocol, vehicle, reload, and Unicode paths. Game objects and events in these automated checks are controlled fixtures, not a substitute for testing in a real save.

Use `-RefreshCatalog` to extract global and public type signatures from the selected game again. Generated files contain API metadata only, not game implementations. The current catalogs and checks target 42.21.0; changing game versions requires a new review and in-game validation.

Artifacts include `dist/PZDebugMCP-mod-0.3.1.zip`, `dist/PZDebugMCP-workshop-0.3.1.zip`, and `dist/PZDebugMCP-source-0.3.1.zip`, with SHA-256 checksums. Extract the Workshop archive directly into the cache's Workshop directory. It includes a private-visibility workshop.txt, a 256×256 preview.png, metadata and images for the common and 42 directories, and the Java JAR. The source archive excludes local Python environments, game files, recording databases, and machine-specific connection configuration. Add `-InstallMod` to install after validation passes.

In-game acceptance: enter a Debug test save → `pz_status` is online with `debug_enabled=true` → self-test passes → inspect a vehicle while driving → complete a towing capture → reload `example_counter` twice and confirm that its test still runs. Dedicated servers require their own acceptance checks.

## References

- [Original analysis conversation](https://chatgpt.com/share/6ac0dcb7-f4f0-83ee-a225-3d990364fba8)
- [Official Lua/Debug API](https://projectzomboid.com/modding/zombie/Lua/LuaManager.GlobalObject.html)
- [Official vehicle API](https://projectzomboid.com/modding/zombie/vehicles/BaseVehicle.html)
- [MCP transport specification](https://modelcontextprotocol.io/specification/latest/basic/transports)

The built-in Debug mode does not provide an MCP port. This project calls built-in APIs from an in-game mod; local files and bytecode checks take precedence over potentially outdated web documentation.

## Workshop staging validation

Staging defaults to private visibility; new packages do not prefill a Workshop ID. Installation and building prepare local files without submitting a Steam item. After exiting the game, run `tools/check-workshop.ps1` to validate images, version directories, mod.info, and file types through the local game's SteamWorkshopItem.validateContents. The check does not call create/submitUpdate.
