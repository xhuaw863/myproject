package com.yb.hi.controller.emr;

import com.yb.hi.framework.common.R;
import com.yb.hi.service.emr.EmrSignatureService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 病历可靠电子签名接口(Phase D, SM2): 加签 / 验签 / 签名链查看。
 * scope 1住院(定位病历ID) / 2门诊(定位就诊ID); stage 住院 author/resident/attending/director, 门诊 doctor。
 * 密钥取机构 sys_org, 内容(content+structure)规范化 SM3 摘要做 SM2 签名, 验签可对抗篡改。
 */
@RestController
@RequestMapping("/api/emr/sign")
public class EmrSignatureController {

    private final EmrSignatureService signatureService;

    public EmrSignatureController(EmrSignatureService signatureService) {
        this.signatureService = signatureService;
    }

    /** 加签: POST /api/emr/sign/{scope}/{targetId}?stage=xxx (body 可选 {signImg}) */
    @PostMapping("/{scope}/{targetId}")
    public R<Map<String, Object>> sign(@PathVariable Integer scope,
                                       @PathVariable Long targetId,
                                       @RequestParam String stage,
                                       @RequestBody(required = false) Map<String, Object> body) {
        String signImg = body == null ? null : (body.get("signImg") == null ? null : String.valueOf(body.get("signImg")));
        return signatureService.sign(scope, targetId, stage, signImg);
    }

    /** 验签: GET /api/emr/sign/verify/{scope}/{targetId}?stage=xxx (stage 空则验最新有效签名) */
    @GetMapping("/verify/{scope}/{targetId}")
    public R<Map<String, Object>> verify(@PathVariable Integer scope,
                                         @PathVariable Long targetId,
                                         @RequestParam(required = false) String stage) {
        return signatureService.verify(scope, targetId, stage);
    }

    /** 签名链: GET /api/emr/sign/chain/{scope}/{targetId} (有效签名时间正序) */
    @GetMapping("/chain/{scope}/{targetId}")
    public R<List<Map<String, Object>>> chain(@PathVariable Integer scope,
                                              @PathVariable Long targetId) {
        return signatureService.chain(scope, targetId);
    }
}
