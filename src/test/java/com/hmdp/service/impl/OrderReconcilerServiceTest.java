package com.hmdp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.OrderFailed;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.OrderFailedMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderReconcilerServiceTest {

    @InjectMocks
    private OrderReconcilerService reconciler;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private VoucherOrderMapper voucherOrderMapper;

    @Mock
    private OrderFailedMapper orderFailedMapper;

    @Mock
    private VoucherOrderTxService voucherOrderTxService;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ZSetOperations<String, String> zSetOps;

    @Mock
    private HashOperations<String, Object, Object> hashOps;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(reconciler, "orderCreatedTopic", "order.created");
        ReflectionTestUtils.setField(reconciler, "ageThresholdSeconds", 90L);
        ReflectionTestUtils.setField(reconciler, "batchSize", 200);
        lenient().when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOps);
        lenient().when(stringRedisTemplate.opsForHash()).thenReturn(hashOps);
    }

    private void stubCandidates(String... members) {
        Set<String> set = new LinkedHashSet<>();
        Collections.addAll(set, members);
        when(zSetOps.rangeByScore(eq("seckill:pending:index"), eq(0d), anyDouble(), eq(0L), eq(200L)))
                .thenReturn(set);
    }

    private static double anyDouble() {
        return org.mockito.ArgumentMatchers.anyDouble();
    }

    // ---------- 空场景 ----------

    @Test
    void reconcile_emptyIndex_noop() {
        when(zSetOps.rangeByScore(any(), eq(0d), anyDouble(), eq(0L), eq(200L)))
                .thenReturn(Collections.emptySet());

        reconciler.reconcile();

        verifyNoInteractions(voucherOrderMapper, orderFailedMapper, voucherOrderTxService, kafkaTemplate);
    }

    // ---------- 分支 1：DB 已存在 → CLEANUP ----------

    @Test
    void reconcile_dbHasOrder_cleansPendingAndIndex() {
        stubCandidates("1:5001");
        when(voucherOrderMapper.selectById(5001L)).thenReturn(new VoucherOrder().setId(5001L));

        reconciler.reconcile();

        verify(stringRedisTemplate).delete("seckill:pending:1:5001");
        verify(zSetOps).remove("seckill:pending:index", "1:5001");
        // 没走 release/republish
        verifyNoInteractions(voucherOrderTxService, kafkaTemplate);
    }

    // ---------- 分支 2：tb_order_failed 已存在 → RELEASE ----------

    @Test
    void reconcile_failedHasRow_callsRelease() {
        stubCandidates("1:5002");
        when(voucherOrderMapper.selectById(5002L)).thenReturn(null);
        when(orderFailedMapper.selectById(5002L)).thenReturn(new OrderFailed().setOrderId(5002L));

        Map<Object, Object> pending = new HashMap<>();
        pending.put("userId", "9");
        pending.put("requestId", "req-1");
        when(hashOps.entries("seckill:pending:1:5002")).thenReturn(pending);

        reconciler.reconcile();

        verify(voucherOrderTxService).releaseRedisReservation(1L, 9L, 5002L, false);
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void reconcile_failedHasRow_butPendingMissing_onlyCleansIndex() {
        stubCandidates("1:5003");
        when(voucherOrderMapper.selectById(5003L)).thenReturn(null);
        when(orderFailedMapper.selectById(5003L)).thenReturn(new OrderFailed().setOrderId(5003L));
        when(hashOps.entries("seckill:pending:1:5003")).thenReturn(Collections.emptyMap());

        reconciler.reconcile();

        verify(zSetOps).remove("seckill:pending:index", "1:5003");
        verifyNoInteractions(voucherOrderTxService);
    }

    // ---------- 分支 3：都没有 → REPUBLISH ----------

    @Test
    void reconcile_neitherExists_republishesToKafka() throws Exception {
        stubCandidates("1:5004");
        when(voucherOrderMapper.selectById(5004L)).thenReturn(null);
        when(orderFailedMapper.selectById(5004L)).thenReturn(null);

        Map<Object, Object> pending = new HashMap<>();
        pending.put("userId", "9");
        pending.put("requestId", "req-zzz");
        when(hashOps.entries("seckill:pending:1:5004")).thenReturn(pending);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"orderId\":5004}");

        reconciler.reconcile();

        verify(kafkaTemplate).send(eq("order.created"), eq("9"), eq("{\"orderId\":5004}"));
        verifyNoInteractions(voucherOrderTxService);
    }

    @Test
    void reconcile_neitherExists_butPendingMissing_skipsAndRemovesIndex() {
        stubCandidates("1:5005");
        when(voucherOrderMapper.selectById(5005L)).thenReturn(null);
        when(orderFailedMapper.selectById(5005L)).thenReturn(null);
        when(hashOps.entries("seckill:pending:1:5005")).thenReturn(Collections.emptyMap());

        reconciler.reconcile();

        verify(zSetOps).remove("seckill:pending:index", "1:5005");
        verifyNoInteractions(kafkaTemplate, voucherOrderTxService);
    }

    @Test
    void reconcile_neitherExists_butPendingMissingUserId_skipsWithoutRemove() {
        stubCandidates("1:5006");
        when(voucherOrderMapper.selectById(5006L)).thenReturn(null);
        when(orderFailedMapper.selectById(5006L)).thenReturn(null);

        Map<Object, Object> pending = new HashMap<>();
        pending.put("requestId", "req-x");
        // userId 缺失
        when(hashOps.entries("seckill:pending:1:5006")).thenReturn(pending);

        reconciler.reconcile();

        // 不重投，也不清 index (因为可能只是数据残缺,reconciler 下一轮可再试)
        verifyNoInteractions(kafkaTemplate, voucherOrderTxService);
        verify(zSetOps, never()).remove(any(), eq("1:5006"));
    }

    // ---------- 非法 member ----------

    @Test
    void reconcile_invalidMember_removedFromIndex() {
        stubCandidates("garbage_no_colon");

        reconciler.reconcile();

        verify(zSetOps).remove("seckill:pending:index", "garbage_no_colon");
        verifyNoInteractions(voucherOrderMapper, orderFailedMapper);
    }

    @Test
    void reconcile_nonNumericMember_removedFromIndex() {
        stubCandidates("abc:def");

        reconciler.reconcile();

        verify(zSetOps).remove("seckill:pending:index", "abc:def");
        verifyNoInteractions(voucherOrderMapper, orderFailedMapper);
    }

    // ---------- 异常隔离 ----------

    @Test
    void reconcile_exceptionInOneItem_doesNotAbortBatch() {
        stubCandidates("1:5007", "1:5008");
        when(voucherOrderMapper.selectById(5007L)).thenThrow(new RuntimeException("DB down"));
        when(voucherOrderMapper.selectById(5008L))
                .thenReturn(new VoucherOrder().setId(5008L));

        reconciler.reconcile();

        // 5008 仍然被清理
        verify(stringRedisTemplate).delete("seckill:pending:1:5008");
        verify(zSetOps).remove("seckill:pending:index", "1:5008");
    }
}
