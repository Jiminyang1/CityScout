package com.hmdp.config;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * 启动时按需初始化 Redis 库存。
 * SETNX 而非 SET：Redis 是名额事实源，启动只在首次（key 不存在）从 DB 灌入。
 * 已有数据则保留现状，避免破坏 Kafka 在途消息的库存账本。
 */
@Slf4j
@Component
public class SeckillStockBootstrap implements CommandLineRunner {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Override
    public void run(String... args) {
        List<SeckillVoucher> vouchers = seckillVoucherService.list();
        int initialized = 0;
        int skipped = 0;

        for (SeckillVoucher voucher : vouchers) {
            String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucher.getVoucherId();
            Boolean ok = stringRedisTemplate.opsForValue()
                    .setIfAbsent(stockKey, String.valueOf(voucher.getStock()));
            if (Boolean.TRUE.equals(ok)) {
                // 首次初始化：同步灌入活跃订单的 userId 到 order Set
                seedOrderSet(voucher.getVoucherId());
                initialized++;
            } else {
                skipped++;
            }
        }

        log.info("秒杀库存初始化完成, initialized={}, skipped(已存在)={}", initialized, skipped);
    }

    private void seedOrderSet(Long voucherId) {
        String orderKey = RedisConstants.SECKILL_ORDER_KEY + voucherId;
        List<VoucherOrder> orders = voucherOrderService.query()
                .eq("voucher_id", voucherId)
                .in("status", 1, 2, 3, 5)
                .list();
        for (VoucherOrder order : orders) {
            stringRedisTemplate.opsForSet().add(orderKey, order.getUserId().toString());
        }
    }
}
