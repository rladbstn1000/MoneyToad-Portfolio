-- Validate before writing. User IDs stay strings to preserve Java Long precision.
local userId, refreshHash, expiresAt = ARGV[1], ARGV[2], ARGV[3]
local function positiveInteger(value, maximum)
    return value and value:match('^[1-9]%d*$')
        and (#value < #maximum or (#value == #maximum and value <= maximum))
end
if not positiveInteger(userId, '9223372036854775807')
    or not refreshHash or #refreshHash ~= 64 or not refreshHash:match('^[0-9a-f]+$')
    or not positiveInteger(expiresAt, '9007199254740991') then
    return -1
end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local remaining = tonumber(expiresAt) - now
if remaining <= 0 or remaining > 3600000 then return -1 end
if redis.call('EXISTS', KEYS[1]) ~= 0 then return 0 end
redis.call('HSET', KEYS[1], 'userId', userId, 'refreshHash', refreshHash, 'expiresAt', expiresAt)
redis.call('PEXPIREAT', KEYS[1], expiresAt)
return 1
