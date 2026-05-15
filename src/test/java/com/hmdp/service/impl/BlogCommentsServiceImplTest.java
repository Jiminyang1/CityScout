package com.hmdp.service.impl;

import com.hmdp.entity.BlogComments;
import com.hmdp.mapper.BlogCommentsMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlogCommentsServiceImplTest {

    @Mock
    private BlogCommentsMapper blogCommentsMapper;

    @InjectMocks
    private BlogCommentsServiceImpl blogCommentsService;

    private BlogComments testBlogComment;

    @BeforeEach
    void setUp() {
        testBlogComment = new BlogComments();
        testBlogComment.setId(1L);
        testBlogComment.setBlogId(1L);
        testBlogComment.setUserId(1L);
        testBlogComment.setContent("Test comment");
        ReflectionTestUtils.setField(blogCommentsService, "baseMapper", blogCommentsMapper);
    }

    @Test
    void testSave() {
        when(blogCommentsMapper.insert(any(BlogComments.class))).thenReturn(1);

        boolean result = blogCommentsService.save(testBlogComment);

        assertTrue(result);
        verify(blogCommentsMapper).insert(testBlogComment);
    }

    @Test
    void testRemoveById() {
        when(blogCommentsMapper.deleteById(1L)).thenReturn(1);

        boolean result = blogCommentsService.removeById(1L);

        assertTrue(result);
        verify(blogCommentsMapper).deleteById(1L);
    }

    @Test
    void testRemoveByIds() {
        Collection<Long> ids = Arrays.asList(1L, 2L);
        when(blogCommentsMapper.deleteByIds(ids)).thenReturn(2);

        boolean result = blogCommentsService.removeByIds(ids);

        assertTrue(result);
        verify(blogCommentsMapper).deleteByIds(ids);
    }

    @Test
    void testUpdateById() {
        when(blogCommentsMapper.updateById(testBlogComment)).thenReturn(1);

        boolean result = blogCommentsService.updateById(testBlogComment);

        assertTrue(result);
        verify(blogCommentsMapper).updateById(testBlogComment);
    }

    @Test
    void testGetById() {
        when(blogCommentsMapper.selectById(1L)).thenReturn(testBlogComment);

        BlogComments result = blogCommentsService.getById(1L);

        assertNotNull(result);
        assertEquals(testBlogComment.getId(), result.getId());
        verify(blogCommentsMapper).selectById(1L);
    }

    @Test
    void testListByIds() {
        Collection<Long> ids = Arrays.asList(1L, 2L);
        List<BlogComments> expectedList = Arrays.asList(testBlogComment, new BlogComments());
        when(blogCommentsMapper.selectByIds(ids)).thenReturn(expectedList);

        List<BlogComments> result = blogCommentsService.listByIds(ids);

        assertNotNull(result);
        assertEquals(2, result.size());
        verify(blogCommentsMapper).selectByIds(ids);
    }

    @Test
    void testList() {
        List<BlogComments> expectedList = Arrays.asList(testBlogComment);
        when(blogCommentsMapper.selectList(any())).thenReturn(expectedList);

        List<BlogComments> result = blogCommentsService.list();

        assertNotNull(result);
        assertEquals(1, result.size());
        verify(blogCommentsMapper).selectList(any());
    }

    @Test
    void testCount() {
        when(blogCommentsMapper.selectCount(any())).thenReturn(5L);

        long result = blogCommentsService.count();

        assertEquals(5L, result);
        verify(blogCommentsMapper).selectCount(any());
    }
}
