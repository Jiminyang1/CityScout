package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * // * 服务实现类
 * </p>
 *
 * @author Jimin
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getByIdWithCache(Long id) {
        // 1.从Redis中查询商铺缓存
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id; // 商铺缓存的key
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey); // 使用Redis的String类型存储商铺信息, 返回的是JSON字符串

        if (StrUtil.isNotBlank(shopJson)) {
            // 不是 null。
            // 不是空字符串（""）。
            // 不是仅包含空白字符（如空格、制表符等）
            log.debug("从Redis中查询商铺信息");
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        // 1)缓存穿透，说明Redis中存在该商铺信息
        // 到这里说明Redis中不存在该商铺信息或者shopJson是空字符串或者null, 如果不是
        if (shopJson != null) {
            // 如果Redis中存在空值，说明数据库中不存在该商铺
            return Result.fail("商铺不存在");
        }

        // 2)缓存击穿
        // 缓存中没有，需要查询数据库，尝试获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        String clientId = UUID.randomUUID().toString(); // 生成一个唯一的锁标识
        Shop shop = null;
        try {
            Boolean acquire = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, clientId, 10, TimeUnit.SECONDS);
            // 如果获取锁失败，说明有其他线程在查询数据库
            if (!Boolean.TRUE.equals(acquire)) {
                // 等待一段时间后重试
                log.debug("获取锁失败，等待重试");
                Thread.sleep(50);
                return getByIdWithCache(id);
            }
            // 获取锁成功，再次检查缓存 (Double Check Lock)
            // 因为可能在等待锁的过程中，其他线程已经查询并缓存了数据
            shopJson = stringRedisTemplate.opsForValue().get(shopKey);
            if (StrUtil.isNotBlank(shopJson)) {
                log.debug("从Redis中查询商铺信息");
                Shop shop1 = JSONUtil.toBean(shopJson, Shop.class);
                return Result.ok(shop1);
            }
            if (shopJson != null) {
                // 如果Redis中存在空值，说明数据库中不存在该商铺
                return Result.fail("商铺不存在 in double check");
            }
            // 3.如果不存在，根据id查询数据库
            log.debug("从数据库中查询商铺信息");
            shop = getById(id);
            // 4.如果数据库不存在，返回错误信息
            if (shop == null) {
                // 将空值写入Redis，避免缓存穿透
                stringRedisTemplate.opsForValue().set(shopKey, "", 2, TimeUnit.MINUTES);
                return Result.fail("商铺不存在");
            }
            // 5.如果数据库存在，将数据写入Redis
            // 将对象转换为JSON字符串
            log.debug("将商铺信息写入Redis: {}" + shopKey);
            shopJson = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(shopKey, shopJson, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES); // 将商铺信息存入Redis
            // 6.返回商铺信息
            return Result.ok(shop);
        } catch (InterruptedException e) {
            // 处理异常
            Thread.currentThread().interrupt(); // 恢复中断状态
            log.error("线程被中断", e);
            return Result.fail("线程被中断, 请重试");
        } catch (Exception e) {
            // 处理其他异常
            log.error("获取商铺信息失败", e);
            return Result.fail("获取商铺信息失败");
        } finally {
            // 7.释放锁
            // 只有clientId持有锁才能释放锁
            if (clientId.equals(stringRedisTemplate.opsForValue().get(lockKey))) {
                stringRedisTemplate.delete(lockKey);
                log.debug("释放锁成功");
            }
        }
    }

    @Override
    public Result getByIdWithCacheInLogicalExpired(Long id) {
        // 1. 从Redis中查询商铺缓存
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id; // 商铺缓存的key
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey); // 使用Redis的String类型存储商铺信息, 返回的是JSON字符串
        // 2. 判断缓存是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 缓存不存在, 返回空
            return Result.fail("商铺不存在");
        }
        // 3.存在
        // 将JSON字符串转换为RedisData对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        // 4.判断逻辑过期时间是否过期
        LocalDateTime expireTime = redisData.getExpireTime();
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class); // 将JSON字符串转换为Shop对象
        // 5.判断逻辑过期时间是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 没有过期, 返回商铺信息
            return Result.ok(shop);
        }
        // 6.过期, 需要更新缓存
        // 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        String clientId = UUID.randomUUID().toString(); // 生成一个唯一的锁标识

        Boolean acquire = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, clientId, 10, TimeUnit.SECONDS);

        // 获取锁成功, 我们开启独立线程更新缓存, 然后自身返回Redis中的数据
        if (Boolean.TRUE.equals(acquire)) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShopToRedis(id, 10L);
                } catch (Exception e) {
                    log.error("更新缓存失败", e);
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    if (clientId.equals(stringRedisTemplate.opsForValue().get(lockKey))) {
                        stringRedisTemplate.delete(lockKey);
                        log.debug("释放锁成功");
                    }
                }
            });
        }

        // 如果获取锁失败，说明有其他线程在查询数据库, 我们直接返回Redis中的数据
        return Result.ok(shop);
    }

    public void saveShopToRedis(Long id, Long expireMinutes) {
        // 1. 查询商铺信息
        Shop shop = getById(id);

        // 2. 封装RedisData对象, 因为我们要将商铺信息和过期时间一起存入Redis
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(expireMinutes));

        // 3. 将封装RedisData对象写入Redis
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(redisData));
        // 我们封装了逻辑过期时间, 所以这里不需要设置过期时间
    }

    @Override
    @Transactional
    public Result updateByIdWithCache(Shop shop) {
        log.debug("更新商铺信息");
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空");
        }
        // 1. 更新数据库
        updateById(shop);
        // 2. 删除Redis缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByTypeAndLocation(Integer typeId, Integer current, Double x, Double y) {
        // 1.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        // Redis GEOSEARCH/GEORADIUS 的 limit 是 count, 不是 end index，所以我们查到当前页需要的数据即可
        // 但为了后续的 list.stream().skip(from).limit(pageSize)
        // 更简单，我们多查一点，或者直接在Redis层面做更精确分页（如果支持）
        // 这里我们先按原来的逻辑，limit(end) 意味着 limit(from + pageSize)
        int pageSize = SystemConstants.DEFAULT_PAGE_SIZE;
        int end = from + pageSize; // 这是需要从redis获取的总数上限，以便后续stream().skip().limit()能工作

        // 2.查询redis中的商铺信息
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        // 注意：GEORADIUS/GEOSEARCH 返回的结果是按距离排序的
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y), // 使用 fromCoordinate
                new Distance(5000, RedisGeoCommands.DistanceUnit.METERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                        .includeDistance()
                        .limit(end) // 限制返回的条目数
        );

        // 3.解析结果
        if (results == null || results.getContent().isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();

        // 4. 手动进行二次分页 (skip & limit)
        // 如果Redis返回的结果数量不足以覆盖到 from，说明请求的页码超出了实际数据
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = new ArrayList<>(pageSize);
        Map<String, Distance> distanceMap = new HashMap<>(pageSize);

        list.stream().skip(from).limit(pageSize).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });

        if (ids.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 5.根据id查询Shop信息，并保持从Redis GEO查询返回的顺序
        String idsStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id, " + idsStr + ")").list();

        // 6.封装距离信息并返回
        for (Shop shop : shops) {
            // 从distanceMap获取对应shopId的distance对象，然后获取其值
            Distance shopDistance = distanceMap.get(String.valueOf(shop.getId()));
            if (shopDistance != null) {
                shop.setDistance(shopDistance.getValue());
            }
        }
        return Result.ok(shops);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10); // 线程池
}
