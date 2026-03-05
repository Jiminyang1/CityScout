package com.hmdp.benchmark.controller;

import com.hmdp.benchmark.dto.BenchmarkSeckillResponse;
import com.hmdp.benchmark.service.BenchmarkSeckillStrategy;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/benchmark/voucher-order")
public class BenchmarkVoucherOrderController {

    @Resource
    private BenchmarkSeckillStrategy benchmarkSeckillStrategy;

    @PostMapping("/seckill/{variant}/{voucherId}")
    public BenchmarkSeckillResponse seckill(@PathVariable("variant") String variant,
                                            @PathVariable("voucherId") Long voucherId) {
        String normalizedVariant = variant == null ? "" : variant.trim().toUpperCase();
        String requestId = java.util.UUID.randomUUID().toString();

        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) {
            return BenchmarkSeckillResponse.fail(normalizedVariant, requestId, "UNAUTHORIZED", "用户未登录");
        }
        Long userId = user.getId();

        switch (normalizedVariant) {
            case "A":
                return benchmarkSeckillStrategy.runA(voucherId, userId);
            case "B":
                return benchmarkSeckillStrategy.runB(voucherId, userId);
            case "C":
                return benchmarkSeckillStrategy.runC(voucherId, userId);
            case "D":
                return benchmarkSeckillStrategy.runD(voucherId, userId);
            default:
                return BenchmarkSeckillResponse.fail(normalizedVariant, requestId,
                        "INVALID_VARIANT", "variant must be A/B/C/D");
        }
    }
}
