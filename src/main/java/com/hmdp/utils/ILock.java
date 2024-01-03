package com.hmdp.utils;

/**
 * ClassName:ILock
 * Package:IntelliJ IDEA
 * Description:
 *
 * @Author 吴苏杰
 * @Create 2024/1/3 16:24
 * @Version 1.0
 */
public interface ILock {

    /**
     * 尝试获取锁
     * @param timeoutSec    锁持有的超时时间，过期后制动释放
     * @return  true代表获取锁成功；false代表获取锁失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
