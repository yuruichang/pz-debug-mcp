if PZDebugMCP then return PZDebugMCP end
local J = require 'PZDebugMCP/Json'
local Focus = require 'PZDebugMCP/Focus'
local Data = require 'PZDebugMCP/Data'
local B = { Json = J, tests = {}, modules = {}, traces = {}, events = {}, sequence = 0 }
PZDebugMCP = B
local handlers = {}
local function now() return getTimestampMs() end
local function clip(value) return tostring(value):sub(1, 4096) end
local function fail(code, message) error({ code = code, message = message }) end
local function isDebug() return type(getDebug) == 'function' and getDebug() == true end
local function integer(value, default, minimum, maximum)
    if value == nil or value == J.null then value = default end
    if type(value) ~= 'number' or value ~= math.floor(value) or value < minimum or value > maximum then
        fail('ARGUMENT', 'Integer out of range')
    end
    return value
end
local function read(name)
    local reader = getFileReader(B.path .. name, false)
    if not reader then return nil end
    local ok, line = pcall(function() return reader:readLine() end)
    reader:close()
    if not ok or not line or #line > 524288 then return nil end
    return line
end
local function writeRaw(name, text, append)
    local writer = getFileWriter(B.path .. name, true, append == true)
    if not writer then error('Cannot open bridge writer: ' .. name) end
    local ok, why = pcall(function() writer:write(text) end)
    writer:close()
    if not ok then error(why) end
end
local function write(name, text) writeRaw(name, text .. '\n', false) end
local function jsonWrite(name, value) write(name, J.encode(value)) end
local function safeCall(object, method)
    if not object then return J.null end
    local ok, value = pcall(function() return object[method](object) end)
    if not ok or value == nil then return J.null end
    if type(value) == 'number' or type(value) == 'boolean' or type(value) == 'string' then return value end
    return tostring(value)
end

