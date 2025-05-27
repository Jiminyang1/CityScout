package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;


import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1. 是否需要拦截, 从thredlocal中获取用户信息
        UserDTO userDTO = UserHolder.getUser();
        if (userDTO == null) {
            response.setStatus(401, "Unauthorized");
            return false;
        }
        //2. 有用户登录状态，放行
        return true;

    }
}
