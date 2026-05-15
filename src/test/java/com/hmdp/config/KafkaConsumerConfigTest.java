package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.utils.RedisConstants;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaConsumerConfigTest {

    private final KafkaConsumerConfig config = new KafkaConsumerConfig();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    void markDltFenceBeforePublish_validOrderEvent_writesFence() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "order.created",
                0,
                42L,
                "7",
                "{\"orderId\":1001,\"userId\":7,\"voucherId\":9,\"requestId\":\"req-1\"}");

        config.markDltFenceBeforePublish(record, objectMapper, stringRedisTemplate);

        verify(valueOperations).set(
                RedisConstants.SECKILL_DLT_FENCE_KEY + 1001L,
                "1",
                RedisConstants.SECKILL_DLT_FENCE_TTL,
                TimeUnit.MINUTES);
    }

    @Test
    void markDltFenceBeforePublish_invalidPayload_doesNotWriteFence() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "order.created",
                0,
                42L,
                "7",
                "not-json");

        config.markDltFenceBeforePublish(record, objectMapper, stringRedisTemplate);

        verify(stringRedisTemplate, never()).opsForValue();
    }
}
