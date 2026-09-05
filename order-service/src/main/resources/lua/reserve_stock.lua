-- ========================================================================
-- 原子扣减场次库存（HASH 版本）
-- ========================================================================
-- KEYS[1] = Redis key, 格式: "session:{sessionId}"
-- ARGV[1] = playerCnt, 要扣减的玩家数（正整数）
--
-- Redis HASH 结构:
--   session:{sessionId} => { capacity: "6", booked: "3" }
--
-- 返回值:
--   1  = 扣减成功，booked 已原子 +playerCnt
--   0  = 名额不足，booked + playerCnt > capacity
--  -1  = Redis key 不存在，Java 层需先调用 lazyInit(sessionId) 再重试
-- ========================================================================

local key = KEYS[1]
local playerCnt = tonumber(ARGV[1])

-- 1. key 不存在 → 让 Java 层懒加载
local exists = redis.call('EXISTS', key)
if exists == 0 then
    return -1
end

-- 2. 读 HASH 两个字段
local booked = tonumber(redis.call('HGET', key, 'booked'))
local capacity = tonumber(redis.call('HGET', key, 'capacity'))

-- 3. 防御：空字段（理论上不会发生，懒加载会一起写）
if booked == nil then booked = 0 end
if capacity == nil then return -1 end

-- 4. 原子判断 + 扣减
if booked + playerCnt <= capacity then
    redis.call('HINCRBY', key, 'booked', playerCnt)
    return 1
else
    return 0
end
