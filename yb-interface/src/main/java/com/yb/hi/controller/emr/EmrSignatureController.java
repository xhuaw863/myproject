package com.yb.hi.controller.emr;

import com.yb.hi.entity.emr.HisEmrSignatureRule;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
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
 * 病历可靠电子签名接口(Phase D + 病历P2规则链, SM2): 加签 / 验签 / 签名链查看 / 规则链与完成度 / 规则驱动签署。
 * scope 1住院(定位病历ID) / 2门诊(定位就诊ID); stage 住院 author/resident/attending/director, 门诊 doctor。
 * 密钥取机构 sys_org, 内容(content+structure)规范化 SM3 摘要做 SM2 签名, 验签可对抗篡改。
 * 规则链按 his_emr_signature_rule(病历类型1-15)校验签署顺序与签署人职称档位(CV08.30.005)。
 */
@RestController
@RequestMapping("/api/emr/sign")
public class EmrSignatureController {

    private final EmrSignatureService signatureService;
    private final OrgAccessGuard guard;

    public EmrSignatureController(EmrSignatureService signatureService, OrgAccessGuard guard) {
        this.signatureService = signatureService;
        this.guard = guard;
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

    /** 规则驱动签署: POST /api/emr/sign/{scope}/{targetId}/rule?stage=xxx (body 可选 {signerId,titleCode}, 校验顺序与职称档位) */
    @PostMapping("/{scope}/{targetId}/rule")
    public R<Map<String, Object>> signByRule(@PathVariable Integer scope,
                                             @PathVariable Long targetId,
                                             @RequestParam String stage,
                                             @RequestBody(required = false) Map<String, Object> body) {
        Long signerId = body == null || body.get("signerId") == null ? null : Long.valueOf(String.valueOf(body.get("signerId")));
        String titleCode = body == null || body.get("titleCode") == null ? null : String.valueOf(body.get("titleCode"));
        return signatureService.signByRule(targetId, scope, stage, signerId, titleCode);
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

    /** 签名规则链: GET /api/emr/sign/chain/{recordType}?orgId= (病历类型1-15, 机构级优先回退默认链; orgId 缺省取登录机构, 非牵头锁定本机构) */
    @GetMapping("/chain/{recordType}")
    public R<List<HisEmrSignatureRule>> ruleChain(@PathVariable Integer recordType,
                                                  @RequestParam(required = false) Long orgId) {
        return R.ok(signatureService.getSignatureChain(recordType, guard.scopeOrgId(orgId)));
    }

    /** 签名完成度: GET /api/emr/sign/status/{scope}/{targetId} (chain 各环节已签/待签 + allComplete) */
    @GetMapping("/status/{scope}/{targetId}")
    public R<Map<String, Object>> status(@PathVariable Integer scope,
                                         @PathVariable Long targetId) {
        return R.ok(signatureService.getSignatureStatus(targetId, scope));
    }
}