function B.recordEvent(kind, message)
    B.sequence = B.sequence + 1
    B.events[#B.events + 1] = { sequence = B.sequence, timestamp_ms = now(), kind = clip(kind), message = clip(message) }
    if #B.events > 256 then table.remove(B.events, 1) end
end

function B.registerTest(name, callback, description)
    assert(type(name) == 'string' and name:match('^[%w_]+$'), 'Invalid test name')
    assert(type(callback) == 'function', 'Test callback required')
    B.tests[name] = { run = callback, description = clip(description or name) }
end

function B.registerReloadable(name, specification)
    assert(type(name) == 'string' and name:match('^[%w_]+$'), 'Invalid module name')
    local path = specification.path
    assert(type(path) == 'string' and path:match('^media/lua/') and path:match('%.lua$')
        and not path:find('..', 1, true) and not path:find('\\', 1, true), 'Invalid reload path')
    assert(not path:find('PZDebugMCP/Bridge.lua', 1, true) and not path:find('PZDebugMCP/Json.lua', 1, true), 'Bridge cannot reload itself')
    assert(type(specification.cleanup) == 'function' and type(specification.init) == 'function', 'cleanup/init required')
    specification.enabled = specification.enabled ~= false
    B.modules[name] = specification
end

local function vehicle(id)
    if id == nil or id == J.null then
        if B.endpoint ~= 'client' then fail('VEHICLE_ID', 'Server requires vehicle_id') end
        local player = getPlayer()
        local current = player and player:getVehicle()
        if not current then fail('VEHICLE_NOT_FOUND', 'Local player is not in a vehicle') end
        return current
    end
    id = integer(id, nil, -32768, 32767)
    local found = getVehicleById(id)
    if not found then
        local cell = getCell()
        local list = cell and cell:getVehicles()
        if list then
            for i = 0, list:size() - 1 do
                local candidate = list:get(i)
                if candidate:getId() == id then found = candidate; break end
            end
        end
    end
    if not found then fail('VEHICLE_NOT_FOUND', 'Vehicle is not loaded: ' .. tostring(id)) end
    return found
end

local function relation(v, method)
    local ok, other = pcall(function() return v[method](v) end)
    if not ok then return J.null, false end
    return other and other:getId() or J.null, true
end

local function snapshot(v, parts)
    local towing, towingSupported = relation(v, 'getVehicleTowing')
    local towedBy, towedBySupported = relation(v, 'getVehicleTowedBy')
    local result = { id = v:getId(), script = safeCall(v, 'getScriptName'),
        x = v:getX(), y = v:getY(), z = v:getZ(), speed_kmh = safeCall(v, 'getCurrentSpeedKmHour'),
        speed_2d = safeCall(v, 'getSpeed2D'), engine_running = safeCall(v, 'isEngineRunning'),
        engine_state = safeCall(v, 'getEngineState'), net_player_id = safeCall(v, 'getNetPlayerId'),
        removed = safeCall(v, 'isRemovedFromWorld'), towing_id = towing, towed_by_id = towedBy,
        capabilities = { towing = towingSupported, towed_by = towedBySupported } }
    if parts then
        result.parts = J.array()
        local ok, why = pcall(function()
            result.part_count = v:getPartCount()
            for i = 0, math.min(result.part_count, 128) - 1 do
                local part = v:getPartByIndex(i)
                result.parts[#result.parts + 1] = { id = safeCall(part, 'getId'), condition = safeCall(part, 'getCondition'),
                    content_amount = safeCall(part, 'getContainerContentAmount') }
            end
            result.parts_truncated = result.part_count > 128
        end)
        if not ok then result.parts_error = clip(why) end
    end
    return result
end

local function names(registry, kind)
    local result = J.array()
    for name, entry in pairs(registry) do
        if kind == 'module' then result[#result + 1] = { name = name, path = entry.path, enabled = entry.enabled }
        else result[#result + 1] = { name = name, description = entry.description } end
    end
    return result
end

handlers.status = function()
    return { protocol = 1, version = '0.2.3', game_version = getCore():getVersionNumber(),
        session = B.session, endpoint = B.endpoint, timestamp_ms = now(), debug_enabled = isDebug(),
        mode = isServer() and 'server' or (isClient() and 'multiplayer_client' or 'singleplayer'),
        capabilities = { inspect_vehicle = true, vehicle_trace = true, run_test = true,
            debug_errors = type(getLuaDebuggerErrors) == 'function', reload_lua = type(reloadLuaFile) == 'function',
            reload_server_lua = type(reloadServerLuaFile) == 'function', arbitrary_lua = false,
            breakpoint_control = false, paused_polling = B.pausedPolling },
        focus_pause = B.focusPause, recorder = Data.status(),
        tests = names(B.tests, 'test'), modules = names(B.modules, 'module') }
end

handlers.list_debug_interfaces = Data.list
handlers.query_debug = Data.query
handlers.configure_recorder = Data.configure
handlers.watch_debug = Data.watch
B.Data = Data

local function collectErrors()
    if type(getLuaDebuggerErrors) ~= 'function' then return end
    local list = getLuaDebuggerErrors()
    if not list then return end
    local count = list:size()
    if count < B.errorIndex then B.errorIndex = 0 end
    for i = B.errorIndex, math.min(count, B.errorIndex + 20) - 1 do
        B.recordEvent('lua_error', list:get(i))
        B.errorIndex = i + 1
    end
end

handlers.read_errors = function(args)
    collectErrors()
    local reset = args.session and args.session ~= J.null and args.session ~= B.session or false
    local after = reset and 0 or integer(args.after, 0, 0, 2147483647)
    local limit = integer(args.limit, 50, 1, 100)
    local first = B.events[1] and B.events[1].sequence or B.sequence + 1
    local out, cursor = J.array(), after
    for _, entry in ipairs(B.events) do
        if entry.sequence > after and #out < limit then out[#out + 1] = entry; cursor = entry.sequence end
    end
    return { events = out, cursor = cursor, session = B.session, reset = reset,
        gap = after < first - 1, has_more = cursor < B.sequence }
end

handlers.inspect_vehicle = function(args)
    return { timestamp_ms = now(), vehicle = snapshot(vehicle(args.vehicle_id), args.include_parts == true) }
end

handlers.capture_vehicle_trace = function(args)
    local action = args.action or 'start'
    if action == 'start' then
        local v = vehicle(args.vehicle_id)
        local duration = args.duration_seconds or 10
        if type(duration) ~= 'number' or duration < 1 or duration > 60 then fail('ARGUMENT', 'Duration must be 1..60 seconds') end
        local interval = integer(args.interval_ms, 100, 50, 1000)
        local count, oldest = 0, nil
        for id, trace in pairs(B.traces) do
            count = count + 1
            if trace.done and (not oldest or trace.started < B.traces[oldest].started) then oldest = id end
        end
        if count >= 4 then
            if oldest then B.traces[oldest] = nil else fail('TRACE_LIMIT', 'Four captures are already active') end
        end
        B.traceSequence = B.traceSequence + 1
        local id = B.session .. '-' .. tostring(B.traceSequence)
        B.traces[id] = { id = id, vehicle_id = v:getId(), started = now(), deadline = now() + duration * 1000,
            next_sample = 0, interval = interval, samples = J.array(), sequence = 0, done = false, dropped = 0 }
        return { trace_id = id, vehicle_id = v:getId(), duration_seconds = duration, interval_ms = interval, capacity = 600 }
    end
    if action ~= 'read' and action ~= 'stop' then fail('ARGUMENT', 'Trace action must be start/read/stop') end
    local trace = B.traces[args.trace_id]
    if not trace then fail('TRACE_NOT_FOUND', 'Unknown trace or capture was evicted') end
    if action == 'stop' then trace.done = true end
    local after, limit = integer(args.after, 0, 0, 2147483647), integer(args.limit, 100, 1, 100)
    local samples, cursor = J.array(), after
    local first = trace.samples[1] and trace.samples[1].sequence or trace.sequence + 1
    for _, sample in ipairs(trace.samples) do
        if sample.sequence > after and #samples < limit then samples[#samples + 1] = sample; cursor = sample.sequence end
    end
    return { trace_id = trace.id, samples = samples, cursor = cursor, done = trace.done,
        gap = after < first - 1, dropped = trace.dropped, has_more = cursor < trace.sequence }
end

local function sampleTraces(timestamp)
    for _, trace in pairs(B.traces) do
        if not trace.done then
            if timestamp > trace.deadline then trace.done = true
            elseif timestamp >= trace.next_sample then
                trace.next_sample = timestamp + trace.interval
                trace.sequence = trace.sequence + 1
                local ok, value = pcall(function() return snapshot(vehicle(trace.vehicle_id), false) end)
                local sample = { sequence = trace.sequence, timestamp_ms = timestamp }
                if ok then
                    sample.vehicle = value
                    if value.towing_id ~= J.null then
                        local linked, other = pcall(function() return snapshot(vehicle(value.towing_id), false) end)
                        if linked then sample.trailer = other end
                    end
                    if trace.lastTowing ~= nil and trace.lastTowing ~= value.towing_id then sample.relationship_changed = true end
                    trace.lastTowing = value.towing_id
                else
                    sample.error = type(value) == 'table' and value or { code = 'SAMPLE_ERROR', message = clip(value) }
                end
                trace.samples[#trace.samples + 1] = sample
                if #trace.samples > 600 then table.remove(trace.samples, 1); trace.dropped = trace.dropped + 1 end
            end
        end
    end
end

handlers.run_test = function(args)
    local test = B.tests[args.name or 'bridge_self_test']
    if not test then fail('TEST_NOT_FOUND', 'Test is not registered') end
    local arguments = args.arguments
    if arguments == nil or arguments == J.null then arguments = {} end
    if type(arguments) ~= 'table' then fail('ARGUMENT', 'Test arguments must be an object') end
    return { name = args.name or 'bridge_self_test', timestamp_ms = now(), result = test.run(arguments) }
end

handlers.reload_mod_lua = function(args)
    local spec = B.modules[args.module]
    if not spec then fail('MODULE_NOT_ALLOWED', 'Module is not registered') end
    if not spec.enabled then fail('MODULE_DISABLED', 'Restart the game to restore a failed module') end
    -- B42 reloadServerLuaFile targets the server cache directory, not mod files.
    local loader = reloadLuaFile
    if type(loader) ~= 'function' then fail('UNSUPPORTED', 'Reload API unavailable') end
    spec.enabled = false
    local ok, why = pcall(spec.cleanup)
    if not ok then fail('CLEANUP_FAILED', clip(why)) end
    B.modules[args.module] = nil
    -- reloadLuaFile returns the script's value; nil alone does not indicate success.
    B.reloading = true
    ok, why = pcall(function() loader(spec.path) end)
    B.reloading = false
    local replacement = B.modules[args.module]
    if not ok or not replacement then
        if replacement then pcall(replacement.cleanup) end
        spec.enabled = false
        B.modules[args.module] = spec
        fail('RELOAD_FAILED', ok and 'Reloaded script did not register the module' or clip(why))
    end
    replacement.enabled = false
    ok, why = pcall(replacement.init)
    if not ok then
        pcall(replacement.cleanup)
        fail('INIT_FAILED', clip(why))
    end
    replacement.enabled = true
    B.recordEvent('reload', args.module)
    return { module = args.module, reloaded = true, path = replacement.path }
end

B.registerTest('bridge_self_test', function()
    local encoded = J.encode({ text = 'bridge', items = J.array({ 1, true, J.null }) })
    local decoded = J.decode(encoded)
    return { passed = decoded.text == 'bridge' and decoded.items[3] == J.null,
        debug_enabled = isDebug(), endpoint = B.endpoint, protocol = 1 }
end, 'JSON codec and Debug bridge health')

B.registerTest('vehicle_relationships', function(args)
    local v = vehicle(args.vehicle_id)
    local towing = v:getVehicleTowing()
    local towedBy = v:getVehicleTowedBy()
    return { passed = (not towing or towing:getVehicleTowedBy() == v) and (not towedBy or towedBy:getVehicleTowing() == v),
        vehicle = snapshot(v, false), has_relationship = towing ~= nil or towedBy ~= nil }
end, 'Check reciprocal towing relationships without changing vehicles')

local function respond(request, ok, value)
    local envelope = { protocol = 1, id = request.id, session = B.session, ok = ok, timestamp_ms = now() }
    if ok then envelope.result = value else envelope.error = value end
    local encoded, data = pcall(J.encode, envelope)
    if not encoded then
        envelope.ok = false; envelope.result = nil
        envelope.error = { code = 'SERIALIZATION', message = clip(data) }
        data = J.encode(envelope)
    end
    write('response.json', data)
    write('response.ready.txt', request.id)
end

local function poll()
    local text = read('request.json')
    if not text or text == B.lastText then return end
    local ok, request = pcall(J.decode, text)
    if not ok or type(request) ~= 'table' or type(request.id) ~= 'string' or not request.id:match('^[a-f0-9]+$') or #request.id ~= 32 then
        B.lastText = text; B.recordEvent('protocol_error', 'Malformed request'); return
    end
    B.lastText = text
    if request.id == B.lastId then return end
    if request.protocol ~= 1 or request.endpoint ~= B.endpoint or request.session ~= B.session then return end
    if type(request.sequence) ~= 'number' or request.sequence ~= math.floor(request.sequence)
        or request.sequence < 1 or request.sequence > 2147483647 then
        B.recordEvent('protocol_error', 'Invalid request sequence'); return
    end
    if request.sequence <= B.lastSequence then return end
    local claimText = read('claim.json')
    local claimOk, claim = false, nil
    if claimText and claimText ~= '' then claimOk, claim = pcall(J.decode, claimText) end
    if claimOk and type(claim) == 'table' and claim.session == B.session and claim.id == request.id then return end
    B.lastId, B.lastSequence = request.id, request.sequence
    if type(request.expires_ms) ~= 'number' or request.expires_ms < now() then
        respond(request, false, { code = 'EXPIRED', message = 'Request expired before execution' }); return
    end
    if request.operation ~= 'status' and not isDebug() then
        respond(request, false, { code = 'DEBUG_DISABLED', message = 'Start the game with -debug' }); return
    end
    local handler = handlers[request.operation]
    if not handler then respond(request, false, { code = 'UNKNOWN_OPERATION', message = 'Unsupported operation' }); return end
    if type(request.arguments) ~= 'table' or request.arguments == J.null then
        respond(request, false, { code = 'ARGUMENT', message = 'Arguments must be an object' }); return
    end
    -- Persist the claim before invoking anything with side effects.
    jsonWrite('claim.json', { id = request.id, session = B.session, sequence = request.sequence })
    ok, request.result = pcall(handler, request.arguments)
    if not ok then
        local why = request.result
        request.result = type(why) == 'table' and why or { code = 'GAME_ERROR', message = clip(why) }
        B.recordEvent('request_error', request.result.message or 'Unknown error')
    end
    respond(request, ok, request.result)
end

function B.start(endpoint)
    if B.started then
        Events.OnTick.Remove(B.tick)
        if B.pausedPolling then Events.OnTickEvenPaused.Remove(B.tick) end
    end
    B.traces, B.events, B.sequence = {}, {}, 0
    B.lastId, B.lastText, B.lastSequence = nil, nil, 0
    B.started, B.endpoint = true, endpoint
    B.path = 'PZDebugMCP/' .. endpoint .. '/'
    B.session = string.format('%.0f', now()) .. '-' .. tostring(ZombRand(1000000000))
    B.errorIndex, B.traceSequence, B.lastPoll, B.lastHeartbeat = 0, 0, 0, 0
    B.focusPause = Focus.apply(endpoint)
    Data.start(B, writeRaw)
    B.pausedPolling = Events.OnTickEvenPaused ~= nil
    local function tick()
        local timestamp = now()
        local ok, why = pcall(function()
            if timestamp - B.lastHeartbeat >= 1000 then
                B.lastHeartbeat = timestamp
                B.focusPause = Focus.apply(B.endpoint)
                jsonWrite('heartbeat.json', { protocol = 1, session = B.session, endpoint = B.endpoint,
                    timestamp_ms = timestamp, debug_enabled = isDebug(), version = '0.2.3',
                    focus_pause = B.focusPause, game_version = getCore():getVersionNumber() })
            end
            if timestamp - B.lastPoll >= 100 then
                B.lastPoll = timestamp
                poll()
                if isDebug() then collectErrors() end
            end
            if isDebug() then sampleTraces(timestamp) end
            Data.tick(timestamp, isDebug())
        end)
        if not ok and timestamp - (B.lastFailure or 0) >= 1000 then
            B.lastFailure = timestamp
            print('[PZDebugMCP] ' .. clip(why))
        end
    end
    B.tick = tick
    -- Both events may fire during play; timestamps prevent double polling/sampling.
    Events.OnTick.Add(tick)
    if B.pausedPolling then Events.OnTickEvenPaused.Add(tick) end
    B.recordEvent('bridge_started', endpoint)
    tick()
end

return B
