local J = require 'PZDebugMCP/Json'
local Catalog = require 'PZDebugMCP/Catalog'
local Types = require 'PZDebugMCP/Types/Index'
local Policy = require 'PZDebugMCP/ReadPolicy'
local D = { catalog = Catalog }
local globalByName = {}
for _, entry in ipairs(Catalog.globals) do
    globalByName[entry.name] = globalByName[entry.name] or {}
    table.insert(globalByName[entry.name], entry)
end
local function reject(code, message) error({ code = code, message = message }) end
local function number(value, default, low, high)
    if value == nil or value == J.null then value = default end
    if type(value) ~= 'number' or value ~= math.floor(value) or value < low or value > high then reject('ARGUMENT', 'Integer out of range') end
    return value
end
local function errorValue(why)
    if type(why) == 'table' and why.code then return why end
    return { code = 'READ_FAILED', message = tostring(why):sub(1, 1024) }
end
local function safe(fn)
    local ok, value = pcall(fn)
    if ok then return value end
    return nil, errorValue(value)
end

local function enqueue(job)
    local s = D.state
    if s.queueTail - s.queueHead >= s.config.max_jobs then s.queueDropped = s.queueDropped + 1; return end
    s.queueTail = s.queueTail + 1
    s.queue[s.queueTail] = job
end

local function descriptor(value, origin, depth, hint)
    local s = D.state
    if value == nil or value == J.null then return J.null end
    local kind = type(value)
    if kind == 'number' or kind == 'boolean' then return value end
    if kind == 'string' then
        if #value > 4096 then return { kind = 'string', value = value:sub(1, 4096), truncated = true, length = #value } end
        return value
    end
    local existing = s.reverse[value]
    if existing and s.handles[existing] then return { kind = kind, handle = existing } end
    s.handleSequence = s.handleSequence + 1
    local id = s.session .. ':h' .. tostring(s.handleSequence)
    local slot = (s.handleSequence - 1) % s.config.max_handles + 1
    local old = s.handleSlots[slot]
    if old and s.handles[old] then
        s.reverse[s.handles[old].value] = nil
        s.handles[old] = nil
        s.handlesEvicted = s.handlesEvicted + 1
    end
    s.handleSlots[slot] = id
    local className = type(hint) == 'string' and hint:gsub('<.*>', '') or nil
    if className == 'java.lang.Class' or className == 'java.lang.ClassLoader' or className == 'java.lang.invoke.MethodHandles$Lookup' then
        return { kind = 'restricted', class = className, reason = 'official_debug_reflection_restriction' }
    end
    if kind == 'userdata' and className and Types[className] and className ~= 'java.lang.Object' then
        local count = getNumClassFunctions(value)
        if count > 0 then
            className = tostring(getClassFunction(value, 0)):match('%s([%w_.$]+)%.[%w_]+%(') or className
        end
    end
    local entry = { value = value, origin = origin, depth = depth or 0,
        class = className }
    s.handles[id], s.reverse[value] = entry, id
    if entry.depth <= s.config.max_depth and kind == 'table' and rawget(value, '__debugNative') ~= true and origin ~= 'root:_G' then
        enqueue({ handle = id, offset = 0, automatic = true })
    else s.depthLimited = s.depthLimited + 1 end
    return { kind = kind, handle = id, class = entry.class }
end

local function handle(id)
    local entry = D.state.handles[id]
    if not entry then reject('HANDLE_EXPIRED', 'Object handle belongs to another session or has been evicted') end
    return entry
end

