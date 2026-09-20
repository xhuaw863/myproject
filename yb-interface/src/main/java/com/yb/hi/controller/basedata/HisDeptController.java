package com.yb.hi.controller.basedata;

import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.basedata.HisDeptService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 科室管理接口
 */
@RestController
@RequestMapping("/api/his/dept")
public class HisDeptController {

    private final HisDeptService service;

    public HisDeptController(HisDeptService service) {
        this.service = service;
    }

    @GetMapping("/list")
    public R<List<HisDept>> list(@RequestParam(required = false) Long orgId) {
        return R.ok(service.listAll(orgId));
    }

    @GetMapping("/enabled")
    public R<List<HisDept>> enabled(@RequestParam(required = false) Long orgId) {
        return R.ok(service.listEnabled(orgId));
    }

    /** 科室层级树(大类→科室→窗口/诊室), 用于树形维护与授权科室选择 */
    @GetMapping("/tree")
    public R<List<HisDept>> tree(@RequestParam(required = false) Long orgId) {
        return R.ok(service.listTree(orgId));
    }

    @PostMapping
    public R<Void> create(@RequestBody HisDept e) {
        if (e.getOrgId() == null) {
            LoginUser lu = UserContext.get();
            if (lu != null) {
                e.setOrgId(lu.getOrgId());
            }
        }
        service.saveDept(e);
        return R.ok();
    }

    @PutMapping
    public R<Void> update(@RequestBody HisDept e) {
        service.updateDept(e);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        service.removeById(id);
        return R.ok();
    }
}
