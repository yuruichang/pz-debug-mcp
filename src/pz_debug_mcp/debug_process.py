"""A bounded, serialized RPC connection to a debugger helper process."""

from __future__ import annotations

import json
import queue
import subprocess
import threading
import uuid
from collections import deque

from .bridge import BridgeError


class DebugProcess:
    def __init__(self, command: list[str], timeout: float = 15):
        self.command = command
        self.timeout = timeout
        self.lock = threading.RLock()
        self.process = None
        self.replies = queue.Queue(maxsize=32)
        self.stderr = deque(maxlen=32)
        self.state = "closed"
        self.last_error = None
        self.pending = None

    def _fault(self, code, message, process=None):
        if process is not None and self.process is not process:
            return
        self.state = "faulted"
        self.last_error = {"code": code, "message": message[:2048]}
        try:
            self.replies.put_nowait({"fault": self.last_error})
        except queue.Full:
            pass

    def _read(self, process):
        try:
            while True:
                line = process.stdout.readline(1_048_577)
                if not line:
                    self._fault("DEBUGGER_EXITED", "Debugger helper closed its output stream", process)
                    return
                if len(line) > 1_048_576 or not line.endswith("\n"):
                    self._fault("DEBUGGER_PROTOCOL", "Debugger response exceeds size limit", process)
                    return
                value = json.loads(line)
                if not isinstance(value, dict) or value.get("protocol") != 1:
                    raise ValueError("Invalid debugger response")
                if self.process is not process:
                    return
                self.replies.put(value, timeout=1)
        except (ValueError, OSError, queue.Full) as error:
            self._fault("DEBUGGER_PROTOCOL", str(error), process)

    def _read_errors(self, process):
        try:
            for line in process.stderr:
                if self.process is not process:
                    return
                self.stderr.append(line[:2048].rstrip())
        except (OSError, ValueError):
            pass

    def _start(self):
        if self.process is not None:
            return
        self.replies = queue.Queue(maxsize=32)
        self.last_error = None
        self.stderr.clear()
        try:
            self.process = subprocess.Popen(
                self.command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace",
                creationflags=subprocess.CREATE_NO_WINDOW if hasattr(subprocess, "CREATE_NO_WINDOW") else 0,
            )
        except OSError as error:
            self.last_error = {"code": "DEBUGGER_UNAVAILABLE", "message": str(error)[:2048]}
            raise BridgeError("DEBUGGER_UNAVAILABLE", str(error)) from error
        self.state = "ready"
        self.read_thread = threading.Thread(target=self._read, args=(self.process,), daemon=True)
        self.error_thread = threading.Thread(target=self._read_errors, args=(self.process,), daemon=True)
        self.read_thread.start()
        self.error_thread.start()

    def request(self, operation: str, arguments: dict | None = None):
        with self.lock:
            if self.state == "faulted":
                error = self.last_error or {"code": "DEBUGGER_FAULT", "message": "Debugger helper failed"}
                raise BridgeError(error["code"], error["message"])
            self._start()
            if self.pending:
                raise BridgeError("INDETERMINATE", "A debugger command is awaiting confirmation; detach before reconnecting")
            key = uuid.uuid4().hex
            self.last_error = None
            self.pending = key
            try:
                payload = json.dumps({"protocol": 1, "id": key, "operation": operation,
                    "arguments": arguments or {}}, ensure_ascii=False, allow_nan=False)
                if len(payload.encode("utf-8")) > 65536:
                    raise BridgeError("TOO_LARGE", "Debugger command exceeds 64 KiB")
                self.process.stdin.write(payload + "\n")
                self.process.stdin.flush()
                response = self.replies.get(timeout=self.timeout)
                if "fault" in response:
                    raise BridgeError(response["fault"]["code"], response["fault"]["message"])
                if response.get("id") != key or not isinstance(response.get("ok"), bool):
                    self._fault("DEBUGGER_PROTOCOL", "Response does not match the outstanding command")
                    raise BridgeError("DEBUGGER_PROTOCOL", "Response ID mismatch")
                self.pending = None
                if not response["ok"]:
                    error = response.get("error", {})
                    self.last_error = {"code": error.get("code", "DEBUGGER_ERROR"), "message": error.get("message", "Debugger operation failed")}
                    raise BridgeError(error.get("code", "DEBUGGER_ERROR"), error.get("message", "Debugger operation failed"))
                return response.get("result")
            except queue.Empty as error:
                self.last_error = {"code": "TIMEOUT", "message": "Debugger command timed out; execution may still be pending"}
                raise BridgeError("TIMEOUT", self.last_error["message"]) from error
            except (OSError, UnicodeError) as error:
                self._fault("DEBUGGER_IO", str(error))
                raise BridgeError("DEBUGGER_IO", str(error)) from error
            except BridgeError:
                if not self.last_error:
                    self.pending = None
                raise

    def status(self):
        return {"helper_state": self.state, "pid": self.process.pid if self.process else None,
                "pending": self.pending, "last_error": self.last_error, "stderr": list(self.stderr)}

    def close(self):
        with self.lock:
            process = self.process
            if process is None:
                self.state = "closed"
                return
            self.process = None
            # EOF is the helper's guaranteed resume/detach path, including an unresolved command.
            try:
                process.stdin.close()
                process.wait(timeout=5)
            except (OSError, subprocess.TimeoutExpired):
                process.terminate()
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=3)
            for stream in (process.stdout, process.stderr):
                stream.close()
            self.read_thread.join(timeout=1)
            self.error_thread.join(timeout=1)
            self.pending = None
            self.state = "closed"
