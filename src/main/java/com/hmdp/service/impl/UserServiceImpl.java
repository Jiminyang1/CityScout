package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    @Lazy
    private IUserService userService;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. check phone number format
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("Invalid phone number");
        }
        // 2. generate code
        String code = RandomUtil.randomNumbers(6);
        // 3. save code to reids
        // session.setAttribute("code", code), and expire in 2 minutes
        String key = LOGIN_CODE_KEY + phone;
        stringRedisTemplate.opsForValue().set(key, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 4. send code to phone
        // TODO send code to phone
        log.debug("send code to phone: {}", code);
        return Result.ok();
    }

    @Override
    public Result userLogin(LoginFormDTO loginForm, HttpSession session) {

        String phoneNumber = loginForm.getPhone();
        // 1. check phone number format and code format
        if (RegexUtils.isPhoneInvalid(phoneNumber)) {
            return Result.fail("Invalid phone number");
        }

        // 2. check code from redis
        String key = LOGIN_CODE_KEY + phoneNumber;
        Object cacheCode = stringRedisTemplate.opsForValue().get(key);
        String inputCode = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(inputCode)) {
            return Result.fail("Invalid code");
        }

        // 3. check if user exists
        User user = query().eq("phone", loginForm.getPhone()).one();
        // 4. create new user
        if (user == null) {
            user = createUnerWithPhone(loginForm.getPhone());
        }

        // 5. save user to redis
        // 5.1 create UUID and add user identifier
        String token = UUID.randomUUID().toString() + "_" + user.getId();
        // 5.2 convert user to UserDTO and save to redis as Hash key value pair
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // DTO不只一个属性，不能直接用userDTO.toString()，需要转换成Map<String, Object>类型
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 这里redis的map的key是String类型，value是Object类型，所以需要转换成String类型
        // 5.3 save user to redis
        String userKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(userKey, userMap);
        // 5.4 set expire time 30 minutes
        stringRedisTemplate.expire(userKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 6. return token
        return Result.ok(token);
    }

    @Override
    public Result logout(String token) {
        if (token != null && !token.isEmpty()) {
            // 1. 删除Redis中的token
            String key = LOGIN_USER_KEY + token;
            stringRedisTemplate.delete(key);
            // 2. 清理ThreadLocal中的用户信息
            UserHolder.removeUser();
        }
        return Result.ok();
    }

    @Override
    public Result commonFollow(Long userId, Long targetUserId) {
        // 1. 参数校验
        if (userId == null || targetUserId == null) {
            return Result.fail("参数不能为空");
        }

        // 2. 获取当前登录用户
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("用户未登录");
        }

        // 3. 获取两个用户的关注集合
        String key1 = "follow:user:" + currentUser.getId();
        String key2 = "follow:user:" + targetUserId;

        // 4. 获取交集
        Set<String> intersected = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersected == null || intersected.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 5. 解析用户ID
        Set<Long> commonFollowedUserIds = intersected.stream()
                .map(Long::valueOf)
                .collect(Collectors.toSet());

        // 6. 查询用户信息
        List<User> users = userService.listByIds(commonFollowedUserIds);
        if (users == null || users.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }

        // 7. 转换为DTO
        List<UserDTO> userDTOs = users.stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOs);
    }

    @Override
    public Result queryUserInfoById(Long userId) {
        // 查询详情
        User info = getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }

    private User createUnerWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(5));
        save(user); // save()方法是MyBatis-Plus提供的一个方法，用于保存实体对象到数据库。
        log.debug("create new user: {}", user);
        return user;
    }
}
