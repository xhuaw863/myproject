package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yb.hi.entity.inpatient.HisSurgeryAuthRule;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.inpatient.HisSurgeryAuthRuleMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手术操作权限规则服务: 两种模式配置(1按手术等级 2按服务项目自定义分类),
 * 提供术者权限判定 check 与候选主刀列表 candidates(供申请/排程时过滤)。
 * 判定顺序: 命中规则且白名单含该医师→放行; 命中规则但白名单无→回退职工自身手术级别权限;
 * 未命中任何规则→按职工自身级别(his_staff.surgery_level 1-4, 5其他/未配置视为不限制)。
 */
@Slf4j
@Service
public class SurgeryAuthRuleService {

    private final HisSurgeryAuthRuleMapper ruleMapper;
    private final OrgAccessGuard guard;
    private final JdbcTemplate jdbcTemplate;

    public SurgeryAuthRuleService(HisSurgeryAuthRuleMapper ruleMapper, OrgAccessGuard guard,
                                  JdbcTemplate jdbcTemplate) {
        this.ruleMapper = ruleMapper;
        this.guard = guard;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ==================== 规则 CRUD ==================== */

    /** 规则列表(机构隔离读) */
    public List<Map<String, Object>> list(Long orgId) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, rule_name, rule_type, surgery_level, surgery_codes, allow_staff_ids, remark, status,"
                        + " create_by, create_time"
                        + " FROM his_surgery_auth_rule WHERE deleted = 0 AND tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        Long scope = guard.scopeOrgId(orgId);
        if (scope != null) {
            sql.append(" AND org_id = ?");
            args.add(scope);
        }
        sql.append(" ORDER BY rule_type, id DESC");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    public HisSurgeryAuthRule create(HisSurgeryAuthRule rule) {
        validate(rule);
        rule.setId(null);
        rule.setOrgId(guard.currentOrgId());
        if (rule.getStatus() == null) {
            rule.setStatus(1);
        }
        ruleMapper.insert(rule);
        log.info("手术权限规则创建: id={}, name={}, type={}", rule.getId(), rule.getRuleName(), rule.getRuleType());
        return rule;
    }

    public HisSurgeryAuthRule update(Long id, HisSurgeryAuthRule rule) {
        HisSurgeryAuthRule exist = requireRule(id);
        validate(rule);
        exist.setRuleName(rule.getRuleName());
        exist.setRuleType(rule.getRuleType());
        exist.setSurgeryLevel(rule.getSurgeryLevel());
        exist.setSurgeryCodes(rule.getSurgeryCodes());
        exist.setAllowStaffIds(rule.getAllowStaffIds());
        exist.setRemark(rule.getRemark());
        if (rule.getStatus() != null) {
            exist.setStatus(rule.getStatus());
        }
        ruleMapper.updateById(exist);
        return ruleMapper.selectById(id);
    }

    public void delete(Long id) {
        requireRule(id);
        ruleMapper.deleteById(id);
    }

    private HisSurgeryAuthRule requireRule(Long id) {
        HisSurgeryAuthRule r = ruleMapper.selectById(id);
        if (r == null) {
            throw new BizException(404, "权限规则不存在");
        }
        Long scope = guard.scopeOrgId(r.getOrgId());
        if (scope == null || !scope.equals(r.getOrgId())) {
            throw new BizException(403, "无权操作其他机构的权限规则");
        }
        return r;
    }

    private void validate(HisSurgeryAuthRule rule) {
        if (rule == null || !StringUtils.hasText(rule.getRuleName())) {
            throw new BizException(400, "规则名称不能为空");
        }
        if (rule.getRuleType() == null || (rule.getRuleType() != 1 && rule.getRuleType() != 2)) {
            throw new BizException(400, "规则类型必填: 1按手术等级 2按自定义分类");
        }
        if (rule.getRuleType() == 1 && rule.getSurgeryLevel() == null) {
            throw new BizException(400, "等级模式须指定受限最低手术级别");
        }
        if (rule.getRuleType() == 2 && !StringUtils.hasText(rule.getSurgeryCodes())) {
            throw new BizException(400, "自定义分类模式须填写手术编码/名称清单");
        }
        if (!StringUtils.hasText(rule.getAllowStaffIds())) {
            throw new BizException(400, "允许主刀的职工清单不能为空");
        }
    }

    /* ==================== 权限判定 ==================== */

    /**
     * 主刀权限判定: 返回 {allowed, reason}。
     * staffId/surgeryLevel 均可空(空=无法判定, 按放行处理, 由前端提示补充)。
     */
    public Map<String, Object> check(Long staffId, String surgeryCode, String surgeryName, Integer surgeryLevel) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("allowed", true);
        out.put("reason", null);
        if (staffId == null) {
            return out;
        }
        List<HisSurgeryAuthRule> rules = ruleMapper.selectList(new LambdaQueryWrapper<HisSurgeryAuthRule>()
                .eq(HisSurgeryAuthRule::getStatus, 1));
        boolean hit = false;
        String hitRule = null;
        for (HisSurgeryAuthRule r : rules) {
            boolean matched = false;
            if (r.getRuleType() != null && r.getRuleType() == 2) {
                matched = matchCustom(r, surgeryCode, surgeryName);
            } else if (r.getRuleType() != null && r.getRuleType() == 1
                    && surgeryLevel != null && r.getSurgeryLevel() != null
                    && surgeryLevel >= r.getSurgeryLevel()) {
                matched = true;
            }
            if (!matched) {
                continue;
            }
            hit = true;
            if (containsId(r.getAllowStaffIds(), staffId)) {
                out.put("reason", "命中规则[" + r.getRuleName() + "]白名单放行");
                return out;
            }
            if (hitRule == null) {
                hitRule = r.getRuleName();
            }
        }
        // 回退/未命中规则: 按职工自身授权手术级别(his_staff.surgery_level: 1-4级, 5其他/空=不限制)
        Integer staffLevel = staffSurgeryLevel(staffId);
        if (staffLevel != null && staffLevel >= 1 && staffLevel <= 4
                && surgeryLevel != null && staffLevel < surgeryLevel) {
            out.put("allowed", false);
            out.put("reason", hitRule != null
                    ? "不满足权限规则[" + hitRule + "], 且医师授权级别(第" + staffLevel + "级)低于该手术(第" + surgeryLevel + "级)"
                    : "医师授权手术级别(第" + staffLevel + "级)低于该手术(第" + surgeryLevel + "级)");
            return out;
        }
        if (!hit) {
            out.put("reason", null);
        }
        return out;
    }

