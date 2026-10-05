package com.yb.hi.service.medtech;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.medtech.HisCriticalRule;
import com.yb.hi.entity.medtech.HisCriticalValue;
import com.yb.hi.entity.medtech.HisExamReport;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.medtech.HisCriticalRuleMapper;
import com.yb.hi.mapper.medtech.HisCriticalValueMapper;
import com.yb.hi.mapper.medtech.HisExamReportMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 危急值服务(报告→复核→通知→接收→处置全流程闭环 + 阈值规则维护)。
 * 口径:
 * 1) 危急值状态: 0已发现(待复核) -> 1已复核 -> 2已通知 -> 3已接收 -> 4已处置, 每步乐观锁 UPDATE ... WHERE status=前值;
 * 2) 检测: 提交送审时逐条比对启用规则(is_active=1), 低于下限/高于上限即命中;
 *    命中置结果项 abnormal_flag=4(偏低危急)/3(偏高危急) + 报告 critical_flag=1 + 生成危急值记录(同报告同项目防重复);
 * 3) 规则匹配: 项目编码精确优先, 名称兜底; 患者类型按当前患者年龄推导(>=14成人 adult, 否则 child),
 *    命中具体类型规则优先, 无则回落通用(patient_type 为空)规则;
 * 4) 规则删除为物理删除(唯一键含 item_code, 软删残留会撞唯一键);
 * 5) 租户级表跨表查询走 JdbcTemplate 显式租户过滤。
 */
@Slf4j
@Service
public class CriticalValueService {

    /** 结果项异常标志: 危急值(偏低方向) */
    private static final int FLAG_CRITICAL_LOW = 4;
    /** 结果项异常标志: 危急值(偏高方向) */
    private static final int FLAG_CRITICAL_HIGH = 3;

    private final HisCriticalValueMapper criticalValueMapper;
    private final HisCriticalRuleMapper criticalRuleMapper;
    private final HisExamReportMapper reportMapper;
    private final JdbcTemplate jdbcTemplate;

