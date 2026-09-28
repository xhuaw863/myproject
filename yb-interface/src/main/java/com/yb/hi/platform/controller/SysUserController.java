package com.yb.hi.platform.controller;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.dto.UserSaveReq;
import com.yb.hi.platform.entity.SysRole;
import com.yb.hi.platform.entity.SysUser;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.platform.service.SysRoleService;
import com.yb.hi.platform.service.SysUserOrgService;
import com.yb.hi.platform.service.SysUserRoleService;
import com.yb.hi.platform.service.SysUserService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用户管理接口(医院管理员)
 * 读: 非牵头机构强制本院(scopeOrgId); 写: 仅牵头机构管理员(requireLeadWrite)。
 */
@RestController
@RequestMapping("/api/sys/user")
public class SysUserController {

    private final SysUserService userService;
    private final SysRoleService roleService;
    private final OrgAccessGuard guard;
    private final SysUserOrgService userOrgService;
    private final SysUserRoleService userRoleService;

    public SysUserController(SysUserService userService, SysRoleService roleService, OrgAccessGuard guard,
                             SysUserOrgService userOrgService, SysUserRoleService userRoleService) {
        this.userService = userService;
        this.roleService = roleService;
        this.guard = guard;
        this.userOrgService = userOrgService;
        this.userRoleService = userRoleService;
    }

    @GetMapping("/list")
    public R<List<SysUser>> list(@RequestParam(required = false) Long orgId) {
        List<SysUser> users = userService.list(guard.scopeOrgId(orgId));
        // 批量回显消除逐行 N+1(原逐用户 2 次查询, 688 用户膨胀为约 1400 次往返致列表约 1s); 口径与 allowedOrgIds/listRoleIds 一致
        Map<Long, List<Long>> orgGrantMap = userOrgService.mapGrantedOrgIds();
        Map<Long, List<Long>> roleMap = userRoleService.mapRoleIds();
        for (SysUser u : users) {
            Set<Long> orgs = new LinkedHashSet<>();
            if (u.getOrgId() != null) {
                orgs.add(u.getOrgId());
            }
            orgs.addAll(orgGrantMap.getOrDefault(u.getId(), Collections.emptyList()));
            u.setLoginOrgIds(new ArrayList<>(orgs));
            u.setRoleIds(roleMap.getOrDefault(u.getId(), Collections.emptyList()));
        }
        return R.ok(users);
    }