    /**
     * 候选主刀列表(带权限标记): 按关键字检索在职职工, 逐人评估是否可主刀该手术。
     * 返回 [{id, name, surgery_level, allowed, reason}]
     */
    public List<Map<String, Object>> candidates(String keyword, String surgeryCode, String surgeryName,
                                                Integer surgeryLevel) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, staff_name, surgery_level FROM his_staff"
                        + " WHERE deleted = 0 AND tenant_id = ? AND status = 1");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (StringUtils.hasText(keyword)) {
            sql.append(" AND (staff_name LIKE ? OR py_code LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw);
            args.add(kw);
        }
        sql.append(" ORDER BY id LIMIT 30");
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
        for (Map<String, Object> row : rows) {
            Long sid = ((Number) row.get("id")).longValue();
            Map<String, Object> chk = check(sid, surgeryCode, surgeryName, surgeryLevel);
            row.put("allowed", chk.get("allowed"));
            row.put("reason", chk.get("reason"));
        }
        return rows;
    }

    /* ==================== 工具 ==================== */

    /** 自定义分类匹配: 手术编码或名称命中清单(逗号分隔, 任一段被包含即算命中) */
    private boolean matchCustom(HisSurgeryAuthRule r, String code, String name) {
        if (!StringUtils.hasText(r.getSurgeryCodes())) {
            return false;
        }
        List<String> items = Arrays.stream(r.getSurgeryCodes().split(","))
                .map(String::trim).filter(StringUtils::hasText).collect(java.util.stream.Collectors.toList());
        for (String item : items) {
            if (StringUtils.hasText(code) && code.contains(item)) {
                return true;
            }
            if (StringUtils.hasText(name) && (name.contains(item) || item.contains(name))) {
                return true;
            }
        }
        return false;
    }

    /** 职工ID是否在白名单(逗号分隔) */
    private boolean containsId(String ids, Long staffId) {
        if (!StringUtils.hasText(ids) || staffId == null) {
            return false;
        }
        return Arrays.stream(ids.split(","))
                .map(String::trim)
                .anyMatch(s -> s.equals(String.valueOf(staffId)));
    }

    /** 职工授权手术级别(his_staff.surgery_level, 非数字或空返回 null) */
    private Integer staffSurgeryLevel(Long staffId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT surgery_level FROM his_staff WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                staffId, tenantId());
        if (rows.isEmpty() || rows.get(0).get("surgery_level") == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(rows.get(0).get("surgery_level")).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
