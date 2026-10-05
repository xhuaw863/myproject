package com.yb.hi.service.inpatient;

import cn.hutool.crypto.digest.BCrypt;
import com.yb.hi.common.UploadUrlSigner;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 电子签名服务(T34): 二次密码验证 + 签名图片落盘留痕 + 预设签名读取 + 签名日志查询。
 * <p>密码口径与登录链路一致(hutool BCrypt, 散列只落库不入日志);
 * 文件口径与 FileUploadController 一致({his.upload.path}/signatures/{yyyyMM}/{uuid}.png,
 * 经 WebMvcConfig 静态映射以 {his.upload.url-prefix} 前缀访问)。
 * <p>说明: JdbcTemplate 手写 SQL 不走 MyBatis-Plus 租户插件, 全部显式带 tenant_id AND deleted=0。
 */
@Slf4j
@Service
public class SignatureService {

    /** 签名图片 Base64 解码后大小上限(手写签名 PNG 通常 < 200KB, 2MB 留足余量) */
    private static final int MAX_IMG_BYTES = 2 * 1024 * 1024;

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    private final JdbcTemplate jdbcTemplate;

    @Value("${his.upload.path:./data/upload/}")
    private String uploadPath;

    @Value("${his.upload.url-prefix:/uploads/}")
    private String urlPrefix;

    @Autowired
    private UploadUrlSigner uploadUrlSigner;

    public SignatureService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ==================== 密码验证 ==================== */

    /** 二次密码验证(当前登录用户): 与登录链路同口径 hutool BCrypt.checkpw; 密码错误返回 false(不抛异常)。 */
    public boolean verifyPassword(String password) {
        LoginUser cur = UserContext.get();
        if (cur == null || cur.getUserId() == null) {
            throw new BizException(401, "未登录");
        }
        return verifyPasswordForUser(cur.getUserId(), password);
    }

    /** 指定用户密码验证(双人核对用): 目标用户限本租户, 散列比对不落明文。 */
    public boolean verifyPasswordForUser(Long userId, String password) {
        if (userId == null || password == null || password.isEmpty()) {
            return false;
        }
        List<String> hashes = jdbcTemplate.queryForList(
                "SELECT password FROM sys_user WHERE id = ? AND tenant_id = ? AND deleted = 0 LIMIT 1",
                String.class, userId, TenantContext.require());
        if (hashes.isEmpty() || hashes.get(0) == null) {
            return false;
        }
        try {
            return BCrypt.checkpw(password, hashes.get(0));
        } catch (Exception e) {
            // 散列格式异常(历史脏数据)按验证失败处理, 不向外抛栈
            log.warn("密码散列比对异常: userId={}, msg={}", userId, e.getMessage());
            return false;
        }
    }

    /* ==================== 签名保存 ==================== */

    /**
     * 保存签名: Base64 PNG → 落盘 signatures/{yyyyMM}/{uuid}.png → 写 his_signature_log 留痕 → 返回访问URL。
     * 日志写入失败时补偿清理已落盘文件, 不留下孤儿图片。
     */
    public String saveSignature(String actionType, String refType, Long refId, String signImgBase64) {
        LoginUser cur = UserContext.get();
        if (cur == null || cur.getUserId() == null) {
            throw new BizException(401, "未登录");
        }
        if (actionType == null || actionType.trim().isEmpty()) {
            throw new BizException(400, "签名场景(actionType)不能为空");
        }
        byte[] png = decodePng(signImgBase64);
        String url = writePngFile(png);
        try {
            jdbcTemplate.update("INSERT INTO his_signature_log"
                            + " (user_id, user_name, action_type, ref_type, ref_id, sign_img_url, ip_address, sign_time, tenant_id, deleted)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, 0)",
                    cur.getUserId(), displayName(cur), actionType.trim(), refType, refId, url,
                    clientIp(), TenantContext.require());
        } catch (Exception e) {
            deleteQuietly(url);
            throw e instanceof RuntimeException ? (RuntimeException) e
                    : new BizException("签名日志写入失败: " + e.getMessage());
        }
        log.info("电子签名完成: user={}, action={}, ref={}/{}", cur.getUsername(), actionType, refType, refId);
        return url;
    }

    /** 解析 data:image/png;base64,xxx(或裸 Base64)为 PNG 字节; 非法格式/非 PNG/超限均抛业务异常。 */
    private byte[] decodePng(String signImg) {
        if (signImg == null || signImg.trim().isEmpty()) {
            throw new BizException(400, "签名图片为空");
        }
        String b64 = signImg.trim();
        int comma = b64.indexOf(',');
        if (b64.startsWith("data:") && comma > 0) {
            b64 = b64.substring(comma + 1);
        }
        byte[] bytes;
        try {
            // MIME 解码器容忍换行/空白, 兼容各类前端导出实现
            bytes = Base64.getMimeDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new BizException(400, "签名图片 Base64 非法");
        }
        // PNG 魔数校验(0x89 'P' 'N' 'G'), 防止任意字节流落盘
        if (bytes.length < 8 || bytes[0] != (byte) 0x89 || bytes[1] != 0x50 || bytes[2] != 0x4E || bytes[3] != 0x47) {
            throw new BizException(400, "签名图片必须为 PNG 格式");
        }
        if (bytes.length > MAX_IMG_BYTES) {
            throw new BizException(400, "签名图片过大(超过 2MB)");
        }
        return bytes;
    }

