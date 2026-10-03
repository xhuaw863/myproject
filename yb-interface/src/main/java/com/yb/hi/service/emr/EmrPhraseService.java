package com.yb.hi.service.emr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yb.hi.entity.emr.HisEmrPhrase;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.emr.EmrPhraseMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 病历常用语服务(病历P2): 全局/科室/个人三级作用域(scope 0/1/2)常用语库, 按分类(category)聚合
 * 供书写区快速插入, usage_count 热度降序。列表口径: 全局(scope=0) + 本科室(scope=1, dept_code 匹配)
 * + 个人(scope=2, 创建人本人); 写路径仅开放个人常用语且仅创建人(或管理员)可维护。
 * tenant_id 由租户插件自动注入, 实体不显式映射; org_id 取登录机构快照。
 */
@Slf4j
@Service
public class EmrPhraseService {

    /** 作用域: 0全局 1科室 2个人 */
    private static final int SCOPE_GLOBAL = 0;
    private static final int SCOPE_DEPT = 1;
    private static final int SCOPE_PERSONAL = 2;

    private final EmrPhraseMapper phraseMapper;

    public EmrPhraseService(EmrPhraseMapper phraseMapper) {
        this.phraseMapper = phraseMapper;
    }

    /**
     * 分类常用语列表: 全局 + 本科室(deptCode 匹配) + 个人(creatorId 本人), 启用行, 热度降序。
     * deptCode/creatorId 为空时分别跳过科室/个人维度(仅返回全局), 不会误放行他科/他人常用语。
     *
     * @param category  分类(chief_complaint/present_illness/...), 空则全部分类
     * @param deptCode  当前科室编码(scope=1 匹配键)
     * @param creatorId 当前职工ID(his_staff.id, scope=2 匹配键)
     */
    public List<HisEmrPhrase> listByCategory(String category, String deptCode, Long creatorId) {
        LambdaQueryWrapper<HisEmrPhrase> qw = new LambdaQueryWrapper<>();
        qw.eq(StringUtils.hasText(category), HisEmrPhrase::getCategory, category);
        qw.eq(HisEmrPhrase::getEnabled, 1);
        qw.and(w -> {
            w.eq(HisEmrPhrase::getScope, SCOPE_GLOBAL);
            if (StringUtils.hasText(deptCode)) {
                w.or(o -> o.eq(HisEmrPhrase::getScope, SCOPE_DEPT).eq(HisEmrPhrase::getDeptCode, deptCode));
            }
            if (creatorId != null) {
                w.or(o -> o.eq(HisEmrPhrase::getScope, SCOPE_PERSONAL).eq(HisEmrPhrase::getCreatorId, creatorId));
            }
        });
        qw.orderByDesc(HisEmrPhrase::getUsageCount);
        qw.orderByDesc(HisEmrPhrase::getId);
        return phraseMapper.selectList(qw);
    }

    /** 使用计数 +1(热度排序), 逻辑删除行由 @TableLogic 自动过滤 */
    public void incrementUsage(Long phraseId) {
        phraseMapper.update(null, new LambdaUpdateWrapper<HisEmrPhrase>()
                .eq(HisEmrPhrase::getId, phraseId)
                .setSql("usage_count = usage_count + 1"));
    }

    /** 新增个人常用语(scope 强制 2, 创建人取当前登录职工, 组织归属取登录机构) */
    public Long create(HisEmrPhrase phrase) {
        LoginUser cur = UserContext.get();
        if (cur == null) {
            throw new BizException(401, "未登录");
        }
        if (cur.getStaffId() == null) {
            throw new BizException(403, "当前账号未关联职工, 无法维护个人常用语");
        }
        if (!StringUtils.hasText(phrase.getContent())) {
            throw new BizException(400, "常用语内容不能为空");
        }
        phrase.setId(null);
        phrase.setScope(SCOPE_PERSONAL);
        phrase.setCreatorId(cur.getStaffId());
        if (phrase.getEnabled() == null) {
            phrase.setEnabled(1);
        }
        if (phrase.getUsageCount() == null) {
            phrase.setUsageCount(0);
        }
        phrase.setOrgId(cur.getOrgId());
        phraseMapper.insert(phrase);
        return phrase.getId();
    }

    /** 更新常用语: 作用域/创建人不允许改; 全局/科室仅管理员, 个人仅创建人(或管理员) */
    public void update(HisEmrPhrase phrase) {
        if (phrase == null || phrase.getId() == null) {
            throw new BizException(400, "常用语ID不能为空");
        }
        HisEmrPhrase old = phraseMapper.selectById(phrase.getId());
        if (old == null) {
            throw new BizException(404, "常用语不存在: " + phrase.getId());
        }
        requirePhraseMaintainer(old);
        phrase.setScope(old.getScope());
        phrase.setCreatorId(old.getCreatorId());
        phraseMapper.updateById(phrase);
    }

    /** 删除常用语(逻辑删): 全局/科室仅管理员, 个人仅创建人(或管理员) */
    public void delete(Long id) {
        if (id == null) {
            throw new BizException(400, "常用语ID不能为空");
        }
        HisEmrPhrase old = phraseMapper.selectById(id);
        if (old == null) {
            throw new BizException(404, "常用语不存在: " + id);
        }
        requirePhraseMaintainer(old);
        phraseMapper.deleteById(id);
    }

    /* ==================== 内部 ==================== */

    /** 维护权校验: 全局/科室(种子)仅管理员; 个人(scope=2)创建人本人或管理员 */
    private void requirePhraseMaintainer(HisEmrPhrase row) {
        LoginUser cur = UserContext.get();
        if (cur == null) {
            throw new BizException(401, "未登录");
        }
        boolean admin = cur.hasAnyRole(Roles.ADMIN, Roles.SUPER_ADMIN, Roles.ORG_ADMIN);
        if (row.getScope() != null && row.getScope() == SCOPE_PERSONAL) {
            boolean owner = cur.getStaffId() != null && cur.getStaffId().equals(row.getCreatorId());
            if (!owner && !admin) {
                throw new BizException(403, "仅创建人或管理员可维护该个人常用语");
            }
            return;
        }
        if (!admin) {
            throw new BizException(403, "全局/科室常用语仅管理员可维护");
        }
    }
}
