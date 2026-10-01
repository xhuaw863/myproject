package com.yb.hi.controller.doctor;

import com.yb.hi.entity.doctor.HisConsent;
import com.yb.hi.entity.doctor.HisDogBiteRegister;
import com.yb.hi.entity.doctor.HisGreenChannelCredit;
import com.yb.hi.entity.doctor.HisOutpAgent;
import com.yb.hi.entity.doctor.HisReferral;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.doctor.OutpWsBusinessService;
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
 * 门诊诊间业务接口(OP-D 14.8/14.11): 知情同意 / 代办 / 转诊 / 绿通信用 / 犬伤登记
 */
@RestController
@RequestMapping("/api/his/outp-biz")
public class OutpWsBusinessController {

    private final OutpWsBusinessService service;

    public OutpWsBusinessController(OutpWsBusinessService service) {
        this.service = service;
    }

    /* ===== 知情同意书 ===== */

    @PostMapping("/consent/create")
    public R<HisConsent> consentCreate(@RequestBody HisConsent consent) {
        return R.ok(service.consentCreate(consent));
    }

    @GetMapping("/consent/list")
    public R<List<HisConsent>> consentList(@RequestParam Long visitId) {
        return R.ok(service.consentList(visitId));
    }

    @PostMapping("/consent/{id}/sign")
    public R<HisConsent> consentSign(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String signName = str(body, "patientSignName");
        String relation = str(body, "relation");
        String witness = str(body, "witnessName");
        return R.ok(service.consentSign(id, signName, relation, witness));
    }

    @PostMapping("/consent/{id}/cancel")
    public R<HisConsent> consentCancel(@PathVariable Long id) {
        return R.ok(service.consentCancel(id));
    }

    /* ===== 代办登记 ===== */

    @PostMapping("/agent/create")
    public R<HisOutpAgent> agentCreate(@RequestBody HisOutpAgent agent) {
        return R.ok(service.agentCreate(agent));
    }

    @GetMapping("/agent/list")
    public R<List<HisOutpAgent>> agentList(@RequestParam Long visitId) {
        return R.ok(service.agentList(visitId));
    }

    /* ===== 转诊登记 ===== */

    @PostMapping("/referral/create")
    public R<HisReferral> referralCreate(@RequestBody HisReferral referral) {
        return R.ok(service.referralCreate(referral));
    }

    @GetMapping("/referral/list")
    public R<List<HisReferral>> referralList(@RequestParam Long patientId) {
        return R.ok(service.referralList(patientId));
    }

    @PostMapping("/referral/{id}/status")
    public R<HisReferral> referralStatus(@PathVariable Long id, @RequestParam Integer status) {
        return R.ok(service.referralUpdateStatus(id, status));
    }

    /* ===== 绿色通道信用 ===== */

    @PostMapping("/green/create")
    public R<HisGreenChannelCredit> greenCreate(@RequestBody HisGreenChannelCredit credit) {
        return R.ok(service.greenCreate(credit));
    }

    @GetMapping("/green/list")
    public R<List<HisGreenChannelCredit>> greenList(@RequestParam Long patientId) {
        return R.ok(service.greenList(patientId));
    }

    @PostMapping("/green/{id}/revoke")
    public R<HisGreenChannelCredit> greenRevoke(@PathVariable Long id) {
        return R.ok(service.greenRevoke(id));
    }

    /* ===== 犬伤登记 ===== */

    @PostMapping("/dogbite/create")
    public R<HisDogBiteRegister> dogbiteCreate(@RequestBody HisDogBiteRegister register) {
        return R.ok(service.dogbiteCreate(register));
    }

    @GetMapping("/dogbite/list")
    public R<List<HisDogBiteRegister>> dogbiteList(@RequestParam Long visitId) {
        return R.ok(service.dogbiteList(visitId));
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : v.toString();
    }
}
