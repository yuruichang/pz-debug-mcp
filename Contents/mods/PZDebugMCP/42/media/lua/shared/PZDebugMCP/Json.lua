local J = {}
local charOk, wideChar = pcall(string.char, 256)
local utf16Strings = charOk and string.byte(wideChar) == 256
J.null = {}
local arrayMeta = { __jsonArray = true }
function J.array(value) return setmetatable(value or {}, arrayMeta) end

local function quote(value)
    return '"' .. value:gsub('[%z\1-\31\\"]', function(c)
        if c == '"' then return '\\"' end
        if c == '\\' then return '\\\\' end
        return string.format('\\u%04x', string.byte(c))
    end) .. '"'
end

function J.encode(value)
    local seen, nodes = {}, 0
    local encode
    encode = function(v, depth)
        nodes = nodes + 1
        if depth > 24 or nodes > 20000 then error('JSON complexity limit') end
        if v == nil or v == J.null then return 'null' end
        local kind = type(v)
        if kind == 'boolean' then return v and 'true' or 'false' end
        if kind == 'number' then
            if v ~= v or v == math.huge or v == -math.huge then return 'null' end
            return tostring(v)
        end
        if kind == 'string' then return quote(v) end
        if kind ~= 'table' then error('JSON requires plain data: ' .. kind) end
        if seen[v] then error('JSON cycle') end
        seen[v] = true
        local out, meta = {}, getmetatable(v)
        if meta == arrayMeta then
            for i = 1, #v do out[i] = encode(v[i], depth + 1) end
            seen[v] = nil
            return '[' .. table.concat(out, ',') .. ']'
        end
        for key, child in pairs(v) do
            if type(key) ~= 'string' then error('JSON object keys must be strings; use Json.array') end
            out[#out + 1] = quote(key) .. ':' .. encode(child, depth + 1)
        end
        seen[v] = nil
        return '{' .. table.concat(out, ',') .. '}'
    end
    local result = encode(value, 0)
    local bytes = #result
    if utf16Strings then
        bytes = 0
        local i = 1
        while i <= #result do
            local code = string.byte(result, i)
            if code < 128 then bytes = bytes + 1
            elseif code < 2048 then bytes = bytes + 2
            elseif code >= 55296 and code <= 56319 then bytes = bytes + 4; i = i + 1
            else bytes = bytes + 3 end
            i = i + 1
        end
    end
    if bytes > 524288 then error('JSON size limit') end
    return result
end

local function utf8(code)
    -- PZ Kahlua uses Java UTF-16 strings; standard Lua uses byte strings.
    if utf16Strings then
        if code < 65536 then return string.char(code) end
        code = code - 65536
        return string.char(55296 + math.floor(code / 1024), 56320 + code % 1024)
    end
    if code < 128 then return string.char(code) end
    if code < 2048 then return string.char(192 + math.floor(code / 64), 128 + code % 64) end
    if code < 65536 then return string.char(224 + math.floor(code / 4096), 128 + math.floor(code / 64) % 64, 128 + code % 64) end
    return string.char(240 + math.floor(code / 262144), 128 + math.floor(code / 4096) % 64, 128 + math.floor(code / 64) % 64, 128 + code % 64)
end

function J.decode(text)
    if type(text) ~= 'string' or #text > 524288 then error('JSON size limit') end
    local pos, nodes = 1, 0
    local function skip()
        while text:sub(pos, pos):match('[ \t\r\n]') do pos = pos + 1 end
    end
    local function hex()
        local value = text:sub(pos, pos + 3)
        if #value ~= 4 or not value:match('^%x%x%x%x$') then error('Invalid unicode escape') end
        pos = pos + 4
        return tonumber(value, 16)
    end
    local function stringValue()
        pos = pos + 1
        local out = {}
        while pos <= #text do
            local c = text:sub(pos, pos)
            pos = pos + 1
            if c == '"' then return table.concat(out) end
            if c == '\\' then
                local esc = text:sub(pos, pos)
                pos = pos + 1
                local escapes = { ['"'] = '"', ['\\'] = '\\', ['/'] = '/', b = '\b', f = '\f', n = '\n', r = '\r', t = '\t' }
                if esc == 'u' then
                    local code = hex()
                    if code >= 55296 and code <= 56319 then
                        if text:sub(pos, pos + 1) ~= '\\u' then error('Missing low surrogate') end
                        pos = pos + 2
                        local low = hex()
                        if low < 56320 or low > 57343 then error('Invalid low surrogate') end
                        code = 65536 + (code - 55296) * 1024 + low - 56320
                    elseif code >= 56320 and code <= 57343 then error('Unexpected low surrogate') end
                    out[#out + 1] = utf8(code)
                elseif escapes[esc] then out[#out + 1] = escapes[esc]
                else error('Invalid escape') end
            else
                if string.byte(c) < 32 then error('Control character in JSON') end
                out[#out + 1] = c
            end
        end
        error('Unterminated string')
    end
    local parse
    parse = function(depth)
        nodes = nodes + 1
        if depth > 24 or nodes > 20000 then error('JSON complexity limit') end
        skip()
        local c = text:sub(pos, pos)
        if c == '"' then return stringValue() end
        if c == '{' or c == '[' then
            local object, ending = c == '{', c == '{' and '}' or ']'
            pos = pos + 1
            local value = object and {} or J.array()
            skip()
            if text:sub(pos, pos) == ending then pos = pos + 1; return value end
            while true do
                skip()
                local key
                if object then
                    if text:sub(pos, pos) ~= '"' then error('Expected object key') end
                    key = stringValue()
                    if value[key] ~= nil then error('Duplicate key') end
                    skip()
                    if text:sub(pos, pos) ~= ':' then error('Expected colon') end
                    pos = pos + 1
                else key = #value + 1 end
                value[key] = parse(depth + 1)
                skip()
                c = text:sub(pos, pos)
                pos = pos + 1
                if c == ending then return value end
                if c ~= ',' then error('Expected comma') end
            end
        end
        for _, word in ipairs({ 'true', 'false', 'null' }) do
            if text:sub(pos, pos + #word - 1) == word then
                pos = pos + #word
                if word == 'null' then return J.null end
                return word == 'true'
            end
        end
        local start = pos
        if c == '-' then pos = pos + 1 end
        c = text:sub(pos, pos)
        if c == '0' then pos = pos + 1
        elseif c:match('[1-9]') then
            repeat pos = pos + 1 until not text:sub(pos, pos):match('%d')
        else error('Invalid JSON value') end
        if text:sub(pos, pos) == '.' then
            pos = pos + 1
            if not text:sub(pos, pos):match('%d') then error('Invalid fraction') end
            repeat pos = pos + 1 until not text:sub(pos, pos):match('%d')
        end
        c = text:sub(pos, pos)
        if c == 'e' or c == 'E' then
            pos = pos + 1
            c = text:sub(pos, pos)
            if c == '+' or c == '-' then pos = pos + 1 end
            if not text:sub(pos, pos):match('%d') then error('Invalid exponent') end
            repeat pos = pos + 1 until not text:sub(pos, pos):match('%d')
        end
        local number = tonumber(text:sub(start, pos - 1))
        if not number or number == math.huge or number == -math.huge then error('Invalid number') end
        return number
    end
    local value = parse(0)
    skip()
    if pos <= #text then error('Trailing JSON data') end
    return value
end

return J
