package com.hmdp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler((record, ex) ->
                log.error("Kafka消息进入兜底处理, topic={}, partition={}, offset={}, key={}, value={}",
                        record.topic(), record.partition(), record.offset(), record.key(), record.value(), ex),
                new FixedBackOff(500L, 3L));
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("Kafka消费重试, topic={}, partition={}, offset={}, attempt={}",
                        record.topic(), record.partition(), record.offset(), deliveryAttempt, ex));
        errorHandler.setCommitRecovered(true);
        errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);

        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
