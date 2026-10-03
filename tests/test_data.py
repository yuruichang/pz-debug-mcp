import json
from pathlib import Path
import tempfile
import unittest

from pz_debug_mcp.bridge import Bridge, atomic_json
from pz_debug_mcp.record_store import RecordStore
import test_bridge


class DebugDataTests(unittest.TestCase):
    setUp = test_bridge.LuaTests.setUp
    request = test_bridge.LuaTests.request
    def test_catalog_covers_all_official_signatures(self):
        first = self.request('list_debug_interfaces', {'limit': 100})['result']
        self.assertEqual(first['total'], 764)
        readers = self.request('list_debug_interfaces', {'readers_only': True})['result']
        self.assertEqual(readers['total'], 330)
        self.assertTrue(first['has_more'])
        self.assertEqual(self.request('list_debug_interfaces', {'offset': 764})['result']['items'], [])

    def test_non_vehicle_objects_fields_inheritance_and_tables(self):
        player = self.request('query_debug', {'target': 'getPlayer'})['result']['data']['value']['handle']
        health = self.request('query_debug', {'target': player, 'member': 'getHealth'})
        self.assertEqual(health['result']['data']['value'], 75)
        field = self.request('query_debug', {'target': player, 'action': 'field', 'member': 'health'})
        self.assertEqual(field['result']['data']['value'], 75)
        climate = self.request('query_debug', {'target': 'getClimateManager'})['result']['data']['value']['handle']
        self.assertEqual(self.request('query_debug', {'target': climate, 'member': 'getTemperature'})['result']['data']['value'], 17.5)
        table = self.request('query_debug', {'target': 'root:SandboxVars'})['result']['data']['value']['handle']
        self.assertEqual(self.request('query_debug', {'target': table, 'action': 'table', 'member': 'DayLength'})['result']['data']['value'], 3)

    def test_object_catalog_and_pagination(self):
        player = self.request('query_debug', {'target': 'getPlayer'})['result']['data']['value']['handle']
        first = self.request('list_debug_interfaces', {'scope': 'object', 'handle': player, 'limit': 10})['result']
        self.assertTrue(first['has_more'])
        self.assertEqual(len(first['items']), 10)
        second = self.request('query_debug', {'target': player, 'action': 'inspect', 'offset': first['next_offset'], 'limit': 10})['result']['data']
        self.assertEqual(second['offset'], 10)

    def test_mutators_factories_arbitrary_functions_and_argument_counts_denied(self):
        for name in ['setDebug', 'reloadLuaFile', 'getFileWriter', 'getTexture', 'not_an_api']:
            self.assertFalse(self.request('query_debug', {'target': name})['ok'])
        core = self.request('query_debug', {'target': 'getCore'})['result']['data']['value']['handle']
        self.assertEqual(self.request('query_debug', {'target': core, 'member': 'setOptionPauseOnFocusloss', 'arguments': [True]})['error']['code'], 'NOT_A_READER')
        player = self.request('query_debug', {'target': 'getPlayer'})['result']['data']['value']['handle']
        self.assertEqual(self.request('query_debug', {'target': player, 'member': 'getHealth', 'arguments': [1]})['error']['code'], 'NOT_A_READER')
        table = self.request('query_debug', {'target': 'root:_G'})['result']['data']['value']['handle']
        self.assertFalse(self.request('query_debug', {'target': table, 'member': 'getPlayer'})['ok'])

    def test_object_handle_arguments_to_official_debug_helpers(self):
        player = self.request('query_debug', {'target': 'getPlayer'})['result']['data']['value']['handle']
        fields = self.request('query_debug', {'target': 'getNumClassFields', 'arguments': [{'handle': player}]})['result']['data']['value']
        self.assertEqual(fields, 1)

    def test_automatic_recording_without_requests_and_pause(self):
        before = self.lua.globals().B.Data.state.sequence
        self.lua.execute('for i=1,30 do clock=clock+100; B.tick() end')
        self.assertGreater(self.lua.globals().B.Data.state.sequence, before)
        self.assertIsNone(self.lua.globals().B.Data.state.lastError)
        self.request('configure_recorder', {'enabled': False})
        stopped = self.lua.globals().B.Data.state.sequence
        self.lua.execute('clock=clock+1000; B.tick()')
        self.assertEqual(self.lua.globals().B.Data.state.sequence, stopped)

    def test_handle_reset_after_resize_and_session_change(self):
        player = self.request('query_debug', {'target': 'getPlayer'})['result']['data']['value']['handle']
        self.request('configure_recorder', {'max_handles': 128})
        self.assertEqual(self.request('query_debug', {'target': player, 'action': 'inspect'})['error']['code'], 'HANDLE_EXPIRED')

    def test_record_segments_rotate_and_publish_committed_index(self):
        self.request('configure_recorder', {'enabled': False})
        self.lua.execute("for i=1,2100 do B.Data.query({target='getDebug'}) end")
        index = json.loads(self.lua.globals().files[self.lua.globals().B.path + 'records/index.json'])
        self.assertEqual(len(index['segments']), 16)
        self.assertGreater(index['first'], 1)
        self.assertGreaterEqual(index['last'], 2100)

    def test_watched_queries_are_collected_without_model_calls(self):
        watch = self.request('watch_debug', {'query': {'target': 'getTimestampMs'}, 'interval_ms': 100})['result']
        initial = watch['initial']['record_sequence']
        self.lua.execute('for i=1,15 do clock=clock+100; B.tick() end')
        listing = self.request('watch_debug', {'action': 'list'})['result']['watches']
        self.assertGreater(listing[0]['last_record'], initial)
        self.assertTrue(self.request('watch_debug', {'action': 'remove', 'watch_id': watch['watch_id']})['result']['removed'])
        self.assertEqual(self.request('watch_debug', {'action': 'list'})['result']['watches'], [])
        player = self.request('query_debug', {'target': 'getPlayer'})['result']['data']['value']['handle']
        self.lua.execute("clock=clock+1000; B.start('client')")
        self.assertEqual(self.request('query_debug', {'target': player, 'action': 'inspect'})['error']['code'], 'HANDLE_EXPIRED')


