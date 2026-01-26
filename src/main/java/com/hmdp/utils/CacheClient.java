package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
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
 * 缓存工具类
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 设置缓存
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time , TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /**
     * 设置带有逻辑过期的缓存
     */
    public void setWithLogicalExpire(String key, Object value, Long time , TimeUnit unit){
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存空值 解决缓存穿透方案
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                         Function<ID,R>deFallback,Long time,TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis中查询商铺缓存 JSON格式
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.1 判断是否串是否notblank
        if(StrUtil.isNotBlank(json)) {
            //3.不是空串，存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        //2.2 判断是否是空值/空串
        if(json != null){
            //不是NULL 一定是空值/空串
            return null;
        }
        //4.不存在 查询数据库
        R r = deFallback.apply(id);
        //5.不存在 返回错误
        if(r == null){
            //缓存穿透解决 将空值/空串写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //6.存在 写入redis 设置过期时间
        this.set(key,r,time,unit);
        return r;
    }

    /**
     * 逻辑过期 解决缓存击穿方案
     */
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R>type,
                                           Function<ID,R>deFallback,Long time,TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //log.debug(String.valueOf(StrUtil.isBlank(json)));

        //2.判断是否串是否为Blank
        if(StrUtil.isBlank(json)) {
            //3.blank 去数据库中查
            R r1 = deFallback.apply(id);
            if(r1 != null){
                this.setWithLogicalExpire(key,r1,time,unit);
                return r1;
            }else return null;
        }
        //4.存在 先把JSON反序列化为对象
        RedisData redisData = JSONUtil.toBean(json,RedisData.class);
        JSONObject data =(JSONObject) redisData.getData();
        R r  = JSONUtil.toBean(data,type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if(expireTime.isAfter((LocalDateTime.now()))) {
            //5.1未过期 直接返回
            return r;
        }
        //5.2已经过期 需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2判断是否成功获取成功
        if(isLock){
            //6.3成功 开启一个线程 实现缓存重建
            CACHE_REBULID_EXECUTOR.submit(()->{
                try {
                    //查询数据库
                    R r1 = deFallback.apply(id);
                    //写入Redis
                    this.setWithLogicalExpire(key,r1,time,unit);
                }catch (Exception e){
                    //异常处理
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //6.4无论是否获取锁成功 返回过期信息
        return r;
    }

    /**
     * 线程池
     */
    private static final ExecutorService CACHE_REBULID_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 获取锁
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",100,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

}
