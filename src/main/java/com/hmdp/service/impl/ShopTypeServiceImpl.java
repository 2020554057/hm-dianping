package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //将店铺类型信息缓存到Redis中
    @Override
    public Result queryTypeList() {
        //1、从Redis中查询商铺类型的数据
        String shopTypeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        //2、判断缓存中是否存在
        if (StrUtil.isNotBlank(shopTypeJson)) {
            return Result.ok(JSONUtil.toList(shopTypeJson, ShopType.class));
        }
        //3、不存在从数据库中查找
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        if (shopTypeList.isEmpty() || shopTypeList.size()<0){
            return Result.fail("商铺类型错误！");
        }
        //4、将商铺类型数据缓存到Redis中
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY,JSONUtil.toJsonStr(shopTypeList),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //5、返回
        return Result.ok(shopTypeList);
    }
}