    /**
     * 服务端分页查用户: 避免全库账号(数千)一次性下发。读隔离与非牵头一致锁定本机构(精确),
     * 牵头用前端已按机构树解析好的 orgIds(含下级级联)。仅对当前页记录富化 loginOrgIds/roleIds。
     */
    @GetMapping("/page")
    public R<Map<String, Object>> page(@RequestParam(required = false) List<Long> orgIds,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String role,
                                       @RequestParam(required = false) Integer status,
                                       @RequestParam(defaultValue = "1") long page,
                                       @RequestParam(defaultValue = "20") long size) {
        List<Long> effOrgIds = guard.isLead() ? orgIds : Collections.singletonList(guard.currentOrgId());
        String roleCode = (role == null || role.trim().isEmpty()) ? null : role.trim();
        Long filterRoleId = null;
        if (roleCode != null) {
            SysRole r = roleService.findByCode(roleCode);
            filterRoleId = r == null ? null : r.getId();
        }
        long safePage = page < 1 ? 1 : page;
        long safeSize = size < 1 ? 20 : Math.min(size, 100000L);
        IPage<SysUser> p = userService.pageQuery(effOrgIds, keyword, roleCode, filterRoleId, status, safePage, safeSize);
        List<SysUser> recs = p.getRecords();
        if (!recs.isEmpty()) {
            List<Long> ids = new ArrayList<>();
            for (SysUser u : recs) {
                ids.add(u.getId());
            }
            Map<Long, List<Long>> orgGrant = userOrgService.mapGrantedOrgIds(ids);
            Map<Long, List<Long>> roleMap = userRoleService.mapRoleIds(ids);
            for (SysUser u : recs) {
                Set<Long> orgs = new LinkedHashSet<>();
                if (u.getOrgId() != null) {
                    orgs.add(u.getOrgId());
                }
                orgs.addAll(orgGrant.getOrDefault(u.getId(), Collections.emptyList()));
                u.setLoginOrgIds(new ArrayList<>(orgs));
                u.setRoleIds(roleMap.getOrDefault(u.getId(), Collections.emptyList()));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", recs);
        out.put("total", p.getTotal());
        out.put("page", p.getCurrent());
        out.put("size", p.getSize());
        return R.ok(out);
    }

    /** 左树角标数据源: 按机构统计启用账号数(非牵头仅本机构); 前端据此沿父链上卷得各级累计 */
    @GetMapping("/org-counts")
    public R<Map<Long, Integer>> orgCounts(@RequestParam(required = false) List<Long> orgIds) {
        List<Long> eff = guard.isLead() ? orgIds : Collections.singletonList(guard.currentOrgId());
        return R.ok(userService.countEnabledByOrg(eff));
    }

    @PostMapping
    public R<Void> create(@RequestBody UserSaveReq req) {
        guard.requireLeadWrite();
        if (req.getUsername() == null || req.getPassword() == null) {
            throw new BizException(400, "账号与密码不能为空");
        }
        String roleCode = resolveRoleCode(req);
        Long primaryRoleId = primaryRoleId(req);
        SysUser created = userService.createUser(UserContext.get().getTenantId(), req.getUsername(), req.getPassword(),
                req.getRealName(), roleCode,
                req.getStaffId(), req.getDeptId(), req.getOrgId(), primaryRoleId, req.getPhone(),
                req.getDeptScope());
        userOrgService.setLoginOrgs(created.getId(), created.getOrgId(), req.getLoginOrgIds());
        userRoleService.replaceRoles(created.getId(), primaryRoleId, req.getRoleIds());
        return R.ok();
    }

    @PutMapping
    public R<Void> update(@RequestBody UserSaveReq req) {
        guard.requireLeadWrite();
        userService.assertStaffNotBound(req.getStaffId(), req.getId());
        SysUser u = new SysUser();
        u.setId(req.getId());
        u.setRealName(req.getRealName());
        u.setRole(resolveRoleCode(req));
        u.setStaffId(req.getStaffId());
        u.setDeptId(req.getDeptId());
        u.setOrgId(req.getOrgId());
        u.setRoleId(primaryRoleId(req));
        u.setPhone(req.getPhone());
        u.setDeptScope(req.getDeptScope());
        u.setStatus(req.getStatus());
        userService.update(u);
        if (req.getLoginOrgIds() != null) {
            userOrgService.setLoginOrgs(req.getId(), req.getOrgId(), req.getLoginOrgIds());
        }
        // 多角色关联: roleIds 非 null 或显式传了主角色时覆盖重建; 旧前端不传 roleIds 时退化为单主角色
        if (req.getRoleIds() != null || req.getRoleId() != null) {
            userRoleService.replaceRoles(req.getId(), primaryRoleId(req), req.getRoleIds());
        }
        return R.ok();
    }

    /** 主角色ID: 优先 roleId(旧单角色传参), 回落 roleIds 首位(多角色约定主角色置首) */
    private Long primaryRoleId(UserSaveReq req) {
        if (req.getRoleId() != null) {
            return req.getRoleId();
        }
        List<Long> ids = req.getRoleIds();
        return ids == null || ids.isEmpty() ? null : ids.get(0);
    }

    /** 优先按主角色ID取权威角色编码; 无角色ID时回退 role 字符串(默认 DOCTOR) */
    private String resolveRoleCode(UserSaveReq req) {
        Long pid = primaryRoleId(req);
        if (pid != null) {
            SysRole r = roleService.getById(pid);
            if (r != null) {
                return r.getRoleCode();
            }
        }
        return req.getRole() == null ? "DOCTOR" : req.getRole();
    }

    /** 重置密码: 改 POST body 传新密码, 避免明文密码落入 access/应用日志(#4); 后端兜长度底线 */
    @PostMapping("/{id}/reset-password")
    public R<Void> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        guard.requireLeadWrite();
        String password = body == null ? null : body.get("password");
        if (password == null || password.length() < 6) {
            throw new BizException(400, "密码至少6位");
        }
        userService.resetPassword(id, password);
        return R.ok();
    }

    @PostMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        guard.requireLeadWrite();
        userService.updateStatus(id, status);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        guard.requireLeadWrite();
        /* 禁删当前登录账号: 防牵头管理员误删自己导致无人可维护用户(#3) */
        if (id != null && id.equals(UserContext.userId())) {
            throw new BizException(400, "不能删除自己的账号, 请先由其他管理员交接");
        }
        userService.delete(id);
        return R.ok();
    }
}
