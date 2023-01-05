package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.ObjectUtils;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 根据id查询店铺
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        // Shop shop = queryWithPassThrough(id);
        // Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 缓存击穿
        // 1、 互斥锁解决缓存击穿
        // Shop shop = queryWithPassMutex(id);
        // 2、 逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (ObjectUtils.isEmpty(shop)) {
            Shop shopData = getById(id);
            if (ObjectUtils.isNotEmpty(shopData)) {
                cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + id,shopData,100L, TimeUnit.SECONDS);
                return Result.ok(shopData);
            }
            return Result.fail("店铺不存在！");
        }

        // 7、返回
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期解决缓存击穿（不需要考虑缓存穿透的情况）
     * @param id
     * @return
     */
//    public Shop queryWithLogicExpire(Long id) {
//        String key = CACHE_SHOP_KEY + id;
//        // 1、 从Redis查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        // 2、 判断是否存在
//        if (StringUtils.isBlank(shopJson)) {
//            // 3、 不存在，直接返回
//            return null;
//        }
//        // 命中，需要先json反序列化为对象
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//        // 判断是否过期
//        if (expireTime.isAfter(LocalDateTime.now())) {
//            // 1、 未过期，直接返回店铺信息
//            return  shop;
//        }
//        // 2、 已过期，需要缓存重建
//        String lockKey = LOCK_SHOP_KEY + id;
//        boolean isLock = tryLock(lockKey);
//        if (isLock) {
//            CACHE_REBUILD_EXECUTOR.submit(() -> {
//                try {
//                    // 重建缓存
//                    this.saveShop2Redis(id, 20L);
//                } finally {
//                    // 释放锁
//                    unLock(lockKey);
//                }
//            } );
//    }
//        // 7、返回
//        return shop;
//    }

//    public Shop queryWithPassMutex(Long id) {
//        String key = CACHE_SHOP_KEY + id;
//        // 1、 从Redis查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        // 2、 判断是否存在
//        if (StringUtils.isNotBlank(shopJson)) {
//            // 3、 存在，直接返回
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//        // 判断命中的是否是空值
//        if ("".equals(shopJson)) {
//            return null;
//        }
//        // 实现缓存重建
//        // 获取互斥锁
//        String lockKey = LOCK_SHOP_KEY + id;
//        Shop shop = null;
//        try {
//            boolean isLock = tryLock(lockKey);
//            // 判断是否获取成功
//            if (!isLock) {
//                // 失败。则休眠重试
//                Thread.sleep(50);
//                return queryWithPassMutex(id);
//            }
//            // 4、 成功 直接根据id查询数据库
//            shop = getById(id);
//            Thread.sleep(200);
//            if (ObjectUtils.isEmpty(shop)) {
//                // 将空值写入redis
//                stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
//                // 5、 不存在，返回错误
//                return null;
//            }
//            // 6、存在，写入redis
//            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));
//            stringRedisTemplate.expire(key, CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            // 释放锁
//            unLock(lockKey);
//        }
//        // 7、返回
//        return shop;
//    }


//    public Shop queryWithPassThrough(Long id) {
//        String key = CACHE_SHOP_KEY + id;
//        // 1、 从Redis查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        // 2、 判断是否存在
//        if (StringUtils.isNotBlank(shopJson)) {
//            // 3、 存在，直接返回
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//        // 判断命中的是否是空值
//        if ("".equals(shopJson)) {
//            return null;
//        }
//        // 4、 不存在 直接根据id查询数据库
//        Shop shop = getById(id);
//        if (ObjectUtils.isEmpty(shop)) {
//            // 将空值写入redis
//            stringRedisTemplate.opsForValue().set(key,"", CACHE_NULL_TTL, TimeUnit.MINUTES);
//            // 5、 不存在，返回错误
//            return null;
//        }
//        // 6、存在，写入redis
//        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));
//        stringRedisTemplate.expire(key, CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        // 7、返回
//        return shop;
//    }

//    private boolean tryLock(String key) {
//        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
//        return BooleanUtil.isTrue(flag);
//    }
//
//    private void unLock(String key) {
//        stringRedisTemplate.delete(key);
//    }

    public void saveShop2Redis(Long id, Long expireSeconds) {
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 更新shop
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空！");
        }
        // 1、 更新数据库
        updateById(shop);
        // 2、 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
