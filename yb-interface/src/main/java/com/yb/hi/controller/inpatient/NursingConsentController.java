package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.NursingConsentDTO;
import com.yb.hi.entity.inpatient.HisNursingConsent;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.NursingConsentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 护理告知书/同意书接口(护士站): 创建(待签) / 签名(患者/家属手写签名 base64) / 撤回 / 列表 / 详情。
 * 三态状态机(0待签→1已签 / →2已撤回)与归属机构校验均在 Service 内完成。
 */
@RestController
@RequestMapping("/api/his/inp/nursing/consent")
public class NursingConsentController {

    private final NursingConsentService consentService;

    public NursingConsentController(NursingConsentService consentService) {
        this.consentService = consentService;
    }

    /** 创建告知书: consentType 限固定枚举集, status=0 待签。 */
    @PostMapping({"", "/"})
    public R<HisNursingConsent> create(@RequestBody NursingConsentDTO dto) {
        return R.ok(consentService.create(dto));
    }

    /**
     * 签名: 患者/家属手写签名图片(base64, POST body 传输), 至少一方非空;
     * 家属代签时 signerName 必填, 回填签名时间后置 status=1 已签。
     */
    @PutMapping("/{id}/sign")
    public R<HisNursingConsent> sign(@PathVariable Long id,
                                     @RequestBody SignRequest body) {
        return R.ok(consentService.sign(id, body.getPatientSigBase64(), body.getFamilySigBase64(),
                body.getSignerName(), body.getSignerRelation()));
    }

    /** 撤回: 待签/已签告知书均可撤回(签名图片保留留痕), 置 status=2。 */
    @PutMapping("/{id}/revoke")
    public R<HisNursingConsent> revoke(@PathVariable Long id) {
        return R.ok(consentService.revoke(id));
    }

    /** 告知书列表(按就诊): 待签置顶, 最新在前。 */
    @GetMapping("/list")
    public R<List<HisNursingConsent>> list(@RequestParam Long inpVisitId) {
        return R.ok(consentService.listByVisit(inpVisitId));
    }

    /** 告知书详情(含签名图片 base64)。 */
    @GetMapping("/{id}")
    public R<HisNursingConsent> getById(@PathVariable Long id) {
        return R.ok(consentService.getById(id));
    }

    /** 签名请求体(签名图片较长, 走 body 避免 URL 长度限制) */
    public static class SignRequest {
        /** 患者签名图片 base64 */
        private String patientSigBase64;
        /** 家属签名图片 base64 */
        private String familySigBase64;
        /** 签名人姓名(家属代签必填) */
        private String signerName;
        /** 与患者关系 */
        private String signerRelation;

        public String getPatientSigBase64() {
            return patientSigBase64;
        }

        public void setPatientSigBase64(String patientSigBase64) {
            this.patientSigBase64 = patientSigBase64;
        }

        public String getFamilySigBase64() {
            return familySigBase64;
        }

        public void setFamilySigBase64(String familySigBase64) {
            this.familySigBase64 = familySigBase64;
        }

        public String getSignerName() {
            return signerName;
        }

        public void setSignerName(String signerName) {
            this.signerName = signerName;
        }

        public String getSignerRelation() {
            return signerRelation;
        }

        public void setSignerRelation(String signerRelation) {
            this.signerRelation = signerRelation;
        }
    }
}
