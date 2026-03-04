package com.hmdp.benchmark.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BenchmarkSeckillResponse {
    private boolean success;
    private String code;
    private Long orderId;
    private String msg;
    private String variant;
    private String requestId;

    public static BenchmarkSeckillResponse ok(String variant, String requestId, Long orderId, String msg) {
        return BenchmarkSeckillResponse.builder()
                .success(true)
                .code("SUCCESS")
                .orderId(orderId)
                .msg(msg)
                .variant(variant)
                .requestId(requestId)
                .build();
    }

    public static BenchmarkSeckillResponse fail(String variant, String requestId, String code, String msg) {
        return BenchmarkSeckillResponse.builder()
                .success(false)
                .code(code)
                .msg(msg)
                .variant(variant)
                .requestId(requestId)
                .build();
    }
}
