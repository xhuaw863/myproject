package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.AllergyDTO;
import com.yb.hi.entity.inpatient.HisInpAllergy;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.InpAllergyService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 住院患者过敏记录接口: 就诊/患者维度查询、录入、失效删除, 药品过敏比对(开嘱拦截联动)。
 * 认证与职工校验由服务层统一实施; 租户/机构隔离由框架自动注入。
 */
@RestController
@RequestMapping("/api/his/inp/allergy")
public class InpAllergyController {

    private final InpAllergyService inpAllergyService;

    public InpAllergyController(InpAllergyService inpAllergyService) {
        this.inpAllergyService = inpAllergyService;
    }

    /** 就诊维度过敏记录(仅有效) */
    @GetMapping("/list")
    public R<List<HisInpAllergy>> listByVisit(@RequestParam Long visitId) {
        return inpAllergyService.listByVisit(visitId);
    }

    /** 患者维度过敏记录(跨就诊汇总) */
    @GetMapping("/patient")
    public R<List<HisInpAllergy>> listByPatient(@RequestParam Long patientId) {
        return inpAllergyService.listByPatient(patientId);
    }

    /** 新增过敏记录 */
    @PostMapping
    public R<HisInpAllergy> add(@RequestBody AllergyDTO dto) {
        return inpAllergyService.add(dto);
    }

    /** 失效过敏记录(逻辑失效保留留痕) */
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        return inpAllergyService.remove(id);
    }

    /** 药品过敏比对(返回命中的过敏原描述列表, 空=无冲突) */
    @GetMapping("/check")
    public R<List<String>> check(@RequestParam Long patientId, @RequestParam Long drugId) {
        return R.ok(inpAllergyService.checkDrugAllergy(patientId, drugId));
    }

    /** 是否存在指定过敏原(编码精确匹配) */
    @GetMapping("/has")
    public R<Boolean> has(@RequestParam Long patientId, @RequestParam String allergenCode) {
        return R.ok(inpAllergyService.hasAllergen(patientId, allergenCode));
    }
}
