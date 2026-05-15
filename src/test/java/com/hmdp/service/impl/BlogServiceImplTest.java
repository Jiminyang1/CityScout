package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlogServiceImplTest {

    @Mock
    private BlogMapper blogMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private IUserService userService;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @InjectMocks
    private BlogServiceImpl blogService;

    private final Long TEST_BLOG_ID = 1L;
    private final Long TEST_USER_ID = 100L;
    
    private Blog testBlog;
    private UserDTO testUserDTO;
    private User testUser;

    @BeforeEach
    void setUp() {
        testBlog = new Blog();
        testBlog.setId(1L);
        testBlog.setTitle("Test Blog");
        testBlog.setContent("Test Content");
        testBlog.setUserId(1L);
        testBlog.setLiked(0);

        testUserDTO = new UserDTO();
        testUserDTO.setId(TEST_USER_ID);
        testUserDTO.setNickName("TestUser");
        testUserDTO.setIcon("test-icon.jpg");

        testUser = new User();
        testUser.setId(1L);
        testUser.setNickName("TestUser");
        testUser.setIcon("test-icon.jpg");

        UserHolder.saveUser(testUserDTO);
        ReflectionTestUtils.setField(blogService, "baseMapper", blogMapper);
        lenient().when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void likeBlog_whenNotLiked_shouldAddLike() {
        when(zSetOperations.score(anyString(), anyString())).thenReturn(null);
        when(blogMapper.update(any(), any())).thenReturn(1);

        Result result = blogService.likeBlog(TEST_BLOG_ID);

        assertTrue(result.getSuccess());
        verify(zSetOperations).add(anyString(), anyString(), anyDouble());
        verify(blogMapper).update(any(), any());
    }

    @Test
    void likeBlog_whenAlreadyLiked_shouldRemoveLike() {
        when(zSetOperations.score(anyString(), anyString())).thenReturn(1.0);
        when(blogMapper.update(any(), any())).thenReturn(1);

        Result result = blogService.likeBlog(TEST_BLOG_ID);

        assertTrue(result.getSuccess());
        verify(zSetOperations).remove(anyString(), anyString());
        verify(blogMapper).update(any(), any());
    }

    @Test
    void testSaveBlog_Success() {
        when(blogMapper.insert(any(Blog.class))).thenReturn(1);

        Result result = blogService.saveBlog(testBlog);

        assertNotNull(result);
        assertTrue(result.getSuccess());
        assertEquals(1L, result.getData());
    }

    @Test
    void testSaveBlog_UserNotLoggedIn() {
        UserHolder.removeUser();

        Result result = blogService.saveBlog(testBlog);

        assertNotNull(result);
        assertFalse(result.getSuccess());
        assertEquals("用户未登录，无法发布笔记", result.getErrorMsg());
    }

    @Test
    void testSaveBlog_SaveFailed() {
        when(blogMapper.insert(any(Blog.class))).thenReturn(0);

        Result result = blogService.saveBlog(testBlog);

        assertNotNull(result);
        assertFalse(result.getSuccess());
        assertEquals("发布笔记失败", result.getErrorMsg());
    }

    @Test
    void testQueryMyBlogs_UserNotLoggedIn() {
        UserHolder.removeUser();

        Result result = blogService.queryMyBlogs(1);

        assertNotNull(result);
        assertFalse(result.getSuccess());
        assertEquals("用户未登录", result.getErrorMsg());
    }

    @Test
    void testQueryBlogById_NotFound() {
        when(blogMapper.selectById(1L)).thenReturn(null);

        Result result = blogService.queryBlogById(1L);

        assertNotNull(result);
        assertFalse(result.getSuccess());
        assertEquals("笔记不存在", result.getErrorMsg());
    }

    @Test
    void testSave() {
        when(blogMapper.insert(any(Blog.class))).thenReturn(1);

        boolean result = blogService.save(testBlog);

        assertTrue(result);
        verify(blogMapper).insert(testBlog);
    }

    @Test
    void testRemoveById() {
        when(blogMapper.deleteById(1L)).thenReturn(1);

        boolean result = blogService.removeById(1L);

        assertTrue(result);
        verify(blogMapper).deleteById(1L);
    }

    @Test
    void testUpdateById() {
        when(blogMapper.updateById(testBlog)).thenReturn(1);

        boolean result = blogService.updateById(testBlog);

        assertTrue(result);
        verify(blogMapper).updateById(testBlog);
    }

    @Test
    void testGetById() {
        when(blogMapper.selectById(1L)).thenReturn(testBlog);

        Blog result = blogService.getById(1L);

        assertNotNull(result);
        assertEquals(testBlog.getId(), result.getId());
        verify(blogMapper).selectById(1L);
    }

    @Test
    void testCount() {
        when(blogMapper.selectCount(any())).thenReturn(5L);

        long result = blogService.count();

        assertEquals(5L, result);
        verify(blogMapper).selectCount(any());
    }
}
