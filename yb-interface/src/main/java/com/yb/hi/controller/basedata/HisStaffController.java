package com.yb.hi.controller.basedata;

import com.alibaba.excel.EasyExcel;
import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.entity.basedata.HisStaffRxAuth;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.ExportGuard;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.basedata.HisStaffRxAuthService;
import com.yb.hi.service.basedata.HisStaffService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 职工管理接口
 * 读: 非牵头机构强制本院(scopeOrgId); 写: 仅牵头机构管理员(requireLeadWrite)。
 */
@RestController
@RequestMapping("/api/his/staff")
public class HisStaffController {

    private final HisStaffService service;
    private final HisStaffRxAuthService rxAuthService;
    private final OrgAccessGuard guard;

    public HisStaffController(HisStaffService service, HisStaffRxAuthService rxAuthService, OrgAccessGuard guard) {
        this.service = service;
        this.rxAuthService = rxAuthService;
        this.guard = guard;
    }

    @GetMapping("/list")
    public R<List<HisStaff>> list(@RequestParam(required = false) Long orgId,
                                  @RequestParam(required = false) Long deptId,
                                  @RequestParam(required = false) String staffType,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) Integer status,
                                  @RequestParam(required = false) Integer canRegister,
                                  @RequestParam(required = false) String rxAuth,
                                  @RequestParam(defaultValue = "true") boolean withChildren,
                                  @RequestParam(defaultValue = "true") boolean withSubOrgs) {
        /* 机构级联仅牵头机构生效: 非牵头读隔离锁定本机构, 不得穿透到下级 */
        return R.ok(service.listByFilter(guard.scopeOrgId(orgId), withSubOrgs && guard.isLead(), deptId, staffType, keyword, withChildren, status, canRegister, rxAuth));
    }

    /** 导出职工列表(xlsx): 与列表同一筛选(机构/科室/类别/关键字/可挂号/处方权限及两个级联开关), 一次性导出全部匹配行 */
    @GetMapping("/export")
    @SuppressWarnings("unchecked")
    public void export(@RequestParam(required = false) Long orgId,
                       @RequestParam(required = false) Long deptId,
                       @RequestParam(required = false) String staffType,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) Integer status,
                       @RequestParam(required = false) Integer canRegister,
                       @RequestParam(required = false) String rxAuth,
                       @RequestParam(defaultValue = "true") boolean withChildren,
                       @RequestParam(defaultValue = "true") boolean withSubOrgs,
                       HttpServletResponse resp) throws IOException {
        Map<String, Object> data = service.exportRows(guard.scopeOrgId(orgId), withSubOrgs && guard.isLead(),
                deptId, staffType, keyword, withChildren, status, canRegister, rxAuth);
        String fname = "职工列表_" + LocalDate.now() + ".xlsx";
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        resp.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        ExportGuard.checkRows((java.util.Collection<?>) data.get("rows"), "职工");
        EasyExcel.write(resp.getOutputStream())
                .head((List<List<String>>) data.get("head"))
                .sheet("职工")
                .doWrite((List<List<Object>>) data.get("rows"));
    }

    @GetMapping("/{id}")
    public R<HisStaff> get(@PathVariable Long id) {
        return R.ok(service.getById(id));
    }

    @PostMapping
    public R<Void> create(@RequestBody HisStaff e) {
        guard.requireLeadWrite();
        service.saveStaff(e);
        return R.ok();
    }

    @PutMapping
    public R<Void> update(@RequestBody HisStaff e) {
        guard.requireLeadWrite();
        service.updateStaff(e);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        guard.requireLeadWrite();
        service.removeById(id);
        return R.ok();
    }

    /* ================= 处方权限·按级授权明细(T2 阶段5-3) =================
     * 读: 列出某职工的按级授权(抗菌分级/麻醉/精一/精二, 各自有效期);
     * 写: 仅牵头机构管理员(requireLeadWrite), 与职工维护同档位; staffId 以路径为准防越权。
     */
    @GetMapping("/{id}/rx-auth")
    public R<List<HisStaffRxAuth>> rxAuthList(@PathVariable Long id) {
        return R.ok(rxAuthService.listByStaff(id));
    }

    @PostMapping("/{id}/rx-auth")
    public R<HisStaffRxAuth> rxAuthSave(@PathVariable Long id, @RequestBody HisStaffRxAuth e) {
        guard.requireLeadWrite();
        e.setStaffId(id);
        return R.ok(rxAuthService.saveAuth(e));
    }

    @DeleteMapping("/rx-auth/{authId}")
    public R<Void> rxAuthDelete(@PathVariable Long authId) {
        guard.requireLeadWrite();
        rxAuthService.removeAuth(authId);
        return R.ok();
    }
}
