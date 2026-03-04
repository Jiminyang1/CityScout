package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    private final StringRedisTemplate stringRedisTemplate;

    public FollowServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result follow(Long id, Boolean isFollowed) {
        //1. 获取当前用户的ID
        Long userId = UserHolder.getUser().getId();
        if(isFollowed){
            //2. 如果是关注，则新增一条关注记录
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            boolean success = this.save(follow);
            if(!success){
                return Result.fail("关注失败");
            }
            stringRedisTemplate.opsForSet().add("follow:user:" + userId, id.toString());
            return Result.ok();
        } else {
            //3. 如果是取消关注，则删除对应的关注记录
            boolean success = this.remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", id));
            if(!success){
                return Result.fail("取消关注失败");
            }
            stringRedisTemplate.opsForSet().remove("follow:user:" + userId, id.toString());
            return Result.ok();
        }
    }

    @Override
    public Result isFollowed(Long id) {
        //1. 获取当前用户的ID
        Long userId = UserHolder.getUser().getId();
        //2. 查询是否存在关注记录
        boolean isFollowed = this.count(new QueryWrapper<Follow>()
                .eq("user_id", userId)
                .eq("follow_user_id", id)) > 0;
        //3. 返回结果
        return Result.ok(isFollowed);
    }
}
