from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path
from typing import Annotated, Literal

from mcp.server.fastmcp import FastMCP
from mcp.server.fastmcp.exceptions import ToolError
from pydantic import Field

from .bridge import Bridge, BridgeError

Endpoint = Literal["client", "server"]
VehicleId = Annotated[int, Field(ge=-32768, le=32767)]
Limit = Annotated[int, Field(ge=1, le=100)]


def create_server(bridge: Bridge) -> FastMCP:
    mcp = FastMCP("PZ Debug MCP", instructions=(
        "先查询 pz_status，确认执行端和 debug_enabled。仅调用已注册的有限操作。"
        "TIMEOUT/INDETERMINATE 不代表取消，不可自动重试修改操作。"
        "采样 start 返回 trace_id，再用 read/stop 读取；断点与 JVM 暂停可能让桥接失去响应。"))

    async def request(endpoint: str, operation: str, arguments: dict | None = None):
        try:
            return await asyncio.to_thread(bridge.request, endpoint, operation, arguments)
        except BridgeError as error:
            raise ToolError(str(error)) from error

    @mcp.tool(annotations={"readOnlyHint": True, "idempotentHint": True, "openWorldHint": False})
    async def pz_status(endpoint: Endpoint = "client") -> dict:
        """检查心跳、版本、Debug 模式与支持能力。离线也可返回诊断。"""
        status = bridge.status(endpoint)
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
