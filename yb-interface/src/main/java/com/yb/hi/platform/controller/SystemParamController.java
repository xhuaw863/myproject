package com.yb.hi.platform.controller;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.dto.ParamSaveReq;
import com.yb.hi.platform.entity.SysParam;
import com.yb.hi.platform.entity.SysParamGroup;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.platform.service.SystemParamResolver;
import com.yb.hi.platform.service.SystemParamService;
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
import java.util.Map;

/**
 * 系统参数接口(四级作用域: 0全局 / 1租户 / 2机构 / 3科室)。
 *
 * 权限分层: 分组与全局定义仅平台超管可写、牵头ADMIN及以上可读; 各作用域覆盖值按
 * {@link #guardByScope(int)} 动态定档(全局=超管, 租户=牵头ADMIN或超管, 机构/科室=机构管理员及以上,
 * 写入端另有 Service 层归属校验); 解析接口对所有登录用户开放(业务运行时取值)。
 */
@RestController
@RequestMapping("/api/sys/param")
public class SystemParamController {

    private final SystemParamService paramService;
    private final SystemParamResolver paramResolver;
    private final OrgAccessGuard orgAccessGuard;

    public SystemParamController(SystemParamService paramService, SystemParamResolver paramResolver,
                                 OrgAccessGuard orgAccessGuard) {
        this.paramService = paramService;
        this.paramResolver = paramResolver;
        this.orgAccessGuard = orgAccessGuard;
    }

    /* ================= 分组(全局共享) ================= */

    @GetMapping("/groups")
    public R<List<SysParamGroup>> groups() {
        requireAdmin();
        return R.ok(paramService.listGroups());
    }

    @PostMapping("/group")
    public R<Void> saveGroup(@RequestBody SysParamGroup group) {
        requireSuper();
        paramService.saveGroup(group);
        return R.ok();
    }

    @DeleteMapping("/group/{id}")
    public R<Void> deleteGroup(@PathVariable Long id) {
        requireSuper();
        paramService.deleteGroup(id);
        return R.ok();
    }

    /* ================= 全局参数定义 ================= */

    @GetMapping("/definitions")
    public R<List<Map<String, Object>>> definitions(@RequestParam(required = false) String groupCode) {
        requireAdmin();
        return R.ok(paramService.listDefinitions(groupCode));
    }

    @PostMapping("/definition")
    public R<Void> createDefinition(@RequestBody SysParam param) {
        requireSuper();
        paramService.createDefinition(param);
        return R.ok();
    }

    @PutMapping("/definition")
    public R<Void> updateDefinition(@RequestBody SysParam param) {
        requireSuper();
        paramService.updateDefinition(param);
        return R.ok();
    }

    @DeleteMapping("/definition/{key}")
    public R<Void> deleteDefinition(@PathVariable String key) {
        requireSuper();
        paramService.deleteDefinition(key);
        return R.ok();
    }

    /* ================= 作用域视图与覆盖值维护 ================= */

    /** 指定作用域的参数视图(覆盖值/继承值), 按作用域动态定档 */
    @GetMapping("/scope")
    public R<List<Map<String, Object>>> scope(@RequestParam(required = false) Integer scopeLevel,
                                              @RequestParam(required = false) Long scopeId,
                                              @RequestParam(required = false) String groupCode) {
        guardByScope(requireScopeLevel(scopeLevel));
        return R.ok(paramService.listByScope(scopeLevel, scopeId, groupCode));
    }

    /** 生效参数列表(按给定机构/科室上下文解析最终生效值与来源级别) */
    @GetMapping("/effective")
    public R<List<Map<String, Object>>> effective(@RequestParam(required = false) Long orgId,
                                                  @RequestParam(required = false) Long deptId,
                                                  @RequestParam(required = false) String groupCode) {
        requireAdmin();
        return R.ok(paramService.getEffectiveParams(currentTenantId(), orgId, deptId, groupCode));
    }

