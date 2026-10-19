-- Writes a product cache entry unless the key already holds a newer version (docs/shop-design.md §17.3).
-- Entries look like "<version>|P|<json>" (a product), "<version>|M" (no such product) or "<version>|S" (invalidated:
-- the product changed to <version>, rebuild from MySQL). A rebuild that read an old row from MySQL carries an old
-- version, so it cannot overwrite the marker an update left behind: the version is the fencing token.
-- KEYS[1] the entry; ARGV[1] version, ARGV[2] value, ARGV[3] time to live in ms
-- Returns 1 if written, 0 if a newer version is already there.
local current = redis.call('GET', KEYS[1])
if current then
  local version = tonumber(string.match(current, '^(%d+)|'))
  if version and version > tonumber(ARGV[1]) then
    return 0
  end
end
redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
return 1
