package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.doctor.HisChargeAddonRule;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.doctor.HisChargeAddonRuleMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 开立项目自动计费规则服务(OP-C): 查询启用中的加收规则(本机构/本科室 + 通用)。
 * C1 提供配置读取; 按部位/指标/会诊/草药制法维度自动追加收费行在 C2 接线开立流程。
 */
@Service
public class HisChargeAddonRuleService {

    private final HisChargeAddonRuleMapper ruleMapper;

    public HisChargeAddonRuleService(HisChargeAddonRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    /** 主项目命中的有效加收规则(status=1, 指定 itemId)。 */
    public List<HisChargeAddonRule> listByItem(Long itemId) {
        return ruleMapper.selectList(Wrappers.<HisChargeAddonRule>lambdaQuery()
                .eq(HisChargeAddonRule::getStatus, 1)
                .eq(HisChargeAddonRule::getItemId, itemId)
                .orderByAsc(HisChargeAddonRule::getDimThreshold));
    }

    /** 有效规则总览(机构/科室作用域过滤, 供配置页读取)。 */
    public List<HisChargeAddonRule> listEffective(Long deptId) {
        LoginUser user = UserContext.get();
        Long orgId = user != null ? user.getOrgId() : null;
        return ruleMapper.selectList(Wrappers.<HisChargeAddonRule>lambdaQuery()
                .eq(HisChargeAddonRule::getStatus, 1)
                .and(w -> w.isNull(HisChargeAddonRule::getOrgId).or().eq(orgId != null, HisChargeAddonRule::getOrgId, orgId))
                .and(w -> w.isNull(HisChargeAddonRule::getDeptId).or().eq(deptId != null, HisChargeAddonRule::getDeptId, deptId))
                .orderByAsc(HisChargeAddonRule::getItemId));
    }
}
