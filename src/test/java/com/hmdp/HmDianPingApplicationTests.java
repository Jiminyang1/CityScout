package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Disabled("Manual integration helper: requires local MySQL and Redis")
@SpringBootTest
public class HmDianPingApplicationTests {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void testSaveShop() {
        shopService.saveShopToRedis(1L, 1L);
        String key = "cache:shop:" + 1L;
        String value = stringRedisTemplate.opsForValue().get(key);
        org.junit.jupiter.api.Assertions.assertNotNull(value);
    }

    @Test
    public void loadShopDataToRedis() {
        List<Shop> list = shopService.list();
        // Group shops by typeId
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // Insert into Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;
            List<Shop> shops = entry.getValue();
            // Write to Redis GEO
            for (Shop shop : shops) {
                if (shop.getX() != null && shop.getY() != null) {
                    stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()),
                            shop.getId().toString());
                }
            }
        }
    }
}
