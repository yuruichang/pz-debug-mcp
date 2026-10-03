local B = require 'PZDebugMCP/Bridge'
local previous = PZDebugMCPExample
local state = { ticks = previous and previous.ticks or 0 }
PZDebugMCPExample = state
local function tick() state.ticks = state.ticks + 1 end
local function cleanup()
    Events.OnTick.Remove(tick)
    B.tests.example_counter = nil
end
local function init()
    Events.OnTick.Remove(tick)
    Events.OnTick.Add(tick)
    B.registerTest('example_counter', function() return { ticks = state.ticks, passed = true } end,
        'Example reloadable module tick counter')
end
B.registerReloadable('example_counter', { path = 'media/lua/shared/PZDebugMCP/Example.lua', cleanup = cleanup, init = init })
-- During reload the bridge owns initialization after the file registers itself.
if not B.reloading then init() end
return state
