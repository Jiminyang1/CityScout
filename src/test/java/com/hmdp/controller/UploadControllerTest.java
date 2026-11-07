package com.hmdp.controller;

import com.hmdp.dto.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UploadControllerTest {

    @TempDir
    Path tempDir;

    private UploadController uploadController;
    private Path imageBase;

    @BeforeEach
    void setUp() {
        uploadController = new UploadController();
        imageBase = tempDir.resolve("imgs");
        ReflectionTestUtils.setField(uploadController, "imageUploadDir", imageBase.toString());
    }

    @Test
    void deleteBlogImg_deletesFileUnderBlogs() throws Exception {
        Path image = imageBase.resolve("blogs/1/2/x.jpg");
        Files.createDirectories(image.getParent());
        Files.writeString(image, "image");

        Result result = uploadController.deleteBlogImg("/blogs/1/2/x.jpg");

        assertTrue(result.getSuccess());
        assertFalse(Files.exists(image));
    }

    @Test
    void deleteBlogImg_rejectsTraversalAndLeavesOutsideFile() throws Exception {
        Files.createDirectories(imageBase);
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "keep");

        Result relativeTraversal = uploadController.deleteBlogImg("../outside.txt");
        Result absoluteTraversal = uploadController.deleteBlogImg("/blogs/../../outside.txt");

        assertFalse(relativeTraversal.getSuccess());
        assertFalse(absoluteTraversal.getSuccess());
        assertTrue(Files.exists(outside));
    }

    @Test
    void uploadImage_savesValidFileUnderBase() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "file", "a.jpg", "image/jpeg", "image".getBytes(StandardCharsets.UTF_8));

        Result result = uploadController.uploadImage(image);

        assertTrue(result.getSuccess());
        Path savedImage = imageBase.resolve(((String) result.getData()).substring(1)).normalize();
        assertTrue(savedImage.startsWith(imageBase.resolve("blogs")));
        assertTrue(Files.exists(savedImage));
    }

    @Test
    void uploadImage_rejectsTraversalInSuffix() {
        MockMultipartFile image = new MockMultipartFile(
                "file", "a./../../evil", "application/octet-stream", "evil".getBytes(StandardCharsets.UTF_8));

        Result result = uploadController.uploadImage(image);

        assertFalse(result.getSuccess());
        assertEquals("不支持的文件类型", result.getErrorMsg());
    }
}
