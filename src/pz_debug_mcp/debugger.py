"""Debugger controller ownership and cleanup for one MCP server lifetime."""

from pathlib import Path
import os
import shutil
from .bridge import BridgeError
from .debug_process import DebugProcess
from .native_guard import NativeGuard


class Debuggers:
    def __init__(self, bridge):
        self.bridge = bridge
        root = Path(__file__).resolve().parents[2]
        java = os.environ.get("PZDEBUG_JDI_JAVA") or shutil.which("java")
        self.java = DebugProcess([java or "java", "--add-modules", "jdk.jdi", "-jar", str(root / "bin/java-debugger.jar")])
        self.native = DebugProcess([str(root / "bin/native-debugger.exe")])
        self.lua_endpoints = set()
        self.native_guard = None

    def java_request(self, action, arguments):
        if action == "disconnect":
            self.java.close()
            return {"connected": False, **self.java.status()}
        result = self.java.request(action, arguments)
        return {**result, "helper": self.java.status()}

    def native_request(self, action, arguments, endpoint):
        if action == "detach":
            self.native.close()
            if self.native_guard:
                self.native_guard.join()
            return {"connected": False, **self.native.status(), "recovery": self.native_guard.result if self.native_guard else None}
        if action == "attach" and arguments.get("pid") is None:
            status = self.bridge.status(endpoint)
            heartbeat = status.get("heartbeat") or {}
            if not status["online"] or not heartbeat.get("pid"):
                raise BridgeError("TARGET_UNAVAILABLE", "Provide pid or connect to a Java bridge publishing its game PID")
            arguments = {**arguments, "pid": heartbeat["pid"]}
        if action == "attach" and os.name == "nt":
            destination = Path(self.native.command[0]).parent
            for library in ("dbghelp.dll", "dbgcore.dll"):
                source = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / library
                if not (destination / library).exists():
                    try:
                        shutil.copy2(source, destination / library)
                    except OSError as error:
                        raise BridgeError("SYMBOL_RUNTIME", f"Cannot prepare local Windows symbol runtime: {error}") from error
        if action == "attach" and os.name == "nt":
            self.native.close()
            if self.native_guard:
                self.native_guard.join()
            try:
                self.native_guard = NativeGuard(arguments['pid'])
            except OSError as error:
                raise BridgeError("NATIVE_GUARD", str(error)) from error
        try:
            result = self.native.request(action, {k: v for k, v in arguments.items() if v is not None})
        except BridgeError:
            if action == "attach":
                self.native.close()
                if self.native_guard:
                    self.native_guard.join()
            raise
        if self.native_guard:
            if action == "attach":
                self.native_guard.watch(self.native.process)
            if "breakpoints" in result:
                self.native_guard.update(result["breakpoints"])
        return {**result, "helper": self.native.status(), "recovery": self.native_guard.result if self.native_guard else None}

    def lua_request(self, action, arguments, endpoint):
        result = self.bridge.request(endpoint, "lua_debug", {"action": action, **arguments})
        if action == "connect":
            self.lua_endpoints.add(endpoint)
        if action == "disconnect":
            self.lua_endpoints.discard(endpoint)
        return result

    def renew(self):
        for endpoint in tuple(self.lua_endpoints):
            try:
                self.bridge.request(endpoint, "lua_debug", {"action": "heartbeat"})
            except BridgeError:
                # The game owns a finite lease and resumes if this connection disappears.
                pass

    def status(self):
        return {"java": self.java.status(), "native": self.native.status(),
                "native_recovery": self.native_guard.result if self.native_guard else None,
                "lua_endpoints": sorted(self.lua_endpoints)}

    def close(self):
        self.java.close()
        self.native.close()
        if self.native_guard:
            self.native_guard.join()
        for endpoint in tuple(self.lua_endpoints):
            try:
                self.bridge.request(endpoint, "lua_debug", {"action": "disconnect"})
            except BridgeError:
                pass
        self.lua_endpoints.clear()
