-- Bounded read-only validation, loaded through the already registered example module.
local B = require 'PZDebugMCP/Bridge'
local P = require 'PZDebugMCP/ReadPolicy'
local J = B.Json
local Types = require 'PZDebugMCP/Types/Index'
local V = { roots = {}, nodes = {}, queue = {}, head = 0, tail = 0, results = {}, initialized = {}, byOwner = {}, byGlobal = {}, byStatic = {}, aliases = {}, seen = {}, calls = 0, fieldReads = 0, done = false }
local expectedReview = '__REVIEW_ID__'
local cachePath = 'PZDebugMCP/audit/'
local function readFile(name)
    local stream = getFileReader(cachePath .. name, false)
    if not stream then return nil end
    local text = stream:readLine(); stream:close()
    return text and J.decode(text)
end
local function blocked(value, hint)
    return value == nil or value == J.null or type(value) ~= 'userdata' or hint == nil or hint == 'java.lang.Object'
        or hint == 'java.lang.Class' or hint:find('ClassLoader', 1, true) or hint:find('MethodHandles', 1, true)
end
local function initializeClass(name)
    if not name or V.initialized[name] then return end
    V.initialized[name] = true
    local parent = V.superclasses and V.superclasses[name]
    if parent then initializeClass(parent) end
end
local function enqueue(job)
    V.tail = V.tail + 1; V.queue[V.tail] = job
