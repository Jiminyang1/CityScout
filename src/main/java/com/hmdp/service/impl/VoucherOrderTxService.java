package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.entity.OrderReleaseRetry;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.OrderFailedMapper;
import com.hmdp.mapper.OrderReleaseRetryMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.RedisConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Service
public class VoucherOrderTxService {

    private static final Logger log = LoggerFactory.getLogger(VoucherOrderTxService.class);

    private static final DefaultRedisScript<Long> SECKILL_RELEASE_SCRIPT;
    private static final DefaultRedisScript<Long> SECKILL_DUPLICATE_ACTIVE_RELEASE_SCRIPT;

    static {
        SECKILL_RELEASE_SCRIPT = new DefaultRedisScript<>();
        SECKILL_RELEASE_SCRIPT.setLocation(new ClassPathResource("seckill_release.lua"));
        SECKILL_RELEASE_SCRIPT.setResultType(Long.class);

        SECKILL_DUPLICATE_ACTIVE_RELEASE_SCRIPT = new DefaultRedisScript<>();
        SECKILL_DUPLICATE_ACTIVE_RELEASE_SCRIPT.setLocation(
                new ClassPathResource("seckill_duplicate_active_release.lua"));
        SECKILL_DUPLICATE_ACTIVE_RELEASE_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private OrderFailedMapper orderFailedMapper;

    @Resource
    private OrderReleaseRetryMapper orderReleaseRetryMapper;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private PaymentSimulationService paymentSimulationService;

    /**
     * Consumer 落库主流程：插订单 + 扣库存。
     * 任一失败抛异常，Spring Kafka 走 retry → DLT。
     * 成功后 afterCommit 清理 Redis pending。
     */
    @Transactional
    public void handleRealOrderCreation(VoucherOrder voucherOrder) {
        Long orderId = voucherOrder.getId();
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        if (isDltFinalOrProcessing(orderId)) {
            log.warn("订单已进入失败处理路径，跳过真实落库, orderId={}, userId={}, voucherId={}",
                    orderId, userId, voucherId);
            return;
        }

        voucherOrder.setStatus(1);

        int inserted;
        try {
            inserted = voucherOrderMapper.insert(voucherOrder);
        } catch (DuplicateKeyException duplicateKeyException) {
            // 幂等：orderId 重复 (重投) 或 user+voucher 唯一索引拦截 (重复订单)
            log.warn("订单已存在，幂等跳过, orderId={}, userId={}, voucherId={}",
                    orderId, userId, voucherId);
            if (isActiveOrderDuplicate(duplicateKeyException, orderId, userId, voucherId)) {
                registerDuplicateActiveCompensation(voucherId, userId, orderId);
            } else {
                registerPendingCleanup(voucherId, orderId);
            }
            return;
        }
        if (inserted != 1) {
            throw new IllegalStateException("订单写入异常, orderId=" + orderId);
        }

        boolean stockUpdated = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!stockUpdated) {
            // DB stock 已 0，但 Redis 还放行了 — 账本漂移，需要进 DLT 走释放分支
            throw new IllegalStateException("DB stock 不足，orderId=" + orderId + ", voucherId=" + voucherId);
        }

        registerPendingCleanup(voucherId, orderId);
        triggerPaymentAfterCommit(orderId);
    }

    /**
     * 用户主动取消未支付订单。
     * DB 改状态 + 回补 stock 在事务内，Redis 释放在 afterCommit。
     */
    @Transactional
    public boolean cancelUnpaidOrder(Long orderId, Long userId) {
        VoucherOrder order = voucherOrderMapper.selectById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = voucherOrderMapper.update(
                null,
                new UpdateWrapper<VoucherOrder>()
                        .set("status", 4)
                        .set("update_time", now)
                        .eq("id", orderId)
                        .eq("user_id", userId)
                        .eq("status", 1));
        if (updated != 1) {
            return false;
        }

        boolean restored = seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId())
                .update();
        if (!restored) {
            throw new IllegalStateException("取消订单库存回补失败, orderId=" + orderId);
        }

