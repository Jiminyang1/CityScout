package com.hmdp.config;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.impl.SeckillRedisInitializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * 启动时初始化 Redis 秒杀准入数据。
 * SETNX 而非 SET：Redis 是名额事实源，启动只在首次（key 不存在）从 DB 灌入。
 * 已有数据则保留现状，避免破坏 Kafka 在途消息的库存账本。
 * meta 是活动配置缓存，每次启动从 DB 覆盖刷新，供 Lua 在热路径校验时间窗口。
 *
 * 具体写入逻辑在 {@link SeckillRedisInitializer}，运行期 Redis 丢数据时下单接口也复用它现场补齐。
 */
@Slf4j
@Component
public class SeckillStockBootstrap implements CommandLineRunner {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private SeckillRedisInitializer seckillRedisInitializer;

    @Override
    public void run(String... args) {
        List<SeckillVoucher> vouchers = seckillVoucherService.list();
        int initialized = 0;
        int skipped = 0;

        for (SeckillVoucher voucher : vouchers) {
            if (seckillRedisInitializer.initialize(voucher)) {
                initialized++;
            } else {
                skipped++;
            }
        }

        log.info("秒杀 Redis 准入数据初始化完成, metaRefreshed={}, stockInitialized={}, stockSkipped={}",
                vouchers.size(), initialized, skipped);
    }
}
