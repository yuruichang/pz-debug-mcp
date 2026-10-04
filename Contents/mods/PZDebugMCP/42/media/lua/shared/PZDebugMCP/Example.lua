local B = require 'PZDebugMCP/Bridge'
local previous = PZDebugMCPExample
local state = { ticks = previous and previous.ticks or 0 }
PZDebugMCPExample = state
local function tick() state.ticks = state.ticks + 1 end
local function debugCalculation(arguments)
    local input = tonumber(arguments.input) or 5
    local doubled = input * 2
    local answer = doubled + 7
    return { input = input, doubled = doubled, answer = answer, passed = answer == input * 2 + 7 }
end
local function cleanup()
    Events.OnTick.Remove(tick)
    B.tests.example_counter = nil
    B.tests.example_debug = nil
end
local function init()
    Events.OnTick.Remove(tick)
    Events.OnTick.Add(tick)
    B.registerTest('example_counter', function() return { ticks = state.ticks, passed = true } end,
        'Example reloadable module tick counter')
    B.registerTest('example_debug', debugCalculation, 'Scalar calculation for breakpoint and step checks')
end
B.registerReloadable('example_counter', { path = 'media/lua/shared/PZDebugMCP/Example.lua', cleanup = cleanup, init = init })
-- During reload the bridge owns initialization after the file registers itself.
if not B.reloading then init() end
return state
