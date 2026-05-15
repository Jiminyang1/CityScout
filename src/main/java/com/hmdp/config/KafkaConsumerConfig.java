package com.hmdp.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

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
            KafkaTemplate<String, String> kafkaTemplate) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(partitions);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // 失败 3 次后将原消息写入 <topic>.DLT，保留原 partition
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 3L));
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("Kafka消费重试, topic={}, partition={}, offset={}, attempt={}",
                        record.topic(), record.partition(), record.offset(), deliveryAttempt, ex));

        // 不可恢复异常直接进 DLT，不消耗重试次数
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);

        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
