package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.doctor.HisMedicalTemplate;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.doctor.HisMedicalTemplateMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 医生工作站医疗模板服务
 */
@Service
public class HisMedicalTemplateService extends ServiceImpl<HisMedicalTemplateMapper, HisMedicalTemplate> {

    /** 模板类型: soap/rx_set/order_set/fragment + 诊断维护两类(一条记录=一条诊断, content存{code,name,category}JSON) */
    private static final Set<String> TEMPLATE_TYPES = new HashSet<>(
            Arrays.asList("soap", "rx_set", "order_set", "fragment", "diag_personal", "diag_dept"));

    /** 查询当前登录人可见的个人、科室和全院模板。 */
    public List<HisMedicalTemplate> listVisible(String type, String keyword) {
        QueryWrapper<HisMedicalTemplate> q = new QueryWrapper<>();
        q.eq(StringUtils.hasText(type), "template_type", type)
                .like(StringUtils.hasText(keyword), "name", keyword)
                .eq("status", 1);

        LoginUser user = UserContext.get();
        Long staffId = user == null ? null : user.getStaffId();
        Long deptId = user == null ? null : user.getDeptId();
        if (user != null && isAdmin(user)) {
            /* 管理员维护台: 读取本租户全部范围模板(个人/科室/全院), 与写守卫 requireCreatableScope/requireMutable
               的 admin 直通口径对称; 否则无本科室归属的管理员(staff_id 有值而 dept_id 为空)在科室诊断等页签将查不到
               其有权维护的本科室级模板。租户隔离由 MyBatis-Plus 租户插件按 tenant_id 自动生效, 无需在此重复。 */
            return list(q.orderByDesc("is_fav").orderByAsc("sort_order").orderByAsc("id"));
        }
        if (staffId != null && deptId != null) {
            q.and(w -> w.eq("staff_id", staffId)
                    .or(x -> x.isNull("staff_id").eq("dept_id", deptId))
                    .or(x -> x.isNull("staff_id").isNull("dept_id")));
        } else if (staffId != null) {
            q.and(w -> w.eq("staff_id", staffId)
                    .or(x -> x.isNull("staff_id").isNull("dept_id")));
        } else if (deptId != null) {
            q.and(w -> w.isNull("staff_id").eq("dept_id", deptId)
                    .or(x -> x.isNull("staff_id").isNull("dept_id")));
        } else {
            q.isNull("staff_id").isNull("dept_id");
        }
        return list(q.orderByDesc("is_fav").orderByAsc("sort_order").orderByAsc("id"));
    }

    /** OP-D 模板收藏切换: 0↔1(收藏项列表置顶) */
    public HisMedicalTemplate toggleFav(Long id) {
        HisMedicalTemplate template = detail(id);
        requireMutable(template);
        template.setIsFav(template.getIsFav() != null && template.getIsFav() == 1 ? 0 : 1);
        updateById(template);
        return getById(id);
    }

    public HisMedicalTemplate create(HisMedicalTemplate template) {
        validate(template);
        requireCreatableScope(template);
        template.setId(null);
        if (template.getSortOrder() == null) {
            template.setSortOrder(0);
        }
        if (template.getStatus() == null) {
            template.setStatus(1);
        }
        if (template.getIsFav() == null) {
            template.setIsFav(0);
        }
        save(template);
        return template;
    }

    public HisMedicalTemplate update(Long id, HisMedicalTemplate template) {
        HisMedicalTemplate existing = id == null ? null : getById(id);
        if (existing == null) {
            throw new BizException(400, "模板不存在");
        }
        requireMutable(existing);
        validate(template);
        /* 作用范围创建后不可借更新接口变更，避免医生把个人/科室模板提升为全院模板。 */
        template.setId(id);
        template.setStaffId(existing.getStaffId());
        template.setDeptId(existing.getDeptId());
        updateById(template);
        return getById(id);
    }

    public HisMedicalTemplate detail(Long id) {
        HisMedicalTemplate template = getById(id);
        if (template == null) {
            throw new BizException(400, "模板不存在");
        }
        return template;
    }

    public void delete(Long id) {
        HisMedicalTemplate template = id == null ? null : getById(id);
        if (template == null) {
            throw new BizException(400, "模板不存在");
        }
        requireMutable(template);
        if (!removeById(id)) {
            throw new BizException(400, "模板不存在");
        }
    }

    /** 创建范围守卫：医生只能创建本人或本科室模板，全院模板仅管理员可建。 */
    private void requireCreatableScope(HisMedicalTemplate template) {
        LoginUser user = requireLoginUser();
        if (isAdmin(user)) {
            return;
        }
        if (template.getStaffId() != null) {
            if (user.getStaffId() == null || !user.getStaffId().equals(template.getStaffId())) {
                throw new BizException(403, "只能创建本人的个人模板");
            }
            template.setDeptId(null);
            return;
        }
        if (template.getDeptId() != null) {
            if (user.getDeptId() == null || !user.getDeptId().equals(template.getDeptId())) {
                throw new BizException(403, "只能创建本科室模板");
            }
            return;
        }
        throw new BizException(403, "全院模板仅管理员可维护");
    }

    /** 修改、删除及收藏守卫：个人仅本人，科室仅本科室，全院仅管理员。 */
    private void requireMutable(HisMedicalTemplate template) {
        LoginUser user = requireLoginUser();
        if (isAdmin(user)) {
            return;
        }
        if (template.getStaffId() != null) {
            if (user.getStaffId() != null && user.getStaffId().equals(template.getStaffId())) {
                return;
            }
            throw new BizException(403, "无权维护他人个人模板");
        }
        if (template.getDeptId() != null) {
            if (user.getDeptId() != null && user.getDeptId().equals(template.getDeptId())) {
                return;
            }
            throw new BizException(403, "无权维护其他科室模板");
        }
        throw new BizException(403, "全院模板仅管理员可维护");
    }

    private LoginUser requireLoginUser() {
        LoginUser user = UserContext.get();
        if (user == null) {
            throw new BizException(401, "未登录");
        }
        return user;
    }

    private boolean isAdmin(LoginUser user) {
        return user.hasAnyRole(Roles.ADMIN, Roles.SUPER_ADMIN);
    }

    private void validate(HisMedicalTemplate template) {
        if (template == null) {
            throw new BizException(400, "模板不能为空");
        }
        if (!TEMPLATE_TYPES.contains(template.getTemplateType())) {
            throw new BizException(400, "模板类型仅支持 soap/rx_set/order_set/fragment/diag_personal/diag_dept");
        }
        if (!StringUtils.hasText(template.getName())) {
            throw new BizException(400, "模板名称不能为空");
        }
        if (!StringUtils.hasText(template.getContent())) {
            throw new BizException(400, "模板内容不能为空");
        }
    }
}
