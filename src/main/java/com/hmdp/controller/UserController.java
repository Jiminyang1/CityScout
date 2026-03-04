package com.hmdp.controller;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    /**
     * 发送手机验证码
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        // return Result.fail("功能未完成");
        return userService.sendCode(phone, session);
    }

    /**
     * 登录功能
     * 
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session) {
        return userService.userLogin(loginForm, session);
    }

    /**
     * 登出功能
     * 
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(HttpServletRequest request) {
        // 获取请求头中的token
        String token = request.getHeader(RedisConstants.HEADER_AUTHORIZATION);
        // 调用service层的logout方法
        return userService.logout(token);
    }

    @GetMapping("/me")
    public Result me() {
        // TODO 获取当前登录的用户并返回
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId) {
        return userService.queryUserInfoById(userId);
    }

    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long id) {
        // 查询用户
        User user = userService.getById(id);
        if (user == null) {
            return Result.fail("用户不存在");
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回用户信息
        return Result.ok(userDTO);
    }

    // 共同关注
    @GetMapping("/follow/common/{id}")
    public Result commonFollow(@PathVariable("id") Long id) {
        // 获取当前用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("用户未登录");
        }
        // 查询共同关注
        return userService.commonFollow(user.getId(), id);
    }
}
