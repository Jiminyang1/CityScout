package com.hmdp.service.impl;

import com.hmdp.entity.OrderFailed;
import com.hmdp.mapper.OrderFailedMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.Resource;

@Service
public class OrderDltProcessingService {

    private static final Logger log = LoggerFactory.getLogger(OrderDltProcessingService.class);

    @Resource
    private OrderFailedMapper orderFailedMapper;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private VoucherOrderTxService voucherOrderTxService;

    /**
     * DLT 失败事实先落 DB，Redis 释放只在 DB 事务提交后执行。
     * listener 需在本方法返回后再 ack，避免 ack 早于 failed 记录提交。
     */
    @Transactional
    public void persistFailedAndReleaseAfterCommit(OrderFailed failed) {
        if (voucherOrderMapper.selectById(failed.getOrderId()) != null) {
            log.warn("DLT 处理时订单已存在，跳过 failed 记录和 Redis 释放, orderId={}", failed.getOrderId());
            return;
        }

        try {
            orderFailedMapper.insert(failed);
        } catch (DuplicateKeyException e) {
            log.warn("DLT 记录已存在，幂等跳过, orderId={}", failed.getOrderId());
        }

        registerAfterCommitRelease(failed);
    }

    private void registerAfterCommitRelease(OrderFailed failed) {
        Runnable release = () -> {
            try {
                voucherOrderTxService.releaseRedisReservation(
                        failed.getVoucherId(),
                        failed.getUserId(),
                        failed.getOrderId(),
                        false);
            } catch (Exception e) {
                log.error("DLT afterCommit 释放 Redis 名额失败, orderId={}，reconciler 会兜底",
                        failed.getOrderId(), e);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    release.run();
                }
            });
            return;
        }

        release.run();
    }
}
