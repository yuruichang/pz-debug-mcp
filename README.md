# PZ Debug MCP 0.1.0

把支持 MCP 的 AI 客户端连接到正在运行的 Project Zomboid，通过原版 Debug/Lua API 查询游戏状态、记录车辆变化并执行有限的调试操作。

采用分享会话的第一阶段方案：**AI 客户端 → 标准 MCP stdio → 本机 Python 服务 → 带编号的文件消息 → 游戏内 Bridge Mod → 原版 Lua/Debug/游戏对象接口**。

目标版本为 **B42.21.0**。已核对本机游戏 API，并在游戏自带 Kahlua 运行时执行测试；尚未在实际存档内联调。B42.20 与 B41 未验证。

## 安装与连接

需要 Python 3.11+。在此目录执行：

```powershell
.\setup.ps1
.\install-mod.ps1
```

模组复制到 `%USERPROFILE%\Zomboid\mods\PZDebugMCP`。安装脚本会备份已有同名模组，不修改其他模组或存档的启用列表。在游戏中启用 **PZDebugMCP**，给游戏启动选项加入 `-debug`，然后进入测试存档。非 Debug 模式只返回状态，不执行调试请求。

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

错误读取也使用 `after/cursor`，并保存 `session` 以识别重进游戏后的重置；`reset=true` 表示新会话，`gap=true` 表示 256 条环形缓冲已覆盖部分旧事件。原版 Debug 错误与 `console.txt` 是两个来源，本版不接管全局 `print` 或扫描所有日志文件。单条事件最多保留 4096 个字符串单位。

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

- `heartbeat.json`：约每秒更新会话、版本和 Debug 状态；超过三秒视为离线。
- `request.json`：Python 原子替换，包含随机请求 ID、单调递增序号、会话、执行端和绝对过期时间。游戏拒绝已处理的旧序号，延迟重传不会再次执行。
- `claim.json`：游戏在操作前持久写入已领取编号。同一编号不会再次执行。
- `response.json` 与 `response.ready.txt`：游戏先关闭响应文件，最后关闭完成标记；服务只接受编号和会话匹配的完整结果。
- `mailbox.lock`：操作系统进程锁，每个执行端一次处理一个请求；服务退出后自动释放锁。

请求只允许有限的调试操作，不将文件内容当作 Lua 代码执行。JSON 大小最多 512 KiB，嵌套最多 24 层；心跳或响应正好处于写入期间会等待下一次读取。共享邮箱的两个 MCP 实例可以串行使用，不应有两个游戏实例共用同一个端点缓存目录。

`TIMEOUT` 只表示没有及时收到结果，**不代表代码取消或未执行**。已经领取却没有完成结果的请求，会让后续请求返回 `INDETERMINATE`；等待迟到的结果，或重启游戏执行端建立新会话。不要自动重试修改操作，也不要在游戏运行时手动清空 `claim.json` 来绕过此检查。

普通暂停时同时尝试 `OnTickEvenPaused`；Lua 断点暂停或整个 JVM 暂停时桥接可能无法响应。第一版不提供断点、单步、局部变量或 JDWP，也不提供任意 Lua 执行。

## 开发验证与打包

```powershell
.\setup.ps1 -Development
.\build.ps1 -GameDir 'E:\Steam\steamapps\common\ProjectZomboid'
```

构建需要 JDK 的 `javac`，并使用指定游戏自带 Java/Kahlua。它运行标准 MCP 握手、真实文件桥、并发与超时测试、Lua 5.1 行为测试，以及原版 Kahlua 的协议/车辆/重载/Unicode 检查。游戏对象与事件在这些自动测试中是受控桩，不替代实际存档联调。

产物为 `dist/PZDebugMCP-mod-0.1.0.zip` 和 `dist/PZDebugMCP-source-0.1.0.zip`，附 SHA-256 校验文件。源码包不包含本机 Python 环境、游戏文件或本机路径配置。加 `-InstallMod` 可在验证通过后安装模组。

实际游戏验收：进入 Debug 测试存档 → `pz_status` 在线且 `debug_enabled=true` → 自检通过 → 驾驶车辆读取快照 → 完成一组拖挂采样 → 连续重载 `example_counter` 两次并确认测试仍能执行。服务端需另做同样的验收。

## 依据

- [原分析会话](https://chatgpt.com/share/6ac0dcb7-f4f0-83ee-a225-3d990364fba8)
- [官方 Lua/Debug 接口](https://projectzomboid.com/modding/zombie/Lua/LuaManager.GlobalObject.html)
- [官方车辆 API](https://projectzomboid.com/modding/zombie/vehicles/BaseVehicle.html)
- [MCP 传输规范](https://modelcontextprotocol.io/specification/latest/basic/transports)

官方 Debug 模式不自带 MCP 端口。本项目通过游戏内 Mod 调用原版接口；本机文件与字节码核对优先于可能滞后的网页文档。
