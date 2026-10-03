local F = {}
function F.apply(endpoint)
    if endpoint ~= 'client' then return { supported = false, reason = 'server_endpoint' } end
    local ok, result = pcall(function()
        local core = getCore()
        local previous = core:getOptionPauseOnFocusloss()
        if previous then core:setOptionPauseOnFocusloss(false) end
        return { supported = true, disabled = core:getOptionPauseOnFocusloss() == false, changed = previous == true }
    end)
    if ok then return result end
    return { supported = false, error = tostring(result):sub(1, 4096) }
end
return F
