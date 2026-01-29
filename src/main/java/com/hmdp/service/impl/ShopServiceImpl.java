package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.omg.PortableInterceptor.DISCARDING;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.context.Theme;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
/ Shop服务类
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    //商铺缓存
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);

        // TODO 这里应该先用一个测试类将所有的店铺数据预热 否则这个方法的逻辑不对
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class,this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        if(shop == null){
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    //缓存穿透解决方案（redis中存入空对象）
    public Shop queryWithPassThrough(Long id) {
        //1.从redis中查询商铺缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)) {
            //3.存在 直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断缓存中查询的数据是否是空字符串(isNotBlank把null和空字符串给排除了)
        if(Objects.nonNull(shopJson)){
            //不是NULL 一定是空值/空串
            return null;
        }
        //4.当前数据是null，则从数据库中查询店铺数据
        Shop shop = getById(id);
        //5.不存在 返回错误
        if(shop == null){
            //缓存穿透解决 将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //6.存在 写入redis 设置过期时间
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回
        return shop;
    }


    //互斥锁解决缓存击穿
    public Shop queryWithMutex(Long id) {
        //1.从redis中查询商铺缓存（shop的JSON格式）
        String key = CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)) {
            //3.存在 直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断缓存中查询的数据是否是空字符串(isNotBlank把null和空字符串给排除了)
        if(Objects.nonNull(shopJson)){
            //不是NULL 一定是空值
            return null;
        }
        //4.实现缓存重建
        //4.1获取互斥锁
        String lockKey = "lock:shop:"+id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2判断是否获取成功
            //4.3失败 休眠并重试
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4成功 查询数据库
            shop = getById(id);
            //模拟重建的延迟
            Thread.sleep(1000);
            //5.不存在 返回错误
            if (shop == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                //return Result.fail("店铺不存在!");
                return null;
            }
            //6.存在 写入redis 设置过期时间
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }catch(InterruptedException e){
            throw new RuntimeException(e);
        }finally {
            //7.释放互斥锁 （释放锁一定要记得放在finally中，防止死锁）
            unLock(lockKey);
        }
        return shop;
    }



    //逻辑过期解决缓存击穿
    public Shop queryWithLogicalExpire(Long id) {
        //1.从Redis中查询商铺缓存
        String key = CACHE_SHOP_KEY+id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否命中
        if(Objects.isNull(shopJson)) {
            //3.未命中 直接返回
            return null;
        }

        //4.存在 先把JSON反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson,RedisData.class);
        JSONObject data =(JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data,Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        //5.判断是否过期
        if(expireTime.isAfter((LocalDateTime.now()))) {
            //5.1未过期 直接返回
            return shop;
        }

        //5.2已经过期 需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2判断是否成功获取成功
        if(isLock){
            //6.3成功 开启一个线程 实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //缓存重建
                    this.saveShopToRedis(id,20L);
                }catch (Exception e){
                    //异常处理
                    throw new RuntimeException(e);
                }finally {
                    //释放锁 （释放锁一定要记得放在finally中，防止死锁）
                    unLock(lockKey);
                }
            });
        }
        // 6.4、获取锁失败，再次查询缓存，判断缓存是否重建（这里双检是有必要的）
        shopJson = stringRedisTemplate.opsForValue().get(key);
        if (Objects.isNull(shopJson)) {
            return null;
        }

        // 缓存命中，将JSON字符串反序列化为对象，并判断缓存数据是否逻辑过期
        redisData = JSONUtil.toBean(shopJson, RedisData.class);
        // 这里需要先转成JSONObject再转成反序列化，否则可能无法正确映射Shop的字段
        data = (JSONObject) redisData.getData();
        shop = JSONUtil.toBean(data, Shop.class);

        //6.5 返回结果
        return shop;
    }

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //获取锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",100,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    //将封装的Shop存入Redis
    public void saveShopToRedis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺信息
        Shop shop = getById(id);
        //模拟重建延时
        Thread.sleep(10000);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    //更新商铺 【先更新数据库后删除缓存】
    @Transactional
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if(id==null){
            return Result.fail("店铺ID不能为空！");
        }
        //1.更新数据库
        boolean f = this.updateById(shop);
        if (!f){
            // 更新数据库失败，抛出异常，事务回滚
            throw new RuntimeException("数据库更新失败");
        }
        // 2、删除缓存
        f = stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        if (!f){
            // 缓存删除失败，抛出异常，事务回滚
            throw new RuntimeException("缓存删除失败");
        }
        return Result.ok();
    }


    //查询店铺 并根据距离排序
    @Override
    public Result quertShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.是否需要根据坐标查询
        if(x == null || y == null){
            //不需要根据坐标查询，按数据库查询
            Page<Shop>page = query()
                    .eq("type_id",typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            //返回数据
            return Result.ok(page.getRecords());
        }
        //2.计算分页参数
        int from = (current-1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //3.查询redis  limit = end即要读取end条数据
        String key = "shop:geo:" + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().radius(
                key,
                new Circle(x, y, 5000),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().limit(end));
        //4.解析数据
        //4.1 判空
        if(results == null){
            //判空
            return Result.ok(Collections.emptyList());
        }
        //4.2 过滤0-from
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if(list.size()<=from){
            //得到的数据小于from条 返回空
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = new ArrayList<>();
        Map<String,Distance> map = new HashMap<>();
        list.stream()
                .skip(from) //跳过from条
                .forEach(result ->{
                    //获取店铺ID
                    String shopIdStr = result.getContent().getName();
                    ids.add(Long.valueOf(shopIdStr));
                    //获取距离
                    Distance distance = result.getDistance();
                    map.put(shopIdStr,distance);
        });
        //5.根据id查询shop 要保证有序
        String idStr = StrUtil.join(",",ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for(Shop shop :shops){
            //填入距离字段
            shop.setDistance(map.get(shop.getId().toString()).getValue());
        }
        //6.返回
        return Result.ok(shops);
    }
}
