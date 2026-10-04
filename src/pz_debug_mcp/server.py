from __future__ import annotations

import argparse
import asyncio
import json
import sqlite3
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Annotated, Literal

from mcp.server.fastmcp import FastMCP
from mcp.server.fastmcp.exceptions import ToolError
from pydantic import Field

from .bridge import Bridge, BridgeError
from .record_store import RecordStore
from .debugger import Debuggers

Endpoint = Literal["client", "server"]
VehicleId = Annotated[int, Field(ge=-32768, le=32767)]
Limit = Annotated[int, Field(ge=1, le=100)]


def create_server(bridge: Bridge) -> FastMCP:
    store = RecordStore(bridge)
    debuggers = Debuggers(bridge)

    @asynccontextmanager
    async def lifespan(_server):
        stop = asyncio.Event()

        async def archive():
            while not stop.is_set():
                try:
                    await asyncio.to_thread(store.sync_all)
                except (OSError, ValueError, RuntimeError, sqlite3.Error) as error:
                    import logging
                    logging.getLogger(__name__).warning('Recording archive: %s', error)
                try:
                    await asyncio.wait_for(stop.wait(), 0.5)
                except TimeoutError:
                    pass

        task = asyncio.create_task(archive())
        async def leases():
            while not stop.is_set():
                await asyncio.to_thread(debuggers.renew)
                try:
                    await asyncio.wait_for(stop.wait(), 1)
                except TimeoutError:
                    pass
        lease_task = asyncio.create_task(leases())
        try:
            yield {}
        finally:
            stop.set()
            await task
            await lease_task
            await asyncio.to_thread(debuggers.close)

    mcp = FastMCP("PZ Debug MCP", lifespan=lifespan, instructions=(
        "先查询 pz_status，确认执行端和 debug_enabled。仅调用已注册的有限操作。"
        "TIMEOUT/INDETERMINATE 不代表取消，不可自动重试修改操作。"
        "采样 start 返回 trace_id，再用 read/stop 读取。Java 心跳、缓存和 JVM 查询独立于 Lua 回调；"
        "游戏对象查询仍需游戏线程执行；整个 JVM 暂停时使用外部 pz_java_debug/pz_native_debug 控制和恢复。"))

    async def request(endpoint: str, operation: str, arguments: dict | None = None):
        try:
            return await asyncio.to_thread(bridge.request, endpoint, operation, arguments)
        except BridgeError as error:
            raise ToolError(str(error)) from error

    @mcp.tool(annotations={"readOnlyHint": True, "idempotentHint": True, "openWorldHint": False})
    async def pz_status(endpoint: Endpoint = "client") -> dict:
        """检查心跳、版本、Debug 模式与支持能力。离线也可返回诊断。"""
        status = bridge.status(endpoint)
        status["debuggers"] = debuggers.status()
        if status["online"]:
            try:
                status["live"] = await request(endpoint, "status")
            except ToolError as error:
                status["live_error"] = str(error)
        return status

    @mcp.tool(annotations={"readOnlyHint": True, "idempotentHint": True, "openWorldHint": False})
    async def pz_read_errors(endpoint: Endpoint = "client", after: Annotated[int, Field(ge=0)] = 0,
                             limit: Limit = 50, session: str | None = None) -> dict:
        """增量读取原版 Lua Debug 错误和桥接事件。传回上次 cursor/session；reset 表示新会话。"""
        return await request(endpoint, "read_errors", {"after": after, "limit": limit, "session": session})

    @mcp.tool(annotations={"readOnlyHint": True, "idempotentHint": True, "openWorldHint": False})
    async def pz_inspect_vehicle(vehicle_id: VehicleId | None = None, endpoint: Endpoint = "client",
                                 include_parts: bool = False) -> dict:
        """读取已加载车辆快照；省略 ID 时取本地玩家所乘车辆（仅客户端）。"""
        return await request(endpoint, "inspect_vehicle", {"vehicle_id": vehicle_id, "include_parts": include_parts})

    @mcp.tool(annotations={"readOnlyHint": False, "destructiveHint": False, "openWorldHint": False})
    async def pz_capture_vehicle_trace(action: Literal["start", "read", "stop"] = "start",
                                       vehicle_id: VehicleId | None = None, trace_id: str | None = None,
                                       duration_seconds: Annotated[float, Field(ge=1, le=60)] = 10,
                                       interval_ms: Annotated[int, Field(ge=50, le=1000)] = 100,
                                       after: Annotated[int, Field(ge=0)] = 0, limit: Limit = 100,
                                       endpoint: Endpoint = "client") -> dict:
        """在游戏侧连续采样车辆与拖挂关系，最多保留 600 条/4 组。read 按序号增量返回，gap 表示旧样本已覆盖。"""
        return await request(endpoint, "capture_vehicle_trace", {
            "action": action, "vehicle_id": vehicle_id, "trace_id": trace_id,
            "duration_seconds": duration_seconds, "interval_ms": interval_ms, "after": after, "limit": limit})

    @mcp.tool(annotations={"readOnlyHint": False, "destructiveHint": False, "openWorldHint": False})
    async def pz_run_test(name: str = "bridge_self_test", arguments: dict | None = None,
                          endpoint: Endpoint = "client") -> dict:
        """运行已注册测试。内置 bridge_self_test、vehicle_relationships；status 列出可用测试。"""
        return await request(endpoint, "run_test", {"name": name, "arguments": arguments or {}})

    @mcp.tool(annotations={"readOnlyHint": False, "destructiveHint": True, "idempotentHint": False, "openWorldHint": False})
    async def pz_reload_mod_lua(module: str, endpoint: Endpoint = "client") -> dict:
        """仅重载游戏内预注册模块，依次 cleanup → 原版 reloadLuaFile → init。失败时模块被停用。"""
        return await request(endpoint, "reload_mod_lua", {"module": module})

    @mcp.tool(annotations={"readOnlyHint": True, "idempotentHint": True, "openWorldHint": False})
    async def pz_list_debug_interfaces(scope: Literal["globals", "object"] = "globals", handle: str | None = None,
                                       category: str | None = None, readers_only: bool = False,
                                       offset: Annotated[int, Field(ge=0)] = 0, limit: Limit = 50,
                                       endpoint: Endpoint = "client") -> dict:
        """分页浏览原版全部全局 API 签名或对象的字段/方法；标明可读、需要参数和当前执行端可用性。"""
        return await request(endpoint, "list_debug_interfaces", {"scope": scope, "handle": handle,
            "category": category, "readers_only": readers_only, "offset": offset, "limit": limit})

    @mcp.tool(annotations={"readOnlyHint": True, "idempotentHint": True, "openWorldHint": False})
    async def pz_query_debug(target: str, action: Literal["call", "inspect", "field", "table"] = "call",
                             member: str | int | bool | None = None, arguments: list | None = None,
                             field_index: Annotated[int, Field(ge=0)] | None = None,
                             offset: Annotated[int, Field(ge=0)] = 0, limit: Limit = 32,
                             endpoint: Endpoint = "client") -> dict:
        """通用原版数据查询。target 为全局 API 名、root:_G 等表根或对象句柄。对象参数使用 {handle:句柄}；查询结果自动记录。"""
        return await request(endpoint, "query_debug", {"target": target, "action": action, "member": member,
            "arguments": arguments or [], "field_index": field_index, "offset": offset, "limit": limit})

    @mcp.tool(annotations={"readOnlyHint": True, "idempotentHint": True, "openWorldHint": False})
    async def pz_read_recorded_data(after: Annotated[int, Field(ge=0)] = 0, limit: Limit = 50,
                                    target: str | None = None, kind: Literal["global", "object", "query", "java_runtime",
                                    "java_mods", "java_transform", "java_trace", "java_error"] | None = None,
                                    session: str | None = None, recording_session: str | None = None,
                                    endpoint: Endpoint = "client") -> dict:
        """取回自动归档的全程记录；支持离线、历史会话、目标过滤和分页。available_sessions 列出已归档会话，gaps 标明桥接覆盖。"""
        return await asyncio.to_thread(store.read, endpoint, after, limit, target, kind, session, recording_session)

    @mcp.tool(annotations={"readOnlyHint": False, "destructiveHint": False, "openWorldHint": False})
    async def pz_configure_recorder(enabled: bool | None = None,
                                     interval_ms: Annotated[int, Field(ge=50, le=5000)] | None = None,
                                     jobs_per_tick: Annotated[int, Field(ge=1, le=32)] | None = None,
                                     budget_ms: Annotated[int, Field(ge=1, le=20)] | None = None,
                                     max_depth: Annotated[int, Field(ge=0, le=16)] | None = None,
                                     max_handles: Annotated[int, Field(ge=128, le=16384)] | None = None,
                                     max_jobs: Annotated[int, Field(ge=128, le=32768)] | None = None,
                                     refresh_seconds: Annotated[int, Field(ge=1, le=300)] | None = None,
                                     endpoint: Endpoint = "client") -> dict:
        """配置通用记录的轮询、预算和对象图范围；max_handles 改变时旧句柄失效。默认自动开始，不需要 AI 逐次请求。"""
        return await request(endpoint, "configure_recorder", {"enabled": enabled, "interval_ms": interval_ms,
            "jobs_per_tick": jobs_per_tick, "budget_ms": budget_ms, "max_depth": max_depth,
            "max_handles": max_handles, "max_jobs": max_jobs, "refresh_seconds": refresh_seconds})

    @mcp.tool(annotations={"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": False})
    async def pz_watch_debug(action: Literal["add", "list", "remove"] = "add", query: dict | None = None,
                             watch_id: str | None = None,
                             interval_ms: Annotated[int, Field(ge=100, le=60000)] = 1000,
                             endpoint: Endpoint = "client") -> dict:
        """给需要参数的通用查询注册游戏侧持续采集，不需 AI 反复调用；list/remove 管理当前会话订阅。"""
        return await request(endpoint, "watch_debug", {"action": action, "query": query,
            "watch_id": watch_id, "interval_ms": interval_ms})

    @mcp.tool(annotations={"readOnlyHint": True, "idempotentHint": True, "openWorldHint": False})
    async def pz_java_runtime(section: Literal["summary", "metrics", "threads", "classes", "mods", "patches", "roots"] = "summary",
                              filter: str = "", offset: Annotated[int, Field(ge=0)] = 0, limit: Limit = 50,
                              endpoint: Endpoint = "client") -> dict:
        """读取 JVM 状态、线程栈、已加载类、Java 模组/补丁元数据或已采集对象根。Lua 暂停时仍可读取；补丁目标不等同实际最终字节码。"""
        return await request(endpoint, "java_runtime", {"section": section, "filter": filter, "offset": offset, "limit": limit})

    @mcp.tool(annotations={"readOnlyHint": True, "idempotentHint": True, "openWorldHint": False})
    async def pz_inspect_java(target: str, action: Literal["inspect", "field", "methods", "call"] = "inspect",
                              member: str | None = None, field_index: Annotated[int, Field(ge=0)] | None = None,
                              offset: Annotated[int, Field(ge=0)] = 0, limit: Limit = 32,
                              endpoint: Endpoint = "client") -> dict:
        """在游戏线程读取 Java 对象字段/数组或方法元数据。target 为 Java 句柄、root:java:名称或 class:已加载类名；静态字段须确认已初始化，call 仅允许既有审核方法。"""
        return await request(endpoint, "inspect_java", {"target": target, "action": action, "member": member,
            "field_index": field_index, "offset": offset, "limit": limit})

    @mcp.tool(annotations={"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": False})
    async def pz_trace_java(action: Literal["start", "read", "stop"] = "read", class_name: str | None = None,
                            method: str | None = None, parameters: list[str] | None = None,
                            trace_id: str | None = None, duration_seconds: Annotated[int, Field(ge=1, le=30)] = 10,
                            after: Annotated[int, Field(ge=0)] = 0, limit: Limit = 100,
                            endpoint: Endpoint = "client") -> dict:
        """有界追踪已加载 Java 方法的参数、返回值、异常类型和耗时。参数类型精确选择重载；一次一个追踪，最多八个目标。安装的惰性观察钩子保留至 JVM 重启，不改变方法结果。"""
        return await request(endpoint, "trace_java", {"action": action, "class_name": class_name,
            "method": method, "parameters": parameters or [], "trace_id": trace_id,
            "duration_seconds": duration_seconds, "after": after, "limit": limit})

    @mcp.tool(annotations={"readOnlyHint": False, "destructiveHint": False, "openWorldHint": False})
    async def pz_java_debug(action: Literal["connect", "disconnect", "status", "threads", "pause", "resume",
                            "breakpoint_add", "breakpoint_remove", "frames", "step", "object", "bytecode"] = "status",
                            port: Annotated[int, Field(ge=1, le=65535)] = 8801,
                            thread_id: str | None = None, class_name: str | None = None, method: str | None = None,
                            signature: str | None = None, line: Annotated[int, Field(ge=0)] = 0,
                            exception: bool = False, breakpoint_id: str | None = None,
                            depth: Literal["into", "over", "out"] = "into", object_id: str | None = None,
                            session: str | None = None, offset: Annotated[int, Field(ge=0)] = 0, limit: Limit = 32) -> dict:
        """外部JDI控制Java断点、异常断点、线程暂停、栈帧/局部变量、单步和继续；bytecode读取VM当前方法摘要与分页字节码。JDWP须仅在本机启用，断连解除本会话暂停。"""
        args = {"port": port, "thread_id": thread_id, "class_name": class_name, "method": method,
            "signature": signature, "line": line, "exception": exception, "breakpoint_id": breakpoint_id,
            "depth": depth, "object_id": object_id, "session": session, "offset": offset, "limit": limit}
        if action == "frames":
            args["limit"] = min(limit, 64)
        try:
            return await asyncio.to_thread(debuggers.java_request, action, {k:v for k,v in args.items() if v is not None})
        except BridgeError as error:
            raise ToolError(str(error)) from error

    @mcp.tool(annotations={"readOnlyHint": False, "destructiveHint": False, "openWorldHint": False})
    async def pz_lua_debug(action: Literal["connect", "disconnect", "status", "sources", "pause", "resume",
                           "breakpoint_add", "breakpoint_remove", "frames", "step"] = "status",
                           file: str | None = None, line: Annotated[int, Field(ge=1)] | None = None,
                           breakpoint_id: str | None = None, depth: Literal["into", "over", "out"] = "into",
                           endpoint: Endpoint = "client") -> dict:
        """通过原版Kahlua断点/步进入口调试Lua，暂停后读取栈与局部变量；断连或30秒租约到期会恢复，不执行任意Lua表达式。file按实际原型文件名，line为1起始。"""
        args = {"file": file, "line": line, "breakpoint_id": breakpoint_id, "depth": depth}
        try:
            return await asyncio.to_thread(debuggers.lua_request, action, {k:v for k,v in args.items() if v is not None}, endpoint)
        except BridgeError as error:
            raise ToolError(str(error)) from error

    @mcp.tool(annotations={"readOnlyHint": False, "destructiveHint": False, "openWorldHint": False})
    async def pz_native_debug(action: Literal["attach", "detach", "status", "pause", "resume", "modules",
                              "threads", "symbols", "stack", "registers", "locals", "breakpoint_add",
                              "breakpoint_remove", "step", "memory"] = "status",
                              pid: Annotated[int, Field(ge=1)] | None = None,
                              symbol: str | None = None, pattern: str = "*",
                              address: str | None = None, breakpoint_id: Annotated[int, Field(ge=0)] | None = None,
                              thread_id: Annotated[int, Field(ge=0)] | None = None,
                              frame: Annotated[int, Field(ge=0, le=63)] = 0, depth: Literal["into", "over", "out"] = "into",
                              bytes: Annotated[int, Field(ge=1, le=65536)] = 64,
                              offset: Annotated[int, Field(ge=0)] = 0, limit: Limit = 32,
                              endpoint: Endpoint = "client") -> dict:
        """Windows原生调试引擎：模块/符号、OS线程栈和寄存器、断点/步进及有限内存读取。locals需要PDB符号；不提供任意调试器命令。detach恢复目标且移除调试器断点。"""
        args = {"pid": pid, "symbol": symbol, "pattern": pattern, "address": address, "breakpoint_id": breakpoint_id,
            "thread_id": thread_id, "frame": frame, "depth": depth, "bytes": bytes, "offset": offset, "limit": limit}
        try:
            return await asyncio.to_thread(debuggers.native_request, action, args, endpoint)
        except BridgeError as error:
            raise ToolError(str(error)) from error

    @mcp.tool(annotations={"readOnlyHint": True, "idempotentHint": True, "openWorldHint": False})
    async def pz_read_console(cursor: dict | None = None,
                               max_bytes: Annotated[int, Field(ge=1024, le=65536)] = 32768) -> dict:
        """读取本机缓存 console.txt 的有限增量；游戏离线也可用。"""
        try:
            return bridge.console(cursor, max_bytes)
        except BridgeError as error:
            raise ToolError(str(error)) from error

    return mcp


def main():
    parser = argparse.ArgumentParser(description="Project Zomboid 本机 Debug MCP")
    parser.add_argument("--zomboid-dir", type=Path, default=Path.home() / "Zomboid", help="游戏缓存目录，不是 Steam 安装目录")
    parser.add_argument("--timeout", type=float, default=5)
    parser.add_argument("--doctor", action="store_true", help="检查两个执行端并输出 JSON")
    options = parser.parse_args()
    if not 0.5 <= options.timeout <= 30:
        parser.error("timeout 必须在 0.5 到 30 秒之间")
    bridge = Bridge(options.zomboid_dir, options.timeout)
    if options.doctor:
        print(json.dumps({end: bridge.status(end) for end in ("client", "server")}, ensure_ascii=False, indent=2))
    else:
        create_server(bridge).run(transport="stdio")


if __name__ == "__main__":
    main()
