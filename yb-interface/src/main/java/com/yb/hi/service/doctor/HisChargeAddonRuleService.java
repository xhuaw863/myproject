package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.doctor.HisChargeAddonRule;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.doctor.HisChargeAddonRuleMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
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

    /**
     * 保存加收规则(新增/更新, id 为空即新增)。写守卫由控制器 requireLeadOrg 承担, 此处仅做业务校验:
     * part 维度 ratio 模式必须带 dimRatio; fixed 模式必须带 unitPrice; 阈值缺省置 1; 状态缺省置 1。
     */
    public HisChargeAddonRule save(HisChargeAddonRule rule) {
        if (rule == null || rule.getItemId() == null) {
            throw new BizException(400, "加收规则必须关联主收费项目(itemId)");
        }
        if (!StringUtils.hasText(rule.getDimType())) {
            throw new BizException(400, "加收规则必须指定维度类型(part/index/consult/herb_process)");
        }
        String mode = rule.getCalcMode() == null ? "fixed" : rule.getCalcMode().toLowerCase();
        rule.setCalcMode(mode);
        if (rule.getDimThreshold() == null) {
            rule.setDimThreshold(1);
        }
        if ("ratio".equals(mode)) {
            BigDecimal ratio = rule.getDimRatio();
            if (ratio == null || ratio.compareTo(BigDecimal.ZERO) <= 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
                throw new BizException(400, "ratio 模式加收比例(dimRatio)须在 (0,1] 区间, 如 0.5 表示每增一部位按主项目50%");
            }
        } else if ("fixed".equals(mode)) {
            if (rule.getUnitPrice() == null || rule.getUnitPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException(400, "fixed 模式必须配置大于0的加收单价(unitPrice)");
            }
        }
        if (rule.getStatus() == null) {
            rule.setStatus(1);
        }
        if (rule.getId() == null) {
            ruleMapper.insert(rule);
        } else {
            ruleMapper.updateById(rule);
        }
        return ruleMapper.selectById(rule.getId());
    }

    /** 停用加收规则(逻辑删除, 由 MyBatis-Plus 租户插件与 deleted 处理)。 */
    public void delete(Long id) {
        if (id == null) {
            throw new BizException(400, "缺少规则ID");
        }
        ruleMapper.deleteById(id);
    }
}
