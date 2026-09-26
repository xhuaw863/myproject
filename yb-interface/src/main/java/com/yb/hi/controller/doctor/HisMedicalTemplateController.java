package com.yb.hi.controller.doctor;

import com.yb.hi.entity.doctor.HisMedicalTemplate;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.doctor.HisMedicalTemplateService;
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

/**
 * 医生工作站医疗模板接口
 */
@RestController
@RequestMapping("/api/his/template")
public class HisMedicalTemplateController {

    private final HisMedicalTemplateService service;

    public HisMedicalTemplateController(HisMedicalTemplateService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public R<List<HisMedicalTemplate>> list(@RequestParam(required = false) String type,
                                             @RequestParam(required = false) String keyword) {
        return R.ok(service.listVisible(type, keyword));
    }

    @GetMapping("/{id}")
    public R<HisMedicalTemplate> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/create")
    public R<HisMedicalTemplate> create(@RequestBody HisMedicalTemplate template) {
        return R.ok(service.create(template));
    }

    @PutMapping("/{id}")
    public R<HisMedicalTemplate> update(@PathVariable Long id, @RequestBody HisMedicalTemplate template) {
        return R.ok(service.update(id, template));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return R.ok();
    }
}
