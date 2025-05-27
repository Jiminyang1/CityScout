package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    Result getByIdWithCache(Long id);

    Result getByIdWithCacheInLogicalExpired(Long id);

    Result updateByIdWithCache(Shop shop);

    void saveShopToRedis(Long id, Long expireMinutes);

    Result queryShopByTypeAndLocation(Integer typeId, Integer current, Double x, Double y);
}
