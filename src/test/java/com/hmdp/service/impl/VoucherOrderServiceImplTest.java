package com.hmdp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 下单入口在 Lua 返回 3（Redis 准入数据缺失）时的自愈行为。
 */
@ExtendWith(MockitoExtension.class)
class VoucherOrderServiceImplTest {

    @InjectMocks
    private VoucherOrderServiceImpl service;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private RedisIdWorker redisIdWorker;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private SeckillRedisInitializer seckillRedisInitializer;

    @BeforeEach
    void setUp() throws Exception {
        ReflectionTestUtils.setField(service, "orderCreatedTopic", "order.created");
        UserDTO user = new UserDTO();
        user.setId(9L);
        UserHolder.saveUser(user);
        when(redisIdWorker.nextId("order")).thenReturn(5001L);
        lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        lenient().when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(new CompletableFuture<>());
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @SuppressWarnings("unchecked")
    private void stubLua(Long first, Long... rest) {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(first, (Object[]) rest);
    }

    @SuppressWarnings("unchecked")
    private void verifyLuaCalls(int n) {
        verify(stringRedisTemplate, times(n))
                .execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any());
    }

    @Test
    void seckill_luaOk_noInitializerCall() {
        stubLua(0L);

        Result result = service.SeckillVoucherWithLua(1L);

        assertTrue(result.getSuccess());
        assertEquals("5001", result.getData());
        verifyNoInteractions(seckillRedisInitializer);
        verify(kafkaTemplate).send(eq("order.created"), eq("9"), eq("{}"));
    }

    @Test
    void seckill_redisDataLost_reinitializesAndRetriesOnce() {
        stubLua(3L, 0L);
        when(seckillRedisInitializer.ensureInitialized(1L))
                .thenReturn(SeckillRedisInitializer.InitResult.INITIALIZED);

        Result result = service.SeckillVoucherWithLua(1L);

        assertTrue(result.getSuccess());
        assertEquals("5001", result.getData());
        verifyLuaCalls(2);
        verify(kafkaTemplate).send(eq("order.created"), eq("9"), eq("{}"));
    }

    @Test
    void seckill_redisDataLost_retryStillSoldOut_returnsStockMessage() {
        stubLua(3L, 1L);
        when(seckillRedisInitializer.ensureInitialized(1L))
                .thenReturn(SeckillRedisInitializer.InitResult.INITIALIZED);

        Result result = service.SeckillVoucherWithLua(1L);

        assertFalse(result.getSuccess());
        assertEquals("库存不足", result.getErrorMsg());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void seckill_initializerBusy_returnsNotReadyWithoutRetry() {
        stubLua(3L);
        when(seckillRedisInitializer.ensureInitialized(1L))
                .thenReturn(SeckillRedisInitializer.InitResult.BUSY);

        Result result = service.SeckillVoucherWithLua(1L);

        assertFalse(result.getSuccess());
        assertEquals("系统暂未就绪，请稍后重试", result.getErrorMsg());
        verifyLuaCalls(1);
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void seckill_unknownVoucher_returnsNotFound() {
        stubLua(3L);
        when(seckillRedisInitializer.ensureInitialized(1L))
                .thenReturn(SeckillRedisInitializer.InitResult.NOT_FOUND);

        Result result = service.SeckillVoucherWithLua(1L);

        assertFalse(result.getSuccess());
        assertEquals("秒杀券不存在", result.getErrorMsg());
        verifyLuaCalls(1);
    }

    @Test
    void seckill_luaKeysCarryOrderAndVoucher() {
        stubLua(0L);

        service.SeckillVoucherWithLua(1L);

        verify(stringRedisTemplate).execute(any(RedisScript.class),
                eq(List.of("seckill:stock:1", "seckill:order:1", "seckill:pending:1:5001",
                        "seckill:pending:index", "seckill:meta:1")),
                eq("9"), eq("1"), eq("5001"), anyString(), anyString());
    }
}
