package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.HEADER_AUTHORIZATION;

/**
 * @author Jimin
 * @date 2023/10/12
 * @description: 刷新token拦截器, 不处理登录请求的具体逻辑
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 1. 获取请求头中的token
        String token = request.getHeader(HEADER_AUTHORIZATION);
        if (token == null || token.isEmpty()) {
            return true; // 没有token，放行
        }

        // 验证token格式
        if (!token.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}_\\d+$")) {
            return true; // token格式不正确，放行
        }

        // 存在token，进行刷新

        // 2. 获取用户登录状态
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        // 3. 判断用户是否存在
        if (userMap.isEmpty()) {
            return true; // 用户不存在，放行
        }
        // 存在用户信息，刷新token
        // 4. 转化Hash为UserDTO
        UserDTO user = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 5. 刷新用户登陆有效期
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 6. 存在用户信息到ThreadLocal
        UserHolder.saveUser(user);
        return true;
    }
}
