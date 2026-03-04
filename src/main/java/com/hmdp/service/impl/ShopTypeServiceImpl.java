package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author Jimin
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 项目	         内容
     * Redis 类型	 String
     * Key 结构	     cache:shoptype:list
     * Value	     JSON 字符串，序列化后的 List
     * 过期时间     	一般设定为 1 天或更长（可调）
     * 是否缓存空值	可选，但一般这里没有必要
     */
    @Override
    public Result queryShopTypeListWithCache() {
        //1.从Redis中查询商铺类型缓存
        String typeKey = "cache:shoptype:list";
        String typeJson = stringRedisTemplate.opsForValue().get(typeKey);
        if(StrUtil.isNotBlank(typeJson)){
            //2.如果存在，直接返回
            log.debug("从Redis中查询商铺类型信息");
            List<ShopType> typeList = JSONUtil.toList(typeJson, ShopType.class);
            return Result.ok(typeList);
        }
        //3.如果不存在，根据id查询数据库
        log.debug("从数据库中查询商铺类型信息");
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //4.如果数据库不存在，返回错误信息
        if(typeList == null || typeList.isEmpty()){
            return Result.fail("商铺类型不存在");
        }
        //5.如果数据库存在，将数据写入Redis
        stringRedisTemplate.opsForValue().set(
                typeKey,
                JSONUtil.toJsonStr(typeList),
                2,
                TimeUnit.DAYS);
        //6.返回商铺类型信息
        return Result.ok(typeList);
    }
}

