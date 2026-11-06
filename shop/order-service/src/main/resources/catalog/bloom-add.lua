-- Adds ARGV to the Bloom filter KEYS[1] and returns how many were new. Through a script because BF.MADD answers
-- with booleans under RESP3, which a raw command from Spring Data cannot decode; inside a script they are 1 and 0.
local added = 0
for _, new in ipairs(redis.call('BF.MADD', KEYS[1], unpack(ARGV))) do
    if new == 1 then
        added = added + 1
    end
end
return added
