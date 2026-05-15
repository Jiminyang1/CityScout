package com.hmdp.service.impl;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShopServiceImplTest {

    @InjectMocks
    private ShopServiceImpl shopService;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private GeoOperations<String, String> geoOperations;

    @Mock
    private ShopMapper shopMapper;

    private Shop testShop;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(stringRedisTemplate.opsForGeo()).thenReturn(geoOperations);

        testShop = new Shop();
        testShop.setId(1L);
        testShop.setName("Test Shop");
        ReflectionTestUtils.setField(shopService, "baseMapper", shopMapper);
    }

    @Test
    void getByIdWithCache_shouldReturnShopFromCache_whenCacheIsHit() {
        String shopJson = JSONUtil.toJsonStr(testShop);
        when(valueOperations.get(RedisConstants.CACHE_SHOP_KEY + 1L)).thenReturn(shopJson);

        Result result = shopService.getByIdWithCache(1L);

        assertTrue(result.getSuccess());
        assertNotNull(result.getData());
        assertEquals(testShop.getId(), ((Shop) result.getData()).getId());
        verify(shopMapper, never()).selectById(anyLong());
    }

    @Test
    void getByIdWithCache_shouldReturnShopFromDb_whenCacheIsMiss() {
        String shopKey = RedisConstants.CACHE_SHOP_KEY + 1L;
        String lockKey = RedisConstants.LOCK_SHOP_KEY + 1L;

        when(valueOperations.get(shopKey)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(lockKey), anyString(), anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
            String clientId = invocation.getArgument(1);
            when(valueOperations.get(lockKey)).thenReturn(clientId);
            return true;
        });
        when(shopMapper.selectById(1L)).thenReturn(testShop);

        Result result = shopService.getByIdWithCache(1L);

        assertTrue(result.getSuccess());
        assertEquals(testShop.getId(), ((Shop) result.getData()).getId());
        verify(shopMapper).selectById(1L);
        verify(valueOperations).set(eq(shopKey), anyString(), anyLong(), any(TimeUnit.class));
        verify(stringRedisTemplate).delete(lockKey);
    }

    @Test
    void getByIdWithCache_shouldHandleCachePenetration() {
        String shopKey = RedisConstants.CACHE_SHOP_KEY + 1L;
        String lockKey = RedisConstants.LOCK_SHOP_KEY + 1L;

        when(valueOperations.get(shopKey)).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(lockKey), anyString(), anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
            String clientId = invocation.getArgument(1);
            when(valueOperations.get(lockKey)).thenReturn(clientId);
            return true;
        });
        when(shopMapper.selectById(1L)).thenReturn(null);

        Result result = shopService.getByIdWithCache(1L);

        assertFalse(result.getSuccess());
        assertEquals("商铺不存在", result.getErrorMsg());
        verify(valueOperations).set(shopKey, "", 2L, TimeUnit.MINUTES);
    }

    @Test
    void getByIdWithCache_shouldReturnFail_whenCacheReturnsBlank() {
        String shopKey = RedisConstants.CACHE_SHOP_KEY + 1L;
        when(valueOperations.get(shopKey)).thenReturn("");

        Result result = shopService.getByIdWithCache(1L);

        assertFalse(result.getSuccess());
        assertEquals("商铺不存在", result.getErrorMsg());
    }

    @Test
    void getByIdWithCache_shouldReturnFromCache_onDoubleCheck() {
        String shopKey = RedisConstants.CACHE_SHOP_KEY + 1L;
        String lockKey = RedisConstants.LOCK_SHOP_KEY + 1L;
        String shopJson = JSONUtil.toJsonStr(testShop);

        when(valueOperations.get(shopKey)).thenReturn(null).thenReturn(shopJson);
        when(valueOperations.setIfAbsent(eq(lockKey), anyString(), anyLong(), any(TimeUnit.class))).thenAnswer(invocation -> {
            String clientId = invocation.getArgument(1);
            when(valueOperations.get(lockKey)).thenReturn(clientId);
            return true;
        });

        Result result = shopService.getByIdWithCache(1L);

        assertTrue(result.getSuccess());
        assertEquals(testShop.getId(), ((Shop) result.getData()).getId());
        verify(shopMapper, never()).selectById(1L);
        verify(stringRedisTemplate).delete(lockKey);
    }

    @Test
    void getByIdWithCacheInLogicalExpired_shouldReturnData_whenCacheIsNotExpired() {
        RedisData redisData = new RedisData();
        redisData.setData(testShop);
        redisData.setExpireTime(LocalDateTime.now().plusHours(1));
        String shopJson = JSONUtil.toJsonStr(redisData);

        when(valueOperations.get(RedisConstants.CACHE_SHOP_KEY + 1L)).thenReturn(shopJson);

        Result result = shopService.getByIdWithCacheInLogicalExpired(1L);

        assertTrue(result.getSuccess());
        assertNotNull(result.getData());
    }

    @Test
    void getByIdWithCacheInLogicalExpired_shouldReturnFail_whenCacheIsBlank() {
        when(valueOperations.get(RedisConstants.CACHE_SHOP_KEY + 1L)).thenReturn(null);
        Result result = shopService.getByIdWithCacheInLogicalExpired(1L);
        assertFalse(result.getSuccess());
    }

    @Test
    void getByIdWithCacheInLogicalExpired_shouldRebuildCache_whenCacheIsLogicallyExpired() {
        RedisData redisData = new RedisData();
        redisData.setData(testShop);
        redisData.setExpireTime(LocalDateTime.now().minusHours(1)); // Expired
        String shopJson = JSONUtil.toJsonStr(redisData);
        String lockKey = RedisConstants.LOCK_SHOP_KEY + 1L;

        when(valueOperations.get(RedisConstants.CACHE_SHOP_KEY + 1L)).thenReturn(shopJson);
        when(valueOperations.setIfAbsent(eq(lockKey), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        Result result = shopService.getByIdWithCacheInLogicalExpired(1L);

        assertTrue(result.getSuccess());
        assertEquals(testShop.getId(), ((Shop) result.getData()).getId()); // Should return stale data
        verify(valueOperations).setIfAbsent(eq(lockKey), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void saveShopToRedis_shouldSaveDataWithLogicalExpiry() {
        when(shopMapper.selectById(1L)).thenReturn(testShop);

        shopService.saveShopToRedis(1L, 10L);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(RedisConstants.CACHE_SHOP_KEY + 1L), captor.capture());

        RedisData savedData = JSONUtil.toBean(captor.getValue(), RedisData.class);
        assertNotNull(savedData.getExpireTime());
        assertNotNull(savedData.getData());
    }

    @Test
    void updateByIdWithCache_shouldUpdateDbAndEvictCache() {
        when(shopMapper.updateById(any(Shop.class))).thenReturn(1);

        Result result = shopService.updateByIdWithCache(testShop);

        assertTrue(result.getSuccess());
        verify(shopMapper).updateById(testShop);
        verify(stringRedisTemplate).delete(RedisConstants.CACHE_SHOP_KEY + testShop.getId());
    }

    @Test
    void updateByIdWithCache_shouldReturnFail_whenIdIsNull() {
        testShop.setId(null);
        Result result = shopService.updateByIdWithCache(testShop);

        assertFalse(result.getSuccess());
        assertEquals("商铺id不能为空", result.getErrorMsg());
        verify(shopMapper, never()).updateById(any(Shop.class));
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    void queryShopByTypeAndLocation_shouldReturnSortedShops() {
        double x = 120.0, y = 30.0;
        int typeId = 1, current = 1;
        String key = RedisConstants.SHOP_GEO_KEY + typeId;

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> geoResultsContent = List.of(
                new GeoResult<>(new RedisGeoCommands.GeoLocation<>("1", new Point(x, y)), new Distance(100))
        );
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = new GeoResults<>(geoResultsContent, RedisGeoCommands.DistanceUnit.METERS);
        when(geoOperations.search(eq(key), any(GeoReference.class), any(Distance.class), any(RedisGeoCommands.GeoSearchCommandArgs.class)))
                .thenReturn(geoResults);

        when(shopMapper.selectList(any())).thenReturn(List.of(testShop));

        Result result = shopService.queryShopByTypeAndLocation(typeId, current, x, y);

        assertTrue(result.getSuccess());
        List<Shop> shops = (List<Shop>) result.getData();
        assertFalse(shops.isEmpty());
        assertEquals(100.0, shops.get(0).getDistance());
        verify(shopMapper).selectList(any());
    }

    @Test
    void queryShopByTypeAndLocation_shouldReturnEmptyList_whenNoGeoResults() {
        double x = 120.0, y = 30.0;
        int typeId = 1, current = 1;
        String key = RedisConstants.SHOP_GEO_KEY + typeId;

        when(geoOperations.search(eq(key), any(GeoReference.class), any(Distance.class), any(RedisGeoCommands.GeoSearchCommandArgs.class)))
                .thenReturn(new GeoResults<>(Collections.emptyList(), RedisGeoCommands.DistanceUnit.METERS));

        Result result = shopService.queryShopByTypeAndLocation(typeId, current, x, y);

        assertTrue(result.getSuccess());
        List<Shop> shops = (List<Shop>) result.getData();
        assertTrue(shops.isEmpty());
        verify(shopMapper, never()).selectList(any());
    }

    @Test
    void queryShopByTypeAndLocation_shouldReturnEmpty_whenPageIsOutOfBounds() {
        double x = 120.0, y = 30.0;
        int typeId = 1, current = 3; // Requesting page 3
        String key = RedisConstants.SHOP_GEO_KEY + typeId;

        // Redis returns results that only fill page 1
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> geoResultsContent = List.of(
                new GeoResult<>(new RedisGeoCommands.GeoLocation<>("1", new Point(x, y)), new Distance(100))
        );
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = new GeoResults<>(geoResultsContent, RedisGeoCommands.DistanceUnit.METERS);
        when(geoOperations.search(eq(key), any(GeoReference.class), any(Distance.class), any(RedisGeoCommands.GeoSearchCommandArgs.class)))
                .thenReturn(geoResults);

        Result result = shopService.queryShopByTypeAndLocation(typeId, current, x, y);

        assertTrue(result.getSuccess());
        List<Shop> shops = (List<Shop>) result.getData();
        assertTrue(shops.isEmpty());
        verify(shopMapper, never()).selectList(any());
    }
}
