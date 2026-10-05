package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.doctor.HisDiseaseReportTriggerRule;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.doctor.HisDiseaseReportTriggerRuleMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * 疾病报卡触发规则服务(全局表): 供 HisDiseaseReportService 触发判定查表 + ADMIN 维护界面 CRUD。
 * 规则即数据: 增删改启停即时生效, 无需发版; 表清空 = 全局不自动弹卡(语义正确, 由管理员负责)。
 */
@Service
public class HisDiseaseReportTriggerRuleService extends ServiceImpl<HisDiseaseReportTriggerRuleMapper, HisDiseaseReportTriggerRule> {

    private static final List<String> MATCH_TYPES = Arrays.asList("prefix", "exact", "class");

    /** 触发判定用: 启用规则按 priority 升序(同优先级按 id 稳定), 首个命中即定大类 */
    public List<HisDiseaseReportTriggerRule> listEnabledOrdered() {
        return lambdaQuery().eq(HisDiseaseReportTriggerRule::getEnabled, 1)
                .orderByAsc(HisDiseaseReportTriggerRule::getPriority)
                .orderByAsc(HisDiseaseReportTriggerRule::getId)
                .list();
    }

    /** 维护界面用: 全量(含停用), 按优先级排序 */
    public List<HisDiseaseReportTriggerRule> listAllOrdered() {
        return lambdaQuery()
                .orderByAsc(HisDiseaseReportTriggerRule::getPriority)
                .orderByAsc(HisDiseaseReportTriggerRule::getId)
                .list();
    }

    /** 新增规则(校验后落库; 同 match_type+code_pattern 已存在则拒绝, 防重复触发源) */
    public HisDiseaseReportTriggerRule createRule(HisDiseaseReportTriggerRule rule) {
        validate(rule);
        long dup = lambdaQuery()
                .eq(HisDiseaseReportTriggerRule::getMatchType, rule.getMatchType())
                .eq(HisDiseaseReportTriggerRule::getCodePattern, rule.getCodePattern())
                .count();
        if (dup > 0) {
            throw new BizException(400, "同匹配方式下该模式已存在规则: " + rule.getCodePattern());
        }
        if (rule.getId() != null) {
            rule.setId(null);
        }
        save(rule);
        return rule;
    }

    /** 修改规则(按 id 全字段覆盖, 逻辑删行不可改) */
    public HisDiseaseReportTriggerRule updateRule(Long id, HisDiseaseReportTriggerRule rule) {
        HisDiseaseReportTriggerRule exist = getById(id);
        if (exist == null) {
            throw new BizException(400, "规则不存在: " + id);
        }
        validate(rule);
        rule.setId(id);
        updateById(rule);
        return getById(id);
    }

    /** 行内启停即时生效 */
    public HisDiseaseReportTriggerRule toggle(Long id, Integer enabled) {
        HisDiseaseReportTriggerRule exist = getById(id);
        if (exist == null) {
            throw new BizException(400, "规则不存在: " + id);
        }
        exist.setEnabled(enabled != null && enabled == 1 ? 1 : 0);
        // 载回实体审计字段非空, MP strictUpdateFill 不覆盖旧值: 置空让填充器重写当前操作人/时间
        exist.setUpdateBy(null);
        exist.setUpdateTime(null);
        updateById(exist);
        return exist;
    }

    /** 删除(逻辑删, @TableLogic) */
    public void removeRule(Long id) {
        if (getById(id) == null) {
            throw new BizException(400, "规则不存在: " + id);
        }
        removeById(id);
    }

    private void validate(HisDiseaseReportTriggerRule rule) {
        if (rule == null) {
            throw new BizException(400, "规则内容不能为空");
        }
        if (rule.getReportCategory() == null || rule.getReportCategory() < 1
                || (rule.getReportCategory() > 5 && rule.getReportCategory() != 9)) {
            throw new BizException(400, "报卡大类非法(1传染病 2精障 3肿瘤 4高血压 5糖尿病 9其他)");
        }
        if (!StringUtils.hasText(rule.getMatchType()) || !MATCH_TYPES.contains(rule.getMatchType().trim().toLowerCase())) {
            throw new BizException(400, "匹配方式仅支持 prefix/exact/class");
        }
        rule.setMatchType(rule.getMatchType().trim().toLowerCase());
        if (!StringUtils.hasText(rule.getCodePattern())) {
            throw new BizException(400, "匹配模式不能为空");
        }
        rule.setCodePattern(rule.getCodePattern().trim());
        if (rule.getPriority() == null) {
            rule.setPriority(100);
        }
        if (rule.getEnabled() == null) {
            rule.setEnabled(1);
        }
    }
}
