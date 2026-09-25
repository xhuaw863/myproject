package com.yb.hi.controller.basedata;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.basedata.BatchTemplateReq;
import com.yb.hi.entity.basedata.HisScheduleTemplate;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.basedata.ScheduleTemplateService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 周排班模板管理接口(机构级业务过程)
 * 读: 硬限定当前登录机构(currentOrgId, 经科室归属过滤); 写: 本机构管理员(requireSelfOrgWrite)。
 */
@RestController
@RequestMapping("/api/his/schedule/template")
public class ScheduleTemplateController {

    private final ScheduleTemplateService templateService;
    private final OrgAccessGuard guard;

    public ScheduleTemplateController(ScheduleTemplateService templateService, OrgAccessGuard guard) {
        this.templateService = templateService;
        this.guard = guard;
    }

    @GetMapping("/list")
    public R<IPage<HisScheduleTemplate>> list(@RequestParam(required = false) Long deptId,
                                              @RequestParam(required = false) Long staffId,
                                              @RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "50") long size) {
        return R.ok(templateService.listPage(guard.currentOrgId(), deptId, staffId, page, size));
    }

    @PostMapping
    public R<HisScheduleTemplate> create(@RequestBody HisScheduleTemplate template) {
        guard.requireSelfOrgWrite();
        return R.ok(templateService.create(template));
    }

    @PutMapping
    public R<HisScheduleTemplate> update(@RequestBody HisScheduleTemplate template) {
        guard.requireSelfOrgWrite();
        return R.ok(templateService.update(template));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        guard.requireSelfOrgWrite();
        templateService.delete(id);
        return R.ok(null);
    }

    @PostMapping("/batch")
    public R<Map<String, Object>> batch(@RequestBody BatchTemplateReq req) {
        guard.requireSelfOrgWrite();
        return R.ok(templateService.batchCreate(req));
    }
}
