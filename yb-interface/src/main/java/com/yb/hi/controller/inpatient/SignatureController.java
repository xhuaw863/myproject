package com.yb.hi.controller.inpatient;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.inpatient.SignatureService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 电子签名接口(T34): 二次密码验证 + 手写签名落盘留痕 + 预设签名读取 + 签名日志查询。
 * <p>守卫口径: 签名是全部岗位的通用能力(医嘱/病历/药审/病案首页/执行核对), 登录即可用,
 * 不在 BizRoleInterceptor 中做模块级角色限制; 租户隔离由服务层显式 tenant_id 条件保证。
 */
@RestController
@RequestMapping("/api/his/signature")
public class SignatureController {

    private final SignatureService signatureService;

    public SignatureController(SignatureService signatureService) {
        this.signatureService = signatureService;
    }

    /**
     * 二次密码验证(当前登录用户): 通过返回 data=true, 密码错误返回 data=false。
     * 密码错误不抛异常, 由前端据此提示重输并停留在签名对话框。
     */
    @PostMapping("/verify-password")
    public R<Boolean> verifyPassword(@RequestBody Map<String, Object> body) {
        String password = body == null ? null : (String) body.get("password");
        return R.ok(signatureService.verifyPassword(password));
    }

    /** 保存签名: {actionType, refType, refId, signImg(Base64 PNG)} → 返回签名图片访问URL。 */
    @PostMapping("/sign")
    public R<String> sign(@RequestBody Map<String, Object> body) {
        if (body == null) {
            throw new BizException(400, "请求体不能为空");
        }
        String actionType = (String) body.get("actionType");
        String refType = (String) body.get("refType");
        Long refId = toLong(body.get("refId"));
        String signImg = (String) body.get("signImg");
        return R.ok(signatureService.saveSignature(actionType, refType, refId, signImg));
    }

    /** 当前用户预设签名(职工签名图URL), 未配置返回 data=null。 */
    @GetMapping("/my-sign")
    public R<String> mySign() {
        return R.ok(signatureService.getSignatureUrl(UserContext.userId()));
    }

    /** 按关联单据查签名日志(时间倒序, 最多 200 条), 供签署追溯与合规审计。 */
    @GetMapping("/logs")
    public R<List<Map<String, Object>>> logs(@RequestParam String refType, @RequestParam Long refId) {
        return R.ok(signatureService.getSignatureLogs(refType, refId));
    }

    /** 宽松数值解析: JSON 数字体可能是 Integer/Long/String, 统一转 Long, 非法返回 null。 */
    private Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
