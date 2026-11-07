package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.utils.RedisConstants;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    // ---------- 重试策略：基础设施暂时不可用 vs 业务失败 ----------

    private static Exception transientFailure() {
        return new ListenerExecutionFailedException("listener failed",
                new CannotCreateTransactionException("Could not open JDBC Connection",
                        new SQLTransientConnectionException("HikariPool-1 - Connection is not available")));
    }

    private static Exception businessFailure() {
        return new ListenerExecutionFailedException("listener failed",
                new IllegalStateException("DB stock 不足，orderId=1001, voucherId=9"));
    }

    @Test
    void backOffFor_transientFailure_isUnboundedExponentialCappedAt30s() {
        BackOff backOff = config.backOffFor(transientFailure());
        assertNotNull(backOff);

        BackOffExecution execution = backOff.start();
        assertEquals(1000L, execution.nextBackOff());
        assertEquals(2000L, execution.nextBackOff());
        long last = 0;
        for (int i = 0; i < 500; i++) {
            last = execution.nextBackOff();
            assertNotEquals(BackOffExecution.STOP, last);
        }
        assertEquals(30000L, last);
    }

    @Test
    void backOffFor_businessFailure_fallsBackToDefaultBackOff() {
        assertNull(config.backOffFor(businessFailure()));
    }

    @Test
    void businessBackOff_allowsThreeRetriesThenStops() {
        BackOffExecution execution = config.businessBackOff().start();
        assertEquals(500L, execution.nextBackOff());
        assertEquals(500L, execution.nextBackOff());
        assertEquals(500L, execution.nextBackOff());
        assertEquals(BackOffExecution.STOP, execution.nextBackOff());
    }

    @Test
    void transientBackOff_withPositiveMaxElapsed_eventuallyStops() {
        ReflectionTestUtils.setField(config, "transientMaxElapsedMs", 5000L);
        BackOffExecution execution = config.transientBackOff().start();
        long next;
        int guard = 0;
        while ((next = execution.nextBackOff()) != BackOffExecution.STOP && guard < 100) {
            guard++;
        }
        assertEquals(BackOffExecution.STOP, next);
        assertTrue(guard < 10);
    }

    /** 还要继续重试：error handler seek 回原 offset，并抛 RecordInRetryException 通知容器。 */
    private static void expectRetry(DefaultErrorHandler handler, Exception ex, ConsumerRecord<String, String> record,
                                    Consumer<String, String> consumer, MessageListenerContainer container) {
        // RecordInRetryException 是 spring-kafka 包内可见类，只能按名字断言
        RuntimeException inRetry = assertThrows(RuntimeException.class,
                () -> handler.handleRemaining(ex, List.of(record), consumer, container));
        assertEquals("RecordInRetryException", inRetry.getClass().getSimpleName());
    }

    /** 重试耗尽：交给 recoverer（生产环境里就是发到 DLT），不再抛异常。 */
    private static void expectRecovered(DefaultErrorHandler handler, Exception ex, ConsumerRecord<String, String> record,
                                        Consumer<String, String> consumer, MessageListenerContainer container) {
        assertDoesNotThrow(() -> handler.handleRemaining(ex, List.of(record), consumer, container));
    }

    private DefaultErrorHandler fastErrorHandler(ConsumerRecordRecoverer recoverer) {
        ReflectionTestUtils.setField(config, "businessRetryIntervalMs", 1L);
        ReflectionTestUtils.setField(config, "transientInitialIntervalMs", 1L);
        ReflectionTestUtils.setField(config, "transientMaxIntervalMs", 2L);
        return config.orderCreatedErrorHandler(recoverer);
    }

    @Test
    @SuppressWarnings("unchecked")
    void errorHandler_dbUnavailable_keepsRetryingAndNeverPublishesToDlt() {
        ConsumerRecordRecoverer recoverer = mock(ConsumerRecordRecoverer.class);
        DefaultErrorHandler handler = fastErrorHandler(recoverer);
        Consumer<String, String> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        lenient().when(container.isRunning()).thenReturn(true);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.created", 0, 10L, "7", "{}");

        // 旧配置下第 4 次失败就会进 DLT；现在 30 次都应该继续 seek 回去重试
        for (int i = 0; i < 30; i++) {
            expectRetry(handler, transientFailure(), record, consumer, container);
        }

        verify(recoverer, never()).accept(any(), any());
        verify(consumer, times(30)).seek(new TopicPartition("order.created", 0), 10L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void errorHandler_businessFailure_goesToDltAfterThreeRetries() {
        ConsumerRecordRecoverer recoverer = mock(ConsumerRecordRecoverer.class);
        DefaultErrorHandler handler = fastErrorHandler(recoverer);
        Consumer<String, String> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        lenient().when(container.isRunning()).thenReturn(true);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.created", 0, 11L, "7", "{}");

        for (int i = 0; i < 3; i++) {
            expectRetry(handler, businessFailure(), record, consumer, container);
        }
        verify(recoverer, never()).accept(any(), any());

        expectRecovered(handler, businessFailure(), record, consumer, container);
        verify(recoverer, times(1)).accept(eq(record), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void errorHandler_dbRecoversThenBusinessFailure_restartsBusinessRetryCount() {
        ConsumerRecordRecoverer recoverer = mock(ConsumerRecordRecoverer.class);
        DefaultErrorHandler handler = fastErrorHandler(recoverer);
        Consumer<String, String> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        lenient().when(container.isRunning()).thenReturn(true);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("order.created", 0, 12L, "7", "{}");

        for (int i = 0; i < 10; i++) {
            expectRetry(handler, transientFailure(), record, consumer, container);
        }
        // DB 恢复后才暴露出真正的业务失败：异常类型变化，按业务策略重新计数（首次 + 3 次重试）
        for (int i = 0; i < 3; i++) {
            expectRetry(handler, businessFailure(), record, consumer, container);
        }
        verify(recoverer, never()).accept(any(), any());

        expectRecovered(handler, businessFailure(), record, consumer, container);
        verify(recoverer, times(1)).accept(eq(record), any());
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
