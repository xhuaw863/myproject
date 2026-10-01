package com.yb.hi.controller.doctor;

import com.yb.hi.entity.doctor.HisPreConsult;
import com.yb.hi.entity.doctor.HisUserDisplayPref;
import com.yb.hi.entity.doctor.HisVitalSign;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.doctor.OutpEncounterService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 门诊医生站就诊增强接口(OP-A): 显示偏好 / 生命体征(含趋势与设备采集占位) / 诊前预问诊 / 发热登记。
 */
@RestController
@RequestMapping("/api/his/outp")
public class OutpEncounterController {

    private final OutpEncounterService service;

    public OutpEncounterController(OutpEncounterService service) {
        this.service = service;
    }

    /* ---------- 患者信息栏/布局显示偏好 ---------- */

    @GetMapping("/pref")
    public R<HisUserDisplayPref> getPref(@RequestParam String scene) {
        return R.ok(service.getPref(scene));
    }

    @PutMapping("/pref")
    public R<HisUserDisplayPref> savePref(@RequestParam String scene, @RequestBody Map<String, Object> body) {
        return R.ok(service.savePref(scene, body == null ? null : str(body.get("configJson"))));
    }

    /* ---------- 生命体征 ---------- */

    @GetMapping("/vital")
    public R<List<HisVitalSign>> listVital(@RequestParam Long visitId) {
        return R.ok(service.listVitalByVisit(visitId));
    }

    @PostMapping("/vital")
    public R<Map<String, Object>> saveVital(@RequestBody HisVitalSign vital) {
        boolean fever = service.saveVital(vital);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("feverTriggered", fever);
        return R.ok(data);
    }

    /** 体征采集设备对接占位: 与手工录入同链路, source 固定为"设备" */
    @PostMapping("/vital/device")
    public R<Map<String, Object>> saveVitalFromDevice(@RequestBody HisVitalSign vital) {
        vital.setSource("设备");
        boolean fever = service.saveVital(vital);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("feverTriggered", fever);
        return R.ok(data);
    }

    @GetMapping("/vital/trend")
    public R<List<HisVitalSign>> trend(@RequestParam Long patientId,
                                       @RequestParam(defaultValue = "30") int limit) {
        return R.ok(service.trend(patientId, limit));
    }

    /* ---------- 诊前预问诊 ---------- */

    @GetMapping("/pre-consult")
    public R<HisPreConsult> getPreConsult(@RequestParam Long visitId) {
        return R.ok(service.getPreConsult(visitId));
    }

    @PostMapping("/pre-consult")
    public R<HisPreConsult> savePreConsult(@RequestParam Long visitId, @RequestBody Map<String, Object> body) {
        return R.ok(service.savePreConsult(visitId, body == null ? null : str(body.get("contentJson"))));
    }

    /* ---------- 发热登记 ---------- */

    @GetMapping("/fever")
    public R<List<?>> listFever(@RequestParam Long visitId) {
        return R.ok(service.listFeverByVisit(visitId));
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
