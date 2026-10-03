package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.NursingEducationDTO;
import com.yb.hi.entity.inpatient.HisNursingEducation;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.NursingEducationService;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 护理健康宣教接口(护士站): 宣教登记 / 列表 / 详情 / 删除(误录) / 知识分类库。
 * 分类/方式/评价枚举校验与归属机构校验均在 Service 内完成。
 */
@RestController
@RequestMapping("/api/his/inp/nursing/education")
public class NursingEducationController {

    private final NursingEducationService educationService;

    public NursingEducationController(NursingEducationService educationService) {
        this.educationService = educationService;
    }

    /** 登记宣教记录: knowledgeCategory 限固定枚举集, 宣教人=当前登录职工。 */
    @PostMapping({"", "/"})
    public R<HisNursingEducation> record(@RequestBody NursingEducationDTO dto) {
        return R.ok(educationService.record(dto));
    }

    /** 宣教记录列表(按就诊): 宣教时间倒序。 */
    @GetMapping("/list")
    public R<List<HisNursingEducation>> list(@RequestParam Long inpVisitId) {
        return R.ok(educationService.listByVisit(inpVisitId));
    }

    /** 宣教记录详情。 */
    @GetMapping("/{id}")
    public R<HisNursingEducation> getById(@PathVariable Long id) {
        return R.ok(educationService.getById(id));
    }

    /** 删除宣教记录(逻辑删除, 误录场景)。 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        educationService.delete(id);
        return R.ok();
    }

    /** 知识分类库: [{code, name, description, items[]}], 供宣教登记面板下拉与条目提示。 */
    @GetMapping("/knowledge-categories")
    public R<List<Map<String, Object>>> knowledgeCategories() {
        return R.ok(educationService.getKnowledgeCategories());
    }
}
