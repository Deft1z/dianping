package com.hmdp.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 基于Redis实现的分布式锁
 */
public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;

    //锁名称不能写死 要和业务有关
    private String name;
    private static final String KEY_PREFIX = "LOCK:";
    private static final String ID_PREFIX = UUID.randomUUID() +"-";

    //引入lua脚本(unlock.lua)
    private static final DefaultRedisScript<Long>UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    //构造函数,初始化
    public SimpleRedisLock(String name,StringRedisTemplate stringRedisTemplate){
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //获取锁
    //timeout 锁持有的超时时间 setnx ex中的ex
    @Override
    public boolean tryLock(long timeout) {
        //锁的value = UUID + 线程id
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //执行setnx来获取锁 锁key = KEY_PREFIX + 业务名称 锁value = UUID+线程标识 用于后续判断是否是自己持有锁，是的话才删除
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name,threadId,timeout, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    //lua脚本改进释放锁
    @Override
    public void unLock() {
        //调用lua脚本 将判断锁+删除锁封装 保证原子性
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX+name),ID_PREFIX + Thread.currentThread().getId());
    }

    //释放锁初始版本
//    @Override
//    public void unLock() {
//        //UUID + 线程标识id
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        //获取锁中的value
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX+name);
//        //释放锁前要比较标识是否一致
//        if(id.equals(threadId)){
//            //一致 释放
//            stringRedisTemplate.delete(KEY_PREFIX+name);
//        }
//    }
}
