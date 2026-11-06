-- Decides whether a buyer gets a unit of a flash sale, atomically (docs/shop-design.md §16.2).
-- KEYS[1] units left, KEYS[2] buyer -> order id, KEYS[3] order id -> buyer, KEYS[4] the queue of accepted orders
-- ARGV[1] buyer, ARGV[2] new order id, ARGV[3] sale id, ARGV[4] payment token
-- Returns {outcome, order id}: ACCEPTED (queued now, ARGV[2]), BOUGHT (the order this buyer already holds),
-- SOLD_OUT, or NO_SALE (the sale is not loaded into Redis).

-- First, so that a buyer who retries after the sale sold out still learns their order.
local earlier = redis.call('HGET', KEYS[2], ARGV[1])
if earlier then
  return {'BOUGHT', earlier}
end
local left = redis.call('GET', KEYS[1])
if not left then
  return {'NO_SALE', ''}
end
if tonumber(left) <= 0 then
  return {'SOLD_OUT', ''}
end
redis.call('DECR', KEYS[1])
redis.call('HSET', KEYS[2], ARGV[1], ARGV[2])
redis.call('HSET', KEYS[3], ARGV[2], ARGV[1])
-- Approximate trimming keeps the stream bounded; a million entries is far more than any backlog.
redis.call('XADD', KEYS[4], 'MAXLEN', '~', '1000000', '*',
  'order_id', ARGV[2], 'sale_id', ARGV[3], 'user_id', ARGV[1], 'payment_token', ARGV[4])
return {'ACCEPTED', ARGV[2]}
