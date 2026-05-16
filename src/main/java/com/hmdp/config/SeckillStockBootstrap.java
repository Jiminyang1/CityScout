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

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 启动时初始化 Redis 秒杀准入数据。
 * SETNX 而非 SET：Redis 是名额事实源，启动只在首次（key 不存在）从 DB 灌入。
 * 已有数据则保留现状，避免破坏 Kafka 在途消息的库存账本。
 * meta 是活动配置缓存，每次启动从 DB 覆盖刷新，供 Lua 在热路径校验时间窗口。
 */
@Slf4j
@Component
public class SeckillStockBootstrap implements CommandLineRunner {

    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

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
            writeMeta(voucher);

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

        log.info("秒杀 Redis 准入数据初始化完成, metaRefreshed={}, stockInitialized={}, stockSkipped={}",
                vouchers.size(), initialized, skipped);
    }

    private void writeMeta(SeckillVoucher voucher) {
        String metaKey = RedisConstants.SECKILL_META_KEY + voucher.getVoucherId();
        stringRedisTemplate.opsForHash().putAll(metaKey, Map.of(
                "beginTs", String.valueOf(toEpochMillis(voucher.getBeginTime())),
                "endTs", String.valueOf(toEpochMillis(voucher.getEndTime()))));
    }

    private long toEpochMillis(LocalDateTime time) {
        return time.atZone(SYSTEM_ZONE).toInstant().toEpochMilli();
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
