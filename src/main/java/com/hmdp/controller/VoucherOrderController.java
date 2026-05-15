package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author Jimin
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 秒杀优惠券下单
     *
     * @param voucherId 优惠券id
     * @return 结果
     */
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.SeckillVoucher(voucherId);
    }

    /**
     * 查询当前用户最近的券订单
     *
     * @return 订单列表
     */
    @GetMapping("my")
    public Result queryMyOrders() {
        return voucherOrderService.queryMyOrders();
    }

    /**
     * 查询当前用户的订单状态
     *
     * @param orderId 订单id
     * @return 订单状态
     */
    @GetMapping("{id}")
    public Result queryOrder(@PathVariable("id") Long orderId) {
        return voucherOrderService.queryOrder(orderId);
    }

    /**
     * MVP 前端手动模拟支付成功
     *
     * @param orderId 订单id
     * @return 订单状态
     */
    @PostMapping("{id}/simulate-pay")
    public Result simulatePay(@PathVariable("id") Long orderId) {
        return voucherOrderService.simulatePay(orderId);
    }

    /**
     * 取消未支付订单，并回补库存与 Redis 预扣状态
     *
     * @param orderId 订单id
     * @return 订单状态
     */
    @PostMapping("{id}/cancel")
    public Result cancelOrder(@PathVariable("id") Long orderId) {
        return voucherOrderService.cancelOrder(orderId);
    }
}
