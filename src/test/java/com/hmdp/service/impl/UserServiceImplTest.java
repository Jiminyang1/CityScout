package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private UserMapper userMapper;

    @Mock
    private IUserService userService;

    @InjectMocks
    private UserServiceImpl userServiceImpl;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;
    @Mock
    private SetOperations<String, String> setOperations;

    private static final String TEST_PHONE = "13812345678";
    private static final String TEST_CODE = "123456";
    private static final String TEST_TOKEN = "test_token";

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        ReflectionTestUtils.setField(userServiceImpl, "baseMapper", userMapper);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void testSendCode_ValidPhone() {
        Result result = userServiceImpl.sendCode(TEST_PHONE, null);
        assertTrue(result.getSuccess());
        verify(stringRedisTemplate.opsForValue()).set(eq(LOGIN_CODE_KEY + TEST_PHONE), anyString(), anyLong(), any());
    }

    @Test
    void testSendCode_InvalidPhone() {
        Result result = userServiceImpl.sendCode("123", null);
        assertFalse(result.getSuccess());
        assertEquals("Invalid phone number", result.getErrorMsg());
    }

    @Test
    void testUserLogin_Success_ExistingUser() {
        LoginFormDTO loginForm = new LoginFormDTO();
        loginForm.setPhone(TEST_PHONE);
        loginForm.setCode(TEST_CODE);

        User user = new User();
        user.setId(1L);
        user.setPhone(TEST_PHONE);
        user.setNickName("test");

        when(stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + TEST_PHONE)).thenReturn(TEST_CODE);
        when(userMapper.selectOne(any())).thenReturn(user);

        Result result = userServiceImpl.userLogin(loginForm, null);

        assertTrue(result.getSuccess());
        assertNotNull(result.getData());
        verify(stringRedisTemplate.opsForHash()).putAll(anyString(), anyMap());
    }

    @Test
    void testUserLogin_Success_NewUser() {
        LoginFormDTO loginForm = new LoginFormDTO();
        loginForm.setPhone(TEST_PHONE);
        loginForm.setCode(TEST_CODE);

        when(stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + TEST_PHONE)).thenReturn(TEST_CODE);
        when(userMapper.selectOne(any())).thenReturn(null);
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(1L);
            return 1;
        });

        Result result = userServiceImpl.userLogin(loginForm, null);

        assertTrue(result.getSuccess());
        assertNotNull(result.getData());
        verify(userMapper).insert(any(User.class));
        verify(stringRedisTemplate.opsForHash()).putAll(anyString(), anyMap());
    }

    @Test
    void testUserLogin_InvalidPhone() {
        LoginFormDTO loginForm = new LoginFormDTO();
        loginForm.setPhone("123");
        Result result = userServiceImpl.userLogin(loginForm, null);
        assertFalse(result.getSuccess());
        assertEquals("Invalid phone number", result.getErrorMsg());
    }

    @Test
    void testUserLogin_InvalidCode() {
        LoginFormDTO loginForm = new LoginFormDTO();
        loginForm.setPhone(TEST_PHONE);
        loginForm.setCode("wrongcode");

        when(stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + TEST_PHONE)).thenReturn(TEST_CODE);

        Result result = userServiceImpl.userLogin(loginForm, null);

        assertFalse(result.getSuccess());
        assertEquals("Invalid code", result.getErrorMsg());
    }

    @Test
    void testUserLogin_NullCode() {
        LoginFormDTO loginForm = new LoginFormDTO();
        loginForm.setPhone(TEST_PHONE);
        loginForm.setCode("111111");

        when(stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + TEST_PHONE)).thenReturn(null);

        Result result = userServiceImpl.userLogin(loginForm, null);

        assertFalse(result.getSuccess());
        assertEquals("Invalid code", result.getErrorMsg());
    }

    @Test
    void testLogout() {
        Result result = userServiceImpl.logout(TEST_TOKEN);
        assertTrue(result.getSuccess());
        verify(stringRedisTemplate).delete(LOGIN_USER_KEY + TEST_TOKEN);
    }

    @Test
    void testLogout_WithNullToken() {
        Result result = userServiceImpl.logout(null);
        assertTrue(result.getSuccess());
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    void testCommonFollow_HasCommon() {
        Long userId = 1L;
        Long targetUserId = 2L;
        UserDTO currentUser = new UserDTO();
        currentUser.setId(userId);
        UserHolder.saveUser(currentUser);

        when(setOperations.intersect(anyString(), anyString())).thenReturn(Collections.singleton("3"));
        when(userService.listByIds(any())).thenReturn(Collections.singletonList(new User()));

        Result result = userServiceImpl.commonFollow(userId, targetUserId);

        assertTrue(result.getSuccess());
        assertFalse(((List<?>) result.getData()).isEmpty());
    }

    @Test
    void testCommonFollow_NoCommon() {
        Long userId = 1L;
        Long targetUserId = 2L;
        UserDTO currentUser = new UserDTO();
        currentUser.setId(userId);
        UserHolder.saveUser(currentUser);

        when(setOperations.intersect(anyString(), anyString())).thenReturn(Collections.emptySet());

        Result result = userServiceImpl.commonFollow(userId, targetUserId);

        assertTrue(result.getSuccess());
        assertTrue(((List<?>) result.getData()).isEmpty());
    }

    @Test
    void testCommonFollow_UserNotLoggedIn() {
        Result result = userServiceImpl.commonFollow(1L, 2L);
        assertFalse(result.getSuccess());
        assertEquals("用户未登录", result.getErrorMsg());
    }

    @Test
    void testCommonFollow_InvalidParams() {
        UserDTO currentUser = new UserDTO();
        currentUser.setId(1L);
        UserHolder.saveUser(currentUser);
        Result result = userServiceImpl.commonFollow(null, 2L);
        assertFalse(result.getSuccess());
        assertEquals("参数不能为空", result.getErrorMsg());
    }

    @Test
    void testQueryUserInfoById_UserExists() {
        User user = new User();
        user.setId(1L);
        when(userMapper.selectById(1L)).thenReturn(user);

        Result result = userServiceImpl.queryUserInfoById(1L);

        assertTrue(result.getSuccess());
        assertNotNull(result.getData());
    }

    @Test
    void testQueryUserInfoById_UserNotExists() {
        when(userMapper.selectById(9999L)).thenReturn(null);

        Result result = userServiceImpl.queryUserInfoById(9999L);

        assertTrue(result.getSuccess());
        assertNull(result.getData());
    }
}
