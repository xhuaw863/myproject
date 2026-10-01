package com.yb.hi.controller;

import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.StdDictImportService;
import com.yb.hi.service.StdDictMaintainService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 企业字典维护接口: std_supplier 虽为全局 L0 标准字典, 但作为医共体基础数据
 * 由牵头机构系统管理员维护(与"医共体字典"三目录同档守卫), 平台超级管理员亦可维护。
 * 复用通用标准字典维护服务(列白名单 + PreparedStatement), 字典键固定为 supplier。
 *  端点(相对 /api/supplier-dict):
 *   GET  /page          分页列表(keyword 支持名称/编码/拼音)
 *   GET  /row/{id}      单行完整数据
 *   POST /              新增(body=列名->值)
 *   PUT  /{id}          修改
 *   DELETE /{id}        删除
 *   GET  /import        从医保各目录去重全量重写(幂等)
 *   GET  /files         企业资质文件列表(?supCode=)
 *   POST /upload-file   上传资质文件(multipart, 支持 PDF/图片)
 *   DELETE /file/{id}   删除资质文件(软删)
 * 只读浏览(供目录企业远程下拉)仍走通用 /api/std-dict/query/supplier(所有登录用户可读)。
 */
@RestController
@RequestMapping("/api/supplier-dict")
public class SupplierDictController {

    private static final String KEY = "supplier";
    private static final List<String> DOC_TYPES = Arrays.asList("许可证", "GMP", "GSP", "营业执照", "其他");
    private static final List<String> ALLOWED_EXT = Arrays.asList("pdf", "jpg", "jpeg", "png", "zip", "rar");
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    private final StdDictMaintainService maintainService;
    private final StdDictImportService importService;
    private final OrgAccessGuard guard;
    private final JdbcTemplate jdbc;

    @Value("${his.upload.path:./data/upload/}")
    private String uploadPath;

    public SupplierDictController(StdDictMaintainService maintainService,
                                  StdDictImportService importService,
                                  OrgAccessGuard guard,
                                  JdbcTemplate jdbc) {
        this.maintainService = maintainService;
        this.importService = importService;
        this.guard = guard;
        this.jdbc = jdbc;
    }

    @GetMapping("/columns")
    public R<List<Map<String, Object>>> columns() {
        requireCanMaintain();
        return R.ok(maintainService.columns(KEY));
    }

    @GetMapping("/page")
    public R<Map<String, Object>> page(@RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long size) {
        requireCanMaintain();
        return R.ok(maintainService.page(KEY, keyword, page, size));
    }

    @GetMapping("/row/{id}")
    public R<Map<String, Object>> row(@PathVariable long id) {
        requireCanMaintain();
        return R.ok(maintainService.row(KEY, id));
    }

    @PostMapping
    public R<Long> insert(@RequestBody Map<String, Object> data) {
        requireCanMaintain();
        return R.ok("新增成功", maintainService.insert(KEY, data));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable long id, @RequestBody Map<String, Object> data) {
        requireCanMaintain();
        maintainService.update(KEY, id, data);
        return R.ok("修改成功", null);
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable long id) {
        requireCanMaintain();
        maintainService.delete(KEY, id);
        return R.ok("删除成功", null);
    }

    @GetMapping("/import")
    public R<Map<String, Object>> importSupplier() {
        requireCanMaintain();
        return R.ok(importService.importOne(KEY));
    }

    /* ===================== 资质证照文件管理 ===================== */

    /** 按企业编码查询已上传的资质文件列表 */
    @GetMapping("/files")
    public R<List<Map<String, Object>>> fileList(@RequestParam String supCode) {
        requireCanMaintain();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, sup_code, doc_type, file_name, file_path, file_size, mime_type, remark, upload_by, upload_time "
                        + "FROM std_supplier_file WHERE sup_code=? AND deleted=0 ORDER BY id DESC", supCode);
        return R.ok(rows);
    }

    /** 上传资质文件(PDF/JPG/PNG, 最大20MB) */
    @PostMapping("/upload-file")
    public R<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file,
                                             @RequestParam String supCode,
                                             @RequestParam(required = false) String docType,
                                             @RequestParam(required = false) String remark) {
        requireCanMaintain();
        if (file == null || file.isEmpty()) { return R.fail("上传文件为空"); }
        if (!StringUtils.hasText(supCode)) { return R.fail("企业编码不能为空"); }
        String ext = resolveExt(file.getOriginalFilename());
        if (ext == null || !ALLOWED_EXT.contains(ext)) {
            return R.fail("不支持的文件格式, 仅允许: " + String.join("/", ALLOWED_EXT));
        }
        try {
            String ym = LocalDate.now().format(YM);
            String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            Path root = Paths.get(uploadPath).toAbsolutePath().normalize();
            Path dir = root.resolve("supplier-doc").resolve(ym);
            Files.createDirectories(dir);
            Path target = dir.resolve(filename);
            try (InputStream in = file.getInputStream()) { Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING); }
            String relPath = "supplier-doc/" + ym + "/" + filename;
            String dbDocType = (StringUtils.hasText(docType) && DOC_TYPES.contains(docType)) ? docType : "其他";
            LoginUser u = UserContext.get();
            String uploadBy = u != null ? u.getUsername() : "system";
            jdbc.update("INSERT INTO std_supplier_file (sup_code, doc_type, file_name, file_path, file_size, mime_type, remark, upload_by, upload_time) "
                            + "VALUES (?,?,?,?,?,?,?,?,?)",
                    supCode, dbDocType, file.getOriginalFilename(), relPath, file.getSize(),
                    file.getContentType(), remark, uploadBy, LocalDateTime.now());
            Long newId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", newId);
            data.put("fileName", file.getOriginalFilename());
            data.put("docType", dbDocType);
            data.put("fileSize", file.getSize());
            data.put("url", "/uploads/" + relPath);
            return R.ok(data);
        } catch (Exception e) {
            return R.fail("文件上传失败: " + e.getMessage());
        }
    }

    /** 软删除资质文件 */
    @DeleteMapping("/file/{id}")
    public R<Void> deleteFile(@PathVariable Long id) {
        requireCanMaintain();
        jdbc.update("UPDATE std_supplier_file SET deleted=1 WHERE id=?", id);
        return R.ok("已删除", null);
    }

    /* ===================== 内部方法 ===================== */

    /** 平台超级管理员或牵头机构系统管理员可维护; 其余只读(403)。 */
    private void requireCanMaintain() {
        LoginUser u = UserContext.get();
        if (u != null && u.hasRole(Roles.SUPER_ADMIN)) {
            return;
        }
        guard.requireLeadOrg("仅牵头机构系统管理员或平台超级管理员可维护企业字典");
    }

    private String resolveExt(String originalName) {
        if (!StringUtils.hasText(originalName)) { return null; }
        int dot = originalName.lastIndexOf('.');
        if (dot < 0 || dot >= originalName.length() - 1) { return null; }
        return originalName.substring(dot + 1).toLowerCase();
    }
}
