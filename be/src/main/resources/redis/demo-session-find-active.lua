local function positiveInteger(value, maximum)
    return value and value:match('^[1-9]%d*$')
        and (#value < #maximum or (#value == #maximum and value <= maximum))
end
if redis.call('TYPE', KEYS[1]).ok ~= 'hash' or redis.call('HLEN', KEYS[1]) ~= 3 then return {} end
local value = redis.call('HMGET', KEYS[1], 'userId', 'refreshHash', 'expiresAt')
if not positiveInteger(value[1], '9223372036854775807')
    or not value[2] or #value[2] ~= 64 or not value[2]:match('^[0-9a-f]+$')
    or not positiveInteger(value[3], '9007199254740991') then return {} end
local ttl = redis.call('PTTL', KEYS[1])
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local remaining = tonumber(value[3]) - now
if ttl <= 0 or ttl > 3600000 or remaining <= 0 or remaining > 3600000 then return {} end
return {value[1], value[3]}
