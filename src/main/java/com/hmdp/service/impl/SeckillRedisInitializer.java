package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 把一张秒杀券的 Redis 准入数据（seckill:order / seckill:meta / seckill:stock）从 MySQL 灌入 Redis。
 *
 * 两个入口：
 * 1. 应用启动：{@link com.hmdp.config.SeckillStockBootstrap} 对所有秒杀券调用 {@link #initialize}。
 * 2. 运行期自愈：Redis 重启丢数据后 seckill.lua 返回 3（未初始化），下单接口调用 {@link #ensureInitialized}
 *    按券现场补齐，不必再重启后端。
 *
 * 写入顺序：先已购用户集合，再时间窗 meta，最后 SETNX 库存。Lua 要求 stock 与 meta 都存在才放行，
 * 所以在 stock 写入前不会有请求被放进来，也就不会出现"集合还没灌完就放行了已有订单用户"的窗口。
 *
 * 库存仍以 SETNX 写入：Redis 是名额事实源，key 还在时绝不覆盖，避免破坏 Kafka 在途消息的账本。
 * 已知局限：Redis 整体丢数据后，用 DB 库存重建时无法扣除仍在 Kafka 里的在途订单，Redis 可能暂时多放行几个；
 * 这些请求会被消费者的 DB 条件扣减（stock > 0）拦下并进入 DLT，不会在 MySQL 超卖。
 */
@Slf4j
@Component
public class SeckillRedisInitializer {

    public enum InitResult {
        /** 已（重新）初始化，或 Redis 数据本来就完整 */
        INITIALIZED,
        /** MySQL 里没有这张秒杀券 */
        NOT_FOUND,
        /** 其他请求正在初始化，或 10 秒内刚确认过券不存在 */
        BUSY
    }

    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) end return 0",
            Long.class);

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 运行期按券补齐。用分布式锁保证同一时刻只有一个请求查库重建；
     * 券不存在时不释放锁，让锁自然过期，相当于 10 秒的负缓存，防止不存在的 voucherId 反复打到 DB。
     */
    public InitResult ensureInitialized(Long voucherId) {
        String lockKey = RedisConstants.SECKILL_INIT_LOCK_KEY + voucherId;
        String token = UUID.randomUUID().toString();
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(
                lockKey, token, RedisConstants.SECKILL_INIT_LOCK_TTL_SECONDS, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            return InitResult.BUSY;
        }

        boolean keepLockAsNegativeCache = false;
        try {
            SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
            if (voucher == null) {
                keepLockAsNegativeCache = true;
                log.warn("秒杀券不存在，跳过 Redis 初始化, voucherId={}", voucherId);
                return InitResult.NOT_FOUND;
            }
            boolean stockCreated = initialize(voucher);
            log.warn("秒杀 Redis 准入数据缺失，已从 DB 现场补齐, voucherId={}, stockCreated={}", voucherId, stockCreated);
            return InitResult.INITIALIZED;
        } finally {
            if (!keepLockAsNegativeCache) {
                stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), token);
            }
        }
    }

    /**
     * 补齐一张券的 Redis 准入数据。
     *
     * @return true 表示本次新建了库存 key（首次或丢数据后重建）；false 表示库存 key 已存在，只刷新了 meta
     */
    public boolean initialize(SeckillVoucher voucher) {
        Long voucherId = voucher.getVoucherId();
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;

        boolean stockMissing = !Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey));
        if (stockMissing) {
            seedOrderSet(voucherId);
        }
        writeMeta(voucher);
        if (!stockMissing) {
            return false;
        }
        Boolean created = stringRedisTemplate.opsForValue()
                .setIfAbsent(stockKey, String.valueOf(voucher.getStock()));
        return Boolean.TRUE.equals(created);
    }

    private void writeMeta(SeckillVoucher voucher) {
        String metaKey = RedisConstants.SECKILL_META_KEY + voucher.getVoucherId();
        stringRedisTemplate.opsForHash().putAll(metaKey, Map.of(
                "beginTs", String.valueOf(toEpochMillis(voucher.getBeginTime())),
                "endTs", String.valueOf(toEpochMillis(voucher.getEndTime()))));
    }

    /** 把 DB 里仍然有效（未支付/已支付/已核销/退款中）的订单用户灌入一人一单集合。SADD 幂等。 */
    private void seedOrderSet(Long voucherId) {
        String orderKey = RedisConstants.SECKILL_ORDER_KEY + voucherId;
        List<VoucherOrder> orders = voucherOrderMapper.selectList(new QueryWrapper<VoucherOrder>()
                .eq("voucher_id", voucherId)
                .in("status", 1, 2, 3, 5));
        String[] userIds = orders.stream()
                .map(order -> order.getUserId().toString())
                .distinct()
                .toArray(String[]::new);
        if (userIds.length > 0) {
            stringRedisTemplate.opsForSet().add(orderKey, userIds);
        }
    }

    private long toEpochMillis(LocalDateTime time) {
        return time.atZone(SYSTEM_ZONE).toInstant().toEpochMilli();
    }
}