local function appendRecord(kind, target, data)
    local s = D.state
    local sequence = s.sequence + 1
    local entry = { session = s.session, sequence = sequence, timestamp_ms = getTimestampMs(), kind = kind, target = target, data = data }
    local encoded = J.encode(entry)
    if #encoded > 65536 then reject('RECORD_TOO_LARGE', 'Record exceeds 64K string units') end
    local bytes = #encoded * 3 + 1 -- Conservative upper bound for Kahlua UTF-8 output.
    local segment = s.segments[s.slot]
    if not segment or segment.count >= 128 or segment.bytes + bytes > 262144 then
        if segment then s.slot = s.slot % 16 + 1 end
        segment = { slot = s.slot, first = sequence, last = sequence, count = 0, bytes = 0 }
        -- Truncate the old slot before publishing its replacement in the index.
        D.write('records/segment-' .. string.format('%02d', s.slot) .. '.log', '', false)
        s.segments[s.slot] = segment
    end
    D.write('records/segment-' .. string.format('%02d', s.slot) .. '.log', encoded .. '\n', true)
    segment.count, segment.bytes, segment.last = segment.count + 1, segment.bytes + bytes, sequence
    s.sequence = sequence
    s.recent[#s.recent + 1] = entry
    if #s.recent > 256 then table.remove(s.recent, 1) end
    s.indexDirty = true
    return sequence
end

local function callArguments(arguments)
    if arguments == nil or arguments == J.null then arguments = J.array() end
    if type(arguments) ~= 'table' or #arguments > 16 then reject('ARGUMENT', 'Expected up to 16 arguments') end
    local values = {}
    for i = 1, #arguments do
        local value = arguments[i]
        if value == J.null then value = nil
        elseif type(value) == 'table' then
            if type(value.handle) ~= 'string' then reject('ARGUMENT', 'Object arguments require a handle') end
            value = handle(value.handle).value
        elseif type(value) ~= 'number' and type(value) ~= 'string' and type(value) ~= 'boolean' then reject('ARGUMENT', 'Unsupported argument') end
        values[i] = value
    end
    return values, #arguments
end

local function globalCall(name, args)
    local entries = globalByName[name]
    if not entries then reject('API_NOT_FOUND', 'Not an official global API: ' .. tostring(name)) end
    local values, count = callArguments(args)
    local permitted = false
    local selected
    for _, entry in ipairs(entries) do
        if entry.readable and Policy.globalAllowed(name, entry.parameters) and #entry.parameters == count then permitted = true; selected = entry end
    end
    if not permitted then reject('NOT_A_READER', 'API is not a reader or argument count is incorrect') end
    if type(_G[name]) ~= 'function' then reject('API_UNAVAILABLE', 'API is not exposed in this execution context') end
    if not Policy.nativeCallable(_G[name]) then reject('UNREVIEWED_CALLABLE', 'Global API was replaced by an unreviewed Lua function') end
    return _G[name](unpack(values, 1, count)), selected.returns
end

local function native(value)
    return type(value) == 'userdata' or (type(value) == 'table' and rawget(value, '__debugNative') == true)
end

local function methodInfo(value, index)
    local method = getClassFunction(value, index)
    local text = tostring(method)
    local nameReader = Policy.member(method, 'getName')
    local name = nameReader and nameReader(method) or text:match('%.([%w_]+)%(')
    if not name then reject('METADATA_UNAVAILABLE', 'Method name unavailable') end
    local count = getMethodParameterCount(method)
    local args = J.array()
    for i = 0, count - 1 do args[#args + 1] = getMethodParameter(method, i) end
    local returns = text:match('(%S+)%s+[^%s%(]+%(') or 'unknown'
    local owner = text:match('%s([%w_.$]+)%.[%w_]+%(')
    return { kind = 'method', name = name, parameters = args, returns = returns,
        declaring_class = owner, readable = Policy.methodAllowed(owner, name, args), available = Policy.member(value, name) ~= nil }
end

local function fieldName(field, index)
    local reader = Policy.member(field, 'getName')
    return reader and reader(field) or tostring(field):match('%.([%w_]+)$') or ('field_' .. tostring(index))
end

local function objectLayout(entry)
    local value = entry.value
    if entry.layout then return entry.layout end
    local layout = { fields = 0, methods = 0 }
    if native(value) then
        if type(value) == 'userdata' and (not entry.class or entry.class == 'java.lang.Object') then
            layout.error = { code = 'UNVERIFIED_OBJECT', message = 'Object has no verified type metadata' }
            entry.layout = layout
            return layout
        end
        local fieldCount, fieldError = safe(function() return getNumClassFields(value) end)
        local methodCount, methodError = safe(function() return getNumClassFunctions(value) end)
        layout.fields, layout.methods = fieldCount or 0, methodCount or 0
        layout.error = fieldError or methodError
        local seen, signatures, public = {}, {}, J.array()
        local function addClass(name)
            if not name or seen[name] then return end
            seen[name] = true
            local class = Types[name]
            if not class then return end
            for _, method in ipairs(class.methods) do
                local key = method.name .. '(' .. table.concat(method.parameters, ',') .. ')'
                if not signatures[key] then
                    signatures[key] = true
                    public[#public + 1] = { kind = 'method', name = method.name, returns = method.returns,
                        parameters = J.array(method.parameters), readable = Policy.methodAllowed(name, method.name, method.parameters),
                        available = Policy.member(value, method.name) ~= nil, declaring_class = name }
                end
            end
            for _, parent in ipairs(class.parents) do addClass(parent) end
        end
        addClass(entry.class)
        if #public > 0 then layout.methodEntries, layout.methods = public, #public end
        local list = safe(function() return instanceof(value, 'List') end)
        if list then layout.collection = 'list' end
    elseif type(value) == 'table' then
        layout.keys = {}
        for key in pairs(value) do
            local own = value == _G and type(key) == 'string' and key:match('^PZDebugMCP')
            if not own and (type(key) == 'string' or type(key) == 'number' or type(key) == 'boolean') then table.insert(layout.keys, key) end
        end
        table.sort(layout.keys, function(a, b) return type(a) .. tostring(a) < type(b) .. tostring(b) end)
    else layout.error = { code = 'NOT_INSPECTABLE', message = 'Only objects and tables can be inspected' } end
    entry.layout = layout
    return layout
end

local function inspect(id, offset, limit, values)
    local entry = handle(id)
    local value, layout = entry.value, objectLayout(entry)
    local result = { handle = id, origin = entry.origin, offset = offset, items = J.array() }
    if layout.error then result.reflection_error = layout.error end
    local total = layout.keys and #layout.keys or layout.fields + layout.methods
    if layout.collection == 'list' then total = value:size() end
    result.total = total
    for i = offset, math.min(total, offset + limit) - 1 do
        local ok, item = pcall(function()
            if layout.collection == 'list' then
                return { kind = 'element', index = i, value = descriptor(value:get(i), entry.origin .. '[' .. tostring(i) .. ']', entry.depth + 1) }
            end
            if layout.keys then
                local key = layout.keys[i + 1]
                return { kind = 'table_entry', key = key, value = descriptor(rawget(value, key), entry.origin .. '.' .. tostring(key), entry.depth + 1) }
            end
            if i < layout.fields then
                local field = getClassField(value, i)
                local name = fieldName(field, i)
                local raw = getClassFieldVal(value, field)
                return { kind = 'field', name = name, field_index = i, accessible = raw ~= '<private>',
                    value = descriptor(raw, entry.origin .. '.' .. name, entry.depth + 1) }
            end
            local original = layout.methodEntries and layout.methodEntries[i - layout.fields + 1]
            local info = {}
            if original then for key, child in pairs(original) do info[key] = child end
            else info = methodInfo(value, i - layout.fields) end
            if values and info.readable and info.available and #info.parameters == 0 then
                local got, why = safe(function() return Policy.member(value, info.name)(value) end)
                if why then info.error = why
                else info.value = descriptor(got, entry.origin .. ':' .. info.name, entry.depth + 1, info.returns) end
            end
            return info
        end)
        result.items[#result.items + 1] = ok and item or { index = i, error = errorValue(item) }
    end
    result.next_offset = offset + #result.items
    result.has_more = result.next_offset < total
    result.depth_limited = entry.depth > D.state.config.max_depth
    return result
end

function D.list(args)
    local offset, limit = number(args.offset, 0, 0, 1000000), number(args.limit, 50, 1, 100)
    if args.scope == 'object' then return inspect(args.handle, offset, limit, false) end
    local entries = J.array()
    for _, original in ipairs(Catalog.globals) do
        if (not args.category or args.category == J.null or args.category == original.category)
            and (not args.readers_only or original.readable) then
            local item = { name = original.name, returns = original.returns, readable = original.readable,
                policy = original.policy, category = original.category, parameters = J.array(),
                available = type(_G[original.name]) == 'function' }
            for _, parameter in ipairs(original.parameters) do table.insert(item.parameters, parameter) end
            item.collection = original.readable and #original.parameters == 0 and 'automatic' or 'requires_arguments'
            if not original.readable then item.collection = 'not_a_reader' end
            entries[#entries + 1] = item
        end
    end
    local page = J.array()
    for i = offset + 1, math.min(#entries, offset + limit) do page[#page + 1] = entries[i] end
    return { scope = 'globals', total = #entries, items = page, next_offset = offset + #page,
        has_more = offset + #page < #entries, target_build = Catalog.target_build,
        table_roots = J.array({ '_G', 'SandboxVars', 'SandboxOptions', 'ModData' }) }
end

function D.query(args)
    local action, target = args.action or 'call', args.target
    if type(target) ~= 'string' then reject('ARGUMENT', 'A global name or object handle is required') end
    local entry = D.state.handles[target]
    local data
    if action == 'inspect' then
        data = inspect(target, number(args.offset, 0, 0, 1000000), number(args.limit, 32, 1, 100), true)
    elseif action == 'table' then
        local object = handle(target).value
        if type(object) ~= 'table' or native(object) then reject('ARGUMENT', 'Target is not a Lua table') end
        local key = args.member
        if type(key) ~= 'string' and type(key) ~= 'number' and type(key) ~= 'boolean' then reject('ARGUMENT', 'Table key must be scalar') end
        data = { key = key, value = descriptor(rawget(object, key), entry.origin .. '.' .. tostring(key), entry.depth + 1) }
    elseif action == 'field' then
        local object = handle(target).value
        local index = args.field_index
        if index == nil or index == J.null then
            for i = 0, getNumClassFields(object) - 1 do
                if fieldName(getClassField(object, i), i) == args.member then index = i; break end
            end
        end
        if index == nil then reject('FIELD_NOT_FOUND', 'Field is not visible to the official debugger') end
        index = number(index, nil, 0, getNumClassFields(object) - 1)
        local field = getClassField(object, index)
        local value = getClassFieldVal(object, field)
        local name = fieldName(field, index)
        data = { name = name, field_index = index, accessible = value ~= '<private>', value = descriptor(value, entry.origin .. '.' .. name, entry.depth + 1) }
    elseif action == 'call' then
        local value
        if entry then
            if not native(entry.value) then reject('NOT_A_READER', 'Lua functions and table functions are not executed') end
            local arguments, count = callArguments(args.arguments)
            local layout = objectLayout(entry)
            local selected
            for i = 1, layout.methods do
                local method = layout.methodEntries and layout.methodEntries[i] or methodInfo(entry.value, i - 1)
                if method.name == args.member and #method.parameters == count and method.readable then selected = method; break end
            end
            if not selected then reject('NOT_A_READER', 'Method signature is not a public reader with this argument count') end
            local callback = Policy.member(entry.value, args.member)
            if not callback then reject('API_UNAVAILABLE', 'Method is not exposed') end
            value = callback(entry.value, unpack(arguments, 1, count))
            data = { value = descriptor(value, entry.origin .. ':' .. args.member, entry.depth + 1, selected.returns) }
        elseif target:sub(1, 5) == 'root:' then
            local key = target:sub(6)
            if key ~= '_G' and key ~= 'SandboxVars' and key ~= 'SandboxOptions' and key ~= 'ModData' then reject('API_NOT_FOUND', 'Unknown table root') end
            value = _G[key]
        else
            local hint
            value, hint = globalCall(target, args.arguments)
            data = { value = descriptor(value, target, 0, hint) }
        end
        if not data then data = { value = descriptor(value, target, 0) } end
    else reject('ARGUMENT', 'Action must be call/inspect/field/table') end
    local sequence = appendRecord('query', target, data)
    D.flush()
    return { session = D.state.session, record_sequence = sequence, data = data }
end

function D.status()
    local s = D.state
    local watched = 0
    for _ in pairs(s.watches) do watched = watched + 1 end
    return { enabled = s.config.enabled, sequence = s.sequence, automatic_roots = #s.roots,
        global_api_signatures = #Catalog.globals, catalog_types = Catalog.type_count, watched_queries = watched,
        root_passes = s.rootPasses, roots_visited = s.rootsVisited, pending_jobs = s.queueTail - s.queueHead,
        handles_created = s.handleSequence, handles_evicted = s.handlesEvicted,
        queue_dropped = s.queueDropped, depth_limited = s.depthLimited, config = s.config,
        coverage = 'reviewed_readers_only', read_policy = 'reviewed_allowlist_v1', automatic_object_graph = false, requires_arguments_are_on_demand = true,
        records_directory = 'Lua/PZDebugMCP/' .. D.endpoint .. '/records',
        last_error = s.lastError or J.null }
end

function D.watch(args)
    local s, action = D.state, args.action or 'add'
    if action == 'remove' then
        local existed = s.watches[args.watch_id] ~= nil
        s.watches[args.watch_id] = nil
        return { removed = existed }
    end
    if action == 'list' then
        local out = J.array()
        for id, watch in pairs(s.watches) do
            out[#out + 1] = { watch_id = id, query = watch.query, interval_ms = watch.interval,
                last_record = watch.lastRecord, last_error = watch.error or J.null }
        end
        return { watches = out }
    end
    if action ~= 'add' or type(args.query) ~= 'table' or args.query == J.null then reject('ARGUMENT', 'A query is required') end
    if D.status().watched_queries >= 128 then reject('WATCH_LIMIT', 'At most 128 watched queries') end
    local interval = number(args.interval_ms, 1000, 100, 60000)
    local result = D.query(args.query)
    s.watchSequence = s.watchSequence + 1
    local id = s.session .. ':w' .. tostring(s.watchSequence)
    s.watches[id] = { query = args.query, interval = interval, next = getTimestampMs() + interval, lastRecord = result.record_sequence }
    return { watch_id = id, interval_ms = interval, initial = result }
end

function D.configure(args)
    local s = D.state
    for key, bounds in pairs({ interval_ms = {50, 5000}, jobs_per_tick = {1, 32}, budget_ms = {1, 20},
        max_depth = {0, 16}, max_handles = {128, 16384}, max_jobs = {128, 32768}, refresh_seconds = {1, 300} }) do
        if args[key] ~= nil and args[key] ~= J.null then
            local value = number(args[key], nil, bounds[1], bounds[2])
            if key == 'max_handles' and value ~= s.config[key] then
                s.handles, s.reverse, s.handleSlots = {}, {}, {}
                s.queue, s.queueHead, s.queueTail = {}, 0, 0
            end
            s.config[key] = value
        end
    end
    if args.enabled ~= nil and args.enabled ~= J.null then s.config.enabled = args.enabled == true end
    s.indexDirty = true
    return D.status()
end

function D.flush()
    local s = D.state
    local segments, first = J.array(), s.sequence + 1
    for _, segment in pairs(s.segments) do
        segments[#segments + 1] = segment
        first = math.min(first, segment.first)
    end
    D.write('records/index.json', J.encode({ schema = 1, session = s.session, first = first, last = s.sequence,
        segments = segments, timestamp_ms = getTimestampMs(), recorder = D.status() }) .. '\n', false)
    s.indexDirty = false
end

function D.start(bridge, writer)
    D.endpoint, D.write = bridge.endpoint, writer
    local roots, seen = {}, {}
    local priority = { 'getPlayer', 'getCell', 'getWorld', 'getClimateManager', 'getGameTime', 'getCore' }
    for _, name in ipairs(priority) do
        if globalByName[name] then roots[#roots + 1] = name; seen[name] = true end
    end
    for _, entry in ipairs(Catalog.globals) do
        if entry.readable and #entry.parameters == 0 and not seen[entry.name] then
            roots[#roots + 1] = entry.name; seen[entry.name] = true
        end
    end
    roots[#roots + 1] = 'root:SandboxVars'
    D.state = { session = bridge.session, sequence = 0, slot = 1, segments = {}, recent = {}, handles = {},
        reverse = {}, handleSlots = {}, handleSequence = 0, handlesEvicted = 0, queue = {}, queueHead = 0, queueTail = 0,
        queueDropped = 0, depthLimited = 0, roots = roots, rootIndex = 1, rootPasses = 0, rootsVisited = 0,
        lastTick = 0, lastFlush = 0, refreshAt = 0, turn = 0, unavailable = {}, watches = {}, watchSequence = 0,
        config = { enabled = true, interval_ms = 100, jobs_per_tick = 4, budget_ms = 2,
            max_depth = 4, max_handles = 4096, max_jobs = 8192, refresh_seconds = 10 } }
    D.flush()
end

local function collectRoot(name)
    local s = D.state
    if s.unavailable[name] and type(_G[name]) ~= 'function' then return end
    if name:sub(1, 5) ~= 'root:' then
        if type(_G[name]) ~= 'function' then
            s.unavailable[name] = true
            appendRecord('global', name, { error = { code = 'API_UNAVAILABLE', message = 'API is not exposed' } })
            return
        end
        if not Policy.nativeCallable(_G[name]) then
            appendRecord('global', name, { error = { code = 'UNREVIEWED_CALLABLE', message = 'Lua override not executed' } })
            return
        end
    end
    local ok, value, hint = pcall(function()
        if name:sub(1, 5) == 'root:' then return _G[name:sub(6)] end
        return globalCall(name, J.array())
    end)
    if not ok and type(value) == 'table' and value.code == 'API_UNAVAILABLE' then s.unavailable[name] = true end
    local data = ok and { value = descriptor(value, name, 0, hint) } or { error = errorValue(value) }
    if ok and value ~= nil then
        local methods = Policy.automaticMethods[name]
        if methods then
            data.properties = {}
            for _, member in ipairs(methods) do
                local entry = type(data.value) == 'table' and data.value.handle and s.handles[data.value.handle]
                if entry then
                    local layout = objectLayout(entry)
                    for i = 1, layout.methods do
                        local method = layout.methodEntries and layout.methodEntries[i] or methodInfo(value, i - 1)
                        if method.name == member and method.readable and method.available and #method.parameters == 0 then
                            data.properties[member] = Policy.member(value, member)(value)
                            break
                        end
                    end
                end
            end
        end
    end
    appendRecord('global', name, data)
end

function D.tick(timestamp, debugEnabled)
    local s = D.state
    if not debugEnabled or not s.config.enabled then return end
    if timestamp - s.lastTick < s.config.interval_ms then return end
    s.lastTick = timestamp
    local started = getTimestampMs()
    local ok, why = pcall(function()
        for i = 1, s.config.jobs_per_tick do
            if i > 1 and getTimestampMs() - started >= s.config.budget_ms then break end
            s.turn = s.turn + 1
            local due
            if s.turn % 3 == 0 then
                for _, watch in pairs(s.watches) do
                    if watch.next <= timestamp and (not due or watch.next < due.next) then due = watch end
                end
            end
            if due then
                due.next = timestamp + due.interval
                local captured, result = pcall(D.query, due.query)
                if captured then due.lastRecord = result.record_sequence; due.error = nil
                else due.error = errorValue(result) end
            elseif s.turn % 2 == 1 or s.queueHead == s.queueTail then
                local name = s.roots[s.rootIndex]
                if name then collectRoot(name); s.rootsVisited = s.rootsVisited + 1 end
                s.rootIndex = s.rootIndex + 1
                if s.rootIndex > #s.roots then s.rootIndex = 1; s.rootPasses = s.rootPasses + 1 end
            else
                s.queueHead = s.queueHead + 1
                local job = s.queue[s.queueHead]
                s.queue[s.queueHead] = nil
                local entry = s.handles[job.handle]
                if entry then
                    local data = inspect(job.handle, job.offset, 16, true)
                    appendRecord('object', entry.origin, data)
                    if data.has_more then enqueue({ handle = job.handle, offset = data.next_offset, automatic = true }) end
                end
            end
        end
        if s.queueHead == s.queueTail and timestamp >= s.refreshAt then
            s.queue, s.queueHead, s.queueTail = {}, 0, 0
            s.refreshAt = timestamp + s.config.refresh_seconds * 1000
            for id, entry in pairs(s.handles) do
                if entry.depth <= s.config.max_depth and type(entry.value) == 'table' and not native(entry.value) and entry.origin ~= 'root:_G' then
                    entry.layout = nil
                    enqueue({ handle = id, offset = 0, automatic = true })
                end
            end
        end
        if s.indexDirty and timestamp - s.lastFlush >= 1000 then D.flush(); s.lastFlush = timestamp end
    end)
    if not ok then s.lastError = errorValue(why) end
end

return D
