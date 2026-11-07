package com.hmdp.mq;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;

/**
 * 区分"基础设施暂时不可用"和"业务上真的失败"。
 *
 * 秒杀消息在 Lua 预扣成功后才会发出，用户已经被告知抢到了名额。
 * 如果只是 MySQL / Redis 短暂不可用（重启、主从切换、连接池耗尽、锁等待超时），
 * 不应该在几秒内把订单打进 DLT 判死刑，而应该退避重试，等依赖恢复后照常落库。
 * 只有业务失败（例如 DB 库存漂移抛出的 IllegalStateException）才走"有限重试 → DLT"。
 */
public final class TransientFailures {

    private TransientFailures() {
    }

    public static boolean isTransient(Throwable throwable) {
        Throwable cursor = throwable;
        int depth = 0;
        while (cursor != null && depth++ < 32) {
            if (cursor instanceof TransientDataAccessException          // 锁等待超时、死锁、查询超时等
                    || cursor instanceof RecoverableDataAccessException
                    || cursor instanceof DataAccessResourceFailureException // 拿不到 JDBC 连接、Redis 连接失败
                    || cursor instanceof CannotCreateTransactionException   // @Transactional 开事务时连不上库
                    || cursor instanceof SQLTransientException              // 含 Hikari 连接获取超时
                    || cursor instanceof SQLRecoverableException            // 含 MySQL Communications link failure
                    || cursor instanceof ConnectException
                    || cursor instanceof SocketTimeoutException) {
                return true;
            }
            if (cursor.getCause() == cursor) {
                break;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
