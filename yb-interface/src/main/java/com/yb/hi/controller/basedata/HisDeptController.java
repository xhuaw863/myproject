package com.yb.hi.controller.basedata;

import com.alibaba.excel.EasyExcel;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.basedata.HisDeptService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 科室管理接口
 * 读: 非牵头机构强制本院(scopeOrgId); 写: 仅牵头机构管理员(requireLeadWrite)。
 */
@RestController
@RequestMapping("/api/his/dept")
public class HisDeptController {

    private final HisDeptService service;
    private final OrgAccessGuard guard;

    public HisDeptController(HisDeptService service, OrgAccessGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    @GetMapping("/list")
    public R<List<HisDept>> list(@RequestParam(required = false) Long orgId) {
        return R.ok(service.listAll(guard.scopeOrgId(orgId)));
    }

    @GetMapping("/enabled")
    public R<List<HisDept>> enabled(@RequestParam(required = false) Long orgId) {
        return R.ok(service.listEnabled(guard.scopeOrgId(orgId)));
    }

    /**
     * 可排班/可挂号科室(严格本机构): 登录机构的门诊开诊科室。
     * 与 /enabled 区别: 不受牵头可穿透影响, 硬限定为当前登录机构, 且仅返回门诊大类+开诊+启用的科室。
     */
    @GetMapping("/outpatient")
    public R<List<HisDept>> outpatient() {
        return R.ok(service.listSchedulable(guard.currentOrgId()));
    }

    /** 科室层级树(大类→科室→窗口/诊室), 用于树形维护与授权科室选择; withSubOrgs=true 时级联含下级机构科室(仅牵头生效) */
    @GetMapping("/tree")
    public R<List<HisDept>> tree(@RequestParam(required = false) Long orgId,
                                 @RequestParam(defaultValue = "false") boolean withSubOrgs) {
        return R.ok(service.listTree(guard.scopeOrgId(orgId), withSubOrgs && guard.isLead()));
    }

    /** 导出科室列表(xlsx): 与列表同一筛选(机构/含下级机构 + 名称编码关键字/大类/状态), 层级树摊平导出全部匹配行 */
    @GetMapping("/export")
    @SuppressWarnings("unchecked")
    public void export(@RequestParam(required = false) Long orgId,
                       @RequestParam(defaultValue = "false") boolean withSubOrgs,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String deptCategory,
                       @RequestParam(required = false) Integer status,
                       HttpServletResponse resp) throws IOException {
        Map<String, Object> data = service.exportRows(guard.scopeOrgId(orgId), withSubOrgs && guard.isLead(), keyword, deptCategory, status);
        String fname = "科室列表_" + LocalDate.now() + ".xlsx";
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        resp.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        EasyExcel.write(resp.getOutputStream())
                .head((List<List<String>>) data.get("head"))
                .sheet("科室")
                .doWrite((List<List<Object>>) data.get("rows"));
    }

    @PostMapping
    public R<Void> create(@RequestBody HisDept e) {
        guard.requireLeadWrite();
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
        guard.requireLeadWrite();
        service.updateDept(e);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        guard.requireLeadWrite();
        service.removeById(id);
        return R.ok();
    }
}
