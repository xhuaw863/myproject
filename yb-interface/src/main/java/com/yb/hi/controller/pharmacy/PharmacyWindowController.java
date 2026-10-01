package com.yb.hi.controller.pharmacy;

import com.yb.hi.entity.pharmacy.HisPharmacyCrossConfig;
import com.yb.hi.entity.pharmacy.HisPharmacyWindow;
import com.yb.hi.entity.pharmacy.HisWindowDeptRule;
import com.yb.hi.entity.pharmacy.HisWindowSignin;
import com.yb.hi.entity.pharmacy.HisWindowWorkstation;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.pharmacy.PharmacyWindowService;
import com.yb.hi.service.pharmacy.WindowDispatchService;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发药窗口子系统接口(P1): 窗口维护 / 工作站关联 / 科室定向规则 / 跨药房配置 / 签到 / 分窗预览。
 * 配置写(窗口/工作站/规则/跨药房)守卫走牵头 requireLeadWrite(与药房定义一致);
 * 签到为日常业务, 走本机构管理员/药师/超管角色守卫(不限牵头)。
 */
@RestController
@RequestMapping("/api/his/pharmacy/window")
public class PharmacyWindowController {

    private final PharmacyWindowService windowService;
    private final WindowDispatchService dispatchService;
    private final OrgAccessGuard guard;

    public PharmacyWindowController(PharmacyWindowService windowService, WindowDispatchService dispatchService,
                                    OrgAccessGuard guard) {
        this.windowService = windowService;
        this.dispatchService = dispatchService;
        this.guard = guard;
    }

    /* ================= 窗口 ================= */

    @GetMapping("/list")
    public R<List<HisPharmacyWindow>> listWindows(@RequestParam(required = false) Long orgId,
                                                  @RequestParam(required = false) Long pharmacyId) {
        return R.ok(windowService.listWindows(resolveOrgId(orgId), pharmacyId));
    }

    @PostMapping("/save")
    public R<HisPharmacyWindow> saveWindow(@RequestBody HisPharmacyWindow window) {
        guard.requireLeadWrite();
        window.setOrgId(resolveOrgId(window.getOrgId()));
        return R.ok(windowService.saveWindow(window));
    }

    @PostMapping("/{id}/toggle-open")
    public R<HisPharmacyWindow> toggleOpen(@PathVariable Long id, @RequestParam boolean open) {
        guard.requireLeadWrite();
        return R.ok(windowService.toggleOpen(id, open));
    }

    @PostMapping("/{id}/toggle-status")
    public R<HisPharmacyWindow> toggleStatus(@PathVariable Long id, @RequestParam boolean enabled) {
        guard.requireLeadWrite();
        return R.ok(windowService.toggleStatus(id, enabled));
    }

    @DeleteMapping("/{id}")
    public R<Void> deleteWindow(@PathVariable Long id) {
        guard.requireLeadWrite();
        windowService.deleteWindow(id);
        return R.ok();
    }

    /* ================= 工作站↔窗口 ================= */

    @GetMapping("/workstation")
    public R<List<HisWindowWorkstation>> listWorkstations(@RequestParam(required = false) Long windowId) {
        return R.ok(windowService.listWorkstations(windowId));
    }

    @PostMapping("/workstation/save")
    public R<HisWindowWorkstation> saveWorkstation(@RequestBody HisWindowWorkstation ws) {
        guard.requireLeadWrite();
        return R.ok(windowService.saveWorkstation(ws));
    }

    @DeleteMapping("/workstation/{id}")
    public R<Void> deleteWorkstation(@PathVariable Long id) {
        guard.requireLeadWrite();
        windowService.deleteWorkstation(id);
        return R.ok();
    }

    /* ================= 科室→窗口规则 ================= */

    @GetMapping("/dept-rule")
    public R<List<HisWindowDeptRule>> listDeptRules() {
        return R.ok(windowService.listDeptRules());
    }

    @PostMapping("/dept-rule/save")
    public R<HisWindowDeptRule> saveDeptRule(@RequestBody HisWindowDeptRule rule) {
        guard.requireLeadWrite();
        rule.setOrgId(resolveOrgId(rule.getOrgId()));
        return R.ok(windowService.saveDeptRule(rule));
    }

    @DeleteMapping("/dept-rule/{id}")
    public R<Void> deleteDeptRule(@PathVariable Long id) {
        guard.requireLeadWrite();
        windowService.deleteDeptRule(id);
        return R.ok();
    }

    /* ================= 跨药房配置 ================= */

    @GetMapping("/cross-config")
    public R<List<HisPharmacyCrossConfig>> listCrossConfigs(@RequestParam(required = false) Long orgId) {
        return R.ok(windowService.listCrossConfigs(resolveOrgId(orgId)));
    }