    public CriticalValueService(HisCriticalValueMapper criticalValueMapper,
                                HisCriticalRuleMapper criticalRuleMapper,
                                HisExamReportMapper reportMapper,
                                JdbcTemplate jdbcTemplate) {
        this.criticalValueMapper = criticalValueMapper;
        this.criticalRuleMapper = criticalRuleMapper;
        this.reportMapper = reportMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 危急值检测(提交送审时触发) ================= */

    /**
     * 检测报告危急值: 逐条结果明细比对启用规则, 命中则
     * 升级结果项标志(3偏高/4偏低) + 置报告 critical_flag=1 + 创建危急值记录(已存在同项目记录则仅回标不重复建单)。
     * 返回本次新创建的危急值记录(前端弹红色警告框)。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<HisCriticalValue> checkCriticalValues(Long reportId) {
        if (reportId == null) {
            throw new BizException(400, "报告ID不能为空");
        }
        HisExamReport report = reportMapper.selectById(reportId);
        if (report == null) {
            throw new BizException(400, "报告不存在");
        }
        long tid = tenantId();
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT id, item_code, item_name, result_value FROM his_exam_result_item"
                        + " WHERE report_id = ? AND tenant_id = ? AND deleted = 0 ORDER BY id",
                reportId, tid);
        List<HisCriticalValue> created = new ArrayList<>();
        if (items.isEmpty()) {
            return created;
        }
        // 规则库(启用中, Mapper 查询经租户插件自动过滤) + 患者类型(年龄推导)
        List<HisCriticalRule> rules = criticalRuleMapper.selectList(Wrappers.<HisCriticalRule>lambdaQuery()
                .eq(HisCriticalRule::getIsActive, 1));
        if (rules.isEmpty()) {
            return created;
        }
        String patientType = patientTypeOf(report.getPatientId());

        boolean hasCritical = false;
        for (Map<String, Object> item : items) {
            String itemCode = str(item.get("item_code"));
            String itemName = str(item.get("item_name"));
            BigDecimal value = toBd(item.get("result_value"));
            if (value == null) {
                continue;
            }
            HisCriticalRule rule = findRule(rules, itemCode, itemName, patientType);
            if (rule == null) {
                continue;
            }
            int flag;
            if (rule.getHighThreshold() != null && value.compareTo(rule.getHighThreshold()) > 0) {
                flag = FLAG_CRITICAL_HIGH;
            } else if (rule.getLowThreshold() != null && value.compareTo(rule.getLowThreshold()) < 0) {
                flag = FLAG_CRITICAL_LOW;
            } else {
                continue;
            }
            hasCritical = true;
            Long itemId = toLong(item.get("id"));
            jdbcTemplate.update(
                    "UPDATE his_exam_result_item SET abnormal_flag = ?, update_time = NOW() WHERE id = ? AND tenant_id = ?",
                    flag, itemId, tid);

            // 同报告同项目防重复建单(退回重提交时仅回标标志, 不重复生成留痕)
            Long exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM his_critical_value"
                            + " WHERE report_id = ? AND item_code = ? AND tenant_id = ? AND deleted = 0",
                    Long.class, reportId, itemCode, tid);
            if (exists != null && exists > 0) {
                continue;
            }
            HisCriticalValue cv = new HisCriticalValue();
            cv.setOrgId(report.getOrgId());
            cv.setReportId(reportId);
            cv.setOrderId(report.getOrderId());
            cv.setPatientId(report.getPatientId());
            cv.setItemCode(itemCode);
            cv.setItemName(itemName);
            cv.setResultValue(str(item.get("result_value")));
            cv.setDiscoverTime(java.time.LocalDateTime.now());
            cv.setStatus(0);
            criticalValueMapper.insert(cv);
            created.add(cv);
            log.warn("危急值命中: reportId={}, item={}({}), value={}, 规则阈值[{}, {}], flag={}",
                    reportId, itemName, itemCode, item.get("result_value"),
                    rule.getLowThreshold(), rule.getHighThreshold(), flag);
        }
        if (hasCritical) {
            jdbcTemplate.update(
                    "UPDATE his_exam_report SET critical_flag = 1, update_time = NOW() WHERE id = ? AND tenant_id = ?",
                    reportId, tid);
        }
        return created;
    }

    /** 规则匹配: 项目编码精确优先 -> 名称兜底; 同键下具体患者类型优先, 回落通用(patient_type 空) */
    private HisCriticalRule findRule(List<HisCriticalRule> rules, String itemCode, String itemName, String patientType) {
        HisCriticalRule hit = pick(rules, r -> StringUtils.hasText(itemCode)
                && itemCode.equalsIgnoreCase(r.getItemCode()), patientType);
        if (hit != null) {
            return hit;
        }
        return pick(rules, r -> StringUtils.hasText(itemName)
                && itemName.equalsIgnoreCase(r.getItemName()), patientType);
    }

    private HisCriticalRule pick(List<HisCriticalRule> rules, Predicate<HisCriticalRule> match, String patientType) {
        HisCriticalRule general = null;
        for (HisCriticalRule r : rules) {
            if (!match.test(r)) {
                continue;
            }
            if (StringUtils.hasText(patientType) && patientType.equalsIgnoreCase(r.getPatientType())) {
                return r;
            }
            if (!StringUtils.hasText(r.getPatientType()) && general == null) {
                general = r;
            }
        }
        return general;
    }

    /** 患者类型推导(危急值规则维度): 患者年龄>=14 为成人(adult), 否则儿童(child); 无档案返回 null(只匹配通用规则) */
    private String patientTypeOf(Long patientId) {
        if (patientId == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT age FROM his_patient WHERE id = ? AND tenant_id = ? AND deleted = 0",
                patientId, tenantId());
        if (rows.isEmpty() || rows.get(0).get("age") == null) {
            return null;
        }
        try {
            int age = Integer.parseInt(rows.get(0).get("age").toString());
            return age >= 14 ? "adult" : "child";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /* ================= 危急值闭环流转(乐观锁) ================= */

    /** 复核: 0->1, 记录复核技师与复核时间 */
    public HisCriticalValue verifyCritical(Long criticalId, Long techId) {
        transfer(criticalId, 0, 1,
                "verify_tech_id = ?, verify_time = NOW()", techId,
                "该危急值记录不存在或状态已变更(仅待复核可复核)");
        log.info("危急值复核: id={}, techId={}", criticalId, techId);
        return requireById(criticalId);
    }

    /** 通知临床: 1->2, 记录通知时间与通知对象 */
    public HisCriticalValue notifyClinical(Long criticalId, String target) {
        if (!StringUtils.hasText(target)) {
            throw new BizException(400, "被通知人不能为空");
        }
        transfer(criticalId, 1, 2,
                "notify_time = NOW(), notify_target = ?", target.trim(),
                "该危急值记录不存在或状态已变更(仅已复核可通知)");
        log.info("危急值通知临床: id={}, target={}", criticalId, target);
        return requireById(criticalId);
    }

    /** 临床接收回执: 2->3, 记录接收时间与接收人 */
    public HisCriticalValue confirmReceive(Long criticalId, String person) {
        if (!StringUtils.hasText(person)) {
            throw new BizException(400, "接收人不能为空");
        }
        transfer(criticalId, 2, 3,
                "receive_time = NOW(), receive_person = ?", person.trim(),
                "该危急值记录不存在或状态已变更(仅已通知可确认接收)");
        log.info("危急值临床接收: id={}, person={}", criticalId, person);
        return requireById(criticalId);
    }

    /** 记录处置: 3->4, 记录处置时间与处置措施(闭环完成) */
    public HisCriticalValue recordHandle(Long criticalId, String measures) {
        if (!StringUtils.hasText(measures)) {
            throw new BizException(400, "处置措施不能为空");
        }
        transfer(criticalId, 3, 4,
                "handle_time = NOW(), handle_measures = ?", measures.trim(),
                "该危急值记录不存在或状态已变更(仅已接收可记录处置)");
        log.info("危急值处置记录: id={}, measures={}", criticalId, measures);
        return requireById(criticalId);
    }

    /** 乐观锁状态流转公共实现: UPDATE ... SET status=to, <extraSet> WHERE id=? AND status=from; affected=0 报错 */
    private void transfer(Long criticalId, int from, int to, String extraSet, Object extraArg, String errMsg) {
        if (criticalId == null) {
            throw new BizException(400, "危急值记录ID不能为空");
        }
        int affected = jdbcTemplate.update(
                "UPDATE his_critical_value SET status = ?, " + extraSet + ", update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND status = ? AND tenant_id = ? AND deleted = 0",
                to, extraArg, currentUserName(), criticalId, from, tenantId());
        if (affected == 0) {
            throw new BizException(errMsg);
        }
    }

    /* ================= 危急值查询 ================= */

    /** 危急值分页(机构级, status 可选; JOIN his_patient 取患者信息, JOIN his_staff 取复核技师姓名) */
    public IPage<Map<String, Object>> listCriticalValues(Long orgId, Integer status, long page, long size) {
        if (orgId == null) {
            throw new BizException(400, "机构ID不能为空");
        }
        long p = safePage(page);
        long s = safeSize(size);
        StringBuilder where = new StringBuilder(" WHERE cv.deleted = 0 AND cv.tenant_id = ? AND cv.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(orgId);
        if (status != null) {
            where.append(" AND cv.status = ?");
            args.add(status);
        }
        String joins = " FROM his_critical_value cv"
                + " LEFT JOIN his_patient p ON p.id = cv.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_staff v ON v.id = cv.verify_tech_id AND v.deleted = 0"
                + " LEFT JOIN his_exam_report r ON r.id = cv.report_id AND r.deleted = 0";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + joins + where, Long.class, args.toArray());
        String dataSql = "SELECT cv.id, cv.report_id AS reportId, cv.order_id AS orderId, cv.patient_id AS patientId,"
                + " cv.item_code AS itemCode, cv.item_name AS itemName, cv.result_value AS resultValue,"
                + " DATE_FORMAT(cv.discover_time, '%Y-%m-%d %H:%i:%s') AS discoverTime,"
                + " cv.verify_tech_id AS verifyTechId,"
                + " DATE_FORMAT(cv.verify_time, '%Y-%m-%d %H:%i:%s') AS verifyTime,"
                + " DATE_FORMAT(cv.notify_time, '%Y-%m-%d %H:%i:%s') AS notifyTime, cv.notify_target AS notifyTarget,"
                + " DATE_FORMAT(cv.receive_time, '%Y-%m-%d %H:%i:%s') AS receiveTime, cv.receive_person AS receivePerson,"
                + " DATE_FORMAT(cv.handle_time, '%Y-%m-%d %H:%i:%s') AS handleTime, cv.handle_measures AS handleMeasures,"
                + " cv.status, p.name AS patientName, p.patient_no AS patientNo, p.gender_name AS genderName, p.age,"
                + " v.staff_name AS verifyTechName, r.report_no AS reportNo, r.report_type AS reportType"
                + joins + where + " ORDER BY cv.discover_time DESC, cv.id DESC LIMIT ?, ?";
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(dataSql, dataArgs.toArray());

        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setTotal(total == null ? 0L : total);
        result.setRecords(rows);
        return result;
    }

    /**
     * OP-D 医生站危急值待办: 开单医生=本人的就诊产生且尚未被我站接收的记录
     * (status 1已复核待通知 / 2已通知待接收, 经 order→visit.staff_id 归属收口)
     */
    public List<Map<String, Object>> myPendingForDoctor(Long staffId) {
        if (staffId == null) {
            throw new BizException(400, "无法获取当前登录医师");
        }
        String joins = " FROM his_critical_value cv"
                + " JOIN his_order o ON o.id = cv.order_id AND o.deleted = 0"
                + " JOIN his_visit vs ON vs.id = o.visit_id AND vs.deleted = 0"
                + " LEFT JOIN his_patient p ON p.id = cv.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_exam_report r ON r.id = cv.report_id AND r.deleted = 0";
        String sql = "SELECT cv.id, cv.report_id AS reportId, cv.order_id AS orderId, cv.patient_id AS patientId,"
                + " vs.id AS visitId, cv.item_code AS itemCode, cv.item_name AS itemName, cv.result_value AS resultValue,"
                + " DATE_FORMAT(cv.discover_time, '%Y-%m-%d %H:%i:%s') AS discoverTime,"
                + " DATE_FORMAT(cv.notify_time, '%Y-%m-%d %H:%i:%s') AS notifyTime, cv.notify_target AS notifyTarget,"
                + " cv.status, p.name AS patientName, p.patient_no AS patientNo, p.gender_name AS genderName, p.age,"
                + " r.report_no AS reportNo, r.report_type AS reportType, vs.dept_name AS deptName"
                + joins + " WHERE cv.deleted = 0 AND cv.tenant_id = ? AND vs.staff_id = ? AND cv.status IN (1, 2)"
                + " ORDER BY cv.status DESC, cv.discover_time DESC, cv.id DESC LIMIT 20";
        return jdbcTemplate.queryForList(sql, tenantId(), staffId);
    }

    /* ================= 危急值规则维护 ================= */

    /** 规则列表(全部含停用, 按项目编码排序; Mapper 查询经租户插件自动过滤) */
    public List<HisCriticalRule> listRules() {
        return criticalRuleMapper.selectList(Wrappers.<HisCriticalRule>lambdaQuery()
                .orderByAsc(HisCriticalRule::getItemCode)
                .orderByAsc(HisCriticalRule::getId));
    }

    /** 新增规则: 项目编码必填 + 至少一个阈值 + 同项目同患者类型防重(空类型=通用) */
    public HisCriticalRule createRule(HisCriticalRule rule) {
        normalizeRule(rule, true);
        Long dup = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_critical_rule WHERE tenant_id = ? AND deleted = 0 AND item_code = ?"
                        + " AND ((patient_type IS NULL AND ? IS NULL) OR patient_type = ?)",
                Long.class, tenantId(), rule.getItemCode(),
                StringUtils.hasText(rule.getPatientType()) ? rule.getPatientType() : null,
                StringUtils.hasText(rule.getPatientType()) ? rule.getPatientType() : null);
        if (dup != null && dup > 0) {
            throw new BizException("该项目在相同患者类型下已存在规则, 请直接编辑");
        }
        rule.setId(null);
        criticalRuleMapper.insert(rule);
        log.info("危急值规则新增: code={}, name={}, [{}, {}], patientType={}",
                rule.getItemCode(), rule.getItemName(), rule.getLowThreshold(), rule.getHighThreshold(), rule.getPatientType());
        return rule;
    }

    /** 编辑规则: 判存 + 防重(排除自身); 阈值/患者类型允许清空(显式 set 更新, 规避 updateById 忽略 null) */
    public HisCriticalRule updateRule(Long id, HisCriticalRule rule) {
        if (id == null) {
            throw new BizException(400, "规则ID不能为空");
        }
        HisCriticalRule exists = criticalRuleMapper.selectById(id);
        if (exists == null) {
            throw new BizException(400, "规则不存在");
        }
        rule.setId(id);
        normalizeRule(rule, true);
        Long dup = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_critical_rule WHERE tenant_id = ? AND deleted = 0 AND item_code = ? AND id <> ?"
                        + " AND ((patient_type IS NULL AND ? IS NULL) OR patient_type = ?)",
                Long.class, tenantId(), rule.getItemCode(), id,
                StringUtils.hasText(rule.getPatientType()) ? rule.getPatientType() : null,
                StringUtils.hasText(rule.getPatientType()) ? rule.getPatientType() : null);
        if (dup != null && dup > 0) {
            throw new BizException("该项目在相同患者类型下已存在其他规则, 请检查");
        }
        criticalRuleMapper.update(null, Wrappers.<HisCriticalRule>lambdaUpdate()
                .eq(HisCriticalRule::getId, id)
                .set(HisCriticalRule::getItemCode, rule.getItemCode())
                .set(HisCriticalRule::getItemName, rule.getItemName())
                .set(HisCriticalRule::getLowThreshold, rule.getLowThreshold())
                .set(HisCriticalRule::getHighThreshold, rule.getHighThreshold())
                .set(HisCriticalRule::getPatientType, rule.getPatientType())
                .set(HisCriticalRule::getIsActive, rule.getIsActive()));
        log.info("危急值规则编辑: id={}, code={}", id, rule.getItemCode());
        return criticalRuleMapper.selectById(id);
    }

    /** 删除规则(物理删除: 唯一键含 item_code, 软删残留会撞唯一键导致无法重建同项目规则) */
    public void deleteRule(Long id) {
        if (id == null) {
            throw new BizException(400, "规则ID不能为空");
        }
        HisCriticalRule exists = criticalRuleMapper.selectById(id);
        if (exists == null) {
            throw new BizException(400, "规则不存在");
        }
        int affected = jdbcTemplate.update(
                "DELETE FROM his_critical_rule WHERE id = ? AND tenant_id = ?", id, tenantId());
        if (affected == 0) {
            throw new BizException("规则删除失败, 请刷新后重试");
        }
        log.info("危急值规则删除: id={}, code={}", id, exists.getItemCode());
    }

    /** 规则字段归一: 编码必填 + 阈值至少一项 + 名称兜底 + 患者类型空串转 null + 启用默认1 */
    private static void normalizeRule(HisCriticalRule rule, boolean requireThreshold) {
        if (rule == null) {
            throw new BizException(400, "规则内容不能为空");
        }
        if (!StringUtils.hasText(rule.getItemCode())) {
            throw new BizException(400, "项目编码不能为空");
        }
        rule.setItemCode(rule.getItemCode().trim());
        rule.setItemName(StringUtils.hasText(rule.getItemName()) ? rule.getItemName().trim() : rule.getItemCode());
        if (!StringUtils.hasText(rule.getPatientType())) {
            rule.setPatientType(null);
        } else {
            rule.setPatientType(rule.getPatientType().trim().toLowerCase());
        }
        if (rule.getIsActive() == null) {
            rule.setIsActive(1);
        }
        if (requireThreshold && rule.getLowThreshold() == null && rule.getHighThreshold() == null) {
            throw new BizException(400, "危急值下限与上限至少填写一项");
        }
        if (rule.getLowThreshold() != null && rule.getHighThreshold() != null
                && rule.getLowThreshold().compareTo(rule.getHighThreshold()) > 0) {
            throw new BizException(400, "危急值下限不能高于上限");
        }
    }

    /* ================= 辅助 ================= */

    private HisCriticalValue requireById(Long id) {
        HisCriticalValue cv = criticalValueMapper.selectById(id);
        if (cv == null) {
            throw new BizException(400, "危急值记录不存在");
        }
        return cv;
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    private String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername();
    }

    private static long safePage(long page) {
        return page < 1 ? 1 : page;
    }

    private static long safeSize(long size) {
        if (size < 1) {
            return 20;
        }
        return size > 200 ? 200 : size;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.valueOf(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal toBd(Object o) {
        return ExamReportService.toBd(o);
    }

    /* ================= 影像科危急值规则种子(T3 任务书4) ================= */

    /**
     * 影像科专属危急值规则种子(静态, 供 DictSchemaMigration.ensureRisTables 启动期播种):
     * 张力性气胸 / 大量胸腔积液 / 颅内出血六亚型(硬膜外/硬膜下/蛛网膜下腔/脑实质/脑室/小脑脑干) /
     * 急性肺栓塞 / 主动脉夹层 / 主动脉瘤破裂 / 消化道穿孔 / 急性肠梗阻 / 肠扭转 /
     * 脊柱不稳定骨折 / 骨盆骨折。
     * 口径: item_code 统一 IMG_CV_ 前缀落 his_critical_value_rule(gender=0 通用, alert_level=1 危急,
     * tenant_id=1 默认租户), 阈值列留空——影像危急值为术语/征象型条目, 无数值阈值, 供影像报告
     * 征象命中与危急值上报流程选用; 与既有检验阈值种子(seedCriticalValueRules)同表共存。
     * 幂等: INSERT ... SELECT ... WHERE NOT EXISTS(同 code 同 gender 同租户未删除则跳过), 重复启动不产生副本。
     */
    public static void seedImagingCriticalRules(Connection conn) throws SQLException {
        String sql = "INSERT INTO his_critical_value_rule"
                + " (item_code, item_name, unit, critical_low, critical_high, gender, alert_level, enabled, tenant_id, deleted)"
                + " SELECT ?, ?, NULL, NULL, NULL, 0, 1, 1, 1, 0 FROM DUAL"
                + " WHERE NOT EXISTS (SELECT 1 FROM his_critical_value_rule"
                + " WHERE item_code = ? AND gender = 0 AND tenant_id = 1 AND deleted = 0)";
        /* {item_code, item_name}; 颅内出血六亚型在前段集中 */
        String[][] seeds = {
                {"IMG_CV_TENSION_PTX", "张力性气胸"},
                {"IMG_CV_MASSIVE_PLEURAL", "大量胸腔积液"},
                {"IMG_CV_EDH", "硬膜外出血"},
                {"IMG_CV_SDH", "硬膜下出血"},
                {"IMG_CV_SAH", "蛛网膜下腔出血"},
                {"IMG_CV_IPH", "脑实质出血"},
                {"IMG_CV_IVH", "脑室内出血"},
                {"IMG_CV_CEREBELLUM_HEMO", "小脑/脑干出血"},
                {"IMG_CV_PE", "急性肺栓塞"},
                {"IMG_CV_AO_DISSECTION", "主动脉夹层"},
                {"IMG_CV_AO_RUPTURE", "主动脉瘤破裂"},
                {"IMG_CV_GI_PERFORATION", "消化道穿孔"},
                {"IMG_CV_BOWEL_OBSTRUCTION", "急性肠梗阻"},
                {"IMG_CV_VOLVULUS", "肠扭转"},
                {"IMG_CV_SPINE_UNSTABLE_FX", "脊柱不稳定骨折"},
                {"IMG_CV_PELVIS_FX", "骨盆骨折"},
        };
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String[] s : seeds) {
                ps.setString(1, s[0]);
                ps.setString(2, s[1]);
                ps.setString(3, s[0]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}

