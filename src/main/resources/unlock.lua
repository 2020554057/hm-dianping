---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by wusujie.
--- DateTime: 2024/1/3 18:12
-- 比较缓存中的线程标识与当前线程标识是否一致
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    -- 一致，直接删除
    return redis.call('del', KEYS[1])
end
-- 不一致，返回0
return 0