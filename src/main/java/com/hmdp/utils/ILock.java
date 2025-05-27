package com.hmdp.utils;


/**
 * Distributed lock interface
 */
public interface ILock {

    /**
     * Try to acquire the lock
     */
    boolean tryLock(long timeoutSec);

    /**
     * Release the lock
     */
    void unlock();
}