        createRedisReleaseRetry(order);
        registerRedisReleaseAfterCommit(order.getVoucherId(), order.getUserId(), orderId, true);
        log.info("未支付订单已取消，afterCommit 将释放 Redis 名额, orderId={}, userId={}, voucherId={}",
                orderId, userId, order.getVoucherId());
        return true;
    }

    public int expireUnpaidOrders(LocalDateTime expireBefore, int limit) {
        List<VoucherOrder> orders = voucherOrderMapper.selectList(
                new QueryWrapper<VoucherOrder>()
                        .eq("status", 1)
                        .le("create_time", expireBefore)
                        .orderByAsc("create_time")
                        .last("LIMIT " + limit));
        int expired = 0;
        for (VoucherOrder order : orders) {
            if (cancelUnpaidOrder(order.getId(), order.getUserId())) {
                expired++;
            }
        }
        return expired;
    }

    public int retryPendingRedisReleases(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1000));
        List<OrderReleaseRetry> rows = orderReleaseRetryMapper.selectList(
                new QueryWrapper<OrderReleaseRetry>()
                        .orderByAsc("update_time")
                        .last("LIMIT " + safeLimit));

        int released = 0;
        for (OrderReleaseRetry row : rows) {
            try {
                VoucherOrder order = voucherOrderMapper.selectById(row.getOrderId());
                if (!shouldRetryRedisRelease(row, order)) {
                    orderReleaseRetryMapper.deleteById(row.getOrderId());
                    continue;
                }

                releaseRedisReservation(row.getVoucherId(), row.getUserId(), row.getOrderId(), true);
                orderReleaseRetryMapper.deleteById(row.getOrderId());
                released++;
            } catch (Exception e) {
                try {
                    recordRedisReleaseRetryFailure(row.getOrderId(), e);
                } catch (Exception recordException) {
                    log.warn("记录 Redis 释放重试失败信息失败, orderId={}", row.getOrderId(), recordException);
                }
                log.warn("重试释放 Redis 名额失败, orderId={}", row.getOrderId(), e);
            }
        }
        return released;
    }

    /**
     * DLT / Reconciler 调用：彻底释放 Redis 名额（用户从 set 移除、stock+1、pending 清理）。
     * forceUserRelease=false：仅 pending 还在时才释放（DLT、reconciler 主用法）
     * forceUserRelease=true：cancel 场景，pending 可能已被 consumer 清理但 user 还在 set
     */
    public Long releaseRedisReservation(Long voucherId, Long userId, Long orderId, boolean forceUserRelease) {
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
        String orderKey = RedisConstants.SECKILL_ORDER_KEY + voucherId;
        String pendingKey = RedisConstants.SECKILL_PENDING_KEY + voucherId + ":" + orderId;
        String pendingIndexKey = RedisConstants.SECKILL_PENDING_INDEX_KEY;

        Long result = stringRedisTemplate.execute(
                SECKILL_RELEASE_SCRIPT,
                Arrays.asList(stockKey, orderKey, pendingKey, pendingIndexKey),
                userId.toString(),
                voucherId.toString(),
                orderId.toString(),
                forceUserRelease ? "1" : "0");
        log.info("释放 Redis 名额完成, orderId={}, userId={}, voucherId={}, result={}",
                orderId, userId, voucherId, result);
        return result;
    }

    public Long compensateDuplicateActiveOrder(Long voucherId, Long userId, Long orderId) {
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
        String orderKey = RedisConstants.SECKILL_ORDER_KEY + voucherId;
        String pendingKey = RedisConstants.SECKILL_PENDING_KEY + voucherId + ":" + orderId;
        String pendingIndexKey = RedisConstants.SECKILL_PENDING_INDEX_KEY;

        Long result = stringRedisTemplate.execute(
                SECKILL_DUPLICATE_ACTIVE_RELEASE_SCRIPT,
                Arrays.asList(stockKey, orderKey, pendingKey, pendingIndexKey),
                userId.toString(),
                voucherId.toString(),
                orderId.toString());
        log.info("重复活跃订单 Redis 补偿完成, orderId={}, userId={}, voucherId={}, result={}",
                orderId, userId, voucherId, result);
        return result;
    }

    /**
     * Consumer 落库 afterCommit：仅清理 pending（user 名额保留给真实订单）。
     */
    private void registerPendingCleanup(Long voucherId, Long orderId) {
        String pendingKey = RedisConstants.SECKILL_PENDING_KEY + voucherId + ":" + orderId;
        String pendingIndexKey = RedisConstants.SECKILL_PENDING_INDEX_KEY;
        String indexMember = voucherId + ":" + orderId;

        Runnable cleanup = () -> {
            try {
                stringRedisTemplate.delete(pendingKey);
                stringRedisTemplate.opsForZSet().remove(pendingIndexKey, indexMember);
            } catch (Exception e) {
                log.warn("清理 Redis pending 失败, orderId={}，reconciler 会兜底", orderId, e);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    cleanup.run();
                }
            });
            return;
        }
        cleanup.run();
    }

    /**
     * DB 已有该用户的活跃订单时，本次 Lua 预扣需要还回 stock，但 user set 必须保留。
     */
    private void registerDuplicateActiveCompensation(Long voucherId, Long userId, Long orderId) {
        Runnable compensation = () -> {
            try {
                compensateDuplicateActiveOrder(voucherId, userId, orderId);
            } catch (Exception e) {
                log.warn("重复活跃订单 Redis 补偿失败, orderId={}，reconciler 会兜底", orderId, e);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    compensation.run();
                }
            });
            return;
        }
        compensation.run();
    }

    /**
     * 取消订单 afterCommit：完整释放（user + stock + pending）。
     */
    private void registerRedisReleaseAfterCommit(Long voucherId, Long userId, Long orderId, boolean forceUserRelease) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        releaseRedisReservation(voucherId, userId, orderId, forceUserRelease);
                        clearRedisReleaseRetry(orderId);
                    } catch (Exception e) {
                        log.warn("afterCommit 释放 Redis 名额失败, orderId={}，release retry 会兜底", orderId, e);
                    }
                }
            });
            return;
        }
        releaseRedisReservation(voucherId, userId, orderId, forceUserRelease);
        clearRedisReleaseRetry(orderId);
    }

    private void triggerPaymentAfterCommit(Long orderId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    paymentSimulationService.simulateThirdPartyPaymentAsync(orderId);
                }
            });
            return;
        }
        paymentSimulationService.simulateThirdPartyPaymentAsync(orderId);
    }

    private boolean isActiveOrderDuplicate(DuplicateKeyException duplicateKeyException,
                                           Long orderId,
                                           Long userId,
                                           Long voucherId) {
        VoucherOrder existingById = voucherOrderMapper.selectById(orderId);
        if (existingById != null) {
            return false;
        }

        Long activeCount = voucherOrderMapper.selectCount(new QueryWrapper<VoucherOrder>()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .in("status", 1, 2, 3, 5)
                .ne("id", orderId));
        if (activeCount != null && activeCount > 0) {
            return true;
        }

        return containsMessage(duplicateKeyException, "uk_user_voucher_active");
    }

    private void createRedisReleaseRetry(VoucherOrder order) {
        OrderReleaseRetry retry = new OrderReleaseRetry()
                .setOrderId(order.getId())
                .setUserId(order.getUserId())
                .setVoucherId(order.getVoucherId());
        try {
            orderReleaseRetryMapper.insert(retry);
        } catch (DuplicateKeyException e) {
            log.debug("Redis 释放重试记录已存在, orderId={}", order.getId());
        }
    }

    private void clearRedisReleaseRetry(Long orderId) {
        try {
            orderReleaseRetryMapper.deleteById(orderId);
        } catch (Exception e) {
            log.warn("清理 Redis 释放重试记录失败, orderId={}，稍后会幂等重试", orderId, e);
        }
    }

    private boolean shouldRetryRedisRelease(OrderReleaseRetry row, VoucherOrder order) {
        if (order == null) {
            return false;
        }
        if (!row.getUserId().equals(order.getUserId()) || !row.getVoucherId().equals(order.getVoucherId())) {
            return false;
        }
        return Integer.valueOf(4).equals(order.getStatus()) || Integer.valueOf(6).equals(order.getStatus());
    }

    private boolean isDltFinalOrProcessing(Long orderId) {
        if (orderFailedMapper.selectById(orderId) != null) {
            return true;
        }
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisConstants.SECKILL_DLT_FENCE_KEY + orderId));
    }

    private void recordRedisReleaseRetryFailure(Long orderId, Exception e) {
        String message = e.getMessage();
        if (message == null) {
            message = e.getClass().getName();
        }
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }

        orderReleaseRetryMapper.update(
                null,
                new UpdateWrapper<OrderReleaseRetry>()
                        .setSql("retry_count = retry_count + 1")
                        .set("last_error", message)
                        .set("update_time", LocalDateTime.now())
                        .eq("order_id", orderId));
    }

    private boolean containsMessage(Throwable throwable, String needle) {
        Throwable cursor = throwable;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null && message.contains(needle)) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
