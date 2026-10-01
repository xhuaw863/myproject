package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.doctor.HisRxSplitRule;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.doctor.HisRxSplitRuleMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 处方自动拆方规则服务(OP-C): 查询启用中的拆方规则(本机构/本科室 + 通用), 按优先级升序。
 * C1 提供配置读取; C2 提供 suggest() 只读拆方建议(不改药房语义的 create/createBatch, 由前端据建议预分组后调 createBatch)。
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

    /**
     * OP-C 拆方建议(纯只读计算, 不落库): 按启用规则优先级, 把传入项目分入命中的处方组, 未命中项归入默认组。
     * 每个 item 为属性扁平 map, 依 splitDim 取对应键(usage/insutype/chronicDise/specialDrug/pharmacy)与规则 dimValue 等值匹配。
     * 返回组列表: [{ruleId,ruleName,splitDim,dimValue,indexes:[..]}, ...默认组{ruleName:默认组,indexes:[..]}]。
     */
    public List<Map<String, Object>> suggest(List<Map<String, Object>> items, Long deptId) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return result;
        }
        List<HisRxSplitRule> rules = listEffective(deptId);
        LinkedHashSet<Integer> assigned = new LinkedHashSet<>();
        for (HisRxSplitRule r : rules) {
            String key = dimKey(r.getSplitDim());
            if (key == null || r.getDimValue() == null) {
                continue;
            }
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                if (assigned.contains(i)) {
                    continue;
                }
                Object v = items.get(i).get(key);
                if (v != null && r.getDimValue().equals(String.valueOf(v))) {
                    idx.add(i);
                    assigned.add(i);
                }
            }
            if (!idx.isEmpty()) {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("ruleId", r.getId());
                g.put("ruleName", r.getRuleName());
                g.put("splitDim", r.getSplitDim());
                g.put("dimValue", r.getDimValue());
                g.put("indexes", idx);
                result.add(g);
            }
        }
        List<Integer> rest = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!assigned.contains(i)) {
                rest.add(i);
            }
        }
        if (!rest.isEmpty()) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("ruleName", "默认组");
            g.put("indexes", rest);
            result.add(g);
        }
        return result;
    }

    /** splitDim → item 属性键映射。 */
    private static String dimKey(String dim) {
        if (dim == null) {
            return null;
        }
        switch (dim) {
            case "usage":
                return "usage";
            case "insutype":
                return "insutype";
            case "chronic_dise":
                return "chronicDise";
            case "special_drug":
                return "specialDrug";
            case "pharmacy":
                return "pharmacy";
            default:
                return null;
        }
    }
}
