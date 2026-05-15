package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 这里可以添加其他的配置，比如拦截器、消息转换器等
    // 添加拦截器
    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        //刷新默认拦截所有请求
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**");

        //登陆拦截器拦截特定请求需要登陆
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/api/user/code", // 发送验证码
                        "/api/user/login", // 登录
                        "/api/blog/hot", // 热门博客
                        "/api/shop/**", // 商铺相关
                        "/api/shop-type/**", // 商铺类型
                        "/api/voucher/**", // 优惠券
                        "/api/upload/**" // 上传
                );

    }
}
