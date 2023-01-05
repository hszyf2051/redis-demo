package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.ObjectUtils;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @author yif
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
     * 设置逻辑过期时间
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透
     * keyPrefix 参数前缀 type为返回值类型的类，Function为函数式编程，交给传来的对象查询数据库
     * @param id
     * @return
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1、 从Redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2、 判断是否存在
        if (StringUtils.isNotBlank(json)) {
            // 3、 存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值
        if ("".equals(json)) {
            return null;
        }
        // 4、 不存在 直接根据id查询数据库
        R r = dbFallback.apply(id);
        if (ObjectUtils.isEmpty(r)) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 5、 不存在，返回错误
            return null;
        }
        // 6、存在，写入redis
        this.set(key, r, time, unit);
        // 7、返回
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期解决缓存击穿（不需要考虑缓存穿透的情况）
     * @param id
     * @return
     */
    public <R,ID> R queryWithLogicExpire(
        String keyPrefix,ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = CACHE_SHOP_KEY + id;
        // 1、 从Redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2、 判断是否存在
        if (StringUtils.isBlank(json)) {
            // 3、 不存在，直接返回
            return null;
        }
        // 命中，需要先json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 1、 未过期，直接返回信息
            return r;
        }
        // 2、 已过期，需要缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 重建缓存
                    // 查询数据库
                    R r1 = dbFallback.apply(id);
                    // 写入Redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } finally {
                    // 释放锁
                    unLock(lockKey);
                }
            } );
        }
        // 7、返回
        this.set(key, r, time, unit);
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}
