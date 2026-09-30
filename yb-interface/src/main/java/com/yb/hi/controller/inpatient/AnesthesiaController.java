package com.yb.hi.controller.inpatient;

import com.yb.hi.dto.inpatient.AnesthesiaDTO;
import com.yb.hi.entity.inpatient.HisAnesthesia;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.inpatient.AnesthesiaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 麻醉记录接口: 查询(按手术一对一) / 创建 / 编辑 / 追加生命体征 / 追加术中事件 / 完成。
 * 机构隔离: 经手术归属校验(Service 内), 写以 currentOrgId 归属。
 */
@RestController
@RequestMapping("/api/his/anesthesia")
public class AnesthesiaController {

    private final AnesthesiaService anesthesiaService;
    private final OrgAccessGuard guard;

    public AnesthesiaController(AnesthesiaService anesthesiaService, OrgAccessGuard guard) {
        this.anesthesiaService = anesthesiaService;
        this.guard = guard;
    }

    /** 手术的麻醉记录(一对一; 未创建时返回 null) */
    @GetMapping("/{surgeryId}")
    public R<HisAnesthesia> getBySurgery(@PathVariable Long surgeryId) {
        return R.ok(anesthesiaService.getBySurgeryId(surgeryId));
    }

    /** 创建麻醉记录(一台手术仅一条) */
    @PostMapping
    public R<HisAnesthesia> create(@RequestBody AnesthesiaDTO dto) {
        return R.ok(anesthesiaService.create(dto, guard.currentOrgId()));
    }

    /** 编辑麻醉记录(仅 status=1 记录中) */
    @PutMapping("/{id}")
    public R<HisAnesthesia> update(@PathVariable Long id, @RequestBody AnesthesiaDTO dto) {
        return R.ok(anesthesiaService.update(id, dto));
    }

    /** 追加生命体征({time, hr, sbp, dbp, spo2, temp, etco2, rr}) */
    @PostMapping("/{id}/vital-sign")
    public R<HisAnesthesia> vitalSign(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        return R.ok(anesthesiaService.appendVitalSign(id, data));
    }

    /** 追加术中事件({time, eventType, description}) */
    @PostMapping("/{id}/event")
    public R<HisAnesthesia> event(@PathVariable Long id, @RequestBody Map<String, Object> data) {
        return R.ok(anesthesiaService.appendEvent(id, data));
    }

    /** 完成麻醉记录(status 1->2) */
    @PutMapping("/{id}/complete")
    public R<HisAnesthesia> complete(@PathVariable Long id) {
        return R.ok(anesthesiaService.complete(id));
    }
}
