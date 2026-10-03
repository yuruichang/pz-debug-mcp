"""Single-flight file mailboxes; only the game callback touches game objects."""

from __future__ import annotations

import contextlib
import json
import os
from pathlib import Path
import time
import uuid

PROTOCOL = 1
MAX_BYTES = 524288
ENDPOINTS = ("client", "server")


class BridgeError(RuntimeError):
    def __init__(self, code: str, message: str):
        self.code = code
        super().__init__(f"{code}: {message}")


def read_json(path: Path) -> dict | None:
    try:
        with path.open("rb") as stream:
            data = stream.read(MAX_BYTES + 1)
        if len(data) > MAX_BYTES:
            return None
        value = json.loads(data)
        return value if isinstance(value, dict) else None
    except (OSError, ValueError, UnicodeError):
        return None


def atomic_json(path: Path, data: dict) -> None:
    payload = json.dumps(data, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
    if len(payload) > MAX_BYTES:
        raise BridgeError("TOO_LARGE", "请求超过 512 KiB")
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        with temporary.open("xb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


@contextlib.contextmanager
def mailbox_lock(path: Path, deadline: float):
    # OS locks release automatically after a crashed MCP process.
    with path.open("a+b") as stream:
        if path.stat().st_size == 0:
            stream.write(b"0")
            stream.flush()
        acquired = False
        try:
            while not acquired:
                try:
                    stream.seek(0)
                    if os.name == "nt":
                        import msvcrt
                        msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                    else:
                        import fcntl
                        fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                    acquired = True
                except OSError:
                    if time.monotonic() >= deadline:
                        raise BridgeError("BUSY", "另一条请求正在使用该执行端")
                    time.sleep(0.05)
            yield
        finally:
            if acquired:
                stream.seek(0)
                if os.name == "nt":
                    import msvcrt
                    msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


class Bridge:
    def __init__(self, zomboid_dir: Path, timeout: float = 5):
        self.zomboid_dir = zomboid_dir.expanduser().resolve()
        self.root = self.zomboid_dir / "Lua" / "PZDebugMCP"
        self.timeout = timeout

    def directory(self, endpoint: str) -> Path:
        if endpoint not in ENDPOINTS:
            raise BridgeError("ENDPOINT", "执行端只能为 client 或 server")
        return self.root / endpoint

    def status(self, endpoint: str) -> dict:
        directory = self.directory(endpoint)
        heartbeat = read_json(directory / "heartbeat.json")
        age = None
        if heartbeat and isinstance(heartbeat.get("timestamp_ms"), (int, float)):
            age = (time.time() * 1000 - heartbeat["timestamp_ms"]) / 1000
        online = bool(heartbeat and heartbeat.get("protocol") == PROTOCOL and
                      heartbeat.get("endpoint") == endpoint and isinstance(heartbeat.get("session"), str)
                      and age is not None and -2 <= age <= 3)
        return {"endpoint": endpoint, "online": online, "heartbeat_age_seconds": age,
                "bridge_directory": str(directory), "heartbeat": heartbeat,
                "hint": None if online else "进入已启用 PZDebugMCP 的游戏；检查 -debug 与缓存目录。断点/JVM 暂停也会让心跳停止。"}

    @staticmethod
    def response(directory: Path, request: dict) -> dict | None:
        # Game writers cannot rename atomically: completion marker is closed last.
        try:
            marker = (directory / "response.ready.txt").read_text(encoding="utf-8").strip()
        except OSError:
            return None
        if marker != request["id"]:
            return None
        response = read_json(directory / "response.json")
        if (response and response.get("id") == request["id"] and
                response.get("session") == request["session"] and response.get("protocol") == PROTOCOL):
            return response
        return None

    def request(self, endpoint: str, operation: str, arguments: dict | None = None) -> dict:
        directory = self.directory(endpoint)
        directory.mkdir(parents=True, exist_ok=True)
        deadline = time.monotonic() + self.timeout
        with mailbox_lock(directory / "mailbox.lock", deadline):
            status = self.status(endpoint)
            if not status["online"]:
                raise BridgeError("OFFLINE", status["hint"])
            session = status["heartbeat"]["session"]
            previous = read_json(directory / "request.json")
            if previous and previous.get("session") == session and not self.response(directory, previous):
                claim = read_json(directory / "claim.json")
                if claim and claim.get("id") == previous.get("id") and claim.get("session") == session:
                    raise BridgeError("INDETERMINATE", "上一请求已进入游戏但结果尚未确认。不会重试或覆盖；等待结果或重启该游戏执行端。")
                if previous.get("expires_ms", 0) > time.time() * 1000:
                    raise BridgeError("BUSY", "上一请求仍未过期，请稍后再试")
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise BridgeError("BUSY", "等待邮箱超时")
            sequence = previous.get("sequence", 0) if previous and previous.get("session") == session else 0
            if type(sequence) is not int or not 0 <= sequence < 2147483647:
                raise BridgeError("PROTOCOL", "邮箱序号无效，需重启游戏执行端后再连接")
            request = {"protocol": PROTOCOL, "id": uuid.uuid4().hex, "session": session, "sequence": sequence + 1,
                       "endpoint": endpoint, "expires_ms": int(time.time() * 1000 + remaining * 1000),
                       "operation": operation, "arguments": arguments or {}}
            atomic_json(directory / "request.json", request)
            while time.monotonic() < deadline:
                response = self.response(directory, request)
                if response:
                    if not response.get("ok"):
                        error = response.get("error", {})
                        raise BridgeError(error.get("code", "GAME_ERROR"), error.get("message", "游戏返回错误"))
                    return response["result"]
                current = read_json(directory / "heartbeat.json")
                if current and current.get("session") != session:
                    raise BridgeError("SESSION_CHANGED", "游戏执行端已重启，旧请求不会自动重试")
                time.sleep(0.025)
            raise BridgeError("TIMEOUT", f"请求 {request['id']} 未及时返回；代码可能已执行，超时不代表取消。")

    def console(self, cursor: dict | None = None, max_bytes: int = 32768) -> dict:
        max_bytes = max(1024, min(max_bytes, 65536))
        path = self.zomboid_dir / "console.txt"
        try:
            with path.open("rb") as stream:
                stat = os.fstat(stream.fileno())
                identity = f"{stat.st_dev}:{stat.st_ino}"
                offset = cursor.get("offset", 0) if cursor and cursor.get("file_id") == identity else max(0, stat.st_size - max_bytes)
                reset = bool(cursor and (cursor.get("file_id") != identity or type(offset) is int and offset > stat.st_size))
                if reset:
                    offset = max(0, stat.st_size - max_bytes)
                if type(offset) is not int or offset < 0:
                    raise BridgeError("CURSOR", "日志游标无效")
                stream.seek(offset)
                data = stream.read(max_bytes)
                return {"text": data.decode("utf-8", errors="replace"), "reset": reset,
                        "cursor": {"file_id": identity, "offset": stream.tell()},
                        "has_more": stream.tell() < stat.st_size}
        except FileNotFoundError:
            return {"text": "", "cursor": None, "missing": True}

    def recorded(self, endpoint: str, after: int = 0, limit: int = 50,
                 target: str | None = None, kind: str | None = None, session: str | None = None) -> dict:
        """Read only committed records from fixed local segments, even with the game offline."""
        directory = self.directory(endpoint) / "records"
        index = read_json(directory / "index.json")
        if not index or index.get("schema") != 1:
            return {"records": [], "cursor": after, "missing": True, "session": None, "has_more": False}
        if not isinstance(index.get('recorder'), dict) or index['recorder'].get('read_policy') != 'reviewed_allowlist_v1':
            return {"records": [], "cursor": after, "session": None, "has_more": False,
                    "blocked": True, "reason": "Unverified or legacy recording policy; data files were not read"}
        current = index.get("session")
        reset = bool(session and session != current)
        if reset:
            after = 0
        records = {}
        warnings = []
        for segment in index.get("segments", []):
            slot = segment.get("slot")
            if type(slot) is not int or not 1 <= slot <= 16:
                continue
            try:
                with (directory / f"segment-{slot:02}.log").open("rb") as stream:
                    payload = stream.read(MAX_BYTES + 1)
                if len(payload) > MAX_BYTES:
                    warnings.append(f"segment-{slot:02}: exceeds size limit")
                    continue
                for line in payload.splitlines(keepends=True):
                    if not line.endswith(b"\n"):
                        continue
                    try:
                        record = json.loads(line)
                    except (ValueError, UnicodeError):
                        continue
                    if not isinstance(record, dict):
                        continue
                    sequence = record.get("sequence")
                    if (record.get("session") == current and type(sequence) is int
                            and max(after + 1, index.get("first", 1)) <= sequence <= index.get("last", 0)):
                        records[sequence] = record
            except OSError as error:
                warnings.append(f"segment-{slot:02}: {type(error).__name__}")
        selected, cursor, bytes_used = [], after, 0
        exhausted = True
        for sequence, record in sorted(records.items()):
            matches = (not target or target in record.get("target", "")) and (not kind or kind == record.get("kind"))
            size = len(json.dumps(record, ensure_ascii=False).encode("utf-8"))
            if matches and (len(selected) >= limit or bytes_used + size > 1048576):
                exhausted = False
                break
            cursor = sequence
            if matches:
                selected.append(record)
                bytes_used += size
        if exhausted and not warnings:
            cursor = max(cursor, index.get("last", 0))
        return {"records": selected, "cursor": cursor, "session": current, "reset": reset,
                "gap": after < index.get("first", 1) - 1, "has_more": cursor < index.get("last", 0),
                "committed_through": index.get("last", 0), "recorder": index.get("recorder"), "warnings": warnings}
