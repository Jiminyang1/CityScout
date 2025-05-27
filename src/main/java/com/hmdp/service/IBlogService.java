package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Result likeBlog(Long id);

    Result saveBlog(Blog blog);

    Result queryMyBlogs(Integer current);

    Result queryHotBlogs(Integer current);

    Result queryBlogById(Long id);

    Result queryBlogsByUserId(Long userId, Integer current);
}
