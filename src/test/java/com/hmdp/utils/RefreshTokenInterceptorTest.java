package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenInterceptorTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void preHandle_withoutToken_clearsStaleUser() throws Exception {
        UserDTO stale = new UserDTO();
        stale.setId(99L);
        UserHolder.saveUser(stale);
        when(request.getHeader(RedisConstants.HEADER_AUTHORIZATION)).thenReturn(null);

        RefreshTokenInterceptor interceptor = new RefreshTokenInterceptor(stringRedisTemplate);

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertNull(UserHolder.getUser());
    }

    @Test
    void afterCompletion_clearsAuthenticatedUser() throws Exception {
        String token = "550e8400-e29b-41d4-a716-446655440000_7";
        Map<Object, Object> userMap = new HashMap<>();
        userMap.put("id", "7");
        userMap.put("nickName", "Alice");
        userMap.put("icon", "");

        when(request.getHeader(RedisConstants.HEADER_AUTHORIZATION)).thenReturn(token);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(RedisConstants.LOGIN_USER_KEY + token)).thenReturn(userMap);

        RefreshTokenInterceptor interceptor = new RefreshTokenInterceptor(stringRedisTemplate);

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertNotNull(UserHolder.getUser());
        assertEquals(7L, UserHolder.getUser().getId());

        interceptor.afterCompletion(request, response, new Object(), null);

        assertNull(UserHolder.getUser());
    }
}
