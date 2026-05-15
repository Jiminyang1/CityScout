package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result SeckillVoucher(Long voucherId);

    Result SeckillVoucherWithLua(Long voucherId);

    Result queryOrder(Long orderId);

    Result queryMyOrders();

    Result simulatePay(Long orderId);

    Result cancelOrder(Long orderId);
}
