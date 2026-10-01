package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.doctor.HisRxSplitRule;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.doctor.HisRxSplitRuleMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 处方自动拆方规则服务(OP-C): 查询启用中的拆方规则(本机构/本科室 + 通用), 按优先级升序。
 * C1 提供配置读取; 实际按维度拆分处方组在 C2 接线 HisPrescriptionService.createBatch。
 */
@Service
public class HisRxSplitRuleService {

    private final HisRxSplitRuleMapper ruleMapper;

    public HisRxSplitRuleService(HisRxSplitRuleMapper ruleMapper) {
        this.ruleMapper = ruleMapper;
    }

    /** 有效规则: status=1 且 (机构匹配或通用) 且 (科室匹配或全院通用), 按 priority 升序。 */
    public List<HisRxSplitRule> listEffective(Long deptId) {
        LoginUser user = UserContext.get();
        Long orgId = user != null ? user.getOrgId() : null;
        return ruleMapper.selectList(Wrappers.<HisRxSplitRule>lambdaQuery()
                .eq(HisRxSplitRule::getStatus, 1)
                .and(w -> w.isNull(HisRxSplitRule::getOrgId).or().eq(orgId != null, HisRxSplitRule::getOrgId, orgId))
                .and(w -> w.isNull(HisRxSplitRule::getDeptId).or().eq(deptId != null, HisRxSplitRule::getDeptId, deptId))
                .orderByAsc(HisRxSplitRule::getPriority));
    }
}
