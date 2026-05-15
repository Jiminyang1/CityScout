package com.hmdp.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@Slf4j
@Service
public class OrderTimeoutService {

    @Resource
    private VoucherOrderTxService voucherOrderTxService;

    @Value("${app.order.unpaid-timeout-minutes:15}")
    private long unpaidTimeoutMinutes;

    @Value("${app.order.timeout-scan-limit:100}")
    private int timeoutScanLimit;

    @Scheduled(fixedDelayString = "${app.order.timeout-scan-fixed-delay-ms:30000}")
    public void cancelExpiredUnpaidOrders() {
        LocalDateTime expireBefore = LocalDateTime.now().minusMinutes(unpaidTimeoutMinutes);
        int expired = voucherOrderTxService.expireUnpaidOrders(expireBefore, timeoutScanLimit);
        if (expired > 0) {
            log.info("未支付订单超时取消完成, count={}, expireBefore={}", expired, expireBefore);
        }
    }
}
