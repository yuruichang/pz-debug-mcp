local B = PZDebugMCP
local J = B.Json
B.start('client')
-- The preceding failure test deliberately disables this fixture module.
reloadMode = 'normal'
B.modules.example_counter.enabled = true
assert(B.java ~= nil)
local function call(operation, arguments)
    java_send(operation, J.encode(arguments or {}))
    local text
    for _ = 1, 100 do
        java_waitTick()
        clock = clock + 100
        B.tick()
        text = java_response()
        if text then return J.decode(text) end
    end
    error('Java request did not complete')
end
local status = call('status')
assert(status.ok, J.encode(status))
assert(status.result.backend == 'zombiebuddy_java')
assert(status.result.recorder.read_policy == 'java_fields_reviewed_lua_v1')
local player = call('query_debug', { target = 'getPlayer' })
assert(player.ok, J.encode(player))
local handle = player.result.data.value.handle
assert(handle:match(':j%d+$'))
local health = call('query_debug', { target = handle, action = 'field', member = 'health' })
assert(health.ok, J.encode(health))
assert(health.result.data.value == 76)
local object = call('list_debug_interfaces', { scope = 'object', handle = handle, limit = 100 })
assert(object.ok, J.encode(object))
assert(object.result.total >= 3)
local unknown = call('query_debug', { target = handle, member = 'getMystery' })
assert(not unknown.ok and unknown.error.code == 'NOT_A_READER', J.encode(unknown))
assert(realObject:getSideEffectCount() == 0)
local expired = call('query_debug', { target = 'missing:j999', action = 'inspect' })
assert(not expired.ok and expired.error.code == 'HANDLE_EXPIRED', J.encode(expired))
local opaque = J.decode(PZDebugJava.describe(realOpaque, 'opaque', 0))
assert(opaque.ok and opaque.result.handle)
local configured = call('configure_recorder', { enabled = false })
assert(configured.ok, J.encode(configured))
local stopped = call('status')
assert(stopped.result.recorder.enabled == false)
local selfTest = call('run_test', { name = 'bridge_self_test' })
assert(selfTest.ok and selfTest.result.result.passed)
for _ = 1, 256 do B.recordEvent('fixture', string.rep(string.char(1), 4096)) end
java_waitTick()
clock = clock + 1000
B.tick()
local cached = call('read_errors', { limit = 100 })
assert(cached.ok and cached.result.gap and #cached.result.events == 64, J.encode(cached))
assert(cached.result.events[1].message_truncated)
local reloaded = call('reload_mod_lua', { module = 'example_counter' })
assert(reloaded.ok, J.encode(reloaded))
assert(B.Data.state.lastError == nil, J.encode(B.Data.state.lastError))
