package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderTimeoutServiceTest {

    @Mock
    private VoucherOrderTxService voucherOrderTxService;

    @InjectMocks
    private OrderTimeoutService orderTimeoutService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(orderTimeoutService, "unpaidTimeoutMinutes", 15L);
        ReflectionTestUtils.setField(orderTimeoutService, "timeoutScanLimit", 100);
    }

    @Test
    void cancelExpiredUnpaidOrders_cancelsEveryExpiredOrder() {
        VoucherOrder first = new VoucherOrder().setId(101L).setUserId(11L);
        VoucherOrder second = new VoucherOrder().setId(102L).setUserId(12L);
        when(voucherOrderTxService.listExpiredUnpaidOrders(any(), eq(100)))
                .thenReturn(Arrays.asList(first, second));
        when(voucherOrderTxService.cancelUnpaidOrder(101L, 11L)).thenReturn(true);
        when(voucherOrderTxService.cancelUnpaidOrder(102L, 12L)).thenReturn(true);

        orderTimeoutService.cancelExpiredUnpaidOrders();

        verify(voucherOrderTxService).cancelUnpaidOrder(101L, 11L);
        verify(voucherOrderTxService).cancelUnpaidOrder(102L, 12L);
    }

    @Test
    void cancelExpiredUnpaidOrders_continuesAfterCancellationThrows() {
        VoucherOrder first = new VoucherOrder().setId(201L).setUserId(21L);
        VoucherOrder second = new VoucherOrder().setId(202L).setUserId(22L);
        when(voucherOrderTxService.listExpiredUnpaidOrders(any(), eq(100)))
                .thenReturn(Arrays.asList(first, second));
        when(voucherOrderTxService.cancelUnpaidOrder(201L, 21L))
                .thenThrow(new RuntimeException("database unavailable"));
        when(voucherOrderTxService.cancelUnpaidOrder(202L, 22L)).thenReturn(true);

        orderTimeoutService.cancelExpiredUnpaidOrders();

        verify(voucherOrderTxService).cancelUnpaidOrder(201L, 21L);
        verify(voucherOrderTxService).cancelUnpaidOrder(202L, 22L);
    }

    @Test
    void cancelExpiredUnpaidOrders_usesConfiguredScanLimit() {
        ReflectionTestUtils.setField(orderTimeoutService, "timeoutScanLimit", 37);
        when(voucherOrderTxService.listExpiredUnpaidOrders(any(), eq(37)))
                .thenReturn(Collections.emptyList());

        orderTimeoutService.cancelExpiredUnpaidOrders();

        verify(voucherOrderTxService).listExpiredUnpaidOrders(any(), eq(37));
    }
}
