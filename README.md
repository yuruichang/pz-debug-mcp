# PZ Debug MCP 0.3.1

**简体中文** | [English](README.en.md)

把支持 MCP 的客户端连接到 Project Zomboid。ZombieBuddy Java 核心负责对象字段采集、JVM 和 Java 模组信息、文件通信与后台记录；Lua 适配原版 Debug 全局、表、车辆采样、预定义测试与受控重载。数据在本机归档，按需查询。

保留原文件桥协议和全部既有工具，增加 Java 运行时查询、字段检查和有界方法追踪。Java 诊断邮箱独立于游戏请求：Lua 暂停或游戏请求等待期间，仍可取回已有记录、缓存错误和 JVM 状态。详细边界见 [Java 桥接说明](docs/JAVA_BRIDGE.md)。

个人私有仓库：[yuruichang/pz-debug-mcp](https://github.com/yuruichang/pz-debug-mcp)。每批完成并验证的代码修改均提交、推送；运行数据、存档、游戏文件与本机配置不上传。

目标为 **B42.21.0、Java 25、ZombieBuddy 2.3.2**，已兼容本机优化版；ZombieBuddy 3.x 的新包名 API 尚未适配。旧 Lua 版有实际存档验证记录；0.3.1 Java 版通过 JVM、MCP 和原版 Kahlua 检查，实际存档启动验收单独记录在 [验证说明](VALIDATION.md)。B42.20 与 B41 未验证。

## 安装与连接

需要 Python 3.11+，游戏已安装并启用 ZombieBuddy。源码安装还需 JDK 25：

```powershell
.\setup.ps1 -Development
.\build.ps1
.\install-mod.ps1
```

安装脚本默认准备 `%USERPROFILE%\Zomboid\Workshop\PZDebugMCP` 暂存包，模组位于其中的 `Contents\mods\PZDebugMCP`。已有本地 `mods\PZDebugMCP` 会移入暂存包，避免同一 Mod ID 重复加载；已有暂存模组更新前会备份，现有工坊 ID、可见性、说明和自定义预览保留。在游戏中启用 **ZombieBuddy** 和 **PZDebugMCP**，完整重启并加入 `-debug`，允许本次生成的 Java JAR，然后进入测试存档。非 Debug 模式只返回状态，不执行调试请求。

进入游戏时模组自动把原版 `getOptionPauseOnFocusloss/setOptionPauseOnFocusloss` 设置为关闭，并每秒重新校验。切换到 AI 客户端时游戏继续运行。`pz_status` 的 `focus_pause.disabled=true` 表示设置已经生效；此功能不解除手动暂停、Lua 断点暂停或 JVM 暂停。

`setup.ps1` 生成本机绝对路径配置 `mcp-config.json`。将其中 `pz_debug` 项加入 AI 客户端的 MCP 配置；它使用标准 `mcpServers` JSON 格式。使用其他配置格式的客户端，可填同样的命令、参数和环境变量：

```text
命令：本目录\.venv\Scripts\python.exe
参数：-m pz_debug_mcp.server --zomboid-dir C:\Users\你的用户名\Zomboid
环境变量：PYTHONUTF8=1
传输：stdio
```

客户端应直接启动服务。`run-mcp.cmd` 是手动启动入口，普通运行时等待 MCP 输入属于正常行为。查看连接诊断：

```powershell
.\run-mcp.cmd --doctor
```

`--zomboid-dir` 指向**缓存目录**，不是 Steam 游戏目录。若游戏使用 `-cachedir`，请给安装、配置生成和 MCP 服务指定同一个缓存目录，例如：

```powershell
.\setup.ps1 -ZomboidDir D:\PZTest
.\install-mod.ps1 -ZomboidDir D:\PZTest
.\run-mcp.cmd --zomboid-dir D:\PZTest --doctor
```

服务端也要启用模组与 Debug 模式。客户端和服务器分别使用 `client`、`server` 邮箱；工具的 `endpoint` 参数选择执行端。服务端省略车辆 ID 时会报错。远程服务器需在服务器所在机器运行 MCP 服务并访问其缓存，本版没有跨网络转发。

## 工具

| 工具 | 用途 |
| --- | --- |
| `pz_status` | 版本、心跳、Debug 状态、执行端、能力、已注册测试和模块；离线诊断 |
| `pz_read_errors` | 原版 Lua Debug 错误和桥接事件的有限增量 |
| `pz_inspect_vehicle` | 已加载车辆的位置、速度、引擎、拖挂关系和可选部件快照 |
| `pz_capture_vehicle_trace` | 游戏侧连续采样，`start/read/stop`，分页读取 |
| `pz_run_test` | 已注册的预定义测试 |
| `pz_reload_mod_lua` | 预注册模块的清理、原版重载和初始化 |
| `pz_read_console` | 本机 `console.txt` 增量；离线也可读取 |
| `pz_list_debug_interfaces` | 原版全局接口目录与对象字段/方法目录，支持分类和分页 |
| `pz_query_debug` | 通用全局、对象方法、反射字段及 Lua 表查询；结果自动记录 |
| `pz_read_recorded_data` | 按目标和类型分页检索自动归档的数据，支持历史会话与离线读取 |
| `pz_configure_recorder` | 采集频率、执行预算、对象图深度、句柄容量及刷新周期 |
| `pz_watch_debug` | 注册、列出和移除带参数接口的持续采集任务 |
| `pz_java_runtime` | JVM 指标、线程栈、已加载类、Java 模组/补丁元数据及对象根 |
| `pz_inspect_java` | Java 私有/静态字段、数组和方法元数据；明确报告访问边界 |
| `pz_trace_java` | 指定 Java 方法的参数、返回、异常和耗时，有界采样 |

## 通用数据采集与按需取回

随包附带从本机 42.21.0 签名生成的 **764 个全局 API** 和 **1152 个类型及其父类/接口** 的方法目录。当前只启用 **16 个经字节码审核的全局读取签名**及明确审核的对象方法。其他接口保留目录但默认禁用；不再根据 get/is/has 名称推断无副作用。参见 [读取审核](docs/READER_REVIEW.md)。

桥接无需等待 AI 请求就会启动采集，轮询审核过的根与指定方法，记录健康、坐标、天气、时间、版本和设置等。SandboxVars 使用原始表读取。自动 `_G` 关闭；Java 核心按深度和容量预算遍历已有对象字段，未知 getter 不执行。继承方法通过 Java 元数据浏览，只有审核过的签名可以调用。未暴露成员不再试探调用，Lua 表的 getClass/__index 和表内函数不执行。

每条记录有会话、递增序号、时间戳、目标和纯数据。对象以会话内句柄关联。Java 对象字段使用 Java 反射，包含可访问的私有应用字段；静态字段须确认已初始化，JDK 模块封装不可访问时报告原因。Lua 表保留原始读取，回退后端仍使用原版反射。

句柄按对象身份分配，不按 Java equals/hashCode 的内容相等规则合并。相同内容的两个列表拥有不同句柄；身份哈希碰撞使用原始引用相等比较区分，可变对象内容改变后保持句柄。集合的 get/size 等访问器仍须单独审核，未审核时只浏览字段/方法元数据。

例如查询玩家与健康值：

```json
{"tool":"pz_query_debug","arguments":{"target":"getPlayer"}}
{"tool":"pz_list_debug_interfaces","arguments":{"scope":"object","handle":"上一步返回的句柄","limit":50}}
{"tool":"pz_query_debug","arguments":{"target":"玩家句柄","member":"getHealth"}}
{"tool":"pz_read_recorded_data","arguments":{"target":"getPlayer","limit":50}}
```

查询天气或 Lua 表：

```json
{"tool":"pz_query_debug","arguments":{"target":"getClimateManager"}}
{"tool":"pz_query_debug","arguments":{"target":"天气对象句柄","member":"getTemperature"}}
{"tool":"pz_query_debug","arguments":{"target":"root:SandboxVars"}}
{"tool":"pz_query_debug","arguments":{"target":"沙盒表句柄","action":"table","member":"DayLength"}}
```

需要参数的接口可以传标量或 `{"handle":"对象句柄"}`，例如 `getNumClassFields` 的对象参数。用 `action=field` 和 `member` 或 `field_index` 读取字段；用 `action=inspect` 和 `offset/limit` 分页查看对象。句柄在会话切换、容量淘汰或调整容量后会失效，已有历史数据仍可读取。

对有坐标、索引或对象参数的查询，先确认参数，再注册一次持续采集：

```json
{"tool":"pz_watch_debug","arguments":{"query":{"target":"getNumClassFields","arguments":[{"handle":"玩家句柄"}]},"interval_ms":1000}}
{"tool":"pz_watch_debug","arguments":{"action":"list"}}
{"tool":"pz_read_recorded_data","arguments":{"kind":"query","target":"getNumClassFields"}}
```

最多 128 个持续查询，间隔 100–60000 ms，由游戏回调采集。它们在新会话中重置；旧对象参数失效会在列表中报告错误。

## 数据保存与覆盖边界

游戏缓存 `Lua/PZDebugMCP/{client,server}/records/` 保存 16 个轮换缓冲文件，每个最多 128 条或约 256 KiB，并发布已关闭写入的记录索引。MCP 服务运行后，每 0.5 秒自动把新记录归档到同一执行端的 `recordings.sqlite3`，与 AI 是否发起查询无关。已归档记录保留全部收到的历史，不随游戏缓冲轮换删除；本地归档会随调试时长增长。

`pz_read_recorded_data` 返回 `available_sessions`。指定 `recording_session` 可以检索审核策略下的旧会话；用上次 `cursor` 作为 `after` 分页，`session` 用于识别游标是否跨会话。旧版未审核的记录文件不会打开、归档或返回，旧数据库会话也默认隔离。`gaps` 标出已覆盖记录。要保留全程历史，请从调试开始就让 MCP 服务保持运行。

完整目录不代表所有接口已审核或可执行。Java 核心与 Lua 适配各自默认每 100 ms 调度，最多四项任务，各有约 2 ms 的协作预算，遍历深度四层，各自最多 4096 个句柄。可用 `pz_configure_recorder` 调整预算，但不能因此放开未审核 getter。单个调用无法被预算强制中断。

`pz_status.live.backend=zombiebuddy_java`、`read_policy=java_fields_reviewed_lua_v1` 表示 Java 后端与新记录策略已加载；`automatic_object_graph=true` 仅表示字段图采集，未启用未知方法。`lua_fallback` 和旧策略表示 Java 未加载。接口的后续启用需要先审核实现、补充白名单并验证。未加载区域、无限参数域和暂停执行路径仍有原版限制；不能把当前记录视为全量世界快照。

推荐先调用：

```json
{"tool":"pz_status","arguments":{"endpoint":"client"}}
{"tool":"pz_run_test","arguments":{"name":"bridge_self_test"}}
{"tool":"pz_inspect_vehicle","arguments":{"include_parts":true}}
{"tool":"pz_run_test","arguments":{"name":"vehicle_relationships","arguments":{"vehicle_id":1}}}
```

示例中的外层 `tool/arguments` 表示调用意图，各客户端会转换为标准 MCP `tools/call`。车辆 ID 必须取自实际快照；省略时使用本地玩家正在乘坐的车辆。

车辆采样流程：

1. 调用 `pz_capture_vehicle_trace`，参数 `action=start`、`duration_seconds=10`、`interval_ms=100`，保存返回的 `trace_id`。
2. 在游戏中复现驾驶或拖挂问题。
3. 用 `action=read` 和该 `trace_id` 读取，后续把返回的 `cursor` 作为 `after`；`has_more=true` 时继续翻页。
4. `action=stop` 提前结束，或等待 `done=true`。观察 `relationship_changed`、拖车快照与时间戳。断开后无法再取得旧拖车的新状态，断开前的样本仍保留。

采样间隔 50–1000 ms，持续 1–60 秒，最多四组，每组保留最近 600 条，每次返回最多 100 条。`gap=true` 表示旧样本已经覆盖。开始第五组时会淘汰最旧的已结束组；四组都在运行时拒绝新增。采样频率受实际游戏回调频率限制，不保证固定帧率或物理线程同步。

错误读取也使用 `after/cursor`，并保存 `session` 以识别重进游戏后的重置；`reset=true` 表示新会话，`gap=true` 表示部分旧事件已覆盖。Lua 适配层最多保留 256 条事件，每条最多 4096 个字符串单位；Java 诊断邮箱返回最近 64 条事件，每条消息最多 1024 个字符串单位，并用 `message_truncated` 标记截断。原版 Debug 错误与 `console.txt` 是两个来源，本版不接管全局 `print` 或扫描所有日志文件。

## 给自己的 Mod 注册测试和重载

内置测试：`bridge_self_test` 检查桥接和 JSON；`vehicle_relationships` 检查拖挂两端关系是否互相一致；`example_counter` 用于验证重载后事件不重复。前两项不修改车辆。

模组内可调用：

```lua
local B = require 'PZDebugMCP/Bridge'
B.registerTest('my_vehicle_check', function(args)
    return { passed = true, vehicle_id = args.vehicle_id }
end, 'Vehicle check')
B.recordEvent('correction', 'Trailer correction was applied')
```

测试返回值必须是有限的纯数据表。数组用 `B.Json.array({...})`；空对象用 `{}`，JSON null 用 `B.Json.null`。不要返回 Java 对象、函数、循环表或整个游戏环境。自定义测试在游戏回调中同步运行，必须自行限制耗时，避免循环阻塞。

重载必须在游戏内注册固定路径，外部只能按名称调用。参考 `Contents/mods/PZDebugMCP/42/media/lua/shared/PZDebugMCP/Example.lua`：

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

文件被重载时必须重新注册同名模块；桥接在成功注册后调用新 `init`。清理、重载或初始化失败会停用该模块，避免再次调用半初始化状态；修复代码后重启游戏恢复。模块需要自己管理旧引用、对象迁移和其他资源，重载不代表所有旧对象已经更新。

客户端与服务器的 Mod 文件都使用原版 `reloadLuaFile`。本机 B42.21 的 `reloadServerLuaFile` 会把路径拼接到服务器缓存，因此未用于 Mod 文件重载。桥接核心和 JSON 模块不能通过此工具自我重载。

## 协议与故障处理

邮箱位于 `Zomboid/Lua/PZDebugMCP/{client,server}/`，协议版本 1：

- `heartbeat.json`：Java 约每 500 ms 更新会话、版本和 Debug 状态；超过三秒视为离线。
- `request.json`：Python 原子替换，包含随机请求 ID、单调递增序号、会话、执行端和绝对过期时间。游戏拒绝已处理的旧序号，延迟重传不会再次执行。
- `claim.json`：游戏在操作前持久写入已领取编号。同一编号不会再次执行。
- `response.json` 与 `response.ready.txt`：游戏先关闭响应文件，最后关闭完成标记；服务只接受编号和会话匹配的完整结果。
- `mailbox.lock`：操作系统进程锁，每个邮箱一次处理一个请求；服务退出后自动释放锁。Java 诊断请求使用独立的 `runtime/` 邮箱。

请求只允许有限的调试操作，不将文件内容当作 Lua 代码执行。JSON 大小最多 512 KiB，嵌套最多 24 层；心跳或响应正好处于写入期间会等待下一次读取。共享邮箱的两个 MCP 实例可以串行使用，不应有两个游戏实例共用同一个端点缓存目录。

`TIMEOUT` 只表示没有及时收到结果，**不代表代码取消或未执行**。已经领取却没有完成结果的请求，会让同一邮箱中的后续请求返回 `INDETERMINATE`；独立诊断邮箱仍可继续工作。等待迟到的结果，或重启游戏执行端建立新会话。不要自动重试修改操作，也不要在游戏运行时手动清空 `claim.json` 来绕过此检查。

普通暂停时同时尝试 `OnTickEvenPaused`；Lua 暂停时独立 Java 心跳、JVM 查询、缓存和离线归档仍可读取，游戏对象查询需要回调恢复。整个 JVM 暂停会阻断桥接。协程、调用帧和局部变量接口目前仅列入目录，未审核项不调用。本版不提供完整断点/单步控制、JDWP 或任意 Lua 执行。

## 开发验证与打包

完整接口覆盖结果见 [覆盖报告](docs/INTERFACE_COVERAGE.md)。本次对 764 个全局接口和 24834 个公开方法条目逐项建立状态，当前真实单机环境中 573 个新签名通过只读调用验证，新增 Lua 错误为零。非数据指令、状态写入、纯读证据不足、代理改写及上下文缺失项保留禁用。

复现工具位于 `tools/BytecodeAudit.java`、`audit_interfaces.py`、`prepare_live_validation.py` 和 `run_live_validation.py`。它按真实游戏类路径读取效果元数据，生成审核清单，再临时装入已有的可重载示例模块进行有预算限制的验证。验证结束后恢复示例模块，释放临时对象引用；覆盖报告不包含游戏值。

调用通过只表示在记录的参数和当前对象上下文中返回成功，不代表已经穷尽业务分支、多人/断点环境或所有参数组合。`requires_review` 等状态不是通过，不能以此启用未知 getter。

```powershell
.\setup.ps1 -Development
.\build.ps1 -GameDir 'E:\Steam\steamapps\common\ProjectZomboid'
```

构建需要 JDK 25 的 `javac` 与已安装的 ZombieBuddy，并使用指定游戏自带 Java/Kahlua。它运行标准 MCP 握手、真实文件桥、并发与超时测试、Lua 5.1 行为测试，以及原版 Kahlua 的协议/车辆/重载/Unicode 检查。游戏对象与事件在这些自动测试中是受控桩，不替代实际存档联调。

加 `-RefreshCatalog` 可从指定游戏重新提取全局及公开类型签名；生成内容只有接口元数据，不包含游戏实现。当前目录与测试针对 42.21.0，换版本仍需重新审核与实际联调。

产物包括 `dist/PZDebugMCP-mod-0.3.1.zip`、`dist/PZDebugMCP-workshop-0.3.1.zip` 和 `dist/PZDebugMCP-source-0.3.1.zip`，附 SHA-256 校验文件。工坊包直接解压到缓存的 Workshop 目录，包含私密可见性的 workshop.txt、256×256 preview.png、common/42 元数据与展示图，以及 Java JAR。源码包不包含本机 Python 环境、游戏文件、记录数据库或本机路径配置。加 `-InstallMod` 可在验证通过后安装模组。

实际游戏验收：进入 Debug 测试存档 → `pz_status` 在线且 `debug_enabled=true` → 自检通过 → 驾驶车辆读取快照 → 完成一组拖挂采样 → 连续重载 `example_counter` 两次并确认测试仍能执行。服务端需另做同样的验收。

## 依据

- [原分析会话](https://chatgpt.com/share/6ac0dcb7-f4f0-83ee-a225-3d990364fba8)
- [官方 Lua/Debug 接口](https://projectzomboid.com/modding/zombie/Lua/LuaManager.GlobalObject.html)
- [官方车辆 API](https://projectzomboid.com/modding/zombie/vehicles/BaseVehicle.html)
- [MCP 传输规范](https://modelcontextprotocol.io/specification/latest/basic/transports)

官方 Debug 模式不自带 MCP 端口。本项目通过游戏内 Mod 调用原版接口；本机文件与字节码核对优先于可能滞后的网页文档。

## 创意工坊暂存校验

暂存默认可见性为 private，新条目不预填 Workshop ID。安装和构建只准备本机文件，不提交 Steam 条目。退出游戏后可执行 `tools/check-workshop.ps1`，使用本机游戏的 SteamWorkshopItem.validateContents 校验图片、版本目录、mod.info 与文件类型。此校验不调用 create/submitUpdate。
