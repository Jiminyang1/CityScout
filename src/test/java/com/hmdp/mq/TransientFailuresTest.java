package com.hmdp.mq;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.net.ConnectException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransientFailuresTest {

    private static Exception wrapped(Throwable cause) {
        // 消费者抛出的异常到 error handler 时会被包成 ListenerExecutionFailedException
        return new ListenerExecutionFailedException("listener failed", cause);
    }

    @Test
    void cannotOpenTransaction_becauseHikariTimedOut_isTransient() {
        Exception ex = wrapped(new CannotCreateTransactionException("Could not open JDBC Connection",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available")));
        assertTrue(TransientFailures.isTransient(ex));
    }

    @Test
    void mysqlCommunicationsLinkFailure_isTransient() {
        Exception ex = wrapped(new RuntimeException("mapper failed",
                new SQLRecoverableException("Communications link failure", new ConnectException("refused"))));
        assertTrue(TransientFailures.isTransient(ex));
    }

    @Test
    void springDataAccessInfrastructureFailures_areTransient() {
        assertTrue(TransientFailures.isTransient(wrapped(new CannotGetJdbcConnectionException("no connection"))));
        assertTrue(TransientFailures.isTransient(wrapped(new RedisConnectionFailureException("redis down"))));
        assertTrue(TransientFailures.isTransient(wrapped(new QueryTimeoutException("timeout"))));
        assertTrue(TransientFailures.isTransient(wrapped(new CannotAcquireLockException("lock wait timeout"))));
    }

    @Test
    void businessFailures_areNotTransient() {
        assertFalse(TransientFailures.isTransient(wrapped(new IllegalStateException("DB stock 不足"))));
        assertFalse(TransientFailures.isTransient(wrapped(new IllegalArgumentException("invalid payload"))));
        assertFalse(TransientFailures.isTransient(wrapped(new DuplicateKeyException("duplicate"))));
        assertFalse(TransientFailures.isTransient(null));
    }
}
