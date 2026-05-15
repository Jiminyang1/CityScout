package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.hmdp.entity.OrderFailed;
import com.hmdp.entity.OrderReleaseRetry;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.OrderFailedMapper;
import com.hmdp.mapper.OrderReleaseRetryMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VoucherOrderTxServiceTest {

    @InjectMocks
    private VoucherOrderTxService txService;

    @Mock
    private VoucherOrderMapper voucherOrderMapper;

    @Mock
    private OrderFailedMapper orderFailedMapper;

    @Mock
    private OrderReleaseRetryMapper orderReleaseRetryMapper;

    @Mock
    private ISeckillVoucherService seckillVoucherService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private PaymentSimulationService paymentSimulationService;

    @Mock
    @SuppressWarnings("rawtypes")
    private UpdateChainWrapper updateChainWrapper;

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
        lenient().when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clear();
        }
    }

    // 用 fluent chain mock 模拟 seckillVoucherService.update().setSql().eq().gt().update()
    @SuppressWarnings("unchecked")
    private void mockSeckillUpdate(boolean result) {
        when(seckillVoucherService.update()).thenReturn(updateChainWrapper);
        when(updateChainWrapper.setSql(anyString())).thenReturn(updateChainWrapper);
        when(updateChainWrapper.eq(anyString(), any())).thenReturn(updateChainWrapper);
        lenient().when(updateChainWrapper.gt(anyString(), any())).thenReturn(updateChainWrapper);
        when(updateChainWrapper.update()).thenReturn(result);
    }

    // 触发所有已注册的 afterCommit 回调，模拟事务提交
    private void fireAfterCommit() {
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        for (TransactionSynchronization sync : syncs) {
            sync.afterCommit();
        }
    }

    // ---------- handleRealOrderCreation ----------

    @Test
    void handleRealOrderCreation_happyPath_insertsAndDecrementsAndRegistersAfterCommit() {
        VoucherOrder order = new VoucherOrder().setId(1001L).setUserId(7L).setVoucherId(1L);
        when(voucherOrderMapper.insert(order)).thenReturn(1);
        mockSeckillUpdate(true);

        txService.handleRealOrderCreation(order);

        verify(voucherOrderMapper).insert(order);
        verify(updateChainWrapper).update();
        // afterCommit 之前只允许读取 DLT fence，不应该执行 Redis 写操作
        verify(stringRedisTemplate).hasKey("seckill:dlt:fence:1001");
        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));

        fireAfterCommit();

        // afterCommit 后才删 pending、清 index、触发支付
        verify(stringRedisTemplate).delete("seckill:pending:1:1001");
        verify(paymentSimulationService).simulateThirdPartyPaymentAsync(1001L);
        assertEquals(1, order.getStatus());
    }

    @Test
    void handleRealOrderCreation_duplicateOrderId_idempotentReturnAndStillCleansPending() {
        VoucherOrder order = new VoucherOrder().setId(1002L).setUserId(7L).setVoucherId(1L);
        when(voucherOrderMapper.insert(order))
                .thenThrow(new DuplicateKeyException("PRIMARY"));
        when(voucherOrderMapper.selectById(1002L)).thenReturn(new VoucherOrder().setId(1002L));

        // 不应抛异常
        assertDoesNotThrow(() -> txService.handleRealOrderCreation(order));

        // 没有执行 stock 扣减
        verifyNoInteractions(seckillVoucherService);
        // 没有触发支付
        verifyNoInteractions(paymentSimulationService);

        // afterCommit 触发后清 pending
        fireAfterCommit();
        verify(stringRedisTemplate).delete("seckill:pending:1:1002");
    }

    @Test
    void handleRealOrderCreation_duplicateActiveOrder_restoresRedisStockAndKeepsUserSet() {
        VoucherOrder order = new VoucherOrder().setId(1004L).setUserId(7L).setVoucherId(1L);
        when(voucherOrderMapper.insert(order))
                .thenThrow(new DuplicateKeyException("uk_user_voucher_active"));
        when(voucherOrderMapper.selectById(1004L)).thenReturn(null);
        when(voucherOrderMapper.selectCount(any())).thenReturn(1L);

        assertDoesNotThrow(() -> txService.handleRealOrderCreation(order));

        verifyNoInteractions(seckillVoucherService);
        verifyNoInteractions(paymentSimulationService);
        verify(stringRedisTemplate, never()).delete("seckill:pending:1:1004");

        fireAfterCommit();

        verify(stringRedisTemplate).execute(
                any(RedisScript.class),
                eq(java.util.Arrays.asList(
                        "seckill:stock:1",
                        "seckill:order:1",
                        "seckill:pending:1:1004",
                        "seckill:pending:index")),
                eq("7"), eq("1"), eq("1004"));
    }

    @Test
    void handleRealOrderCreation_stockZero_throwsAndNoAfterCommit() {
        VoucherOrder order = new VoucherOrder().setId(1003L).setUserId(7L).setVoucherId(1L);
        when(voucherOrderMapper.insert(order)).thenReturn(1);
        mockSeckillUpdate(false); // DB stock = 0 → update 返 0 行

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> txService.handleRealOrderCreation(order));
        assertTrue(ex.getMessage().contains("DB stock 不足"));

        // 不应注册 afterCommit
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
    }

    @Test
    void handleRealOrderCreation_failedRowExists_skipsWithoutDbInsert() {
        VoucherOrder order = new VoucherOrder().setId(1005L).setUserId(7L).setVoucherId(1L);
        when(orderFailedMapper.selectById(1005L)).thenReturn(new OrderFailed().setOrderId(1005L));

        txService.handleRealOrderCreation(order);

        verify(voucherOrderMapper, never()).insert(any(VoucherOrder.class));
        verifyNoInteractions(seckillVoucherService, paymentSimulationService);
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
    }

    @Test
    void handleRealOrderCreation_dltFenceExists_skipsWithoutDbInsert() {
        VoucherOrder order = new VoucherOrder().setId(1006L).setUserId(7L).setVoucherId(1L);
        when(orderFailedMapper.selectById(1006L)).thenReturn(null);
        when(stringRedisTemplate.hasKey("seckill:dlt:fence:1006")).thenReturn(true);

        txService.handleRealOrderCreation(order);

        verify(voucherOrderMapper, never()).insert(any(VoucherOrder.class));
        verifyNoInteractions(seckillVoucherService, paymentSimulationService);
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
    }

    // ---------- cancelUnpaidOrder ----------

    @Test
    void cancelUnpaidOrder_happyPath_registersAfterCommitReleaseLua() {
        Long orderId = 2001L;
        Long userId = 9L;
        VoucherOrder order = new VoucherOrder().setId(orderId).setUserId(userId).setVoucherId(1L);
        when(voucherOrderMapper.selectById(orderId)).thenReturn(order);
        when(voucherOrderMapper.update(any(), any())).thenReturn(1);
        mockSeckillUpdate(true);

        boolean ok = txService.cancelUnpaidOrder(orderId, userId);
        assertTrue(ok);

        verify(orderReleaseRetryMapper).insert(any(OrderReleaseRetry.class));
        // 关键：Redis 释放 不应该 在事务内调用
        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));

        // afterCommit 触发后，release Lua 被调用
        fireAfterCommit();
        verify(stringRedisTemplate).execute(any(RedisScript.class), anyList(),
                eq(userId.toString()), eq("1"), eq(orderId.toString()), eq("1"));
        verify(orderReleaseRetryMapper).deleteById(orderId);
    }

    @Test
    void cancelUnpaidOrder_releaseFails_keepsRetryRowForScheduledRetry() {
        Long orderId = 2002L;
        Long userId = 9L;
        VoucherOrder order = new VoucherOrder().setId(orderId).setUserId(userId).setVoucherId(1L);
        when(voucherOrderMapper.selectById(orderId)).thenReturn(order);
        when(voucherOrderMapper.update(any(), any())).thenReturn(1);
        mockSeckillUpdate(true);
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("redis down"));

        boolean ok = txService.cancelUnpaidOrder(orderId, userId);
        assertTrue(ok);

        verify(orderReleaseRetryMapper).insert(any(OrderReleaseRetry.class));
        fireAfterCommit();

        verify(orderReleaseRetryMapper, never()).deleteById(orderId);
    }

    @Test
    void cancelUnpaidOrder_orderNotFound_returnsFalseWithNoSideEffects() {
        when(voucherOrderMapper.selectById(3001L)).thenReturn(null);

        boolean ok = txService.cancelUnpaidOrder(3001L, 9L);
        assertFalse(ok);

        verify(voucherOrderMapper, never()).update(any(), any());
        verifyNoInteractions(seckillVoucherService);
        verifyNoInteractions(orderReleaseRetryMapper);
        fireAfterCommit();
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void cancelUnpaidOrder_wrongUser_returnsFalse() {
        VoucherOrder order = new VoucherOrder().setId(3002L).setUserId(10L).setVoucherId(1L);
        when(voucherOrderMapper.selectById(3002L)).thenReturn(order);

        boolean ok = txService.cancelUnpaidOrder(3002L, 9L);
        assertFalse(ok);
        verify(voucherOrderMapper, never()).update(any(), any());
    }

    @Test
    void cancelUnpaidOrder_statusCasFails_returnsFalseAndNoAfterCommit() {
        VoucherOrder order = new VoucherOrder().setId(3003L).setUserId(9L).setVoucherId(1L);
        when(voucherOrderMapper.selectById(3003L)).thenReturn(order);
        // 状态已经不是 1，CAS update 返回 0
        when(voucherOrderMapper.update(any(), any())).thenReturn(0);

        boolean ok = txService.cancelUnpaidOrder(3003L, 9L);
        assertFalse(ok);

        verifyNoInteractions(seckillVoucherService);
        verifyNoInteractions(orderReleaseRetryMapper);
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
    }

    @Test
    void cancelUnpaidOrder_stockRestoreFails_throwsAndNoAfterCommit() {
        VoucherOrder order = new VoucherOrder().setId(3004L).setUserId(9L).setVoucherId(1L);
        when(voucherOrderMapper.selectById(3004L)).thenReturn(order);
        when(voucherOrderMapper.update(any(), any())).thenReturn(1);
        mockSeckillUpdate(false); // stock+1 失败

        assertThrows(IllegalStateException.class,
                () -> txService.cancelUnpaidOrder(3004L, 9L));

        // 关键：异常发生时，afterCommit 不应注册（否则会释放本不该释放的名额）
        verifyNoInteractions(orderReleaseRetryMapper);
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
    }

    // ---------- retryPendingRedisReleases ----------

    @Test
    void retryPendingRedisReleases_canceledOrder_releasesAndDeletesRetryRow() {
        OrderReleaseRetry row = new OrderReleaseRetry()
                .setOrderId(5001L)
                .setUserId(9L)
                .setVoucherId(1L);
        when(orderReleaseRetryMapper.selectList(any())).thenReturn(Collections.singletonList(row));
        when(voucherOrderMapper.selectById(5001L))
                .thenReturn(new VoucherOrder().setId(5001L).setUserId(9L).setVoucherId(1L).setStatus(4));

        int released = txService.retryPendingRedisReleases(100);

        assertEquals(1, released);
        verify(stringRedisTemplate).execute(any(RedisScript.class), anyList(),
                eq("9"), eq("1"), eq("5001"), eq("1"));
        verify(orderReleaseRetryMapper).deleteById(5001L);
    }

    @Test
    void retryPendingRedisReleases_activeOrder_deletesStaleRetryWithoutRelease() {
        OrderReleaseRetry row = new OrderReleaseRetry()
                .setOrderId(5002L)
                .setUserId(9L)
                .setVoucherId(1L);
        when(orderReleaseRetryMapper.selectList(any())).thenReturn(Collections.singletonList(row));
        when(voucherOrderMapper.selectById(5002L))
                .thenReturn(new VoucherOrder().setId(5002L).setUserId(9L).setVoucherId(1L).setStatus(1));

        int released = txService.retryPendingRedisReleases(100);

        assertEquals(0, released);
        verify(stringRedisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
        verify(orderReleaseRetryMapper).deleteById(5002L);
    }

    @Test
    void retryPendingRedisReleases_releaseFails_recordsFailureAndKeepsRetryRow() {
        OrderReleaseRetry row = new OrderReleaseRetry()
                .setOrderId(5003L)
                .setUserId(9L)
                .setVoucherId(1L);
        when(orderReleaseRetryMapper.selectList(any())).thenReturn(Collections.singletonList(row));
        when(voucherOrderMapper.selectById(5003L))
                .thenReturn(new VoucherOrder().setId(5003L).setUserId(9L).setVoucherId(1L).setStatus(4));
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("redis down"));

        int released = txService.retryPendingRedisReleases(100);

        assertEquals(0, released);
        verify(orderReleaseRetryMapper).update(eq(null), any());
        verify(orderReleaseRetryMapper, never()).deleteById(5003L);
    }

    // ---------- releaseRedisReservation 直接调用 ----------

    @Test
    void releaseRedisReservation_callsLuaWithExpectedKeysAndArgs() {
        txService.releaseRedisReservation(1L, 9L, 4001L, false);

        verify(stringRedisTemplate).execute(
                any(RedisScript.class),
                eq(java.util.Arrays.asList(
                        "seckill:stock:1",
                        "seckill:order:1",
                        "seckill:pending:1:4001",
                        "seckill:pending:index")),
                eq("9"), eq("1"), eq("4001"), eq("0"));
    }

    @Test
    void releaseRedisReservation_forceFlagPassesAsOne() {
        txService.releaseRedisReservation(1L, 9L, 4002L, true);

        verify(stringRedisTemplate).execute(
                any(RedisScript.class), anyList(),
                eq("9"), eq("1"), eq("4002"), eq("1"));
    }
}
