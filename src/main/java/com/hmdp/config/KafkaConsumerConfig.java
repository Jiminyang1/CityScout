package com.hmdp.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.mq.OrderCreatedEvent;
import com.hmdp.mq.TransientFailures;
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
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
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

    /** 业务失败：首次 + 3 次重试（间隔 500ms）后进 DLT。 */
    @Value("${app.kafka.consumer.business-retry.interval-ms:500}")
    private long businessRetryIntervalMs = 500L;

    @Value("${app.kafka.consumer.business-retry.max-retries:3}")
    private long businessRetryMaxRetries = 3L;

    /** 基础设施暂时不可用（DB/Redis 连不上、锁超时等）：指数退避，默认不设上限，等依赖恢复。 */
    @Value("${app.kafka.consumer.transient-retry.initial-interval-ms:1000}")
    private long transientInitialIntervalMs = 1000L;

    @Value("${app.kafka.consumer.transient-retry.multiplier:2.0}")
    private double transientMultiplier = 2.0;

    @Value("${app.kafka.consumer.transient-retry.max-interval-ms:30000}")
    private long transientMaxIntervalMs = 30000L;

    /** 小于等于 0 表示不限时长；正数表示累计退避超过该毫秒数后仍按业务失败进 DLT。 */
    @Value("${app.kafka.consumer.transient-retry.max-elapsed-ms:0}")
    private long transientMaxElapsedMs = 0L;

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

        // 业务失败：首次 + 3 次重试后将原消息写入 <topic>.DLT，保留原 partition
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> {
                    markDltFenceBeforePublish(record, objectMapper, stringRedisTemplate);
                    return new TopicPartition(orderCreatedDltTopic, record.partition());
                });

        DefaultErrorHandler errorHandler = orderCreatedErrorHandler(recoverer);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    DefaultErrorHandler orderCreatedErrorHandler(ConsumerRecordRecoverer recoverer) {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, businessBackOff());
        // DB / Redis 暂时不可用时不要几秒内就进 DLT：Lua 已经承诺了名额，应等依赖恢复后照常落库。
        // 异常类型变化时（例如 DB 恢复后变成真正的业务异常）Spring Kafka 会重置重试状态，改用对应的 BackOff。
        errorHandler.setBackOffFunction((record, ex) -> backOffFor(ex));
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("Kafka消费重试, topic={}, partition={}, offset={}, attempt={}, transient={}",
                        record.topic(), record.partition(), record.offset(), deliveryAttempt,
                        TransientFailures.isTransient(ex), ex));

        // 不可恢复异常直接进 DLT，不消耗重试次数
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
        return errorHandler;
    }

    BackOff businessBackOff() {
        return new FixedBackOff(businessRetryIntervalMs, businessRetryMaxRetries);
    }

    BackOff transientBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(transientInitialIntervalMs, transientMultiplier);
        backOff.setMaxInterval(transientMaxIntervalMs);
        if (transientMaxElapsedMs > 0) {
            backOff.setMaxElapsedTime(transientMaxElapsedMs);
        }
        return backOff;
    }

    /** 返回 null 表示使用 DefaultErrorHandler 的默认（业务）BackOff。 */
    BackOff backOffFor(Exception ex) {
        return TransientFailures.isTransient(ex) ? transientBackOff() : null;
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
