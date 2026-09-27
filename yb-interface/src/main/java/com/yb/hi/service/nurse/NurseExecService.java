package com.yb.hi.service.nurse;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.nurse.HisNurseExec;
import com.yb.hi.entity.nurse.HisPatientAllergy;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.nurse.HisNurseExecMapper;
import com.yb.hi.mapper.nurse.HisPatientAllergyMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 护士站执行服务(注射/输液/皮试/换药统一台账): 待执行工作台 / 执行流转 / 患者过敏档案 / 执行记录查询。
 * 说明:
 * 1) his_nurse_exec 为机构级业务表(MP 租户插件自动注入 tenant_id), Mapper 查询自动带租户与逻辑删;
 *    JdbcTemplate 跨表查询显式带 tenant_id = TenantContext.get();
 * 2) his_order / his_order_item / his_visit 为租户级表(无 org_id), 机构隔离经
 *    his_visit.dept_id -> his_dept.org_id 间接过滤(与任务规格一致); 已落库的执行记录优先用自身 org_id;
 * 3) 执行单生成: 收费完成后(收费模块)调 {@link #createExecRecords(Long)} 为医嘱明细逐项建执行单,
 *    工作台查询时对"已收费未生成"的医嘱幂等兜底补建(行锁+NOT EXISTS 防并发双插), 不依赖收费侧必达;
 * 4) 状态机(乐观锁): exec_status 0待执行 -> 1执行中 -> 2已完成; 0/1 -> -1已取消。
 *    完成联动: 同医嘱单下全部执行单完成(取消不计入)则回写 his_order.exec_status=2;
 *    开始联动: 首条开始时 his_order.exec_status 0->1;
 * 5) 单号: HS + yyyyMMdd + 4位序号, synchronized 生成 + DB 回读当日最大序号兜底重启防撞号。
 */
@Slf4j
@Service
public class NurseExecService {

    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 执行状态: 0待执行 1执行中 2已完成 -1已取消(作废语义与处方/标本一致) */
    public static final int ST_PENDING = 0;
    public static final int ST_RUNNING = 1;
    public static final int ST_FINISHED = 2;
    public static final int ST_CANCELLED = -1;

    /** 执行类型: 皮试/输液/注射/换药(门诊护士站四类, other 保留给后续扩展) */
    public static final String TYPE_SKIN_TEST = "skin_test";
    public static final String TYPE_INFUSION = "infusion";
    public static final String TYPE_INJECTION = "injection";
    public static final String TYPE_DRESSING = "dressing";

    private final HisNurseExecMapper execMapper;
    private final HisPatientAllergyMapper allergyMapper;
    private final JdbcTemplate jdbcTemplate;

    /** 单号内存序号(synchronized 唯一; 跨日重置时从DB回读当日最大序号) */
    private String seqDate;
    private int seqNo = 0;