end
local function register(value, hint, origin, depth)
    hint = type(hint) == 'string' and hint:gsub('<.*>', '') or nil
    if blocked(value, hint) or not Types[hint] or depth > 4 or #V.nodes >= 2500 then return end
    local key = P.identityKey(value)
    local bucket = V.seen[key] or {}
    for _, index in ipairs(bucket) do if rawequal(V.nodes[index].value, value) then return end end
    local n = getNumClassFunctions(value)
    local actual = n > 0 and tostring(getClassFunction(value, 0)):match('%s([%w_.$]+)%.[%w_]+%(') or hint
    if not actual or not Types[actual] then return end
    local index = #V.nodes + 1
    V.nodes[index] = { value = value, class = actual, origin = origin, depth = depth, fields = getNumClassFields(value) }
    bucket[#bucket + 1] = index; V.seen[key] = bucket
    initializeClass(actual)
    enqueue({ kind = 'fields', node = index, offset = 0 })
    enqueue({ kind = 'methods', node = index })
end
local function classContains(actual, wanted, visited)
    if actual == wanted then return true end
    visited = visited or {}
    if visited[actual] then return false end
    visited[actual] = true
    local entry = Types[actual]
    if entry then for _, parent in ipairs(entry.parents) do if classContains(parent, wanted, visited) then return true end end end
    return false
end
local function field(value, wanted)
    for i = 0, getNumClassFields(value) - 1 do
        local f = getClassField(value, i)
        if tostring(f):match('%.([%w_]+)$') == wanted then return getClassFieldVal(value, f) end
    end
end
local function bindings(callback)
    local caller = field(callback, 'caller')
    if caller == nil or caller == '<private>' then return nil end
    local method = field(caller, 'method')
    if method == nil or method == '<private>' then return nil end
    local text = tostring(method)
    local owner = text:match('%s([%w_.$]+)%.[%w_]+%(')
    local name = text:match('%.([%w_]+)%(')
    local parameters = text:match('%((.-)%)')
    return owner, name, parameters
end
local function arguments(spec, receiver)
    local out = {}
    for i, kind in ipairs(spec.parameters) do
        if kind == 'boolean' then out[i] = false
        elseif kind == 'int' or kind == 'short' or kind == 'long' or kind == 'byte' then out[i] = 0
        elseif kind == 'float' or kind == 'double' then out[i] = 0.0
        elseif kind == 'java.lang.String' then out[i] = ''
        elseif kind == 'char' then return nil, 'character_conversion_required'
        elseif kind:find('[]', 1, true) then return nil, 'array_argument_required'
        else
            local found
            for _, node in ipairs(V.nodes) do if classContains(node.class, kind) then found = node.value; break end end
            if not found then return nil, 'object_argument_required:' .. kind end
            out[i] = found
        end
    end
    return out
end
local function evaluate(spec, receiver)
    if V.results[spec.id] and V.results[spec.id].status == 'passed' then return end
    for _, name in ipairs(spec.required_initialized) do
        if not V.initialized[name] then V.results[spec.id] = { status = 'context_missing', reason = 'class_initialization_not_observed:' .. name }; return end
    end
    local callback
    if receiver then callback = P.member(receiver.value, spec.name)
    elseif spec.scope == 'global' then callback = rawget(_G, V.aliases[spec.name] or spec.name)
    else
        if not V.initialized[spec.owner] then V.results[spec.id] = {status='context_missing',reason='static_owner_initialization_not_observed'}; return end
        local simple = spec.owner:match('([^.]+)$')
        local outer = simple:match('^[^$]+')
        local table = rawget(_G, outer)
        for nested in simple:gmatch('%$([^$]+)') do table = type(table)=='table' and rawget(table,nested) or nil end
        callback = type(table)=='table' and rawget(table,spec.name) or nil
    end
    if not callback or not P.nativeCallable(callback) then V.results[spec.id] = { status = 'unavailable', reason = 'not_exposed_or_lua_override' }; return end
    local owner, name, parameters = bindings(callback)
    if owner ~= spec.owner or name ~= spec.name or parameters ~= table.concat(spec.parameters, ',') then
        V.results[spec.id] = { status = 'binding_unverified', reason = 'native_binding_not_exact' }; return
    end
    local args, why = arguments(spec, receiver)
    if not args then V.results[spec.id] = { status = 'context_missing', reason = why }; return end
    local ok, value
    if receiver then ok, value = pcall(callback, receiver.value, unpack(args, 1, #spec.parameters))
    else ok, value = pcall(callback, unpack(args, 1, #spec.parameters)) end
    V.calls = V.calls + 1
    if not ok then
        V.results[spec.id] = { status = 'failed', reason = 'native_call_failed' }
        V.stopped = true
        return
    end
    V.results[spec.id] = { status = 'passed', value_type = type(value), nullable_result = value == nil }
    if value ~= nil then register(value, spec.returns, spec.id, receiver and receiver.depth + 1 or 0) end
end
function V.step()
    if V.done or V.stopped then return V.status() end
    local began, processed = getTimestampMs(), 0
    while V.head < V.tail and processed < 8 and getTimestampMs() - began < 4 do
        V.head = V.head + 1
        local job = V.queue[V.head]; V.queue[V.head] = nil
        if job.kind == 'global' or job.kind == 'static' then evaluate(job.spec, nil)
        elseif job.kind == 'fields' then
            local node = V.nodes[job.node]
            for i = job.offset, math.min(node.fields - 1, job.offset + 7) do
                local f = getClassField(node.value, i)
                local value = getClassFieldVal(node.value, f)
                local hint = tostring(f):match('(%S+)%s+[^%s]+$')
                V.fieldReads = V.fieldReads + 1
                register(value, hint, node.origin .. ':field', node.depth + 1)
            end
            if job.offset + 8 < node.fields then enqueue({kind='fields',node=job.node,offset=job.offset+8}) end
        elseif job.kind == 'methods' then
            local node = V.nodes[job.node]
            if not V.transformed[node.class] then
                local visited, unique = {}, {}
                local function add(name)
                    if visited[name] then return end; visited[name] = true
                    local t = Types[name]
                    if t then
                        local counts = {}
                        for _, method in ipairs(t.methods) do
                            local key = method.name .. ':' .. tostring(#method.parameters)
                            counts[key] = (counts[key] or 0) + 1
                        end
                        for _, method in ipairs(t.methods) do
                            local key = method.name .. ':' .. tostring(#method.parameters)
                            if not unique[key] then
                                unique[key] = true
                                if counts[key] == 1 then
                                    for _, spec in ipairs(V.byOwner[name] or {}) do
                                        if spec.name == method.name and #spec.parameters == #method.parameters then
                                            enqueue({kind='invoke',node=job.node,spec=spec}); break
                                        end
                                    end
                                end
                            end
                        end
                    end
                    if t then for _, parent in ipairs(t.parents) do add(parent) end end
                end
                add(node.class)
            end
        elseif job.kind == 'invoke' then evaluate(job.spec, V.nodes[job.node]) end
        processed = processed + 1
        if V.stopped then break end
    end
    if V.head == V.tail then
        V.passes = V.passes + 1
        local previous = V.progressMarker or -1
        local marker = V.calls + #V.nodes
        if previous == marker or V.passes >= 3 then V.done = true
        else
            V.progressMarker = marker
            for _, spec in ipairs(V.byGlobal) do if not V.results[spec.id] or V.results[spec.id].status ~= 'passed' then enqueue({kind='global',spec=spec}) end end
            for _, spec in ipairs(V.byStatic) do if not V.results[spec.id] or V.results[spec.id].status ~= 'passed' then enqueue({kind='static',spec=spec}) end end
        end
    end
    return V.status()
end
function V.status()
    local counts = {}
    for _, row in pairs(V.results) do counts[row.status] = (counts[row.status] or 0) + 1 end
    return { done = V.done, stopped = V.stopped == true, calls = V.calls, nodes = #V.nodes, field_reads = V.fieldReads,
        queued = V.tail - V.head, passes = V.passes, counts = counts, review_id = V.reviewId }
end
function V.resultsPage(args)
    local out, first = J.array(), args.after or 0
    for i = first + 1, math.min(#V.inventory, first + (args.limit or 100)) do
        local id = V.inventory[i]
        out[#out + 1] = { id = id, validation = V.results[id] or {status='context_missing',reason='receiver_not_present'} }
    end
    return { items = out, cursor = first + #out, total = #V.inventory, has_more = first + #out < #V.inventory }
end
function V.begin()
    if V.reviewId then return V.status() end
    local manifest = readFile('manifest.json')
    if not manifest or manifest.schema ~= 1 or manifest.review_id ~= expectedReview then return { error='audit_manifest_missing_or_mismatched' } end
    V.reviewId, V.transformed, V.aliases, V.superclasses, V.inventory, V.passes = manifest.review_id, manifest.transformed, manifest.aliases, manifest.superclasses, {}, 0
    for _, name in ipairs(manifest.initialized_startup) do initializeClass(name) end
    for _, chunk in ipairs(manifest.chunks) do
        local data = readFile(chunk)
        for _, spec in ipairs(data.approved) do
            V.inventory[#V.inventory + 1] = spec.id
            if spec.scope == 'global' then V.byGlobal[#V.byGlobal + 1] = spec; enqueue({kind='global',spec=spec})
            elseif not spec.static then
                V.byOwner[spec.owner] = V.byOwner[spec.owner] or {}; table.insert(V.byOwner[spec.owner], spec)
            else V.byStatic[#V.byStatic+1]=spec; enqueue({kind='static',spec=spec}) end
        end
    end
    for _, root in ipairs({'getCore','getWorld','getCell','getPlayer','getGameTime','getClimateManager'}) do
        local result = B.Data.query({target=root})
        local h = result.data.value
        if type(h)=='table' and h.handle then
            local entry=B.Data.state.handles[h.handle]
            if entry then register(entry.value,entry.class,root,0) end
        end
    end
    return V.status()
end
return V
