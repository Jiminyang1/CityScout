package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
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

import javax.annotation.Resource;
import java.util.Collections;

@Service
public class VoucherOrderTxService {

    public enum OrderProcessResult {
        SUCCESS,
        DUPLICATE_ORDER,
        NO_STOCK,
        DB_ERROR
    }

    private static final Logger log = LoggerFactory.getLogger(VoucherOrderTxService.class);

    private static final DefaultRedisScript<Long> SECKILL_COMPENSATE_SCRIPT;

    static {
        SECKILL_COMPENSATE_SCRIPT = new DefaultRedisScript<>();
        SECKILL_COMPENSATE_SCRIPT.setLocation(new ClassPathResource("seckill_compensate.lua"));
        SECKILL_COMPENSATE_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private PaymentSimulationService paymentSimulationService;

    @Transactional
    public void handleRealOrderCreation(VoucherOrder voucherOrder) {
        processOrder(voucherOrder, true, true);
    }

    @Transactional
    public OrderProcessResult processOrder(VoucherOrder voucherOrder,
                                           boolean compensateRedisOnFailure,
                                           boolean triggerPaymentAfterCommit) {
        Integer count = voucherOrderMapper.selectCount(new QueryWrapper<VoucherOrder>()
                .eq("user_id", voucherOrder.getUserId())
                .eq("voucher_id", voucherOrder.getVoucherId()));
        if (count != null && count > 0) {
            log.warn("重复订单, userId={}, voucherId={}, orderId={}",
                    voucherOrder.getUserId(), voucherOrder.getVoucherId(), voucherOrder.getId());
            if (compensateRedisOnFailure) {
                compensate(voucherOrder);
            }
            return OrderProcessResult.DUPLICATE_ORDER;
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.warn("DB扣减失败，orderId={}", voucherOrder.getId());
            if (compensateRedisOnFailure) {
                compensate(voucherOrder);
            }
            return OrderProcessResult.NO_STOCK;
        }

        int inserted;
        try {
            inserted = voucherOrderMapper.insert(voucherOrder);
        } catch (DuplicateKeyException duplicateKeyException) {
            log.warn("唯一索引拦截重复订单, userId={}, voucherId={}, orderId={}",
                    voucherOrder.getUserId(), voucherOrder.getVoucherId(), voucherOrder.getId());
            if (compensateRedisOnFailure) {
                compensate(voucherOrder);
            }
            return OrderProcessResult.DUPLICATE_ORDER;
        }

        if (inserted != 1) {
            log.error("订单写入失败，orderId={}", voucherOrder.getId());
            if (compensateRedisOnFailure) {
                compensate(voucherOrder);
            }
            return OrderProcessResult.DB_ERROR;
        }

        if (triggerPaymentAfterCommit) {
            triggerPaymentAfterCommit(voucherOrder.getId());
        }
        return OrderProcessResult.SUCCESS;
    }

    private void compensate(VoucherOrder voucherOrder) {
        Long result = stringRedisTemplate.execute(
                SECKILL_COMPENSATE_SCRIPT,
                Collections.emptyList(),
                voucherOrder.getVoucherId().toString(),
                voucherOrder.getUserId().toString());
        log.warn("补偿执行完成, orderId={}, result={}", voucherOrder.getId(), result);
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
}