    public NurseExecService(HisNurseExecMapper execMapper, HisPatientAllergyMapper allergyMapper,
                            JdbcTemplate jdbcTemplate) {
        this.execMapper = execMapper;
        this.allergyMapper = allergyMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 待执行工作台 ================= */

    /**
     * 待执行医嘱(工作台): 已缴费(paid_flag=1) + 待执行(exec_status=0) 的注射/输液/皮试/换药类执行单。
     * 查询前先对"已收费但未生成执行单"的医嘱幂等兜底补建(收费侧漏调时护士站仍可用)。
     * 排队口径: 急诊(med_type=14)优先, 其余按开单时间先开先做; 限 200 条防误配炸页面。
     * 机构隔离: his_order 无 org_id, 经 his_visit.dept_id -> his_dept.org_id 过滤。
     */
    public List<Map<String, Object>> listPendingOrders(Long orgId, Long deptId, String execType, String keyword) {
        return listOrders(orgId, deptId, execType, keyword, ST_PENDING);
    }

    /**
     * 医嘱执行单列表(按执行状态): 待执行(0)光开单时间排队, 执行中(1)先开始先完成(供"完成/取消"闭环);
     * 仅待执行查询前做兜底补建(执行中/终态单据已生成, 无需补)。
     */
    public List<Map<String, Object>> listOrders(Long orgId, Long deptId, String execType, String keyword, Integer execStatus) {
        Long oid = requireOrg(orgId);
        if (execStatus == null || execStatus == ST_PENDING) {
            ensureExecRecordsForPaidOrders(oid);
        }

        StringBuilder sql = new StringBuilder(
                "SELECT e.id AS execId, e.exec_no AS execNo, e.exec_type AS execType, e.exec_status AS execStatus,"
                        + " e.order_id AS orderId, e.order_item_id AS orderItemId, e.visit_id AS visitId, e.patient_id AS patientId,"
                        + " o.order_no AS orderNo, o.dr_name AS doctorName, o.dept_name AS orderDeptName, o.diag_name AS diagName,"
                        + " oi.item_name AS itemName, oi.spec, oi.unit, oi.quantity, oi.exec_dept AS itemExecDept,"
                        + " v.patient_name AS patientName, v.ipt_otp_no AS visitNo, v.med_type AS medType,"
                        + " CASE v.gender WHEN '1' THEN '男' WHEN '2' THEN '女' ELSE IFNULL(v.gender, '-') END AS genderName,"
                        + " v.age, v.dept_id AS deptId, v.dept_name AS visitDeptName,"
                        + " DATE_FORMAT(o.create_time, '%Y-%m-%d %H:%i:%s') AS orderTime,"
                        + " (SELECT COUNT(*) FROM his_patient_allergy a"
                        + "   WHERE a.patient_id = e.patient_id AND a.is_active = 1 AND a.tenant_id = ? AND a.deleted = 0) AS allergyCount,"
                        + " (SELECT GROUP_CONCAT(a.allergen_name SEPARATOR '、') FROM his_patient_allergy a"
                        + "   WHERE a.patient_id = e.patient_id AND a.is_active = 1 AND a.tenant_id = ? AND a.deleted = 0) AS allergyNames"
                        + " FROM his_nurse_exec e"
                        + " JOIN his_order o ON o.id = e.order_id AND o.deleted = 0 AND o.paid_flag = 1 AND o.status > 0"
                        + " JOIN his_visit v ON v.id = e.visit_id AND v.deleted = 0"
                        + " JOIN his_dept d ON d.id = v.dept_id AND d.deleted = 0 AND d.org_id = ?"
                        + " LEFT JOIN his_order_item oi ON oi.id = e.order_item_id AND oi.deleted = 0"
                        + " WHERE e.deleted = 0 AND e.tenant_id = ? AND e.exec_status = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(tenantId());
        args.add(oid);
        args.add(tenantId());
        args.add(execStatus == null ? ST_PENDING : execStatus);
        if (deptId != null) {
            sql.append(" AND v.dept_id = ?");
            args.add(deptId);
        }
        if (StringUtils.hasText(execType)) {
            sql.append(" AND e.exec_type = ?");
            args.add(execType.trim());
        }
        if (StringUtils.hasText(keyword)) {
            sql.append(" AND (v.patient_name LIKE ? OR o.patient_name LIKE ? OR e.exec_no LIKE ? OR o.order_no LIKE ?)");
            String kw = "%" + keyword.trim() + "%";
            args.add(kw);
            args.add(kw);
            args.add(kw);
            args.add(kw);
        }
        sql.append(" ORDER BY (v.med_type = '14') DESC, COALESCE(e.exec_time, o.create_time) ASC, e.id ASC LIMIT 200");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /**
     * 已收费医嘱的执行单补建兜底: 扫描本机构"已缴费(paid_flag=1)+未执行(exec_status=0)+尚无执行单"的医嘱,
     * 逐单调 createExecRecords(行锁+NOT EXISTS 幂等)。正常链路由收费完成时调用, 此处仅兜底漏调/历史数据;
     * 单条失败仅记日志不阻断列表查询。
     */
    private void ensureExecRecordsForPaidOrders(Long orgId) {
        List<Long> orderIds = jdbcTemplate.queryForList(
                "SELECT o.id FROM his_order o"
                        + " JOIN his_visit v ON v.id = o.visit_id AND v.deleted = 0"
                        + " JOIN his_dept d ON d.id = v.dept_id AND d.deleted = 0 AND d.org_id = ?"
                        + " WHERE o.tenant_id = ? AND o.deleted = 0 AND o.paid_flag = 1 AND o.exec_status = 0 AND o.status > 0"
                        + " AND NOT EXISTS (SELECT 1 FROM his_nurse_exec e"
                        + "   WHERE e.order_id = o.id AND e.tenant_id = o.tenant_id AND e.deleted = 0)"
                        + " ORDER BY o.id DESC LIMIT 20",
                Long.class, orgId, tenantId());
        for (Long orderId : orderIds) {
            try {
                List<HisNurseExec> created = createExecRecords(orderId);
                if (!created.isEmpty()) {
                    log.info("护士站兜底补建执行单: orderId={}, 生成{}条", orderId, created.size());
                }
            } catch (Exception ex) {
                log.warn("护士站兜底补建执行单失败: orderId={}, 原因: {}", orderId, ex.getMessage());
            }
        }
    }

    /* ================= 执行单生成(收费完成后调用/工作台兜底) ================= */

    /**
     * 为医嘱单生成护士执行记录: 收费完成后调用。逐条明细判断是否护士执行类
     * (exec_dept 或 item_name 含 皮试/输液/注射/换药 关键词), 命中且无既有执行单时创建。
     * 幂等: 行锁 his_order + NOT EXISTS(order_item_id), 并发调用不双插;
     * 机构归属: his_visit.dept_id -> his_dept.org_id 解析, 解析失败回退当前登录机构。
     */
    @Transactional
    public List<HisNurseExec> createExecRecords(Long orderId) {
        if (orderId == null) {
            throw new BizException(400, "医嘱ID不能为空");
        }
        long tid = tenantId();
        // 行锁防并发双插: 两个护士同时刷新工作台时串行化, 后到者走 NOT EXISTS 跳过
        List<Map<String, Object>> orders = jdbcTemplate.queryForList(
                "SELECT id, visit_id, patient_id, paid_flag, status FROM his_order"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0 FOR UPDATE", orderId, tid);
        if (orders.isEmpty()) {
            throw new BizException(404, "医嘱单不存在");
        }
        Map<String, Object> order = orders.get(0);
        Number paid = (Number) order.get("paid_flag");
        Number status = (Number) order.get("status");
        if (paid == null || paid.intValue() != 1) {
            throw new BizException(400, "医嘱单未收费, 不能生成执行记录");
        }
        if (status != null && status.intValue() < 0) {
            throw new BizException(400, "医嘱单已作废, 不能生成执行记录");
        }
        Long visitId = toLong(order.get("visit_id"));
        Long patientId = toLong(order.get("patient_id"));
        Long orgId = resolveOrgIdByVisit(visitId);

        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT id, item_code, item_name, exec_dept FROM his_order_item"
                        + " WHERE order_id = ? AND tenant_id = ? AND deleted = 0 ORDER BY id", orderId, tid);
        List<HisNurseExec> created = new ArrayList<>();
        for (Map<String, Object> it : items) {
            Long itemId = toLong(it.get("id"));
            String execType = resolveExecType(str(it.get("item_name")), str(it.get("exec_dept")));
            if (execType == null) {
                continue; // 非护士执行类明细(检验/检查/其他治疗)不进护士台账
            }
            // 双轨去重: 多次疗程类明细已由治疗站疗程计划承接(收费侧先建计划)时, 护士台不再重复建单
            Integer planCnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM his_treatment_plan WHERE order_id = ? AND item_code = ? AND tenant_id = ? AND deleted = 0",
                    Integer.class, orderId, str(it.get("item_code")), tid);
            if (planCnt != null && planCnt > 0) {
                continue;
            }
            Integer existed = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM his_nurse_exec"
                            + " WHERE order_item_id = ? AND tenant_id = ? AND deleted = 0",
                    Integer.class, itemId, tid);
            if (existed != null && existed > 0) {
                continue; // 幂等: 该明细已有执行单
            }
            HisNurseExec exec = new HisNurseExec();
            exec.setOrgId(orgId);
            exec.setVisitId(visitId);
            exec.setOrderId(orderId);
            exec.setOrderItemId(itemId);
            exec.setPatientId(patientId);
            exec.setExecType(execType);
            exec.setExecNo(generateExecNo());
            exec.setExecStatus(ST_PENDING);
            execMapper.insert(exec);
            created.add(exec);
        }
        if (!created.isEmpty()) {
            log.info("护士执行单生成: orderId={}, orgId={}, 明细{}项, 生成执行单{}条, 单号范围 {}~{}",
                    orderId, orgId, items.size(), created.size(),
                    created.get(0).getExecNo(), created.get(created.size() - 1).getExecNo());
        }
        return created;
    }

    /**
     * 护士执行类判定: 优先取 exec_dept(医生开单指定的执行科室), 为空回退 item_name。
     * 匹配优先级 皮试 > 输液 > 注射 > 换药(皮试多为"XX皮试液/皮内注射", 先判防误归注射)。
     * public: 治疗计划侧依此判定"护士站四类单次处置不进疗程"(双轨去重)。
     */
    public static String resolveExecType(String itemName, String execDept) {
        String text = ((itemName == null ? "" : itemName) + " " + (execDept == null ? "" : execDept)).trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.contains("皮试")) {
            return TYPE_SKIN_TEST;
        }
        if (text.contains("输液") || text.contains("静滴")) {
            return TYPE_INFUSION;
        }
        if (text.contains("注射") || text.contains("肌注") || text.contains("静注") || text.contains("静推")) {
            return TYPE_INJECTION;
        }
        if (text.contains("换药")) {
            return TYPE_DRESSING;
        }
        return null;
    }