    /** PNG 落盘: {uploadPath}/signatures/{yyyyMM}/{uuid}.png, 返回 {urlPrefix}/signatures/... 访问URL。 */
    private String writePngFile(byte[] png) {
        try {
            String ym = LocalDate.now().format(YM);
            String filename = UUID.randomUUID().toString().replace("-", "") + ".png";
            Path root = Paths.get(uploadPath).toAbsolutePath().normalize();
            Path dir = root.resolve("signatures").resolve(ym);
            Files.createDirectories(dir);
            Files.write(dir.resolve(filename), png);
            return uploadUrlSigner.appendToken(joinUrl(urlPrefix, "signatures/" + ym + "/" + filename));
        } catch (IOException e) {
            throw new BizException("签名图片保存失败: " + e.getMessage());
        }
    }

    /** 补偿清理: 按访问URL反推磁盘路径并删除(尽力而为, 失败仅记日志)。 */
    private void deleteQuietly(String url) {
        try {
            String prefix = urlPrefix.endsWith("/") ? urlPrefix : urlPrefix + "/";
            if (url == null || !url.startsWith(prefix)) {
                return;
            }
            Path path = Paths.get(uploadPath).toAbsolutePath().normalize()
                    .resolve(uploadUrlSigner.pathOnly(url).substring(prefix.length()));
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.warn("签名文件补偿清理失败: url={}, msg={}", url, e.getMessage());
        }
    }

    /* ==================== 预设签名 / 日志 ==================== */

    /** 获取用户预设签名(职工签名图): sys_user.staff_id → his_staff.sign_img_url; 无职工或未上传返回 null。 */
    public String getSignatureUrl(Long userId) {
        if (userId == null) {
            return null;
        }
        Long tenantId = TenantContext.require();
        List<Long> staffIds = jdbcTemplate.queryForList(
                "SELECT staff_id FROM sys_user WHERE id = ? AND tenant_id = ? AND deleted = 0 LIMIT 1",
                Long.class, userId, tenantId);
        if (staffIds.isEmpty() || staffIds.get(0) == null) {
            return null;
        }
        List<String> urls = jdbcTemplate.queryForList(
                "SELECT sign_img_url FROM his_staff WHERE id = ? AND tenant_id = ? AND deleted = 0 LIMIT 1",
                String.class, staffIds.get(0), tenantId);
        return urls.isEmpty() ? null : urls.get(0);
    }

    /** 按关联单据查签名日志(时间倒序, 最多 200 条), 供签署追溯与合规审计。 */
    public List<Map<String, Object>> getSignatureLogs(String refType, Long refId) {
        if (refType == null || refType.trim().isEmpty() || refId == null) {
            throw new BizException(400, "refType 与 refId 不能为空");
        }
        return jdbcTemplate.queryForList(
                "SELECT id, user_id AS userId, user_name AS userName, action_type AS actionType,"
                        + " ref_type AS refType, ref_id AS refId, sign_img_url AS signImgUrl,"
                        + " ip_address AS ipAddress, sign_time AS signTime"
                        + " FROM his_signature_log"
                        + " WHERE tenant_id = ? AND deleted = 0 AND ref_type = ? AND ref_id = ?"
                        + " ORDER BY sign_time DESC, id DESC LIMIT 200",
                TenantContext.require(), refType.trim(), refId);
    }

    /* ==================== 私有助手 ==================== */

    /** 签名人显示名: 优先姓名, 回退账号, 截断至 50 字符(对齐列宽)。 */
    private String displayName(LoginUser cur) {
        String name = (cur.getRealName() != null && !cur.getRealName().isEmpty())
                ? cur.getRealName() : cur.getUsername();
        if (name == null) {
            name = "user-" + cur.getUserId();
        }
        return name.length() > 50 ? name.substring(0, 50) : name;
    }

    /** 签名来源IP: 优先 X-Forwarded-For 首段(经代理部署), 回退 RemoteAddr; 取不到返回 null。 */
    private String clientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            HttpServletRequest req = attrs.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.trim().isEmpty()) {
                int comma = xff.indexOf(',');
                return (comma > 0 ? xff.substring(0, comma) : xff).trim();
            }
            return req.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }

    /** 拼接URL前缀与相对路径, 保证单斜杠分隔(与 FileUploadController 同口径)。 */
    private String joinUrl(String prefix, String rel) {
        String p = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        String r = rel.startsWith("/") ? rel.substring(1) : rel;
        return p + "/" + r;
    }
}