    @PostMapping("/save")
    public R<Void> save(@RequestBody ParamSaveReq req) {
        if (req == null || req.getScopeLevel() == null) {
            throw new BizException("scopeLevel 不能为空");
        }
        guardByScope(req.getScopeLevel());
        paramService.saveParam(req.getParamKey(), req.getValue(), req.getScopeLevel(), req.getScopeId());
        return R.ok();
    }

    /** 删除覆盖值(恢复继承), 按作用域动态定档 */
    @DeleteMapping("/override")
    public R<Void> deleteOverride(@RequestParam String paramKey,
                                  @RequestParam(required = false) Integer scopeLevel,
                                  @RequestParam(required = false) Long scopeId) {
        guardByScope(requireScopeLevel(scopeLevel));
        paramService.deleteOverride(paramKey, scopeLevel, scopeId);
        return R.ok();
    }

    /* ================= 参数解析(业务运行时取值, 登录即可) ================= */

    @GetMapping("/resolve/{paramKey}")
    public R<String> resolve(@PathVariable String paramKey) {
        return R.ok(paramResolver.resolve(paramKey));
    }

    @GetMapping("/resolve-group/{groupCode}")
    public R<Map<String, String>> resolveGroup(@PathVariable String groupCode) {
        return R.ok(paramResolver.resolveGroup(groupCode));
    }

    /* ================= 守卫 ================= */

    /**
     * 作用域动态守卫: 0全局=平台超管; 1租户=牵头机构ADMIN或超管; 2机构/3科室=机构管理员及以上
     * (机构/科室的写入归属校验在 Service 层按登录机构复核)。
     */
    private void guardByScope(int scopeLevel) {
        if (scopeLevel == 0) {
            requireSuper();
        } else if (scopeLevel == 1) {
            requireLeadWrite();
        } else if (scopeLevel == 2 || scopeLevel == 3) {
            requireOrgAdmin();
        } else {
            throw new BizException("作用域层级非法: " + scopeLevel);
        }
    }

    /** 仅平台超级管理员可维护分组与全局定义 */
    private void requireSuper() {
        LoginUser u = UserContext.get();
        if (u == null || !Roles.SUPER_ADMIN.equals(u.getRole())) {
            throw new BizException(403, "仅平台超级管理员可操作");
        }
    }

    /** 牵头机构管理员或平台超管可读 */
    private void requireAdmin() {
        String role = UserContext.get() == null ? null : UserContext.get().getRole();
        if (!Roles.ADMIN.equals(role) && !Roles.SUPER_ADMIN.equals(role)) {
            throw new BizException(403, "无权访问系统参数维护");
        }
    }

    /** 机构管理员(牵头ADMIN / 非牵头 ORG_ADMIN / 超管)可维护本机构/科室参数 */
    private void requireOrgAdmin() {
        String role = UserContext.get() == null ? null : UserContext.get().getRole();
        if (!Roles.ADMIN.equals(role) && !Roles.ORG_ADMIN.equals(role) && !Roles.SUPER_ADMIN.equals(role)) {
            throw new BizException(403, "仅机构管理员可维护本机构参数");
        }
    }

    /** 租户级写: 牵头机构ADMIN 或平台超管 */
    private void requireLeadWrite() {
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        if (Roles.SUPER_ADMIN.equals(u.getRole())) {
            return;
        }
        orgAccessGuard.requireLeadWrite();
    }

    private Long currentTenantId() {
        LoginUser u = UserContext.get();
        if (u != null && u.getTenantId() != null) {
            return u.getTenantId();
        }
        return TenantContext.get();
    }

    /** scopeLevel 参数缺失时给出友好错误 */
    private int requireScopeLevel(Integer scopeLevel) {
        if (scopeLevel == null) {
            throw new BizException("scopeLevel 不能为空");
        }
        return scopeLevel;
    }
}