    @PostMapping("/cross-config/save")
    public R<HisPharmacyCrossConfig> saveCrossConfig(@RequestBody HisPharmacyCrossConfig cfg) {
        guard.requireLeadWrite();
        cfg.setOrgId(resolveOrgId(cfg.getOrgId()));
        return R.ok(windowService.saveCrossConfig(cfg));
    }

    @PostMapping("/cross-config/{id}/toggle")
    public R<HisPharmacyCrossConfig> toggleCrossConfig(@PathVariable Long id, @RequestParam boolean enabled) {
        guard.requireLeadWrite();
        return R.ok(windowService.toggleCrossConfig(id, enabled));
    }

    @DeleteMapping("/cross-config/{id}")
    public R<Void> deleteCrossConfig(@PathVariable Long id) {
        guard.requireLeadWrite();
        windowService.deleteCrossConfig(id);
        return R.ok();
    }

    /* ================= 签到 ================= */

    @GetMapping("/signin")
    public R<List<HisWindowSignin>> listSignins(@RequestParam(required = false) Long windowId) {
        return R.ok(windowService.listSignins(windowId));
    }

    @PostMapping("/signin")
    public R<HisWindowSignin> signIn(@RequestBody Map<String, Object> body) {
        requirePharmWrite();
        Long windowId = toLong(body.get("windowId"));
        Long patientId = toLong(body.get("patientId"));
        Long visitId = toLong(body.get("visitId"));
        Long prescriptionId = toLong(body.get("prescriptionId"));
        String signinNo = body.get("signinNo") == null ? null : String.valueOf(body.get("signinNo"));
        return R.ok(windowService.signIn(windowId, patientId, visitId, prescriptionId, signinNo, currentUserName()));
    }

    @PostMapping("/signin/{id}/cancel")
    public R<Void> cancelSignin(@PathVariable Long id) {
        requirePharmWrite();
        windowService.cancelSignin(id);
        return R.ok();
    }

    /* ================= 分窗预览(冒烟/调试) ================= */

    /** 预览智能分窗结果: 按 药房×科室×处方类型×特殊标志 计算目标窗口(不落库, 供前端与冒烟验证策略命中) */
    @GetMapping("/dispatch-preview")
    public R<Map<String, Object>> dispatchPreview(@RequestParam Long pharmacyId,
                                                  @RequestParam(required = false) Long deptId,
                                                  @RequestParam(required = false) String rxType,
                                                  @RequestParam(required = false) String specialTypes) {
        Set<String> specials = new HashSet<>();
        if (specialTypes != null && !specialTypes.trim().isEmpty()) {
            for (String s : specialTypes.split(",")) {
                if (!s.trim().isEmpty()) {
                    specials.add(s.trim().toUpperCase());
                }
            }
        }
        Long windowId = dispatchService.assignWindow(pharmacyId, deptId, rxType, specials.isEmpty() ? null : specials);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pharmacyId", pharmacyId);
        out.put("deptId", deptId);
        out.put("rxType", rxType);
        out.put("windowId", windowId);
        HisPharmacyWindow w = windowId == null ? null
                : windowService.listWindows(null, pharmacyId).stream()
                    .filter(x -> windowId.equals(x.getId())).findFirst().orElse(null);
        out.put("windowName", w == null ? null : w.getName());
        out.put("windowType", w == null ? null : w.getWindowType());
        return R.ok(out);
    }

    /* ================= 守卫/工具 ================= */

    /** 签到/取消签到守卫: 本机构管理员/药师/超管(日常业务不限牵头) */
    private void requirePharmWrite() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        if (!lu.hasAnyRole(Roles.ADMIN, Roles.ORG_ADMIN, Roles.PHARMACIST, Roles.SUPER_ADMIN)) {
            throw new BizException(403, "仅本机构管理员或药师可执行窗口签到");
        }
    }

    private String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return lu.getRealName() != null && !lu.getRealName().isEmpty() ? lu.getRealName() : lu.getUsername();
    }

    /** 机构作用域: 牵头可取入参, 非牵头强制本机构; 入参为空回退当前登录机构 */
    private Long resolveOrgId(Long requested) {
        Long oid = guard.scopeOrgId(requested);
        if (oid != null) {
            return oid;
        }
        LoginUser lu = UserContext.get();
        return lu == null ? null : lu.getOrgId();
    }

    private static Long toLong(Object v) {
        return v == null ? null : (v instanceof Number ? ((Number) v).longValue() : Long.valueOf(v.toString().trim()));
    }
}