    /** 机构归属解析: his_visit.dept_id -> his_dept.org_id; 失败回退当前登录机构 */
    private Long resolveOrgIdByVisit(Long visitId) {
        if (visitId != null) {
            List<Long> orgIds = jdbcTemplate.queryForList(
                    "SELECT d.org_id FROM his_visit v JOIN his_dept d ON d.id = v.dept_id AND d.deleted = 0"
                            + " WHERE v.id = ? AND v.deleted = 0", Long.class, visitId);
            if (!orgIds.isEmpty() && orgIds.get(0) != null) {
                return orgIds.get(0);
            }
        }
        LoginUser u = UserContext.get();
        return u == null ? null : u.getOrgId();
    }

    /* ================= 执行流转 ================= */

    /**
     * 开始执行(三查七对确认后): 乐观锁 exec_status 0->1, 记录执行护士与开始时间;
     * 首条开始时联动 his_order.exec_status 0->1(医嘱执行中)。
     */
    @Transactional
    public Map<String, Object> startExec(Long execId, Long nurseId) {
        HisNurseExec exec = requireExec(execId);
        requireSameOrg(exec.getOrgId());
        if (exec.getExecStatus() == null || exec.getExecStatus() != ST_PENDING) {
            throw new BizException(409, "当前执行单状态不可开始(需为待执行)");
        }
        int n = jdbcTemplate.update(
                "UPDATE his_nurse_exec SET exec_status = 1, exec_nurse_id = ?, exec_time = NOW(),"
                        + " update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND exec_status = 0 AND tenant_id = ? AND deleted = 0",
                nurseId, currentUserName(), execId, tenantId());
        if (n == 0) {
            throw new BizException(409, "执行单状态已变更(可能已被其他护士开始), 请刷新后重试");
        }
        // 医嘱单联动 0->1(执行中); 并发冲突(0行)不阻断, 以执行单状态为准
        jdbcTemplate.update(
                "UPDATE his_order SET exec_status = 1, update_time = NOW()"
                        + " WHERE id = ? AND exec_status = 0 AND tenant_id = ? AND deleted = 0",
                exec.getOrderId(), tenantId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("execId", execId);
        out.put("execNo", exec.getExecNo());
        out.put("execStatus", ST_RUNNING);
        out.put("orderId", exec.getOrderId());
        return out;
    }

    /**
     * 完成执行: 乐观锁 exec_status 1->2, 记录结束时间与患者反应;
     * 同医嘱单下全部执行单完成(取消不计入)则回写 his_order.exec_status=2(整单执行完毕)。
     */
    @Transactional
    public Map<String, Object> finishExec(Long execId, Long nurseId, String response) {
        HisNurseExec exec = requireExec(execId);
        requireSameOrg(exec.getOrgId());
        if (exec.getExecStatus() == null || exec.getExecStatus() != ST_RUNNING) {
            throw new BizException(409, "当前执行单状态不可完成(需为执行中)");
        }
        int n = jdbcTemplate.update(
                "UPDATE his_nurse_exec SET exec_status = 2, exec_nurse_id = COALESCE(exec_nurse_id, ?),"
                        + " end_time = NOW(), patient_response = ?, update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND exec_status = 1 AND tenant_id = ? AND deleted = 0",
                nurseId, trimToNull(response), currentUserName(), execId, tenantId());
        if (n == 0) {
            throw new BizException(409, "执行单状态已变更, 请刷新后重试");
        }
        boolean orderFinished = false;
        if (exec.getOrderId() != null) {
            Integer remain = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM his_nurse_exec"
                            + " WHERE order_id = ? AND exec_status NOT IN (2, -1) AND tenant_id = ? AND deleted = 0",
                    Integer.class, exec.getOrderId(), tenantId());
            if (remain != null && remain == 0) {
                int done = jdbcTemplate.update(
                        "UPDATE his_order SET exec_status = 2, update_time = NOW()"
                                + " WHERE id = ? AND exec_status < 2 AND tenant_id = ? AND deleted = 0",
                        exec.getOrderId(), tenantId());
                orderFinished = done > 0;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("execId", execId);
        out.put("execNo", exec.getExecNo());
        out.put("execStatus", ST_FINISHED);
        out.put("orderId", exec.getOrderId());
        out.put("orderFinished", orderFinished);
        return out;
    }

    /**
     * 取消执行单: 乐观锁 exec_status 0/1 -> -1, 记录取消原因(写 remark);
     * 已完成不可取消; 取消后医嘱单执行状态不回退(以完成联动为准)。
     */
    @Transactional
    public Map<String, Object> cancelExec(Long execId, String reason) {
        HisNurseExec exec = requireExec(execId);
        requireSameOrg(exec.getOrgId());
        Integer st = exec.getExecStatus();
        if (st == null || (st != ST_PENDING && st != ST_RUNNING)) {
            throw new BizException(409, "当前执行单状态不可取消(已完成/已取消)");
        }
        String cause = trimToNull(reason);
        if (cause == null) {
            throw new BizException(400, "取消原因不能为空");
        }
        String remark = "取消: " + cause;
        int n = jdbcTemplate.update(
                "UPDATE his_nurse_exec SET exec_status = -1, remark = ?, end_time = NOW(),"
                        + " update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND exec_status = ? AND tenant_id = ? AND deleted = 0",
                remark, currentUserName(), execId, st, tenantId());
        if (n == 0) {
            throw new BizException(409, "执行单状态已变更, 请刷新后重试");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("execId", execId);
        out.put("execNo", exec.getExecNo());
        out.put("execStatus", ST_CANCELLED);
        out.put("remark", remark);
        return out;
    }

    /* ================= 患者过敏档案 ================= */

    /** 患者有效过敏记录(is_active=1, 按登记时间倒序; 供执行前核对与过敏档案页) */
    public List<HisPatientAllergy> getPatientAllergies(Long patientId) {
        if (patientId == null) {
            throw new BizException(400, "患者ID不能为空");
        }
        return allergyMapper.selectList(Wrappers.<HisPatientAllergy>lambdaQuery()
                .eq(HisPatientAllergy::getPatientId, patientId)
                .eq(HisPatientAllergy::getIsActive, 1)
                .orderByDesc(HisPatientAllergy::getRecordTime)
                .orderByDesc(HisPatientAllergy::getId));
    }

    /**
     * 手工登记过敏记录: 默认来源 manual, 记录人与时间为当前登录护士;
     * 同患者同过敏原已有有效记录时拒绝(防重复建档)。
     */
    @Transactional
    public HisPatientAllergy addAllergy(HisPatientAllergy allergy) {
        if (allergy == null || allergy.getPatientId() == null) {
            throw new BizException(400, "患者ID不能为空");
        }
        if (!StringUtils.hasText(allergy.getAllergenName())) {
            throw new BizException(400, "过敏原名称不能为空");
        }
        String name = allergy.getAllergenName().trim();
        Long cnt = allergyMapper.selectCount(Wrappers.<HisPatientAllergy>lambdaQuery()
                .eq(HisPatientAllergy::getPatientId, allergy.getPatientId())
                .eq(HisPatientAllergy::getAllergenName, name)
                .eq(HisPatientAllergy::getIsActive, 1));
        if (cnt != null && cnt > 0) {
            throw new BizException(400, "该患者已登记过敏原\"" + name + "\", 请勿重复建档");
        }
        allergy.setAllergenName(name);
        if (!StringUtils.hasText(allergy.getAllergenType())) {
            allergy.setAllergenType("other");
        }
        if (!StringUtils.hasText(allergy.getSource())) {
            allergy.setSource("manual");
        }
        allergy.setRecordTime(java.time.LocalDateTime.now());
        allergy.setRecordBy(currentStaffId());
        allergy.setIsActive(1);
        allergyMapper.insert(allergy);
        return allergy;
    }

    /** 过敏档案全局检索(患者姓名/患者ID 关键字; 分页, 含患者与登记人姓名) */
    public Page<Map<String, Object>> listAllergies(String keyword, long page, long size) {
        long p = safePage(page);
        long s = safeSize(size);
        StringBuilder where = new StringBuilder(
                " WHERE a.deleted = 0 AND a.tenant_id = ? AND a.is_active = 1");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            where.append(" AND (p.name LIKE ? OR CAST(a.patient_id AS CHAR) LIKE ? OR a.allergen_name LIKE ?)");
            args.add("%" + kw + "%");
            args.add("%" + kw + "%");
            args.add("%" + kw + "%");
        }
        String joins = " FROM his_patient_allergy a"
                + " LEFT JOIN his_patient p ON p.id = a.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_staff s ON s.id = a.record_by AND s.deleted = 0";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + joins + where, Long.class, args.toArray());
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT a.id, a.patient_id AS patientId, p.name AS patientName, p.patient_no AS patientNo,"
                        + " CASE p.gender WHEN '1' THEN '男' WHEN '2' THEN '女' ELSE IFNULL(p.gender, '-') END AS genderName,"
                        + " p.age, a.allergen_type AS allergenType, a.allergen_name AS allergenName, a.allergen_code AS allergenCode,"
                        + " a.severity, a.source, a.source_id AS sourceId,"
                        + " DATE_FORMAT(a.record_time, '%Y-%m-%d %H:%i:%s') AS recordTime,"
                        + " s.staff_name AS recordByName"
                        + joins + where + " ORDER BY a.record_time DESC, a.id DESC LIMIT ?, ?",
                dataArgs.toArray());
        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setTotal(total == null ? 0L : total);
        result.setRecords(rows);
        return result;
    }

