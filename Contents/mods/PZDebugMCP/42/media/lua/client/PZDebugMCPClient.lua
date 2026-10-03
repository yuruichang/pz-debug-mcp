local B = require 'PZDebugMCP/Bridge'
require 'PZDebugMCP/Example'
Events.OnGameStart.Add(function() B.start('client') end)