class RecordedDataTests(unittest.TestCase):
    def test_archive_keeps_old_segments_and_previous_sessions(self):
        with tempfile.TemporaryDirectory() as temporary:
            bridge = Bridge(Path(temporary))
            store = RecordStore(bridge)
            directory = bridge.directory('client') / 'records'
            directory.mkdir(parents=True)
            def publish(session, start, end):
                rows = [{'session': session, 'sequence': n, 'target': 'player', 'kind': 'global', 'data': {'value': n}} for n in range(start, end + 1)]
                (directory / 'segment-01.log').write_text(''.join(json.dumps(row) + '\n' for row in rows), encoding='utf-8')
                atomic_json(directory / 'index.json', {'schema': 1, 'session': session, 'first': start, 'last': end, 'segments': [{'slot': 1}]})
            publish('first', 1, 3)
            store.sync('client')
            publish('first', 4, 6)
            store.sync('client')
            self.assertEqual([r['sequence'] for r in store.read('client')['records']], list(range(1, 7)))
            publish('second', 1, 2)
            store.sync('client')
            self.assertEqual(len(store.read('client', recording_session='first')['records']), 6)
            self.assertEqual(len(store.read('client')['available_sessions']), 2)

    def test_archive_marks_records_lost_before_connection(self):
        with tempfile.TemporaryDirectory() as temporary:
            bridge = Bridge(Path(temporary))
            directory = bridge.directory('client') / 'records'
            directory.mkdir(parents=True)
            row = {'session': 'test', 'sequence': 20, 'target': 'world', 'kind': 'object', 'data': {}}
            (directory / 'segment-01.log').write_text(json.dumps(row) + '\n', encoding='utf-8')
            atomic_json(directory / 'index.json', {'schema': 1, 'session': 'test', 'first': 20, 'last': 20, 'segments': [{'slot': 1}]})
            result = RecordStore(bridge).read('client')
            self.assertEqual(result['gaps'], [{'first': 1, 'last': 19}])
            self.assertTrue(result['gap'])
    def test_offline_record_filter_pagination_partial_line_and_session_reset(self):
        with tempfile.TemporaryDirectory() as temporary:
            bridge = Bridge(Path(temporary))
            self.assertTrue(bridge.recorded('client')['missing'])
            directory = bridge.directory('client') / 'records'
            directory.mkdir(parents=True)
            entries = [{'session': 'test', 'sequence': i, 'target': 'player' if i % 2 else 'weather', 'kind': 'object', 'data': {'value': i}} for i in range(1, 7)]
            (directory / 'segment-01.log').write_bytes(('\n'.join(json.dumps(e) for e in entries) + '\n{"partial":').encode())
            atomic_json(directory / 'index.json', {'schema': 1, 'session': 'test', 'first': 1, 'last': 6, 'segments': [{'slot': 1}]})
            first = bridge.recorded('client', target='player', limit=2)
            self.assertEqual([e['sequence'] for e in first['records']], [1, 3])
            self.assertTrue(first['has_more'])
            next = bridge.recorded('client', after=first['cursor'], target='player')
            self.assertEqual([e['sequence'] for e in next['records']], [5])
            self.assertFalse(next['has_more'])
            self.assertTrue(bridge.recorded('client', after=999, session='old')['reset'])

    def test_real_file_recording_is_readable_after_game_stops(self):
        with tempfile.TemporaryDirectory() as temporary:
            bridge = Bridge(Path(temporary), timeout=3)
            with test_bridge.LuaGame(Path(temporary)):
                bridge.request('client', 'query_debug', {'target': 'getClimateManager'})
                during = bridge.recorded('client', target='getClimateManager')
                self.assertTrue(during['records'])
            self.assertEqual(bridge.recorded('client', target='getClimateManager')['records'], during['records'])
