package com.yb.hi.platform.service;

import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.platform.mapper.SysOrgMapper;
import org.springframework.stereotype.Component;

/**
 * 机构级访问控制守卫(牵头/非牵头分层):
 * - 牵头机构 = 租户内 sys_org.is_lead=1 的机构(人民医院); 县级成员机构(中医院/妇幼等)非牵头;
 * - 牵头 ADMIN 可维护全医共体基础数据; 非牵头 ADMIN 仅本机构且只读(写接口 403);
 * - 读隔离由 {@link #scopeOrgId(Long)} 强制: 非牵头忽略入参、锁定本机构。
 * 与医保接口 {@code requireLeadOrg} 同一语义(ADMIN + 牵头机构), 集中收敛避免各控制器重复实现。
 */
@Component
public class OrgAccessGuard {

    private final SysOrgMapper orgMapper;

    public OrgAccessGuard(SysOrgMapper orgMapper) {
        this.orgMapper = orgMapper;
    }

    /** 当前登录用户是否归属牵头机构(is_lead=1) */
    public boolean isLead() {
        return isLead(UserContext.get());
    }

    /** 指定登录用户是否归属牵头机构(is_lead=1) */
    public boolean isLead(LoginUser lu) {
        if (lu == null || lu.getOrgId() == null) {
            return false;
        }
        SysOrg org = orgMapper.selectById(lu.getOrgId());
        return org != null && org.getIsLead() != null && org.getIsLead() == 1;
    }

    /**
     * 牵头机构写守卫: 仅牵头机构(is_lead=1)的 ADMIN 可写; 非牵头/非 ADMIN 抛 403。
     * 用于医共体字典、医保目录对照等全医共体共享数据的维护入口。
     */
    public void requireLeadOrg(String msg) {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        if (!Roles.ADMIN.equals(lu.getRole()) || !isLead(lu)) {
            throw new BizException(403, msg);
        }
    }

    /** 基础数据(科室/职工/用户/收费项目)写守卫: 非机构管理员只读, 医共体层面由牵头机构统一维护。 */
    public void requireLeadWrite() {
        requireLeadOrg("仅牵头机构管理员可维护基础数据");
    }

    /**
     * 当前登录机构ID(登录时选定的机构, 多点执业切换后即为该机构)。
     */
    public Long currentOrgId() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        if (lu.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 请联系管理员维护归属机构");
        }
        return lu.getOrgId();
    }

    /**
     * 机构级业务写守卫(排班号源等机构自己的业务过程): 仅本机构的机构管理员可维护。
     * 与 {@link #requireLeadWrite()} 的区别: 不要求牵头身份, 每个机构自治自己的排班;
     * 平台超管不受角色限制(跨租户运维入口), 但写入范围仍由 currentOrgId 限定。
     */
    public void requireSelfOrgWrite() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        if (lu.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法维护本机构排班");
        }
        boolean admin = Roles.ADMIN.equals(lu.getRole()) || Roles.ORG_ADMIN.equals(lu.getRole());
        if (!admin && !Roles.SUPER_ADMIN.equals(lu.getRole())) {
            throw new BizException(403, "仅本机构管理员可维护排班号源");
        }
    }

    /**
     * 读隔离(严格本机构): 不论牵头与否一律返回当前登录机构, 忽略调用方入参。
     * 用于机构级业务过程(排班号源), 牵头机构也不得穿透看到成员机构的科室与排班。
     */
    public Long strictCurrentOrgId() {
        return currentOrgId();
    }

    /**
     * 读隔离: 牵头机构返回调用方请求的 orgId(null=全部); 非牵头机构强制返回本机构 orgId,
     * 忽略调用方入参, 保证"只能看到本医疗机构的基础数据"。
     */
    public Long scopeOrgId(Long requested) {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return requested;
        }
        if (isLead(lu)) {
            return requested;
        }
        return lu.getOrgId();
    }
}
