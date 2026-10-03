package com.yb.hi.controller.emr;

import com.yb.hi.framework.common.R;
import com.yb.hi.service.emr.CaSignatureService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 病历多方式签名接口(P8b-1): 文字 / 图片 / CA 三种签名方式 + 验签(单条/批量/归档前) +
 * 患者家属签名留存 + 签名信息回查。
 *
 * 签名记录落 his_emr_signature; 文字/图片复用 /api/emr/sign 同一 SM2 规则链(环节自动推进到下一待签必需环节),
 * CA 方式另附病历原文 SHA-256 哈希与 CA 签名值(当前为模拟实现, 预留厂商 SDK 对接)。
 */
@RestController
@RequestMapping("/api/emr/ca-sign")
public class CaSignatureController {

    private final CaSignatureService caSignatureService;

    public CaSignatureController(CaSignatureService caSignatureService) {
        this.caSignatureService = caSignatureService;
    }

    /** 文字签名: POST /api/emr/ca-sign/sign-text, body {recordId, signerId} */
    @PostMapping("/sign-text")
    public R<Map<String, Object>> signByText(@RequestBody(required = false) Map<String, Object> body) {
        Long recordId = body == null ? null : toLong(body.get("recordId"));
        Long signerId = body == null ? null : toLong(body.get("signerId"));
        return R.ok(caSignatureService.signByText(recordId, signerId));
    }

    /** 图片签名: POST /api/emr/ca-sign/sign-image, body {recordId, signerId, imageBase64} */
    @PostMapping("/sign-image")
    public R<Map<String, Object>> signByImage(@RequestBody(required = false) Map<String, Object> body) {
        Long recordId = body == null ? null : toLong(body.get("recordId"));
        Long signerId = body == null ? null : toLong(body.get("signerId"));
        String imageBase64 = body == null ? null : str(body.get("imageBase64"));
        return R.ok(caSignatureService.signByImage(recordId, signerId, imageBase64));
    }

    /** CA数字签名: POST /api/emr/ca-sign/sign-ca, body {recordId, signerId, certSn, signatureAlgorithm} */
    @PostMapping("/sign-ca")
    public R<Map<String, Object>> signByCa(@RequestBody(required = false) Map<String, Object> body) {
        Long recordId = body == null ? null : toLong(body.get("recordId"));
        Long signerId = body == null ? null : toLong(body.get("signerId"));
        Map<String, Object> caReq = new LinkedHashMap<>();
        if (body != null) {
            caReq.put("certSn", body.get("certSn"));
            caReq.put("signatureAlgorithm", body.get("signatureAlgorithm"));
        }
        return R.ok(caSignatureService.signByCa(recordId, signerId, caReq));
    }

    /** 单条验签: POST /api/emr/ca-sign/verify/{signatureId} */
    @PostMapping("/verify/{signatureId}")
    public R<Map<String, Object>> verify(@PathVariable Long signatureId) {
        return R.ok(caSignatureService.verify(signatureId));
    }

    /** 批量验签: POST /api/emr/ca-sign/batch-verify, body {signatureIds: []} */
    @PostMapping("/batch-verify")
    public R<List<Map<String, Object>>> batchVerify(@RequestBody(required = false) Map<String, Object> body) {
        List<Long> ids = new ArrayList<>();
        Object raw = body == null ? null : body.get("signatureIds");
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                Long id = toLong(o);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return R.ok(caSignatureService.batchVerify(ids));
    }

    /** 归档前自动验签: POST /api/emr/ca-sign/verify-archive/{recordId} */
    @PostMapping("/verify-archive/{recordId}")
    public R<Map<String, Object>> verifyOnArchive(@PathVariable Long recordId) {
        return R.ok(caSignatureService.verifyOnArchive(recordId));
    }

    /** 患者/家属签名留存: POST /api/emr/ca-sign/patient-sign/{signatureId}, body {patientImage, familyImage} */
    @PostMapping("/patient-sign/{signatureId}")
    public R<Void> savePatientSign(@PathVariable Long signatureId,
                                   @RequestBody(required = false) Map<String, Object> body) {
        String patientImage = body == null ? null : str(body.get("patientImage"));
        String familyImage = body == null ? null : str(body.get("familyImage"));
        caSignatureService.savePatientSign(signatureId, patientImage, familyImage);
        return R.ok();
    }

    /** 记录签名信息列表: GET /api/emr/ca-sign/sign-info/{recordId} */
    @GetMapping("/sign-info/{recordId}")
    public R<List<Map<String, Object>>> getSignInfo(@PathVariable Long recordId) {
        return R.ok(caSignatureService.getSignInfo(recordId));
    }

    /** 对象转 Long(null/非法返回 null; 兼容雪花ID字符串入参)。 */
    private static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 对象转字符串(null 返回 null)。 */
    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
