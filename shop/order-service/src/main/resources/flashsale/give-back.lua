-- Gives a cancelled order's unit back to its flash sale (docs/shop-design.md §16.4). Idempotent: only while the buyer
-- still holds this very order, so a repeated call, or one after the buyer bought again, changes nothing.
-- KEYS[1] units left, KEYS[2] buyer -> order id, KEYS[3] order id -> buyer
-- ARGV[1] buyer, ARGV[2] order id
-- Returns 1 if the unit went back, 0 if it had already.
if redis.call('HGET', KEYS[2], ARGV[1]) ~= ARGV[2] then
  return 0
end
redis.call('HDEL', KEYS[2], ARGV[1])
redis.call('HDEL', KEYS[3], ARGV[2])
redis.call('INCR', KEYS[1])
return 1
