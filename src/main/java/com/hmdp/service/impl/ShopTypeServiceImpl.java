package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.ObjectUtils;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author yif
 * @since 2022-12-10
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        // 查询是否有缓存
        List<String> cacheShopType = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, -1);
        if (CollectionUtil.isNotEmpty(cacheShopType)) {
            List<ShopType> shopTypes = cacheShopType.stream()
                    .map(o -> JSONUtil.toBean(o, ShopType.class))
                    .sorted(Comparator.comparingInt(ShopType::getSort))
                    .collect(Collectors.toList());
            return Result.ok(shopTypes);
        }
        List<ShopType> shopTypes = lambdaQuery().orderByAsc(ShopType::getSort).list();
        // 数据库中不存在数据
        if (ObjectUtils.isEmpty(shopTypes)) {
            return Result.fail("商品类别不存在！！！");
        }
        // 转化为json格式的list
        List<String> shopTypeCache = shopTypes.stream()
                .sorted(Comparator.comparingInt(ShopType::getSort))
                .map(JSONUtil::toJsonStr)
                .collect(Collectors.toList());
        // 将list存入redis中
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE_KEY, shopTypeCache);
        stringRedisTemplate.expire(CACHE_SHOP_TYPE_KEY, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        return Result.ok(shopTypes);
    }
}
