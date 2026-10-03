from __future__ import annotations

import asyncio
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest

from lupa.lua51 import LuaRuntime
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

from pz_debug_mcp.bridge import Bridge, BridgeError, MAX_BYTES, atomic_json, read_json

ROOT = Path(__file__).resolve().parents[1]
SHARED = ROOT / 'Contents/mods/PZDebugMCP/42/media/lua/shared'


def runtime():
    lua = LuaRuntime(unpack_returned_tuples=True)
    lua.execute((ROOT / 'tests/harness.lua').read_text(encoding='utf-8'))
    for file in SHARED.rglob('*.lua'):
        name = file.relative_to(SHARED).as_posix()[:-4]
        lua.globals().sources[name] = file.read_text(encoding='utf-8')
    lua.execute("B = require 'PZDebugMCP/Bridge'; require 'PZDebugMCP/Example'; B.start('client')")
    return lua


class LuaGame:
    """One game thread with real Lua 5.1 and real mailbox files."""

    def __init__(self, directory: Path):
        self.directory = directory
        self.ready = threading.Event()
        self.stop = threading.Event()
        self.exception = None
        self.thread = threading.Thread(target=self.run, daemon=True)

    def __enter__(self):
        self.thread.start()
        if not self.ready.wait(3):
            raise AssertionError(f'Game startup failed: {self.exception}')
        return self

    def __exit__(self, *_):
        self.stop.set()
        self.thread.join(3)
        if self.exception:
            raise self.exception

    def run(self):
        try:
            lua = runtime()
            def read(path):
                try:
                    return (self.directory / 'Lua' / path).read_text(encoding='utf-8')
                except OSError:
                    return None
            def write(path, content):
                file = self.directory / 'Lua' / path
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text(content, encoding='utf-8')
            lua.globals().externalRead = read
            lua.globals().externalWrite = write
            lua.globals().clock = int(time.time() * 1000)
            lua.globals().B.start('client')
            self.ready.set()
            while not self.stop.is_set():
                lua.globals().clock = int(time.time() * 1000)
                lua.globals().Events.OnTick.fire()
                self.stop.wait(0.01)
        except BaseException as error:
            self.exception = error


