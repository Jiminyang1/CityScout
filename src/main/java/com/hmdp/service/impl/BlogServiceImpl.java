package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 1.判断当前登录用户是否已经点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 2.如果未点赞，可以点赞
            // 2.1.数据库点赞数 + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 2.2.保存用户到Redis的set集合 zadd key value score
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        } else {
            // 3.如果已点赞，取消点赞
            // 3.1.数据库点赞数 - 1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 3.2.把用户从Redis的set集合移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result saveBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("用户未登录，无法发布笔记");
        }
        blog.setUserId(user.getId());
        boolean success = save(blog);
        if (!success) {
            return Result.fail("发布笔记失败");
        }
        // TODO: 推送给粉丝
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryMyBlogs(Integer current) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("用户未登录");
        }
        Page<Blog> page = query()
                .eq("user_id", user.getId())
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        // 可以选择性地为自己的博客也填充点赞状态（虽然通常自己看自己的博客不强调这个）
        records.forEach(this::queryBlogUserAndLikeState);
        return Result.ok(records);
    }

    @Override
    public Result queryHotBlogs(Integer current) {
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(this::queryBlogUserAndLikeState);
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        queryBlogUserAndLikeState(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryBlogsByUserId(Long userId, Integer current) {
        Page<Blog> page = query()
                .eq("user_id", userId)
                .orderByDesc("create_time") // 通常按创建时间倒序
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(this::queryBlogUserAndLikeState);
        return Result.ok(records);
    }

    private void queryBlogUserAndLikeState(Blog blog) {
        if (blog == null) {
            return;
        }
        // 1. 查询用户信息
        queryBlogUser(blog);
        // 2. 查询点赞状态
        isBlogLikedByCurrentUser(blog);
    }

    private void isBlogLikedByCurrentUser(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 未登录用户，不能判断是否点赞，默认为false
            blog.setIsLike(false);
            return;
        }
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
        blog.setIsLike(score != null);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId); // 调用IUserService获取用户信息
        if (user != null) {
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
    }
}
