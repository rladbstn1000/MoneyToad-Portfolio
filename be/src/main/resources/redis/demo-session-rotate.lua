local function positiveInteger(value, maximum)
    return value and value:match('^[1-9]%d*$')
        and (#value < #maximum or (#value == #maximum and value <= maximum))
end
local function hash(value)
    return value and #value == 64 and value:match('^[0-9a-f]+$')
end
-- Invalid input or identity must never trigger reuse revocation.
if not positiveInteger(ARGV[1], '9223372036854775807')
    or not positiveInteger(ARGV[2], '9007199254740991')
    or not hash(ARGV[3]) or not hash(ARGV[4]) or ARGV[3] == ARGV[4] then return 0 end
if redis.call('TYPE', KEYS[1]).ok ~= 'hash' or redis.call('HLEN', KEYS[1]) ~= 3 then return 0 end
local value = redis.call('HMGET', KEYS[1], 'userId', 'refreshHash', 'expiresAt')
if not positiveInteger(value[1], '9223372036854775807')
    or not hash(value[2]) or not positiveInteger(value[3], '9007199254740991') then return 0 end
local ttl = redis.call('PTTL', KEYS[1])
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local remaining = tonumber(value[3]) - now
if ttl <= 0 or ttl > 3600000 or remaining <= 0 or remaining > 3600000 then return 0 end
if value[1] ~= ARGV[1] or value[3] ~= ARGV[2] then return 0 end
if value[2] ~= ARGV[3] then
    redis.call('DEL', KEYS[1])
    return -1
end
-- HSET keeps the existing absolute expiry; rotation never issues EXPIRE/PEXPIREAT.
redis.call('HSET', KEYS[1], 'refreshHash', ARGV[4])
return 1
