package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.SurgeryMaterialDTO;
import com.yb.hi.entity.inpatient.HisSurgeryMaterial;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.SurgeryMaterialService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 手术耗材接口: 耗材列表 / 登记(双写材料费明细) / 退回(联动退费) / 汇总。
 * 机构隔离: 经手术归属校验(Service 内), 写以 currentOrgId 归属。
 */
@RestController
@RequestMapping("/api/his/surgery-material")
public class SurgeryMaterialController {

    private final SurgeryMaterialService surgeryMaterialService;
    private final OrgAccessGuard guard;

    public SurgeryMaterialController(SurgeryMaterialService surgeryMaterialService, OrgAccessGuard guard) {
        this.surgeryMaterialService = surgeryMaterialService;
        this.guard = guard;
    }

    /** 耗材列表(按手术) */
    @GetMapping("/list/{surgeryId}")
    public R<List<HisSurgeryMaterial>> list(@PathVariable Long surgeryId) {
        return R.ok(surgeryMaterialService.listMaterials(surgeryId));
    }

    /** 添加耗材(同步写入 his_inp_charge_detail 作为材料费) */
    @PostMapping
    public R<HisSurgeryMaterial> add(@RequestBody SurgeryMaterialDTO dto) {
        return R.ok(surgeryMaterialService.addMaterial(dto, guard.currentOrgId()));
    }

    /** 退回耗材(逻辑删除 + 费用明细退费 + 回减就诊总费用) */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        surgeryMaterialService.deleteMaterial(id);
        return R.ok();
    }

    /** 耗材汇总(SUM amount) */
    @GetMapping("/summary/{surgeryId}")
    public R<Map<String, Object>> summary(@PathVariable Long surgeryId) {
        return R.ok(surgeryMaterialService.summary(surgeryId));
    }
}
