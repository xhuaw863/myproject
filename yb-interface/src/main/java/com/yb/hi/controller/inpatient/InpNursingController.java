package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.InpNursingDTO;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.InpNursingService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 住院护理记录接口(护士站): 五类记录(体温单/评估/计划/措施/总结)增删改查 + 体温单图表数据。
 * content 为 JSON 文本; 体温单(recordType=1)结构
 * {time, temperature, pulse, respiration, systolicBp, diastolicBp}(兼容 blood_pressure "120/80")。
 */
@RestController
@RequestMapping("/api/his/inp/nursing")
public class InpNursingController {

    private final InpNursingService nursingService;

    public InpNursingController(InpNursingService nursingService) {
        this.nursingService = nursingService;
    }

    /** 护理记录列表(按就诊): 可选 recordType 筛选, 记录时间倒序。 */
    @GetMapping("/list/{visitId}")
    public R<List<Map<String, Object>>> list(@PathVariable Long visitId,
                                             @RequestParam(required = false) Integer recordType) {
        return R.ok(nursingService.listByVisit(visitId, recordType));
    }

    /** 新建护理记录: recordTime 取当前, 护士取当前登录职工。 */
    @PostMapping("/")
    public R<Map<String, Object>> create(@RequestBody InpNursingDTO dto) {
        return R.ok(nursingService.create(dto, InpNurseController.currentNurseId()));
    }

    /** 编辑护理记录(仅 recordType/content, 签名护士与记录时间不变)。 */
    @PutMapping("/{id}")
    public R<Map<String, Object>> update(@PathVariable Long id, @RequestBody InpNursingDTO dto) {
        return R.ok(nursingService.update(id, dto));
    }

    /** 删除护理记录(逻辑删除)。 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        nursingService.delete(id);
        return R.ok();
    }

    /** 体温单数据(recordType=1): 解析 JSON 提取生命体征, 按时间升序返回(折线图数据源)。 */
    @GetMapping("/temperature/{visitId}")
    public R<List<Map<String, Object>>> temperature(@PathVariable Long visitId) {
        return R.ok(nursingService.getTemperatureData(visitId));
    }

    /** 给药执行记录(只读面板): 指定就诊+日期的药品类医嘱执行流水, 按执行时间升序。 */
    @GetMapping("/medication-admin")
    public R<List<Map<String, Object>>> medicationAdmin(@RequestParam Long visitId, @RequestParam String date) {
        return R.ok(nursingService.getMedicationAdminRecord(visitId, date));
    }
}
