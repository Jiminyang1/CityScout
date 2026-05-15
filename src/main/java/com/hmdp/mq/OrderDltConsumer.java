package com.hmdp.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.OrderFailed;
import com.hmdp.service.impl.OrderDltProcessingService;
import com.hmdp.utils.RedisConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 死信消费：消息处理 N 次失败后由 DeadLetterPublishingRecoverer 转发到这里。
 * 落 tb_order_failed + 释放 Redis 名额 + 告警 log。
 */
@Component
public class OrderDltConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderDltConsumer.class);

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private OrderDltProcessingService orderDltProcessingService;

    @KafkaListener(
            topics = "${app.kafka.topic.order-created-dlt:order.created.DLT}",
            groupId = "${spring.kafka.consumer.group-id}-dlt",
            containerFactory = "dltKafkaListenerContainerFactory")
    public void onDeadLetter(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            @Header(name = KafkaHeaders.DLT_EXCEPTION_FQCN, required = false) byte[] excClass,
            @Header(name = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false) byte[] excMessage,
            Acknowledgment acknowledgment) {
        String reason = buildReason(excClass, excMessage);
        log.error("订单进入死信, key={}, partition={}, offset={}, reason={}, payload={}",
                key, partition, offset, reason, payload);

        OrderCreatedEvent event;
        try {
            event = objectMapper.readValue(payload, OrderCreatedEvent.class);
        } catch (Exception e) {
            // 连 DLT 都解析不出来：只能 ack 跳过 + 留 log，没法做后续处理
            log.error("DLT 消息无法解析，跳过, key={}, payload={}", key, payload, e);
            acknowledgment.acknowledge();
            return;
        }

        OrderFailed failed = new OrderFailed()
                .setOrderId(event.getOrderId())
                .setUserId(event.getUserId())
                .setVoucherId(event.getVoucherId())
                .setRequestId(event.getRequestId())
                .setPayload(payload)
                .setReason(reason.length() > 500 ? reason.substring(0, 500) : reason);

        markDltFence(event);
        orderDltProcessingService.persistFailedAndReleaseAfterCommit(failed);

        acknowledgment.acknowledge();
    }

    private void markDltFence(OrderCreatedEvent event) {
        String fenceKey = RedisConstants.SECKILL_DLT_FENCE_KEY + event.getOrderId();
        stringRedisTemplate.opsForValue().set(
                fenceKey,
                "1",
                RedisConstants.SECKILL_DLT_FENCE_TTL,
                TimeUnit.MINUTES);
    }

    private String buildReason(byte[] excClass, byte[] excMessage) {
        String cls = excClass == null ? "unknown" : new String(excClass, StandardCharsets.UTF_8);
        String msg = excMessage == null ? "" : new String(excMessage, StandardCharsets.UTF_8);
        return cls + ": " + msg;
    }
}
