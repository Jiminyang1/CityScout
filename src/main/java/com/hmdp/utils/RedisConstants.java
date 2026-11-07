package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_CODE_COOLDOWN_KEY = "login:code:cooldown:";
    public static final String LOGIN_CODE_DAILY_KEY = "login:code:daily:";
    public static final Long LOGIN_CODE_COOLDOWN_TTL_SECONDS = 60L;
    public static final Long LOGIN_CODE_DAILY_TTL_DAYS = 2L;
    public static final Long LOGIN_CODE_DAILY_LIMIT = 10L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 30L;

    public static final String HEADER_AUTHORIZATION = "authorization";

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_META_KEY = "seckill:meta:";
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    public static final String SECKILL_PENDING_KEY = "seckill:pending:";
    public static final String SECKILL_PENDING_INDEX_KEY = "seckill:pending:index";
    public static final String SECKILL_DLT_FENCE_KEY = "seckill:dlt:fence:";
    public static final Long SECKILL_DLT_FENCE_TTL = 30L;
    public static final String SECKILL_RELEASED_KEY = "seckill:released:";
    public static final Long SECKILL_RELEASED_TTL_SECONDS = 7 * 24 * 3600L;
    /** Lua 返回"未初始化"时按券懒加载 Redis 准入数据的锁；拿不到锁或券不存在时也起到 10 秒节流作用。 */
    public static final String SECKILL_INIT_LOCK_KEY = "lock:seckill:init:";
    public static final Long SECKILL_INIT_LOCK_TTL_SECONDS = 10L;

    /**
     * seckill:pending:index 的 member。带上 userId，pending Hash 丢失时 reconciler 仍能重投或释放名额。
     * 旧格式为 {voucherId}:{orderId}，reconciler 与释放脚本同时兼容两种格式。
     */
    public static String pendingIndexMember(Long voucherId, Long orderId, Long userId) {
        return voucherId + ":" + orderId + ":" + userId;
    }

    public static String legacyPendingIndexMember(Long voucherId, Long orderId) {
        return voucherId + ":" + orderId;
    }

    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
