package com.hmdp.service.impl;

import com.hmdp.entity.OrderFailed;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.OrderFailedMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderDltProcessingServiceTest {

    @InjectMocks
    private OrderDltProcessingService service;

    @Mock
    private OrderFailedMapper orderFailedMapper;

    @Mock
    private VoucherOrderMapper voucherOrderMapper;

    @Mock
    private VoucherOrderTxService voucherOrderTxService;

    @Test
    void persistFailedAndReleaseAfterCommit_orderAlreadyExists_skipsFailedAndRelease() {
        OrderFailed failed = failed();
        when(voucherOrderMapper.selectById(failed.getOrderId())).thenReturn(new VoucherOrder());

        service.persistFailedAndReleaseAfterCommit(failed);

        verify(voucherOrderMapper).selectById(failed.getOrderId());
        verifyNoInteractions(orderFailedMapper, voucherOrderTxService);
    }

    @Test
    void persistFailedAndReleaseAfterCommit_newFailure_insertsAndReleases() {
        OrderFailed failed = failed();

        service.persistFailedAndReleaseAfterCommit(failed);

        verify(orderFailedMapper).insert(failed);
        verify(voucherOrderTxService).releaseRedisReservation(
                failed.getVoucherId(),
                failed.getUserId(),
                failed.getOrderId(),
                false);
    }

    @Test
    void persistFailedAndReleaseAfterCommit_duplicateFailure_stillReleases() {
        OrderFailed failed = failed();
        when(orderFailedMapper.insert(failed)).thenThrow(new DuplicateKeyException("duplicate"));

        service.persistFailedAndReleaseAfterCommit(failed);

        verify(voucherOrderTxService).releaseRedisReservation(
                failed.getVoucherId(),
                failed.getUserId(),
                failed.getOrderId(),
                false);
    }

    private OrderFailed failed() {
        return new OrderFailed()
                .setOrderId(1001L)
                .setUserId(7L)
                .setVoucherId(9L)
                .setRequestId("req-1")
                .setPayload("{}")
                .setReason("db failed");
    }
}
