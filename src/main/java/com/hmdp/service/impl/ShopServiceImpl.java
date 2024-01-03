package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.*;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;

    //将店铺信息缓存到redis中
    @Override
    public Result queryById(Long id) {
        //*******************自己写的方法实现*****************************
        // 解决缓存穿透
        //Shop shop = queryWithPassTrough(id);
        //基于互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);
        //逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);

        //*******************通过调用自定义工具类实现*****************************
        // 解决缓存穿透
        Shop shop = cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
        // Shop shop = cacheClient
        //         .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);

        if (shop == null){
            return Result.fail("店铺不存在！");
        }
        //4、结束返回shop
        return Result.ok(shop);
    }

    //线程池（10个线程）
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /**
     * 基于逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1、根据商铺id从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2、判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3、缓存未命中（说明不是热点key，返回null）
            return null;
        }
        //4、缓存命中，需要先把json数据反序列化成对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5、判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //5.1、未过期，直接返回店铺信息（旧数据）
            return shop;
        }
        //5.2、过期了，需要进行缓存重建
        //6、缓存重建
        //6.1、尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2、判断是否获取锁
        if (isLock){
            //6.3、获取到了锁，开启独立线程，进行缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    this.saveShop2Redis(id,20L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //返回旧数据
        return shop;
    }

    /**
     * 基于互斥锁解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;

        //1、根据商铺id从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            //不为空返回商铺信息
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的是否是空值 "",(上面已经判断过不为空的情况了，下面有"",和null两种判断，判断不为null的情况)
        if (shopJson != null){
            return null;
        }

        //2、实现缓存重建
        //2.1、尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY+id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //2.2、判断是否获取锁
            if (!isLock){
                //未获取到,休眠等待，隔一段时间重试
                Thread.sleep(50);
                return queryWithMutex(id);//递归调用
            }

            //2.3、根据id从数据库中查找并将数据缓存入Redis缓存中
            shop = getById(id);
            Thread.sleep(200);//模拟重建的延迟
            //判断数据库中是否存在
            if (shop == null){
                //解决缓存穿透，给不存在的数据赋空值
                stringRedisTemplate.opsForValue().set(key,"",CACHE_SHOP_TTL, TimeUnit.MINUTES);
                return null;
            }
            //商铺存在，将商铺信息缓存到Redis中
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //3、释放互斥锁（释放锁一定要记得放在finally中，防止死锁）
            unlock(lockKey);
        }

        //4、结束返回shop
        return shop;
    }

    /**
     * 解决缓存穿透的方法（缓存穿透）
     * @param id
     * @return
     */
    public Shop queryWithPassTrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //1、根据商铺id从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {//判断某字符串是否不为空且长度不为0且不由空白符(whitespace)构成，等于!isBlank(String str)
            //不为空返回商铺信息
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //判断命中的是否是空值 "",(上面已经判断过不为空的情况了，下面有"",和null两种判断，判断不为null的情况)
        if (shopJson != null){
            return null;
        }

        //2、Redis缓存中不存在
        //根据id从数据库中查找
        Shop shop = getById(id);
        //3、判断数据库中是否存在
        if (shop == null){
            //解决缓存穿透，给不存在的数据赋空值
            stringRedisTemplate.opsForValue().set(key,"",CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return null;
        }
        //商铺存在，将商铺信息缓存到Redis中
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //4、结束返回shop
        return shop;
    }


    /**
     * Redis缓存更新策略：先操作数据库，再删除缓存
     * @param shop
     * @return
     */
    @Transactional
    @Override
    public Result updateShopById(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("商铺id不能为空！");
        }
        
        //1、先操作数据库
        updateById(shop);
        //2、再删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    /**
     * 上锁和解锁的方法
     * @param key
     * @return
     */
    //上锁方法
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    //解锁的方法
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 模拟测试，首先向缓存中添加数据
     */
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1、查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);//模拟缓存重建的延迟
        //2、封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3、写入到Redis中
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
}
