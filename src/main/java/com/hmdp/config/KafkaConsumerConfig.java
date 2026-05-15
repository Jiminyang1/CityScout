package com.hmdp.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.mq.OrderCreatedEvent;
import com.hmdp.utils.RedisConstants;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.concurrent.TimeUnit;

@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Value("${app.kafka.topic.order-created:order.created}")
    private String orderCreatedTopic;

    @Value("${app.kafka.topic.order-created-dlt:order.created.DLT}")
    private String orderCreatedDltTopic;

    @Value("${app.kafka.topic.partitions:8}")
    private int partitions;

    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name(orderCreatedTopic)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic orderCreatedDltTopic() {
        return TopicBuilder.name(orderCreatedDltTopic)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            StringRedisTemplate stringRedisTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(partitions);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // 失败 3 次后将原消息写入 <topic>.DLT，保留原 partition
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> {
                    markDltFenceBeforePublish(record, objectMapper, stringRedisTemplate);
                    return new TopicPartition(orderCreatedDltTopic, record.partition());
                });

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 3L));
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("Kafka消费重试, topic={}, partition={}, offset={}, attempt={}",
                        record.topic(), record.partition(), record.offset(), deliveryAttempt, ex));

        // 不可恢复异常直接进 DLT，不消耗重试次数
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);

        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    void markDltFenceBeforePublish(ConsumerRecord<?, ?> record,
                                   ObjectMapper objectMapper,
                                   StringRedisTemplate stringRedisTemplate) {
        Object rawValue = record.value();
        if (!(rawValue instanceof String payload)) {
            log.warn("DLT publish 前无法写 fence，消息体不是字符串, topic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset());
            return;
        }

        try {
            OrderCreatedEvent event = objectMapper.readValue(payload, OrderCreatedEvent.class);
            if (event.getOrderId() == null) {
                log.warn("DLT publish 前无法写 fence，orderId 为空, topic={}, partition={}, offset={}",
                        record.topic(), record.partition(), record.offset());
                return;
            }
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.SECKILL_DLT_FENCE_KEY + event.getOrderId(),
                    "1",
                    RedisConstants.SECKILL_DLT_FENCE_TTL,
                    TimeUnit.MINUTES);
        } catch (JsonProcessingException e) {
            log.warn("DLT publish 前无法写 fence，消息体无法解析, topic={}, partition={}, offset={}, error={}",
                    record.topic(), record.partition(), record.offset(), e.toString());
        } catch (Exception e) {
            log.warn("DLT publish 前写 fence 失败，继续发布 DLT, topic={}, partition={}, offset={}",
                    record.topic(), record.partition(), record.offset(), e);
        }
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> dltKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(partitions);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // DLT 消费失败不能再进入 <topic>.DLT.DLT。这里持续重试，保留 offset，直到 DB/Redis 恢复。
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(new FixedBackOff(1000L, Long.MAX_VALUE));
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("Kafka DLT 消费重试, topic={}, partition={}, offset={}, attempt={}",
                        record.topic(), record.partition(), record.offset(), deliveryAttempt, ex));
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
