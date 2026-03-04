package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
public class PaymentSimulationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentSimulationService.class);

    private static final ExecutorService PAYMENT_EXECUTOR = Executors.newFixedThreadPool(2);

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Value("${payment.simulation.enabled:true}")
    private boolean paymentSimulationEnabled;

    /**
     * 模拟第三方支付（随机耗时 + 随机成功率）
     */
    public void simulateThirdPartyPaymentAsync(Long orderId) {
        if (!paymentSimulationEnabled) {
            return;
        }
        PAYMENT_EXECUTOR.submit(() -> doSimulate(orderId));
    }

    private void doSimulate(Long orderId) {
        long delayMs = ThreadLocalRandom.current().nextLong(300L, 1801L);
        try {
            TimeUnit.MILLISECONDS.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        // 90% 成功
        boolean success = ThreadLocalRandom.current().nextInt(100) < 90;
        if (!success) {
            log.warn("模拟支付失败, orderId={}, delayMs={}", orderId, delayMs);
            return;
        }

        int updated = voucherOrderMapper.update(
                null,
                new UpdateWrapper<VoucherOrder>()
                        .set("status", 2)
                        .set("pay_time", LocalDateTime.now())
                        .eq("id", orderId)
                        .eq("status", 1));
        if (updated == 1) {
            log.info("模拟支付成功, orderId={}, delayMs={}", orderId, delayMs);
        } else {
            log.warn("模拟支付跳过(状态非未支付), orderId={}, delayMs={}", orderId, delayMs);
        }
    }
}
