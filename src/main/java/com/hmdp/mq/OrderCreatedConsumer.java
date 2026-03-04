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

import javax.annotation.Resource;

@Component
public class OrderCreatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderCreatedConsumer.class);

    @Resource
    private VoucherOrderTxService voucherOrderTxService;

    @Resource
    private ObjectMapper objectMapper;

    @KafkaListener(
            topics = {
                    "${app.kafka.topic.order-created:order.created}",
                    "${benchmark.kafka.topic:order.created.bench}"
            },
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onOrderCreated(
            @Payload String payload,
            @Header(KafkaHeaders.RECEIVED_MESSAGE_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_PARTITION_ID) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {
        try {
            OrderCreatedEvent event = objectMapper.readValue(payload, OrderCreatedEvent.class);
            VoucherOrder voucherOrder = new VoucherOrder();
            voucherOrder.setId(event.getOrderId());
            voucherOrder.setUserId(event.getUserId());
            voucherOrder.setVoucherId(event.getVoucherId());

            voucherOrderTxService.handleRealOrderCreation(voucherOrder);
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("消费订单事件失败, key={}, partition={}, offset={}, payload={}",
                    key, partition, offset, payload, e);
            throw new IllegalStateException("consume order-created event failed", e);
        }
    }
}
