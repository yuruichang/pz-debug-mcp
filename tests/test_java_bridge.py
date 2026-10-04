"""Real JVM mailbox and MCP checks; the fixture is not a game save."""

import asyncio
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from pz_debug_mcp.bridge import Bridge, BridgeError
from pz_debug_mcp.record_store import RecordStore


@unittest.skipUnless(os.environ.get('PZDEBUG_JAVA_TEST_CP'), 'Run build.ps1 to build the JVM fixture')
class JavaBridgeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.cache = Path(self.temp.name)
        self.process = subprocess.Popen([
            os.environ['PZDEBUG_JAVA_TEST_EXE'], '--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED',
            '-javaagent:' + os.environ['PZDEBUG_JAVA_TEST_AGENT'], '-cp', os.environ['PZDEBUG_JAVA_TEST_CP'],
            'com.yuruichang.pzdebug.TestMain', '--serve', str(self.cache)],
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding='utf-8')
        self.addCleanup(self.stop)
        self.assertEqual(self.process.stdout.readline().strip(), 'READY')
        self.bridge = Bridge(self.cache, timeout=10)
        deadline = time.monotonic() + 5
        while not self.bridge.status('client')['online'] and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue(self.bridge.status('client')['online'])

    def stop(self):
        if self.process.poll() is None:
            self.process.terminate()
        self.process.communicate(timeout=10)

    def test_runtime_threads_inventory_and_private_field(self):
        status = self.bridge.request('client', 'status')
        self.assertEqual(status['backend'], 'zombiebuddy_java')
        for section in ['summary', 'metrics', 'threads', 'classes', 'roots']:
            result = self.bridge.request('client', 'java_runtime', {'section': section})
            self.assertIsInstance(result, dict)
        field = self.bridge.request('client', 'inspect_java', {
            'target': status['fixture_handle'], 'action': 'field', 'member': 'secret'})
        self.assertEqual(field['data']['value'], 17)
        with self.assertRaises(BridgeError) as error:
            self.bridge.request('client', 'inspect_java', {
                'target': status['fixture_handle'], 'action': 'call', 'member': 'getUnknown'})
        self.assertEqual(error.exception.code, 'NOT_A_READER')

    def test_java_records_archive_and_filter(self):
        self.bridge.request('client', 'inspect_java', {
            'target': self.bridge.request('client', 'status')['fixture_handle'], 'action': 'inspect'})
        deadline = time.monotonic() + 5
        result = {}
        while time.monotonic() < deadline:
            result = RecordStore(self.bridge).read('client', kind='java_runtime')
            if result['records']:
                break
            time.sleep(0.05)
        self.assertTrue(result['records'])
        self.assertEqual(result['records'][0]['target'], 'jvm:metrics')
        self.assertTrue(result['archived'])

    def test_pending_game_request_does_not_block_runtime_mailbox(self):
        status = self.bridge.request('client', 'status')
        paused = self.cache / 'pause-game.txt'
        paused.touch()
        time.sleep(0.08)
        quick = Bridge(self.cache, timeout=0.2)
        with self.assertRaises(BridgeError) as error:
            quick.request('client', 'inspect_java', {'target': status['fixture_handle'], 'action': 'inspect'})
        self.assertEqual(error.exception.code, 'TIMEOUT')
        metrics = self.bridge.request('client', 'java_runtime', {'section': 'metrics'})
        self.assertIn('heap', metrics)
        self.assertTrue(self.bridge.status('client')['online'])
        self.assertTrue(self.bridge.request('client', 'read_errors')['cached'])
        paused.unlink()
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            try:
                result = self.bridge.request('client', 'inspect_java', {'target': status['fixture_handle'], 'action': 'inspect'})
                self.assertIn('items', result['data'])
                break
            except BridgeError as error:
                self.assertIn(error.code, {'INDETERMINATE', 'BUSY'})
                time.sleep(0.05)
        else:
            self.fail('Expired game request did not complete after resuming')

    def test_method_trace_keeps_results_and_stops(self):
        start = self.bridge.request('client', 'trace_java', {'action': 'start',
            'class_name': 'fixture.TraceSubject', 'method': 'compute', 'parameters': ['int'], 'duration_seconds': 2})
        time.sleep(0.15)
        result = self.bridge.request('client', 'trace_java', {'action': 'stop', 'trace_id': start['trace_id']})
        self.assertTrue(result['done'])
        self.assertTrue(result['samples'])
        self.assertTrue(all(sample['result'] == 10 for sample in result['samples']))
        time.sleep(0.1)
        later = self.bridge.request('client', 'trace_java', {'action': 'read', 'trace_id': start['trace_id']})
        self.assertEqual(len(result['samples']), len(later['samples']))

    def test_mcp_sdk_new_java_tools(self):
        async def check():
            params = StdioServerParameters(command=sys.executable,
                args=['-m', 'pz_debug_mcp.server', '--zomboid-dir', str(self.cache), '--timeout', '10'],
                env=dict(os.environ, PYTHONUTF8='1'))
            async with stdio_client(params) as (reader, writer):
                async with ClientSession(reader, writer) as session:
                    await session.initialize()
                    self.assertEqual(len((await session.list_tools()).tools), 15)
                    metrics = await session.call_tool('pz_java_runtime', {'section': 'metrics'})
                    self.assertFalse(metrics.isError)
                    status = self.bridge.request('client', 'status')
                    field = await session.call_tool('pz_inspect_java', {
                        'target': status['fixture_handle'], 'action': 'field', 'member': 'secret'})
                    self.assertFalse(field.isError)
                    field_data = field.structuredContent or json.loads(field.content[0].text)
                    self.assertEqual(field_data['data']['value'], 17)
                    start = await session.call_tool('pz_trace_java', {'action': 'start',
                        'class_name': 'fixture.TraceSubject', 'method': 'compute', 'parameters': ['int']})
                    self.assertFalse(start.isError)
                    start_data = start.structuredContent or json.loads(start.content[0].text)
                    stop = await session.call_tool('pz_trace_java', {
                        'action': 'stop', 'trace_id': start_data['trace_id']})
                    self.assertFalse(stop.isError)
        asyncio.run(check())
