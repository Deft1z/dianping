package com.hmdp.utils;


/**
 * 自定义Ilock类
 * 获取锁 删除锁
 */
public interface ILock {

    //获取锁
    boolean tryLock(long timeout);

    //释放锁
    void unLock();
}
