local B = require 'PZDebugMCP/Bridge'
require 'PZDebugMCP/Example'
Events.OnServerStarted.Add(function()
    if isServer() then B.start('server') end
end)
