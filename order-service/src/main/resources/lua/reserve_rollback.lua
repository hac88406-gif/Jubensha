-- ========================================================================
-- 原子回滚场次库存（HASH 版本）
-- ========================================================================
-- KEYS[1] = Redis key, 格式: "session:{sessionId}"
-- ARGV[1] = playerCnt, 要加回的玩家数（正整数）
--
-- 返回值:
--   1  = 回滚成功，booked 已原子 -playerCnt
--   0  = key 不存在，无需回滚（可能已过期或场次关闭）
-- ========================================================================

local key = KEYS[1]
local playerCnt = tonumber(ARGV[1])

-- key 不存在则静默返回成功，不抛错
local exists = redis.call('EXISTS', key)
if exists == 0 then
    return 0
end

-- 防御：booked 不能减成负数
local booked = tonumber(redis.call('HGET', key, 'booked'))
if booked == nil or booked < playerCnt then
    -- 已为 0 或被提前回滚过，清零即可
    redis.call('HSET', key, 'booked', 0)
    return 1
end

redis.call('HINCRBY', key, 'booked', -playerCnt)
return 1
