package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.NewbornDTO;
import com.yb.hi.entity.inpatient.HisNewborn;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.NewbornService;
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
 * 新生儿建档接口(P2d 产科分娩一体化): 建档 register / 按母亲查询 / 详情 / 补录 / 转科 / 出院。
 * 医嘱复用住院医嘱链(挂 baby_inp_visit_id, 走 /api/his/inp/order)。机构隔离在 Service 内统一走 OrgAccessGuard。
 */
@RestController
@RequestMapping("/api/his/newborn")
public class NewbornController {

    private final NewbornService newbornService;

    public NewbornController(NewbornService newbornService) {
        this.newbornService = newbornService;
    }

    /** 新生儿建档(母亲住院就诊 + 新生儿信息 -> 建患者档案 + 住院就诊 + 绑定记录) */
    @PostMapping("/register")
    public R<HisNewborn> register(@RequestBody NewbornDTO dto) {
        return R.ok(newbornService.register(dto));
    }

    /** 按母亲住院就诊查询新生儿列表 */
    @GetMapping("/list")
    public R<List<Map<String, Object>>> list(@RequestParam Long motherInpVisitId) {
        return R.ok(newbornService.listByMother(motherInpVisitId));
    }

    /** 分娩手术建档引导(module_type=3 且无关联新生儿 -> needRegister) */
    @GetMapping("/guide/{surgeryId}")
    public R<Map<String, Object>> guide(@PathVariable Long surgeryId) {
        return R.ok(newbornService.registerGuide(surgeryId));
    }

    /** 新生儿档案详情 */
    @GetMapping("/{id}")
    public R<HisNewborn> detail(@PathVariable Long id) {
        return R.ok(newbornService.detail(id));
    }

    /** 补录新生儿信息(Apgar/体重/身长/分娩方式等) */
    @PutMapping("/{id}")
    public R<HisNewborn> update(@PathVariable Long id, @RequestBody NewbornDTO dto) {
        return R.ok(newbornService.update(id, dto));
    }

    /** 转科(body 可选 deptId) */
    @PutMapping("/{id}/transfer")
    public R<HisNewborn> transfer(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        Long deptId = null;
        if (body != null && body.get("deptId") != null) {
            Object d = body.get("deptId");
            deptId = d instanceof Number ? ((Number) d).longValue() : Long.valueOf(String.valueOf(d).trim());
        }
        return R.ok(newbornService.transfer(id, deptId));
    }

    /** 出院 */
    @PutMapping("/{id}/discharge")
    public R<HisNewborn> discharge(@PathVariable Long id) {
        return R.ok(newbornService.discharge(id));
    }
}
