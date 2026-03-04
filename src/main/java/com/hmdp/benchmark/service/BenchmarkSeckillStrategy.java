package com.hmdp.benchmark.service;

import com.hmdp.benchmark.dto.BenchmarkSeckillResponse;

public interface BenchmarkSeckillStrategy {

    BenchmarkSeckillResponse runA(Long voucherId, Long userId);

    BenchmarkSeckillResponse runB(Long voucherId, Long userId);

    BenchmarkSeckillResponse runC(Long voucherId, Long userId);

    BenchmarkSeckillResponse runD(Long voucherId, Long userId);
}
