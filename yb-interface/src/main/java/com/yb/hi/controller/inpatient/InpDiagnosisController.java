package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.InpDiagnosisDTO;
import com.yb.hi.entity.inpatient.HisInpDiagnosis;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.InpDiagnosisService;
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
 * 住院诊断接口: 诊断列表(按诊断类型分组) / 新增 / 修改 / 删除。
 * 机构隔离与主诊断互斥在 Service 层(见 InpDiagnosisService)。
 */
@RestController
@RequestMapping("/api/his/inp/diagnosis")
public class InpDiagnosisController {

    private final InpDiagnosisService inpDiagnosisService;

    public InpDiagnosisController(InpDiagnosisService inpDiagnosisService) {
        this.inpDiagnosisService = inpDiagnosisService;
    }

    /** 诊断列表(按diag_type分组: 1入院 2补充 3术后 4出院) */
    @GetMapping("/{visitId}")
    public R<Map<String, List<HisInpDiagnosis>>> list(@PathVariable Long visitId) {
        return R.ok(inpDiagnosisService.listByVisit(visitId));
    }

    /** ICD编码校验(不阻断保存): 返回 {valid, warning, suggestions} 供录入时提醒 */
    @GetMapping("/validate-icd")
    public R<Map<String, Object>> validateIcd(@RequestParam String code) {
        return R.ok(inpDiagnosisService.validateIcdCode(code));
    }

    /** 新增诊断(is_main=1时同就诊同类型其他主诊断自动改为0) */
    @PostMapping
    public R<HisInpDiagnosis> create(@RequestBody InpDiagnosisDTO dto) {
        return R.ok(inpDiagnosisService.save(dto));
    }

    /** 修改诊断 */
    @PutMapping("/{id}")
    public R<HisInpDiagnosis> update(@PathVariable Long id, @RequestBody InpDiagnosisDTO dto) {
        return R.ok(inpDiagnosisService.update(id, dto));
    }

    /** 删除诊断(逻辑删除) */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        inpDiagnosisService.delete(id);
        return R.ok();
    }
}