class LuaTests(unittest.TestCase):
    def setUp(self):
        self.lua = runtime()

    def request(self, operation, arguments=None, **kwargs):
        encoded = json.dumps(arguments or {}, ensure_ascii=False)
        args = self.lua.globals().B.Json.decode(encoded)
        response = self.lua.globals().request(operation, args, kwargs.get('expires'),
                                              kwargs.get('endpoint'), kwargs.get('session'))
        return json.loads(self.lua.globals().B.Json.encode(response))

    def test_codec(self):
        value = {'text': '中文🚗\n"\\', 'array': [None, True, False, 1.5], 'empty': [], 'object': {}}
        codec = self.lua.globals().B.Json
        self.assertEqual(json.loads(codec.encode(codec.decode(json.dumps(value)))), value)
        for text in ['[1,]', '01', '1e999', '{"x":1,"x":2}', '{"x":"\\ud800"}', 'true junk']:
            with self.subTest(text=text), self.assertRaises(Exception):
                codec.decode(text)
        with self.assertRaises(Exception):
            codec.decode('[' * 30 + '0' + ']' * 30)

    def test_vehicle_parts_and_relationships(self):
        data = self.request('inspect_vehicle', {'include_parts': True})['result']['vehicle']
        self.assertEqual(data['towing_id'], 2)
        self.assertEqual(data['parts'][0]['condition'], 80)
        self.assertTrue(self.request('run_test', {'name': 'vehicle_relationships'})['result']['result']['passed'])
        self.lua.execute('vehicles[2].towedBy = nil')
        self.assertFalse(self.request('run_test', {'name': 'vehicle_relationships'})['result']['result']['passed'])

    def test_debug_gate_and_expiry(self):
        self.lua.globals().debugEnabled = False
        self.assertEqual(self.request('inspect_vehicle')['error']['code'], 'DEBUG_DISABLED')
        self.assertTrue(self.request('status')['ok'])
        self.lua.globals().debugEnabled = True
        self.assertEqual(self.request('inspect_vehicle', expires=0)['error']['code'], 'EXPIRED')

    def test_focus_pause_is_automatically_disabled_and_rechecked(self):
        self.assertFalse(self.lua.globals().pauseOnFocusloss)
        self.lua.execute('pauseOnFocusloss=true; debugEnabled=false; clock=clock+1000; B.tick()')
        self.assertFalse(self.lua.globals().pauseOnFocusloss)
        self.assertTrue(self.request('status')['result']['focus_pause']['disabled'])
        self.lua.execute("pauseOnFocusloss=true; clock=clock+1000; B.start('server')")
        self.assertTrue(self.lua.globals().pauseOnFocusloss)

    def test_wrong_session_endpoint_and_deduplication(self):
        self.lua.execute("count=0; B.registerTest('count', function() count=count+1; return {count=count} end)")
        self.request('run_test', {'name': 'count'})
        self.lua.execute('B.lastText = nil; B.lastId = nil; clock = clock + 100; B.tick()')
        self.assertEqual(self.lua.globals().count, 1)
        self.request('run_test', {'name': 'count'}, session='stale')
        self.request('run_test', {'name': 'count'}, endpoint='server')
        self.assertEqual(self.lua.globals().count, 1)

    def test_claim_failure_prevents_execution(self):
        self.lua.execute("count=0; B.registerTest('count', function() count=count+1; return {} end); failWrite = B.path .. 'claim.json'")
        self.request('run_test', {'name': 'count'})
        self.assertEqual(self.lua.globals().count, 0)

    def test_old_request_cannot_replay_after_later_request(self):
        self.lua.execute("count=0; B.registerTest('count', function() count=count+1; return {} end)")
        self.request('run_test', {'name': 'count'})
        self.lua.execute("oldRequest = files[B.path .. 'request.json']")
        self.request('run_test', {'name': 'count'})
        self.lua.execute("files[B.path .. 'request.json'] = oldRequest; clock=clock+100; B.tick()")
        self.assertEqual(self.lua.globals().count, 2)

    def test_reload_cleanup_and_init_failures(self):
        self.lua.execute("B.registerReloadable('bad_cleanup', {path='media/lua/shared/Test.lua', cleanup=function() error('bad') end, init=function() end})")
        self.assertEqual(self.request('reload_mod_lua', {'module': 'bad_cleanup'})['error']['code'], 'CLEANUP_FAILED')
        self.assertFalse(self.lua.globals().B.modules.bad_cleanup.enabled)
        self.lua.execute("sources['PZDebugMCP/Example'] = [[local B=require 'PZDebugMCP/Bridge'; B.registerReloadable('example_counter', {path='media/lua/shared/PZDebugMCP/Example.lua', cleanup=function() B.tests.example_counter=nil end, init=function() error('bad init') end})]]")
        self.assertEqual(self.request('reload_mod_lua', {'module': 'example_counter'})['error']['code'], 'INIT_FAILED')
        self.assertFalse(self.lua.globals().B.modules.example_counter.enabled)

    def test_response_serialization_failure(self):
        self.lua.execute("B.registerTest('bad', function() local v={}; v.loop=v; return v end)")
        self.assertEqual(self.request('run_test', {'name': 'bad'})['error']['code'], 'SERIALIZATION')

    def test_trace_ring_and_detachment(self):
        trace = self.request('capture_vehicle_trace', {'duration_seconds': 60, 'interval_ms': 50})['result']['trace_id']
        self.lua.execute('for i=1,610 do clock=clock+50; B.tick() end; vehicles[1].towing=nil')
        first = self.request('capture_vehicle_trace', {'action': 'read', 'trace_id': trace})['result']
        self.assertTrue(first['gap'])
        self.assertGreater(first['dropped'], 0)
        self.assertEqual(len(first['samples']), 100)
        self.assertLessEqual(len(self.lua.globals().B.traces[trace].samples), 600)
        last = self.request('capture_vehicle_trace', {'action': 'stop', 'trace_id': trace, 'after': 610})['result']
        self.assertTrue(last['done'])
        self.assertTrue(any(s.get('relationship_changed') for s in last['samples']))

    def test_trace_capacity_and_eviction(self):
        identifiers = [self.request('capture_vehicle_trace')['result']['trace_id'] for _ in range(4)]
        self.assertEqual(self.request('capture_vehicle_trace')['error']['code'], 'TRACE_LIMIT')
        self.request('capture_vehicle_trace', {'action': 'stop', 'trace_id': identifiers[0]})
        self.assertTrue(self.request('capture_vehicle_trace')['ok'])
        self.assertEqual(self.request('capture_vehicle_trace', {'action': 'read', 'trace_id': identifiers[0]})['error']['code'], 'TRACE_NOT_FOUND')

    def test_incremental_error_ring_and_session_reset(self):
        self.lua.execute("errorsList[1]='first error'")
        first = self.request('read_errors')['result']
        self.assertTrue(any(e['message'] == 'first error' for e in first['events']))
        second = self.request('read_errors', {'after': first['cursor'], 'session': first['session']})['result']
        self.assertEqual(second['events'], [])
        self.lua.execute("for i=1,300 do B.recordEvent('test', tostring(i)) end")
        self.assertTrue(self.request('read_errors')['result']['gap'])
        self.lua.execute("clock=clock+1000; B.start('client')")
        self.assertTrue(self.request('read_errors', {'after': first['cursor'], 'session': first['session']})['result']['reset'])

    def test_reload_no_duplicate_and_failure_disable(self):
        count = len(self.lua.globals().Events.OnTick.callbacks)
        for _ in range(3):
            self.assertTrue(self.request('reload_mod_lua', {'module': 'example_counter'})['ok'])
            self.assertEqual(len(self.lua.globals().Events.OnTick.callbacks), count)
        self.lua.globals().reloadMode = 'no_registration'
        self.assertEqual(self.request('reload_mod_lua', {'module': 'example_counter'})['error']['code'], 'RELOAD_FAILED')
        self.assertFalse(self.lua.globals().B.modules.example_counter.enabled)
        self.assertEqual(len(self.lua.globals().Events.OnTick.callbacks), count - 1)

    def test_unknown_module_and_test(self):
        self.assertEqual(self.request('reload_mod_lua', {'module': '../evil.lua'})['error']['code'], 'MODULE_NOT_ALLOWED')
        self.assertEqual(self.request('run_test', {'name': 'missing'})['error']['code'], 'TEST_NOT_FOUND')
        self.assertEqual(self.request('eval_lua')['error']['code'], 'UNKNOWN_OPERATION')

    def test_server_loader(self):
        self.lua.execute("serverMode=true; clock=clock+1000; B.start('server')")
        self.assertTrue(self.request('reload_mod_lua', {'module': 'example_counter'})['ok'])
        self.assertEqual(self.lua.globals().lastReloadApi, 'client')
        self.assertEqual(self.request('inspect_vehicle')['error']['code'], 'VEHICLE_ID')


class FileTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.bridge = Bridge(self.directory, timeout=0.3)
        self.mailbox = self.bridge.directory('client')
        self.mailbox.mkdir(parents=True)

    def heartbeat(self, session='test', age=0):
        atomic_json(self.mailbox / 'heartbeat.json', {'protocol': 1, 'session': session,
                    'endpoint': 'client', 'timestamp_ms': int((time.time() - age) * 1000)})

    def test_offline_and_stale_heartbeat(self):
        self.assertFalse(self.bridge.status('client')['online'])
        self.heartbeat(age=5)
        with self.assertRaisesRegex(BridgeError, 'OFFLINE'):
            self.bridge.request('client', 'status')

    def test_atomic_json_and_bounds(self):
        path = self.directory / 'test.json'
        atomic_json(path, {'text': '中文'})
        self.assertEqual(read_json(path), {'text': '中文'})
        with self.assertRaisesRegex(BridgeError, 'TOO_LARGE'):
            atomic_json(path, {'text': 'a' * MAX_BYTES})
        self.assertEqual(list(self.directory.glob('*.tmp')), [])

    def test_timeout_not_cancelled(self):
        self.heartbeat()
        with self.assertRaisesRegex(BridgeError, 'TIMEOUT'):
            self.bridge.request('client', 'run_test')
        self.assertIsNotNone(read_json(self.mailbox / 'request.json'))

    def test_claimed_request_is_not_overwritten(self):
        self.heartbeat()
        previous = {'id': 'a' * 32, 'session': 'test', 'expires_ms': 0}
        atomic_json(self.mailbox / 'request.json', previous)
        atomic_json(self.mailbox / 'claim.json', previous)
        with self.assertRaisesRegex(BridgeError, 'INDETERMINATE'):
            self.bridge.request('client', 'status')
        self.assertEqual(read_json(self.mailbox / 'request.json'), previous)

    def test_incomplete_response_and_wrong_session(self):
        request = {'id': 'a' * 32, 'session': 'test'}
        atomic_json(self.mailbox / 'response.json', {**request, 'protocol': 1, 'ok': True, 'result': {}})
        self.assertIsNone(self.bridge.response(self.mailbox, request))
        (self.mailbox / 'response.ready.txt').write_text(request['id'])
        self.assertIsNotNone(self.bridge.response(self.mailbox, request))
        self.assertIsNone(self.bridge.response(self.mailbox, {**request, 'session': 'other'}))

    def test_console_incremental_and_truncation(self):
        console = self.directory / 'console.txt'
        console.write_text('first\n', encoding='utf-8', newline='\n')
        first = self.bridge.console()
        console.write_text('first\nsecond\n', encoding='utf-8', newline='\n')
        self.assertEqual(self.bridge.console(first['cursor'])['text'], 'second\n')
        console.write_text('new\n', encoding='utf-8')
        self.assertTrue(self.bridge.console({'file_id': first['cursor']['file_id'], 'offset': 100})['reset'])

    def test_file_bridge_and_concurrent_clients(self):
        with LuaGame(self.directory):
            bridge = Bridge(self.directory, timeout=3)
            self.assertTrue(bridge.request('client', 'run_test', {'name': 'bridge_self_test'})['result']['passed'])
            with ThreadPoolExecutor(max_workers=4) as pool:
                results = list(pool.map(lambda _: bridge.request('client', 'inspect_vehicle'), range(8)))
            self.assertEqual([r['vehicle']['id'] for r in results], [1] * 8)

    def test_standard_mcp_stdio(self):
        async def check():
            params = StdioServerParameters(command=sys.executable,
                args=['-m', 'pz_debug_mcp.server', '--zomboid-dir', str(self.directory)],
                env={**os.environ, 'PYTHONUTF8': '1'})
            async with stdio_client(params) as (read, write):
                async with ClientSession(read, write) as session:
                    info = await session.initialize()
                    self.assertEqual(info.serverInfo.name, 'PZ Debug MCP')
                    tools = await session.list_tools()
                    self.assertEqual(len(tools.tools), 7)
                    status = await session.call_tool('pz_status')
                    self.assertFalse(status.isError)
                    status_data = json.loads(status.content[0].text)
                    self.assertTrue(status_data['online'])
                    vehicle = await session.call_tool('pz_inspect_vehicle', {'include_parts': True})
                    self.assertFalse(vehicle.isError)
                    self.assertEqual(json.loads(vehicle.content[0].text)['vehicle']['parts'][0]['condition'], 80)
                    wrong = await session.call_tool('pz_capture_vehicle_trace', {'interval_ms': 1})
                    self.assertTrue(wrong.isError)
                    reload = await session.call_tool('pz_reload_mod_lua', {'module': 'example_counter'})
                    self.assertFalse(reload.isError)
                    test = await session.call_tool('pz_run_test', {'name': 'bridge_self_test'})
                    self.assertTrue(json.loads(test.content[0].text)['result']['passed'])
                    errors = await session.call_tool('pz_read_errors')
                    self.assertFalse(errors.isError)
                    trace = await session.call_tool('pz_capture_vehicle_trace')
                    trace_id = json.loads(trace.content[0].text)['trace_id']
                    await asyncio.sleep(0.15)
                    trace_read = await session.call_tool('pz_capture_vehicle_trace', {'action': 'stop', 'trace_id': trace_id})
                    self.assertTrue(json.loads(trace_read.content[0].text)['done'])
                    missing = await session.call_tool('pz_run_test', {'name': 'missing'})
                    self.assertTrue(missing.isError)
        with LuaGame(self.directory):
            asyncio.run(check())


if __name__ == '__main__':
    unittest.main()
