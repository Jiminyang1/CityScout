package com.hmdp.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.VoucherOrderTxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

@Component
public class OrderCreatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedConsumer.class);

    @Resource
    private VoucherOrderTxService voucherOrderTxService;

    @Resource
    private ObjectMapper objectMapper;

    @KafkaListener(
            topics = "${app.kafka.topic.order-created:order.created}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onOrderCreated(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {
        OrderCreatedEvent event;
        try {
            event = objectMapper.readValue(payload, OrderCreatedEvent.class);
        } catch (Exception e) {
            // 反序列化失败属于不可重试错误，直接抛 IllegalArgumentException
            // → 配合 KafkaConsumerConfig 中 addNotRetryableExceptions 直接进 DLT
            log.error("订单事件反序列化失败, key={}, partition={}, offset={}, payload={}",
                    key, partition, offset, payload, e);
            throw new IllegalArgumentException("invalid order-created payload", e);
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(event.getOrderId());
        voucherOrder.setUserId(event.getUserId());
        voucherOrder.setVoucherId(event.getVoucherId());

        // 业务异常向上传播，由 Spring Kafka 重试 → DLT。不在此处吞异常或 ack。
        voucherOrderTxService.handleRealOrderCreation(voucherOrder);
        acknowledgment.acknowledge();
    }
}
