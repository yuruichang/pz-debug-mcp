local P = {}
P.globals = { getCore = { [''] = true }, getPlayer = { [''] = true }, getCell = { [''] = true },
    getWorld = { [''] = true }, getClimateManager = { [''] = true }, getGameTime = { [''] = true },
    getDebug = { [''] = true }, getTimestampMs = { [''] = true }, getLuaDebuggerErrors = { [''] = true },
    getNumClassFields = { ['Object'] = true }, getClassField = { ['Object,int'] = true },
    getClassFieldVal = { ['Object,Field'] = true }, getNumClassFunctions = { ['Object'] = true },
    getClassFunction = { ['Object,int'] = true }, getMethodParameterCount = { ['Method'] = true }, getMethodParameter = { ['Method,int'] = true } }
P.methods = {
    ['zombie.characters.IsoGameCharacter'] = { getHealth = { [''] = true } },
    ['zombie.iso.IsoMovingObject'] = { getX = { [''] = true }, getY = { [''] = true }, getZ = { [''] = true } },
    ['zombie.iso.weather.ClimateManager'] = { getTemperature = { [''] = true } },
    ['zombie.GameTime'] = { getTimeOfDay = { [''] = true }, getYear = { [''] = true }, getMonth = { [''] = true }, getDay = { [''] = true } },
    ['zombie.core.Core'] = { getVersionNumber = { [''] = true }, getOptionPauseOnFocusloss = { [''] = true } }
}
P.automaticMethods = { getPlayer = { 'getHealth', 'getX', 'getY', 'getZ' }, getClimateManager = { 'getTemperature' },
    getGameTime = { 'getTimeOfDay', 'getYear', 'getMonth', 'getDay' }, getCore = { 'getVersionNumber', 'getOptionPauseOnFocusloss' } }
local function signature(parameters)
    local out = {}
    for _, value in ipairs(parameters or {}) do out[#out + 1] = value:gsub('.*%.', '') end
    return table.concat(out, ',')
end
function P.globalAllowed(name, parameters)
    return P.globals[name] ~= nil and P.globals[name][signature(parameters)] == true
end
function P.methodAllowed(owner, name, parameters)
    local methods = P.methods[owner]
    return methods ~= nil and methods[name] ~= nil and methods[name][signature(parameters)] == true
end
function P.nativeCallable(value)
    return type(value) == 'function' and not instanceof(value, 'LuaClosure')
end
-- Only inspect raw exposure tables; a failed userdata index can open the Debug UI.
function P.member(value, name)
    if type(value) == 'table' then return rawget(value, name) end
    if type(value) ~= 'userdata' then return nil end
    local meta = getmetatable(value)
    for _ = 1, 16 do
        if type(meta) ~= 'table' then return nil end
        local index = rawget(meta, '__index')
        if type(index) ~= 'table' then return nil end
        local found = rawget(index, name)
        if found ~= nil then return found end
        meta = getmetatable(index)
    end
    return nil
end
return P
