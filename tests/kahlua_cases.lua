local B = require 'PZDebugMCP/Bridge'
require 'PZDebugMCP/Example'
B.start('client')
assert(request('status').result.debug_enabled)
assert(request('run_test', { name = 'bridge_self_test' }).result.result.passed)
local vehicle = request('inspect_vehicle', { vehicle_id = 1, include_parts = true }).result.vehicle
assert(vehicle.id == 1 and vehicle.towing_id == 2 and vehicle.parts[1].condition == 80)
assert(request('run_test', { name = 'vehicle_relationships' }).result.result.passed)
local capture = request('capture_vehicle_trace', { action = 'start', vehicle_id = 1 }).result
clock = clock + 200; B.tick()
local trace = request('capture_vehicle_trace', { action = 'read', trace_id = capture.trace_id }).result
assert(#trace.samples >= 1 and trace.samples[1].trailer.id == 2)
assert(request('capture_vehicle_trace', { action = 'stop', trace_id = capture.trace_id }).result.done)
local ticks = #Events.OnTick.callbacks
assert(request('reload_mod_lua', { module = 'example_counter' }).ok)
assert(#Events.OnTick.callbacks == ticks)
assert(request('reload_mod_lua', { module = 'example_counter' }).ok)
assert(#Events.OnTick.callbacks == ticks)
assert(request('run_test', { name = 'example_counter' }).ok)
errorsList[1] = 'test error'
assert(#request('read_errors').result.events >= 2)
assert(request('inspect_vehicle', {}, clock - 1).error.code == 'EXPIRED')
debugEnabled = false
assert(request('inspect_vehicle').error.code == 'DEBUG_DISABLED')
assert(request('status').ok)
debugEnabled = true
reloadMode = 'no_registration'
assert(request('reload_mod_lua', { module = 'example_counter' }).error.code == 'RELOAD_FAILED')
assert(not B.modules.example_counter.enabled)
assert(request('reload_mod_lua', { module = 'example_counter' }).error.code == 'MODULE_DISABLED')
local J = B.Json
local decoded = J.decode('{"text":"\\u4e2d\\u6587\\ud83d\\ude97","empty":[],"null":null}')
assert(decoded.text == string.char(20013, 25991, 55357, 56983))
assert(J.encode(J.array()) == '[]')
assert(not pcall(J.decode, '{"a":1,"a":2}'))
assert(not pcall(J.decode, '01'))
assert(not pcall(J.decode, '[1,]'))
assert(not pcall(J.decode, '1e999'))
assert(not pcall(J.decode, '{"x":"\\ud800"}'))
-- Returning to another world must create a new session and remove old bridge callbacks.
local oldSession = B.session
clock = clock + 1000
B.start('client')
assert(B.session ~= oldSession and #Events.OnTick.callbacks == 1)
assert(#Events.OnTickEvenPaused.callbacks == 1)
serverMode = true
clock = clock + 1000
B.start('server')
assert(request('inspect_vehicle', { vehicle_id = 1 }).ok)
assert(request('inspect_vehicle').error.code == 'VEHICLE_ID')
print('Lua bridge protocol, vehicle, trace, errors, reload, Unicode and session checks passed')
