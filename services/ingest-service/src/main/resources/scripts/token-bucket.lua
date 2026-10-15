-- Token bucket, updated atomically. Uses the Redis server clock so all ingest instances agree on time.
-- KEYS[1] bucket key
-- ARGV[1] capacity (tokens), ARGV[2] refill per second, ARGV[3] tokens requested
-- Returns {allowed (1/0), retry_after_ms}
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end

tokens = math.min(capacity, tokens + math.max(0, now - ts) * rate / 1000)

local allowed = 0
local retry_ms = 0
if requested <= tokens then
  tokens = tokens - requested
  allowed = 1
elseif requested > capacity then
  -- Can never fit; tell the client to retry after a full refill and let it split the batch.
  retry_ms = math.ceil(capacity * 1000 / rate)
else
  retry_ms = math.ceil((requested - tokens) * 1000 / rate)
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
-- An idle bucket is full again after capacity / rate seconds, so it can expire then.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity * 1000 / rate) + 1000)
return {allowed, retry_ms}
