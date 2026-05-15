package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FollowServiceImplTest {

    @Mock
    private FollowMapper followMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private SetOperations<String, String> setOperations;

    private FollowServiceImpl followService;

    private Follow testFollow;
    private UserDTO testUserDTO;

    @BeforeEach
    void setUp() {
        testFollow = new Follow();
        testFollow.setId(1L);
        testFollow.setUserId(1L);
        testFollow.setFollowUserId(2L);

        testUserDTO = new UserDTO();
        testUserDTO.setId(1L);
        testUserDTO.setNickName("TestUser");

        UserHolder.saveUser(testUserDTO);
        lenient().when(stringRedisTemplate.opsForSet()).thenReturn(setOperations);
        
        followService = new FollowServiceImpl(stringRedisTemplate);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void testFollow_AddFollow() {
        followService = spy(followService);
        doReturn(true).when(followService).save(any(Follow.class));

        Result result = followService.follow(2L, true);

        assertNotNull(result);
        assertTrue(result.getSuccess());
        verify(setOperations).add(anyString(), anyString());
    }

    @Test
    void testFollow_RemoveFollow() {
        followService = spy(followService);
        doReturn(true).when(followService).remove(any());

        Result result = followService.follow(2L, false);

        assertNotNull(result);
        assertTrue(result.getSuccess());
        verify(setOperations).remove(anyString(), anyString());
    }

    @Test
    void testFollow_AddFollowFailed() {
        followService = spy(followService);
        doReturn(false).when(followService).save(any(Follow.class));

        Result result = followService.follow(2L, true);

        assertNotNull(result);
        assertFalse(result.getSuccess());
        assertEquals("关注失败", result.getErrorMsg());
    }

    @Test
    void testFollow_RemoveFollowFailed() {
        followService = spy(followService);
        doReturn(false).when(followService).remove(any());

        Result result = followService.follow(2L, false);

        assertNotNull(result);
        assertFalse(result.getSuccess());
        assertEquals("取消关注失败", result.getErrorMsg());
    }

    @Test
    void testIsFollowed_True() {
        followService = spy(followService);
        doReturn(1L).when(followService).count(any());

        Result result = followService.isFollowed(2L);

        assertNotNull(result);
        assertTrue(result.getSuccess());
        assertTrue((Boolean) result.getData());
    }

    @Test
    void testIsFollowed_False() {
        followService = spy(followService);
        doReturn(0L).when(followService).count(any());

        Result result = followService.isFollowed(2L);

        assertNotNull(result);
        assertTrue(result.getSuccess());
        assertFalse((Boolean) result.getData());
    }
}
