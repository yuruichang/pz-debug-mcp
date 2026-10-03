-- Test-only game surface, shared by Lua 5.1 and the shipped Kahlua runtime.
clock = 1700000000000
debugEnabled = true
serverMode = false
clientMode = false
files = {}
sources = {}
cache = {}
vehicles = {}
errorsList = {}
reloadMode = 'normal'
function getTimestampMs() return clock end
function getDebug() return debugEnabled end
function isServer() return serverMode end
function isClient() return clientMode end
function ZombRand(maximum) return 12345 end
pauseOnFocusloss = true
local core = {
    __debugNative = true,
    getVersionNumber = function() return '42.21.0-test' end,
    getOptionPauseOnFocusloss = function() return pauseOnFocusloss end,
    setOptionPauseOnFocusloss = function(self, value) pauseOnFocusloss = value end,
}
function getCore() return core end
local function event()
    local callbacks = {}
    return {
        callbacks = callbacks,
        Add = function(fn) callbacks[#callbacks + 1] = fn end,
        Remove = function(fn)
            for i = #callbacks, 1, -1 do if callbacks[i] == fn then table.remove(callbacks, i) end end
        end,
        fire = function() for _, fn in ipairs(callbacks) do fn() end end
    }
end
Events = { OnTick = event(), OnTickEvenPaused = event(), OnGameStart = event(), OnServerStarted = event() }
function getFileReader(path, create)
    local data = externalRead and externalRead(path) or files[path]
    if not data then return nil end
    return { readLine = function() return data:match('([^\r\n]+)') end, close = function() end }
end
function getFileWriter(path, create, append)
    local content = append and ((externalRead and externalRead(path)) or files[path] or '') or ''
    return { write = function(self, text) content = content .. text end,
        close = function()
            if failWrite == path then error('write failure') end
            if externalWrite then externalWrite(path, content) else files[path] = content end
        end }
end
function getLuaDebuggerErrors()
    return { size = function() return #errorsList end, get = function(self, index) return errorsList[index + 1] end }
end
function getVehicleById(id) return vehicles[id] end
function getCell()
    return { getVehicles = function()
        local list = {}
        for _, v in pairs(vehicles) do list[#list + 1] = v end
        return { size = function() return #list end, get = function(self, index) return list[index + 1] end }
    end }
end
local player = { __debugNative = true, health = 75 }
function player:getVehicle() return vehicles[1] end
function player:getHealth() return self.health end
function getPlayer() return player end
local climate = { __debugNative = true, temperature = 17.5 }
function climate:getTemperature() return self.temperature end
function getClimateManager() return climate end
SandboxVars = { DayLength = 3, ZombieLore = { Speed = 2 } }
function makeVehicle(id)
    local v = { __debugNative = true, id = id, x = 10, y = 20, z = 0, speed = 25 }
    function v:getId() return self.id end
    function v:getX() return self.x end
    function v:getY() return self.y end
    function v:getZ() return self.z end
    function v:getScriptName() return 'Base.CarNormal' end
    function v:getCurrentSpeedKmHour() return self.speed end
    function v:getSpeed2D() return self.speed end
    function v:isEngineRunning() return true end
    function v:getEngineState() return 'Running' end
    function v:getNetPlayerId() return 0 end
    function v:isRemovedFromWorld() return false end
    function v:getVehicleTowing() return self.towing end
    function v:getVehicleTowedBy() return self.towedBy end
    function v:getParts()
        return { getPartCount = function() return 1 end, getPartByIndex = function()
            return { getId = function() return 'Engine' end, getCondition = function() return 80 end, getContainerContentAmount = function() return 0 end }
        end }
    end
    function v:getPartCount() return self:getParts():getPartCount() end
    function v:getPartByIndex(index) return self:getParts():getPartByIndex(index) end
    vehicles[id] = v
    return v
end
local function keys(object, functions)
    local result = {}
    for key, value in pairs(object) do
        if key ~= '__debugNative' and (type(value) == 'function') == functions then result[#result + 1] = key end
    end
    table.sort(result)
    return result
end
function getNumClassFields(object) return #keys(object, false) end
function getClassField(object, index)
    local name = keys(object, false)[index + 1]
    return { getName = function() return name end }
end
function getClassFieldVal(object, field) return object[field:getName()] end
function getNumClassFunctions(object) return #keys(object, true) end
function getClassFunction(object, index)
    local name = keys(object, true)[index + 1]
    local counts = { getPartByIndex = 1, setOptionPauseOnFocusloss = 1 }
    return { getName = function() return name end, count = counts[name] or 0,
        getReturnType = function() return { getName = function() return name:match('^set') and 'void' or 'java.lang.Object' end } end }
end
function getMethodParameterCount(method) return method.count end
function getMethodParameter(method, index) return 'int' end
function instanceof(object, name) return name == 'List' and object.__debugList == true end
makeVehicle(1).towing = makeVehicle(2)
vehicles[2].towedBy = vehicles[1]
function require(name)
    if cache[name] then return cache[name] end
    local source = sources[name]
    assert(source, 'Missing module ' .. name)
    local chunk = assert(loadstring(source, name))
    local result = chunk()
    cache[name] = result
    return result
end
function reloadLuaFile(path)
    lastReloadApi = 'client'
    if reloadMode == 'throw' then error('reload failure') end
    if reloadMode == 'no_registration' then return nil end
    local name = path:gsub('^media/lua/shared/', ''):gsub('%.lua$', '')
    cache[name] = nil
    return require(name)
end
function reloadServerLuaFile(path)
    local result = reloadLuaFile(path)
    lastReloadApi = 'server'
    return result
end

function request(operation, arguments, expires, endpoint, session)
    local B = PZDebugMCP
    requestCounter = (requestCounter or 0) + 1
    local id = string.format('%032x', requestCounter)
    files[B.path .. 'request.json'] = B.Json.encode({ protocol = 1, id = id, sequence = requestCounter,
        session = session or B.session, endpoint = endpoint or B.endpoint,
        expires_ms = expires or clock + 10000, operation = operation, arguments = arguments or {} })
    clock = clock + 100
    B.tick()
    local response = files[B.path .. 'response.json']
    if response then return B.Json.decode(response) end
end