    /* ================= 执行记录查询 ================= */

    /**
     * 执行记录分页(实际发生过的执行: 全状态可查, 供追溯):
     * 时间按 COALESCE(exec_time, create_time) 落区间(未开始即取消的按创建时间), 区间闭界;
     * 行内含创建/开始/结束时间线与执行护士/核对护士姓名, 供前端展开详情。
     */
    public Page<Map<String, Object>> listExecLog(Long orgId, String execType, Long nurseId,
                                                 String startDate, String endDate, long page, long size) {
        Long oid = requireOrg(orgId);
        long p = safePage(page);
        long s = safeSize(size);
        StringBuilder where = new StringBuilder(
                " WHERE e.deleted = 0 AND e.tenant_id = ? AND e.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(oid);
        if (StringUtils.hasText(execType)) {
            where.append(" AND e.exec_type = ?");
            args.add(execType.trim());
        }
        if (nurseId != null) {
            where.append(" AND e.exec_nurse_id = ?");
            args.add(nurseId);
        }
        if (StringUtils.hasText(startDate)) {
            where.append(" AND COALESCE(e.exec_time, e.create_time) >= ?");
            args.add(startDate.trim() + " 00:00:00");
        }
        if (StringUtils.hasText(endDate)) {
            where.append(" AND COALESCE(e.exec_time, e.create_time) <= ?");
            args.add(endDate.trim() + " 23:59:59");
        }
        String joins = " FROM his_nurse_exec e"
                + " LEFT JOIN his_patient p ON p.id = e.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_order o ON o.id = e.order_id AND o.deleted = 0"
                + " LEFT JOIN his_order_item oi ON oi.id = e.order_item_id AND oi.deleted = 0"
                + " LEFT JOIN his_staff s ON s.id = e.exec_nurse_id AND s.deleted = 0"
                + " LEFT JOIN his_staff vs ON vs.id = e.verify_nurse_id AND s.deleted = 0";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + joins + where, Long.class, args.toArray());
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT e.id, e.exec_no AS execNo, e.exec_type AS execType, e.exec_status AS execStatus,"
                        + " e.patient_id AS patientId,"
                        + " p.name AS patientName, p.patient_no AS patientNo,"
                        + " CASE p.gender WHEN '1' THEN '男' WHEN '2' THEN '女' ELSE IFNULL(p.gender, '-') END AS genderName,"
                        + " p.age, oi.item_name AS itemName, oi.spec, oi.quantity, oi.unit, oi.exec_dept AS itemExecDept,"
                        + " o.order_no AS orderNo, o.dr_name AS doctorName, o.dept_name AS orderDeptName,"
                        + " e.exec_nurse_id AS execNurseId, s.staff_name AS nurseName,"
                        + " e.verify_nurse_id AS verifyNurseId, vs.staff_name AS verifyNurseName,"
                        + " DATE_FORMAT(e.create_time, '%Y-%m-%d %H:%i:%s') AS createTime,"
                        + " DATE_FORMAT(e.exec_time, '%Y-%m-%d %H:%i:%s') AS execTime,"
                        + " DATE_FORMAT(e.end_time, '%Y-%m-%d %H:%i:%s') AS endTime,"
                        + " TIMESTAMPDIFF(MINUTE, e.exec_time, e.end_time) AS durationMin,"
                        + " e.patient_response AS patientResponse, e.remark"
                        + joins + where + " ORDER BY COALESCE(e.exec_time, e.create_time) DESC, e.id DESC LIMIT ?, ?",
                dataArgs.toArray());
        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setTotal(total == null ? 0L : total);
        result.setRecords(rows);
        return result;
    }

    /* ================= 供姊妹服务复用(皮试/输液) ================= */

    /** 执行单必读(MP 自动租户过滤 + 逻辑删), 不存在抛 404 */
    public HisNurseExec requireExec(Long execId) {
        if (execId == null) {
            throw new BizException(400, "执行单ID不能为空");
        }
        HisNurseExec exec = execMapper.selectById(execId);
        if (exec == null) {
            throw new BizException(404, "执行单不存在");
        }
        return exec;
    }

    /** 写操作机构校验: 执行单归属机构须与当前登录机构一致(防跨机构误操作) */
    public void requireSameOrg(Long orgId) {
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        if (u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法操作执行单");
        }
        if (orgId != null && !orgId.equals(u.getOrgId())) {
            throw new BizException(403, "该执行单不属于当前登录机构, 无权操作");
        }
    }

    /** 当前登录护士(职工ID, 优先 staffId; 无关联职工时回退 null 由前端提示) */
    public static Long currentStaffId() {
        LoginUser u = UserContext.get();
        return u == null ? null : u.getStaffId();
    }

    /* ================= 内部工具 ================= */

    /** 查询机构作用域: 入参为空回退当前登录机构 */
    private static Long requireOrg(Long orgId) {
        if (orgId != null) {
            return orgId;
        }
        LoginUser u = UserContext.get();
        if (u != null && u.getOrgId() != null) {
            return u.getOrgId();
        }
        throw new BizException(403, "当前账号未归属任何机构, 无法查询护士站数据");
    }

    private static String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername();
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
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

    /* ================= 单号生成 ================= */

    /** 单号生成: HS + yyyyMMdd + 4位序号, synchronized 唯一, 跨日重置时 DB 回读当日最大序号兜底重启 */
    private synchronized String generateExecNo() {
        String today = LocalDate.now().format(NO_DAY);
        if (!today.equals(seqDate)) {
            seqDate = today;
            seqNo = maxSeqFromDb(today);
        }
        seqNo++;
        String no = "HS" + today + String.format("%04d", seqNo);
        while (noExists(no)) {
            seqNo++;
            no = "HS" + today + String.format("%04d", seqNo);
        }
        return no;
    }

    /** 查当日已有执行单号最大序号(重启后防撞号) */
    private int maxSeqFromDb(String today) {
        String like = "HS" + today;
        HisNurseExec one = execMapper.selectOne(Wrappers.<HisNurseExec>lambdaQuery()
                .likeRight(HisNurseExec::getExecNo, like)
                .orderByDesc(HisNurseExec::getExecNo)
                .last("LIMIT 1"));
        if (one == null || one.getExecNo() == null || one.getExecNo().length() < like.length() + 4) {
            return 0;
        }
        try {
            return Integer.parseInt(one.getExecNo().substring(like.length()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 单号是否已存在(租户内唯一键兜底) */
    private boolean noExists(String no) {
        return execMapper.selectCount(Wrappers.<HisNurseExec>lambdaQuery()
                .eq(HisNurseExec::getExecNo, no)) > 0;
    }
}
