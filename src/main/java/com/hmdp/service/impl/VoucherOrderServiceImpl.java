package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private RedissonClient redissonClient;

    // 导入lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new org.springframework.core.io.ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 创建阻塞队列
    private BlockingQueue<VoucherOrder> ordersTaskQueue = new ArrayBlockingQueue<>(
            1024 * 1024 // 队列容量为1024 * 1024, 代表 1024 * 1024个订单
    );
    // 创建单线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = java.util.concurrent.Executors
            .newSingleThreadExecutor();

    @PostConstruct // 在构造方法执行完后立即执行
    private void init() {
        // 启动一个线程,从阻塞队列中获取订单信息,并异步处理
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * 处理订单的线程
     */
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 获取订单信息
                    VoucherOrder voucherOrder = ordersTaskQueue.take(); // 阻塞获取
                    // 2. 处理订单
                    IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
                    proxy.handleRealOrderCreation(voucherOrder);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // 恢复中断状态
                    break; // 退出循环
                } catch (Exception e) {
                    log.error("处理订单异常: ", e);
                }
            }
        }
    }

    @Transactional // 确保数据库操作的原子性
    @Override
    public void handleRealOrderCreation(VoucherOrder voucherOrder) {
        // 1. 扣减数据库库存 (这是必须的，作为最终的库存确认)
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0) // CAS: WHERE stock > 0
                .update();
        if (!success) {
            // 数据库库存不足或并发导致扣减失败
            log.warn("数据库扣减库存失败, 订单ID: {}" + voucherOrder.getVoucherId());
            // 此处需要考虑补偿逻辑：
            // 1. 标记订单为失败状态。
            // 2. (重要但复杂) 尝试恢复Redis中预扣的库存和移除用户的购买记录。
            // 这可能需要另一个Lua脚本或补偿任务。
            // 例如：stringRedisTemplate.opsForValue().increment("stock:vid:" +
            // voucherOrder.getVoucherId());
            // stringRedisTemplate.opsForSet().remove("order:vid:" +
            // voucherOrder.getVoucherId(), voucherOrder.getUserId().toString());
            // 但这些补偿操作也需要考虑原子性和幂等性。
            return; // 或者抛出特定异常由上层处理
        }
        // 2. (可选的最终校验) "一人一单"在数据库层面校验 (如果非常担心Redis与DB的短暂不一致)

        // 3. 创建并保存订单到数据库
        // voucherOrder 对象已经包含了ID, userId, voucherId
        save(voucherOrder); // ServiceImpl的save方法
        log.debug("秒杀优惠券订单创建成功，订单ID: " + voucherOrder.getId()
                + ", 用户ID: " + voucherOrder.getUserId()
                + ", 券ID: " + voucherOrder.getVoucherId());
    }

    public Result SeckillVoucherWithLua(Long voucherId) {
        // 1. 使用lua脚本原子性判读用户是否有下单资格
        Long userId = UserHolder.getUser().getId();
        Long luaFlag = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(), // 这里传入的key列表为空，因为我们在脚本中使用了动态的key
                voucherId.toString(), // voucherId
                userId.toString()// userId
        );
        // 2. 判断结果
        if (luaFlag.intValue() != 0) {
            // 如果lua脚本返回值为null或非0，表示下单失败
            if (luaFlag.intValue() == 1) {
                return Result.fail("库存不足");
            } else if (luaFlag.intValue() == 2) {
                return Result.fail("用户已下单");
            } else {
                return Result.fail("下单失败");
            }
        }
        // 3. 生成订单ID
        VoucherOrder voucherOrder = new VoucherOrder();
        long order = redisIdWorker.nextId("order");
        voucherOrder.setId(order);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 4. 放入阻塞队列
        ordersTaskQueue.add(voucherOrder);
        return Result.ok(order);
    }

    // 使用分布式锁实现秒杀下单---

    @Override
    public Result SeckillVoucher(Long voucherId) {
        // 1. 查询秒杀券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        // 2. 判断秒杀券是否存在
        if (seckillVoucher == null) {
            return Result.fail("秒杀券不存在");
        }
        // 3. 判断秒杀券是否在有效期内
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀未开始");
        }
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }
        // 4. 判断库存是否充足
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        Long userId = UserHolder.getUser().getId();
        // synchronized (userId.toString().intern()){
        // //5. 获取代理对象(事务)
        // IVoucherOrderService proxy = (IVoucherOrderService)
        // AopContext.currentProxy();
        // return proxy.d(voucherId);
        // }
        // 集群环境下使用分布式锁
        // 5. 创建分布式锁
        String lockName = "order:" + userId; // key = "lock:order:" + userId
        // 锁定范围是当前用户
        // SimpleRedisLock simpleRedisLock = new SimpleRedisLock(lockName,
        // stringRedisTemplate);
        RLock lock = redissonClient.getLock(lockName);
        // 6. 尝试获取锁
        boolean success = lock.tryLock(); // 看门狗“自动处理”
        // 如果获取成功：Redisson会使用一个默认的租约期（通常是30秒）。
        // 并且，默认情况下，看门狗会被激活。 看门狗会定期（例如每隔 默认租约期 / 3，即10秒左右）检查持有锁的客户端（你的服务实例）是否还存活。
        // 如果存活，它会自动延长锁的过期时间（续期到又一个30秒）。
        // 这样，只要你的服务实例和持有锁的线程正常运行，锁就不会因为超时而意外释放，即使业务操作耗时较长。
        // 如果获取失败：立即返回 false
        if (!success) {
            // 根据业务逻辑来决定是否重试,这里是抢购场景,所以不重试
            return Result.fail("用户已下单");
        }
        // 如果获取锁成功,则执行下单操作
        try {
            // 7. 获取代理对象(事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            // 8. 执行下单
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 9. 释放锁
            lock.unlock();
        }
    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 一人一单优化
        Long userId = UserHolder.getUser().getId();
        // 这里使用了String的intern方法来获取锁，确保同一个用户在同一时刻只能有一个线程在执行
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("用户已下单");
        }

        // 5. 扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0) // 只更新大于0的库存
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }
        // 6. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long order = redisIdWorker.nextId("order");
        voucherOrder.setId(order);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 7. 保存订单
        save(voucherOrder);
        // 8. 返回订单id
        return Result.ok(order);
    }
}
