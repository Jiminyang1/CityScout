package com.hmdp.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    @Value("${app.upload.image-dir:./data/imgs}")
    private String imageUploadDir;

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            // 获取原始文件名称
            String originalFilename = image.getOriginalFilename();
            String suffix = StrUtil.subAfter(originalFilename, ".", true);
            if (suffix == null || !suffix.matches("^[A-Za-z0-9]{1,10}$")) {
                return Result.fail("不支持的文件类型");
            }
            // 生成新文件名
            String fileName = createNewFileName(suffix);
            // 保存文件
            Path target = Paths.get(imageUploadDir).toAbsolutePath().normalize()
                    .resolve(stripLeadingSlashes(fileName)).normalize();
            Files.createDirectories(target.getParent());
            image.transferTo(target.toFile());
            // 返回结果
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @GetMapping("/blog/delete")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        if (StrUtil.isBlank(filename)) {
            return Result.fail("错误的文件名称");
        }
        Path base = Paths.get(imageUploadDir).toAbsolutePath().normalize();
        Path target = base.resolve(stripLeadingSlashes(filename)).normalize();
        if (!target.startsWith(base.resolve("blogs"))
                || Files.isDirectory(target)) {
            return Result.fail("错误的文件名称");
        }
        FileUtil.del(target.toFile());
        return Result.ok();
    }

    private String createNewFileName(String suffix) {
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 生成文件名
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }

    private String stripLeadingSlashes(String name) {
        int start = 0;
        while (start < name.length() && name.charAt(start) == '/') {
            start++;
        }
        return name.substring(start);
    }
}
