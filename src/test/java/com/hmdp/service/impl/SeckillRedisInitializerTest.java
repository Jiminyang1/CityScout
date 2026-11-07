package com.hmdp.service.impl;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SeckillRedisInitializerTest {

    @InjectMocks
    private SeckillRedisInitializer initializer;

    @Mock
    private ISeckillVoucherService seckillVoucherService;

    @Mock
    private VoucherOrderMapper voucherOrderMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private SetOperations<String, String> setOps;

    @Mock
    private HashOperations<String, Object, Object> hashOps;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        lenient().when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
    }

    private static SeckillVoucher voucher(long id, int stock) {
        return new SeckillVoucher()
                .setVoucherId(id)
                .setStock(stock)
                .setBeginTime(LocalDateTime.of(2026, 11, 7, 10, 0))
                .setEndTime(LocalDateTime.of(2026, 11, 8, 10, 0));
    }

    // ---------- initialize ----------

    @Test
    void initialize_stockMissing_seedsUsersThenMetaThenSetsStockLast() {
        when(stringRedisTemplate.hasKey("seckill:stock:7")).thenReturn(false);
        when(voucherOrderMapper.selectList(any())).thenReturn(List.of(
                new VoucherOrder().setUserId(11L),
                new VoucherOrder().setUserId(12L),
                new VoucherOrder().setUserId(11L)));
        when(valueOps.setIfAbsent("seckill:stock:7", "40")).thenReturn(true);

        assertTrue(initializer.initialize(voucher(7L, 40)));

        // stock 必须最后写：Lua 要 stock 和 meta 都在才放行，集合没灌完之前不能放人进来
        InOrder order = inOrder(setOps, hashOps, valueOps);
        order.verify(setOps).add("seckill:order:7", "11", "12");
        order.verify(hashOps).putAll(eq("seckill:meta:7"), anyMap());
        order.verify(valueOps).setIfAbsent("seckill:stock:7", "40");
    }

    @Test
    void initialize_stockPresent_onlyRefreshesMeta() {
        when(stringRedisTemplate.hasKey("seckill:stock:7")).thenReturn(true);

        assertFalse(initializer.initialize(voucher(7L, 40)));

        verify(hashOps).putAll(eq("seckill:meta:7"), anyMap());
        verifyNoInteractions(voucherOrderMapper, setOps);
        verify(valueOps, never()).setIfAbsent(anyString(), anyString());
    }

    @Test
    void initialize_noActiveOrders_doesNotCallSadd() {
        when(stringRedisTemplate.hasKey("seckill:stock:7")).thenReturn(false);
        when(voucherOrderMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(valueOps.setIfAbsent("seckill:stock:7", "40")).thenReturn(true);

        assertTrue(initializer.initialize(voucher(7L, 40)));

        verifyNoInteractions(setOps);
    }

    @Test
    void initialize_writesMetaAsEpochMillis() {
        when(stringRedisTemplate.hasKey("seckill:stock:7")).thenReturn(true);
        SeckillVoucher v = voucher(7L, 40);

        initializer.initialize(v);

        long begin = v.getBeginTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        long end = v.getEndTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        verify(hashOps).putAll("seckill:meta:7", Map.of(
                "beginTs", String.valueOf(begin),
                "endTs", String.valueOf(end)));
    }

    // ---------- ensureInitialized ----------

    @Test
    void ensureInitialized_lockHeldByOthers_returnsBusyWithoutTouchingDb() {
        when(valueOps.setIfAbsent(eq("lock:seckill:init:7"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        assertEquals(SeckillRedisInitializer.InitResult.BUSY, initializer.ensureInitialized(7L));

        verifyNoInteractions(seckillVoucherService, voucherOrderMapper);
    }

    @Test
    void ensureInitialized_voucherMissing_keepsLockAsNegativeCache() {
        when(valueOps.setIfAbsent(eq("lock:seckill:init:404"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(seckillVoucherService.getById(404L)).thenReturn(null);

        assertEquals(SeckillRedisInitializer.InitResult.NOT_FOUND, initializer.ensureInitialized(404L));

        // 不解锁：锁 10 秒后自然过期，期间同一个不存在的 voucherId 不会再打到 DB
        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), any(List.class), any());
    }

    @Test
    void ensureInitialized_voucherFound_initializesAndReleasesLock() {
        when(valueOps.setIfAbsent(eq("lock:seckill:init:7"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(seckillVoucherService.getById(7L)).thenReturn(voucher(7L, 40));
        when(stringRedisTemplate.hasKey("seckill:stock:7")).thenReturn(false);
        when(voucherOrderMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(valueOps.setIfAbsent("seckill:stock:7", "40")).thenReturn(true);

        assertEquals(SeckillRedisInitializer.InitResult.INITIALIZED, initializer.ensureInitialized(7L));

        verify(valueOps).setIfAbsent("seckill:stock:7", "40");
        verify(stringRedisTemplate).execute(any(RedisScript.class), eq(List.of("lock:seckill:init:7")), anyString());
    }

    @Test
    void ensureInitialized_dbDown_releasesLockAndPropagates() {
        when(valueOps.setIfAbsent(eq("lock:seckill:init:7"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(seckillVoucherService.getById(7L)).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> initializer.ensureInitialized(7L));

        verify(stringRedisTemplate).execute(any(RedisScript.class), eq(List.of("lock:seckill:init:7")), anyString());
    }
}
