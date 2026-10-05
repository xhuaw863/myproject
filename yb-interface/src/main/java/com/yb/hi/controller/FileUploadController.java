package com.yb.hi.controller;

import com.yb.hi.common.FileMagicUtil;
import com.yb.hi.framework.common.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 通用文件上传接口: 目前用于职工头像 / 医师签名图片。
 * <p>图片落盘到 his.upload.path 下, 按 {biz}/{yyyyMM}/{uuid}.{ext} 组织,
 * 返回可通过静态资源映射(his.upload.url-prefix)直接访问的相对URL。
 */
@RestController
@RequestMapping("/api/file")
public class FileUploadController {

    private static final Logger log = LoggerFactory.getLogger(FileUploadController.class);

    /** 允许的图片扩展名(白名单) */
    private static final List<String> ALLOWED_EXT = Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp");
    /** 业务子目录白名单字符校验(防目录穿越) */
    private static final String BIZ_PATTERN = "^[A-Za-z0-9_-]{1,32}$";
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    @Value("${his.upload.path:./data/upload/}")
    private String uploadPath;

    @Value("${his.upload.url-prefix:/uploads/}")
    private String urlPrefix;

    @PostMapping("/upload")
    public R<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "biz", required = false) String biz) {
        if (file == null || file.isEmpty()) {
            return R.fail("上传文件为空");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        // 扩展名白名单优先(部分客户端上传时 Content-Type 为 application/octet-stream, 不能仅依赖它);
        // 若扩展名非法再回退到 Content-Type 推断, 两者均不满足才拒绝。
        String ext = resolveExt(file.getOriginalFilename(), contentType);
        if (ext == null) {
            if (!contentType.startsWith("image/")) {
                return R.fail("仅支持上传图片文件, 允许格式: " + String.join("/", ALLOWED_EXT));
            }
            return R.fail("不支持的图片格式, 仅允许: " + String.join("/", ALLOWED_EXT));
        }
        String safeBiz = (StringUtils.hasText(biz) && biz.matches(BIZ_PATTERN)) ? biz : "common";

        try {
            byte[] bytes = file.getBytes();
            if (!FileMagicUtil.matchesImage(bytes, ext)) {
                return R.fail("文件内容与图片格式不匹配, 已拒绝上传");
            }
            String ym = LocalDate.now().format(YM);
            String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            Path root = Paths.get(uploadPath).toAbsolutePath().normalize();
            Path dir = root.resolve(safeBiz).resolve(ym);
            Files.createDirectories(dir);
            Path target = dir.resolve(filename);
            Files.write(target, bytes);
            String url = joinUrl(urlPrefix, safeBiz + "/" + ym + "/" + filename);
            Map<String, Object> data = new HashMap<>();
            data.put("url", url);
            data.put("name", file.getOriginalFilename());
            data.put("size", file.getSize());
            log.info("文件上传成功: biz={}, url={}, size={}", safeBiz, url, file.getSize());
            return R.ok(data);
        } catch (Exception e) {
            log.error("文件上传失败", e);
            return R.fail("文件上传失败: " + e.getMessage());
        }
    }

    /** 从原始文件名/内容类型解析合法扩展名, 非法返回 null */
    private String resolveExt(String originalFilename, String contentType) {
        String ext = null;
        if (StringUtils.hasText(originalFilename) && originalFilename.contains(".")) {
            ext = originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase();
        }
        if (ext == null || !ALLOWED_EXT.contains(ext)) {
            // 回退: 由内容类型推断
            String ct = contentType.toLowerCase();
            if (ct.contains("png")) { ext = "png"; }
            else if (ct.contains("gif")) { ext = "gif"; }
            else if (ct.contains("bmp")) { ext = "bmp"; }
            else if (ct.contains("webp")) { ext = "webp"; }
            else if (ct.contains("jpeg") || ct.contains("jpg")) { ext = "jpg"; }
            else { ext = null; }
        }
        return (ext != null && ALLOWED_EXT.contains(ext)) ? ext : null;
    }

    /** 拼接URL前缀与相对路径, 保证单斜杠分隔 */
    private String joinUrl(String prefix, String rel) {
        String p = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        String r = rel.startsWith("/") ? rel.substring(1) : rel;
        return p + "/" + r;
    }
}
