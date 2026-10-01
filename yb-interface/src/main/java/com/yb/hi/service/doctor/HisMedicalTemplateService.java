package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.doctor.HisMedicalTemplate;
import com.yb.hi.framework.common.BizException;
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
        template.setIsFav(template.getIsFav() != null && template.getIsFav() == 1 ? 0 : 1);
        updateById(template);
        return getById(id);
    }

    public HisMedicalTemplate create(HisMedicalTemplate template) {
        validate(template);
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
        if (id == null || getById(id) == null) {
            throw new BizException(400, "模板不存在");
        }
        validate(template);
        template.setId(id);
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
        if (id == null || !removeById(id)) {
            throw new BizException(400, "模板不存在");
        }
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
