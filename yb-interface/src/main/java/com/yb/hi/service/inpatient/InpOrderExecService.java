package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.inpatient.HisInpOrder;
import com.yb.hi.entity.inpatient.HisInpOrderExec;
import com.yb.hi.entity.inpatient.HisWard;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpOrderExecMapper;
import com.yb.hi.mapper.inpatient.HisInpOrderMapper;
import com.yb.hi.mapper.inpatient.HisWardMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 住院医嘱审核与执行服务(护士站核心): 待审核医嘱 / 批量审核(长期医嘱联动生成执行计划) / 驳回 /
 * 执行计划查询 / 批量执行 / 标记未执行。
 * 说明:
 * 1) 直接操作 his_inp_order / his_inp_order_exec(MP 租户插件自动过滤租户与逻辑删);
 *    与医生站 InpOrderService 不冲突 —— 医生站负责开立/停止/作废, 护士站负责审核/执行;
 * 2) JOIN 分页查询走 JdbcTemplate 手写 SQL(项目惯例: 原生 SQL 显式带 tenant_id 与 deleted=0);
 * 3) 状态机(乐观更新): order_status 1新开 -(护士审核)->2已审核, 1 -(驳回)->6已作废;
 *    exec_status 1待执行 -(执行)->2已执行, 1 -(未执行)->3未执行;
 * 4) 执行计划生成: 仅长期医嘱(order_type=1)按频次拆解当天执行时间点;
 *    prn 不生成计划, st/未知频次生成 1 条当前时间。
 */
@Slf4j
@Service
public class InpOrderExecService {

    /** 医嘱状态: 1新开 2已审核 3执行中 4已完成 5已停止 6已作废 */
    public static final int ORDER_NEW = 1;
    public static final int ORDER_AUDITED = 2;
    public static final int ORDER_VOID = 6;

    /** 执行状态: 1待执行 2已执行 3未执行 */
    public static final int EXEC_PENDING = 1;
    public static final int EXEC_DONE = 2;
    public static final int EXEC_SKIPPED = 3;

    /** 长期医嘱 */
    public static final int ORDER_TYPE_LONG = 1;

    /** 双人核对留痕时间格式(exec_remark 内记录核对时间) */
    private static final DateTimeFormatter VERIFY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 频次 -> 当天执行时间点(小写匹配)。
     * qd 08:00 / bid 08,16 / tid 08,12,18 / qid 06,12,18,22 / q6h 00,06,12,18;
     * prn 必要时(不生成) / st 立即与其他未知频次(当前时间 1 条)在 generateExecPlan 内单独处理。
     */
    private static final Map<String, List<LocalTime>> FREQ_PLAN;

    static {
        Map<String, List<LocalTime>> m = new LinkedHashMap<>();
        m.put("qd", Arrays.asList(LocalTime.of(8, 0)));
        m.put("bid", Arrays.asList(LocalTime.of(8, 0), LocalTime.of(16, 0)));
        m.put("tid", Arrays.asList(LocalTime.of(8, 0), LocalTime.of(12, 0), LocalTime.of(18, 0)));
        m.put("qid", Arrays.asList(LocalTime.of(6, 0), LocalTime.of(12, 0), LocalTime.of(18, 0), LocalTime.of(22, 0)));
        m.put("q6h", Arrays.asList(LocalTime.of(0, 0), LocalTime.of(6, 0), LocalTime.of(12, 0), LocalTime.of(18, 0)));
        FREQ_PLAN = m;
    }

    private final HisInpOrderMapper orderMapper;
    private final HisInpOrderExecMapper execMapper;
    private final HisWardMapper wardMapper;
    private final JdbcTemplate jdbcTemplate;
    private final SignatureService signatureService;

    public InpOrderExecService(HisInpOrderMapper orderMapper, HisInpOrderExecMapper execMapper,
                               HisWardMapper wardMapper, JdbcTemplate jdbcTemplate,
                               SignatureService signatureService) {
        this.orderMapper = orderMapper;
        this.execMapper = execMapper;
        this.wardMapper = wardMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.signatureService = signatureService;
    }

    /* ================= 待审核医嘱 ================= */

    /**
     * 待审核医嘱列表(本病区 order_status=1): JOIN 患者/床位/开嘱医生,
     * 开立时间正序(先开先审); 支持分页。
     */
    public IPage<Map<String, Object>> listPendingAudit(Long wardId, long page, long size) {
        requireWard(wardId);
        long p = safePage(page);
        long s = safeSize(size);
        String joins = " FROM his_inp_order o"
                + " JOIN his_inp_visit v ON o.inp_visit_id = v.id AND v.deleted = 0"
                + " JOIN his_patient p ON v.patient_id = p.id AND p.deleted = 0"
                + " LEFT JOIN his_bed b ON v.bed_id = b.id AND b.deleted = 0"
                + " LEFT JOIN his_staff s ON o.doctor_id = s.id AND s.deleted = 0"
                + " WHERE v.ward_id = ? AND o.order_status = " + ORDER_NEW
                + " AND o.deleted = 0 AND o.tenant_id = ?";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + joins, Long.class, wardId, tenantId());

        String dataSql = "SELECT o.id, o.inp_visit_id AS inpVisitId, o.order_type AS orderType,"
                + " o.order_category AS orderCategory, o.order_content AS orderContent,"
                + " o.spec, o.dosage, o.dosage_unit AS dosageUnit, o.usage_code AS usageCode,"
                + " o.freq_code AS freqCode, o.quantity, o.unit_price AS unitPrice, o.group_no AS groupNo,"
                + " o.start_time AS startTime, o.order_status AS orderStatus, o.doctor_id AS doctorId,"
                + " s.staff_name AS doctorName,"
                + " v.inp_no AS inpNo, v.patient_id AS patientId, p.name AS patientName,"
                + " p.gender, p.age, p.birth_date AS birthDate,"
                + " b.bed_no AS bedNo, b.room_no AS roomNo,"
                + " DATE_FORMAT(o.create_time, '%Y-%m-%d %H:%i:%s') AS createTime"
                + joins + " ORDER BY o.create_time ASC, o.id ASC LIMIT ?, ?";
        List<Object> args = new ArrayList<>(Arrays.asList(wardId, tenantId(), (p - 1) * s, s));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(dataSql, args.toArray());

        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setTotal(total == null ? 0L : total);
        result.setRecords(rows);
        return result;
    }

    /** 待办统计: 待审核医嘱数(order_status=1) + 待执行医嘱数(exec_status=1)。 */
    public Map<String, Object> todoStats(Long wardId) {
        requireWard(wardId);
        Long pendingAudit = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_inp_order o"
                        + " JOIN his_inp_visit v ON o.inp_visit_id = v.id AND v.deleted = 0"
                        + " WHERE v.ward_id = ? AND o.order_status = " + ORDER_NEW
                        + " AND o.deleted = 0 AND o.tenant_id = ?",
                Long.class, wardId, tenantId());
        Long pendingExec = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_inp_order_exec e"
                        + " JOIN his_inp_visit v ON e.inp_visit_id = v.id AND v.deleted = 0"
                        + " WHERE v.ward_id = ? AND e.exec_status = " + EXEC_PENDING
                        + " AND e.deleted = 0 AND e.tenant_id = ?",
                Long.class, wardId, tenantId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pendingAudit", pendingAudit == null ? 0L : pendingAudit);
        out.put("pendingExec", pendingExec == null ? 0L : pendingExec);
        return out;
    }

    /**
     * 病区待办计数(看板复用): 待审核医嘱数 + 待执行医嘱数。
     * wardId 可选过滤: 为空时统计当前租户全部病区(看板未选定病区场景), 非空时按病区收敛。
     */
    public Map<String, Integer> countPendingByWard(Long wardId) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        String wardClause = "";
        if (wardId != null) {
            wardClause = " AND v.ward_id = ?";
            args.add(wardId);
        }
        Long pendingAudit = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_inp_order o"
                        + " JOIN his_inp_visit v ON o.inp_visit_id = v.id AND v.deleted = 0"
                        + " WHERE o.order_status = " + ORDER_NEW + " AND o.deleted = 0 AND o.tenant_id = ?"
                        + wardClause,
                Long.class, args.toArray());
        Long pendingExec = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_inp_order_exec e"
                        + " JOIN his_inp_visit v ON e.inp_visit_id = v.id AND v.deleted = 0"
                        + " WHERE e.exec_status = " + EXEC_PENDING + " AND e.deleted = 0 AND e.tenant_id = ?"
                        + wardClause,
                Long.class, args.toArray());
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("pendingAudit", pendingAudit == null ? 0 : pendingAudit.intValue());
        out.put("pendingExec", pendingExec == null ? 0 : pendingExec.intValue());
        return out;
    }

    /* ================= 审核 / 驳回 ================= */

    /**
     * 批量审核医嘱: 逐条乐观更新 order_status 1->2 并记录审核护士与时间;
     * 长期医嘱(order_type=1)联动生成当天执行计划。并发状态变更(0 行)跳过并汇总返回。
     */
    @Transactional
    public Map<String, Object> batchAudit(List<Long> orderIds, Long nurseId) {
        if (CollectionUtils.isEmpty(orderIds)) {
            throw new BizException(400, "请选择要审核的医嘱");
        }
        if (nurseId == null) {
            throw new BizException(403, "当前账号未关联职工档案, 无法执行审核签名");
        }
        /* 药审前置校验(T35): 药品类医嘱(drug_id 非空)须先通过药师审核
         * (pharm_audit_status=2通过/0无需药审); 待审(1)或被驳回(3)时整体拒绝护士审核 */
        requirePharmAudited(orderIds);
        int audited = 0;
        int planCount = 0;
        List<Long> skipped = new ArrayList<>();
        for (Long orderId : orderIds) {
            HisInpOrder order = orderMapper.selectById(orderId);
            if (order == null || order.getOrderStatus() == null || order.getOrderStatus() != ORDER_NEW) {
                skipped.add(orderId);
                continue;
            }
            int n = orderMapper.update(null, Wrappers.<HisInpOrder>lambdaUpdate()
                    .eq(HisInpOrder::getId, orderId)
                    .eq(HisInpOrder::getOrderStatus, ORDER_NEW)
                    .set(HisInpOrder::getOrderStatus, ORDER_AUDITED)
                    .set(HisInpOrder::getAuditNurseId, nurseId)
                    .set(HisInpOrder::getAuditTime, LocalDateTime.now())
                    .set(HisInpOrder::getUpdateBy, currentUserName()));
            if (n == 0) {
                skipped.add(orderId);
                continue;
            }
            audited++;
            if (order.getOrderType() != null && order.getOrderType() == ORDER_TYPE_LONG) {
                planCount += generateExecPlan(order);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("audited", audited);
        out.put("execPlans", planCount);
        out.put("skipped", skipped);
        return out;
    }

    /**
     * 驳回医嘱: 乐观更新 order_status 1->6(作废)。
     * 基座表未预留驳回原因列, reason 记入服务日志并随响应返回(留痕可查)。
     */
    public Map<String, Object> rejectOrder(Long orderId, String reason, Long nurseId) {
        if (orderId == null) {
            throw new BizException(400, "医嘱ID不能为空");
        }
        HisInpOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(404, "医嘱不存在");
        }
        requireSameOrg(order.getOrgId());
        if (order.getOrderStatus() == null || order.getOrderStatus() != ORDER_NEW) {
            throw new BizException(409, "仅新开(待审核)医嘱可驳回, 当前状态已变更");
        }
        int n = orderMapper.update(null, Wrappers.<HisInpOrder>lambdaUpdate()
                .eq(HisInpOrder::getId, orderId)
                .eq(HisInpOrder::getOrderStatus, ORDER_NEW)
                .set(HisInpOrder::getOrderStatus, ORDER_VOID)
                .set(HisInpOrder::getUpdateBy, currentUserName()));
        if (n == 0) {
            throw new BizException(409, "医嘱状态已变更(可能已被其他护士处理), 请刷新后重试");
        }
        log.warn("住院医嘱驳回: orderId={}, nurseId={}, reason={}", orderId, nurseId, reason);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("orderStatus", ORDER_VOID);
        out.put("reason", reason);
        return out;
    }

    /* ================= 执行计划 ================= */

    /**
     * 为单条长期医嘱生成当天执行计划(幂等由调用方保证审核一次性触发):
     * 按频次拆时间点, 每个时间点一条 his_inp_order_exec(exec_status=1待执行)。
     *
     * @return 生成的计划条数
     */
    public int generateExecPlan(HisInpOrder order) {
        if (order == null || order.getId() == null) {
            return 0;
        }
        String freq = order.getFreqCode() == null ? "" : order.getFreqCode().trim().toLowerCase();
        if ("prn".equals(freq)) {
            return 0;
        }
        List<LocalDateTime> points = new ArrayList<>();
        List<LocalTime> times = FREQ_PLAN.get(freq);
        if (times != null) {
            LocalDate today = LocalDate.now();
            for (LocalTime t : times) {
                points.add(LocalDateTime.of(today, t));
            }
        } else {
            // st(立即)与其他/无频次: 生成 1 条当前时间
            points.add(LocalDateTime.now());
        }
        for (LocalDateTime planTime : points) {
            HisInpOrderExec exec = new HisInpOrderExec();
            exec.setOrgId(order.getOrgId());
            exec.setOrderId(order.getId());
            exec.setInpVisitId(order.getInpVisitId());
            exec.setPlanTime(planTime);
            exec.setExecStatus(EXEC_PENDING);
            execMapper.insert(exec);
        }
        return points.size();
    }

    /**
     * 执行计划列表(病区 + 日期): JOIN 医嘱/患者/床位/开嘱医生, plan_time 正序;
     * 日期缺省当天; 全部状态一并返回(前端按状态分组渲染);
     * T36 额外携带 drugId/highAlertFlag/doubleCheckFlag/skinTestFlag/skinTestResult 供执行面板渲染皮试列与高危标识。
     */
    public IPage<Map<String, Object>> listExecPlan(Long wardId, LocalDate date, long page, long size) {
        requireWard(wardId);
        LocalDate day = date == null ? LocalDate.now() : date;
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.plusDays(1).atStartOfDay();
        long p = safePage(page);
        long s = safeSize(size);

        String from = " FROM his_inp_order_exec e"
                + " JOIN his_inp_order o ON e.order_id = o.id AND o.deleted = 0"
                + " JOIN his_inp_visit v ON e.inp_visit_id = v.id AND v.deleted = 0"
                + " JOIN his_patient p ON v.patient_id = p.id AND p.deleted = 0"
                + " LEFT JOIN his_bed b ON v.bed_id = b.id AND b.deleted = 0"
                + " LEFT JOIN his_staff s ON o.doctor_id = s.id AND s.deleted = 0"
                + " LEFT JOIN his_staff ns ON e.exec_nurse_id = ns.id AND ns.deleted = 0";
        /* T36 展示列 JOIN(仅数据查询; LEFT JOIN 不改变行数, 计数查询无需携带):
         * 药品目录皮试标志 + 同患者同药品最近一次皮试结果(经 his_nurse_exec 关联患者) */
        String t36Join = " LEFT JOIN his_drug_catalog d ON o.drug_id = d.id AND d.deleted = 0 AND d.tenant_id = e.tenant_id"
                + " LEFT JOIN his_skin_test st ON st.id = ("
                + "   SELECT st2.id FROM his_skin_test st2"
                + "   JOIN his_nurse_exec ne ON st2.exec_id = ne.id AND ne.deleted = 0"
                + "   WHERE st2.drug_id = o.drug_id AND ne.patient_id = v.patient_id"
                + "     AND st2.deleted = 0 AND st2.tenant_id = e.tenant_id"
                + "   ORDER BY st2.create_time DESC, st2.id DESC LIMIT 1)";
        String where = " WHERE v.ward_id = ? AND e.deleted = 0 AND e.tenant_id = ?"
                + " AND e.plan_time >= ? AND e.plan_time < ?";
        Object[] countArgs = {wardId, tenantId(), start, end};
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + where, Long.class, countArgs);

        String dataSql = "SELECT e.id, e.order_id AS orderId, e.inp_visit_id AS inpVisitId,"
                + " DATE_FORMAT(e.plan_time, '%Y-%m-%d %H:%i') AS planTime,"
                + " DATE_FORMAT(e.exec_time, '%Y-%m-%d %H:%i:%s') AS execTime,"
                + " e.exec_nurse_id AS execNurseId, ns.staff_name AS execNurseName,"
                + " e.exec_status AS execStatus, e.exec_remark AS execRemark,"
                + " o.order_type AS orderType, o.order_category AS orderCategory,"
                + " o.order_content AS orderContent, o.spec, o.dosage, o.dosage_unit AS dosageUnit,"
                + " o.usage_code AS usageCode, o.freq_code AS freqCode,"
                + " o.drug_id AS drugId, o.high_alert_flag AS highAlertFlag, o.double_check_flag AS doubleCheckFlag,"
                + " d.skin_test_flag AS skinTestFlag, st.result AS skinTestResult,"
                + " v.inp_no AS inpNo, p.name AS patientName, p.gender, p.age,"
                + " b.bed_no AS bedNo, b.room_no AS roomNo, s.staff_name AS doctorName"
                + from + t36Join + where
                + " ORDER BY e.plan_time ASC, e.id ASC LIMIT ?, ?";
        Object[] dataArgs = {wardId, tenantId(), start, end, (p - 1) * s, s};
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(dataSql, dataArgs);

        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setTotal(total == null ? 0L : total);
        result.setRecords(rows);
        return result;
    }

    /* ================= 执行 / 未执行 ================= */

    /**
     * 批量执行: 逐条乐观更新 exec_status 1->2 并记录执行护士与时间; 并发状态变更(0 行)跳过并汇总返回。
     * <p>T36 双层校验(执行前预检, 任一失败整体不执行, 事务无变更):
     * 1) 皮试结果校验 —— 药品目录 skin_test_flag=1 的药品医嘱, 须存在该患者该药品的皮试记录且
     *    结果为阴性(1)方可执行; 无记录/观察中(0)/阳性(2)/可疑(3)均拦截
     *    (皮试记录属门诊护士站链路: his_skin_test 经 exec_id 关联 his_nurse_exec.patient_id);
     * 2) 双人核对 —— high_alert_flag/double_check_flag=1 的医嘱须由另一名护士核对
     *    (凭据经 query 传入 verifyNurseId/verifyNursePassword, 执行接口 body 为裸数组);
     *    未携带凭据时返回 {needDoubleCheck:true, execIds, orderIds} 由前端弹核对框重发, 本事务不做任何变更;
     *    基座表 his_inp_order_exec 未预留核对护士列(DictSchemaMigration 禁改),
     *    核对护士以 exec_remark 文本留痕("双人核对:护士{id} {time}")。
     */
    @Transactional
    public Map<String, Object> batchExecute(List<Long> execIds, Long nurseId) {
        if (CollectionUtils.isEmpty(execIds)) {
            throw new BizException(400, "请选择要执行的计划");
        }
        if (nurseId == null) {
            throw new BizException(403, "当前账号未关联职工档案, 无法执行签名");
        }
        /* ---- 第一层: 皮试结果校验 + 收集需双人核对的执行计划 ---- */
        List<Long> needCheckExecIds = new ArrayList<>();
        List<Long> needCheckOrderIds = new ArrayList<>();
        for (Long execId : execIds) {
            HisInpOrderExec exec = execMapper.selectById(execId);
            if (exec == null || exec.getExecStatus() == null || exec.getExecStatus() != EXEC_PENDING) {
                continue;
            }
            HisInpOrder order = exec.getOrderId() == null ? null : orderMapper.selectById(exec.getOrderId());
            if (order == null) {
                continue;
            }
            checkSkinTestBeforeExec(order);
            if (isFlag(order.getDoubleCheckFlag()) || isFlag(order.getHighAlertFlag())) {
                needCheckExecIds.add(execId);
                needCheckOrderIds.add(order.getId());
            }
        }
        /* ---- 第二层: 双人核对(凭据经 query 传入) ---- */
        Long verifyNurseId = parseLongOrNull(requestParam("verifyNurseId"));
        String verifyNursePassword = requestParam("verifyNursePassword");
        boolean doubleCheck = !needCheckExecIds.isEmpty();
        if (doubleCheck && verifyNurseId == null) {
            Map<String, Object> ask = new LinkedHashMap<>();
            ask.put("needDoubleCheck", true);
            ask.put("execIds", needCheckExecIds);
            ask.put("orderIds", needCheckOrderIds);
            log.info("执行需双人核对(等待核对凭据): execIds={}, 需核对医嘱={}", needCheckExecIds, needCheckOrderIds.size());
            return ask;
        }
        if (doubleCheck) {
            verifyDoubleCheck(verifyNurseId, verifyNursePassword, nurseId);
        }
        /* ---- 原执行链路(乐观更新) ---- */
        int executed = 0;
        List<Long> skipped = new ArrayList<>();
        for (Long execId : execIds) {
            HisInpOrderExec exec = execMapper.selectById(execId);
            if (exec == null || exec.getExecStatus() == null || exec.getExecStatus() != EXEC_PENDING) {
                skipped.add(execId);
                continue;
            }
            boolean checked = doubleCheck && needCheckExecIds.contains(execId);
            String verifyRemark = checked
                    ? "双人核对:护士" + verifyNurseId + " " + VERIFY_TIME.format(LocalDateTime.now()) : null;
            int n = execMapper.update(null, Wrappers.<HisInpOrderExec>lambdaUpdate()
                    .eq(HisInpOrderExec::getId, execId)
                    .eq(HisInpOrderExec::getExecStatus, EXEC_PENDING)
                    .set(HisInpOrderExec::getExecStatus, EXEC_DONE)
                    .set(HisInpOrderExec::getExecNurseId, nurseId)
                    .set(HisInpOrderExec::getExecTime, LocalDateTime.now())
                    .set(checked, HisInpOrderExec::getExecRemark, verifyRemark)
                    .set(HisInpOrderExec::getUpdateBy, currentUserName()));
            if (n == 0) {
                skipped.add(execId);
                continue;
            }
            executed++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executed", executed);
        out.put("skipped", skipped);
        if (doubleCheck) {
            out.put("doubleCheck", true);
            out.put("verifyNurseId", verifyNurseId);
        }
        return out;
    }

    /**
     * 标记未执行: 乐观更新 exec_status 1->3, 备注必填(记录未执行原因, 写 exec_remark)。
     */
    public Map<String, Object> cancelExec(Long execId, String remark, Long nurseId) {
        if (execId == null) {
            throw new BizException(400, "执行计划ID不能为空");
        }
        String cause = remark == null ? null : remark.trim();
        if (cause == null || cause.isEmpty()) {
            throw new BizException(400, "未执行原因不能为空");
        }
        HisInpOrderExec exec = execMapper.selectById(execId);
        if (exec == null) {
            throw new BizException(404, "执行计划不存在");
        }
        requireSameOrg(exec.getOrgId());
        if (exec.getExecStatus() == null || exec.getExecStatus() != EXEC_PENDING) {
            throw new BizException(409, "仅待执行计划可标记未执行, 当前状态已变更");
        }
        int n = execMapper.update(null, Wrappers.<HisInpOrderExec>lambdaUpdate()
                .eq(HisInpOrderExec::getId, execId)
                .eq(HisInpOrderExec::getExecStatus, EXEC_PENDING)
                .set(HisInpOrderExec::getExecStatus, EXEC_SKIPPED)
                .set(HisInpOrderExec::getExecRemark, "未执行: " + cause)
                .set(HisInpOrderExec::getUpdateBy, currentUserName()));
        if (n == 0) {
            throw new BizException(409, "执行计划状态已变更(可能已被其他护士处理), 请刷新后重试");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("execId", execId);
        out.put("execStatus", EXEC_SKIPPED);
        out.put("remark", "未执行: " + cause);
        out.put("operator", nurseId);
        return out;
    }

    /* ================= 内部工具 ================= */

    /**
     * 皮试结果校验(T36): 药品目录 skin_test_flag=1 的药品医嘱, 须存在该患者该药品的皮试记录且
     * 结果为阴性(1)方可执行; 无记录/观察中(0)/阳性(2)/可疑(3)均拦截。
     * 皮试记录属门诊护士站执行链路(his_skin_test 经 exec_id 关联 his_nurse_exec.patient_id)。
     */
    private void checkSkinTestBeforeExec(HisInpOrder order) {
        if (order == null || order.getDrugId() == null) {
            return;
        }
        List<Map<String, Object>> drugs = jdbcTemplate.queryForList(
                "SELECT generic_name, skin_test_flag FROM his_drug_catalog"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0 LIMIT 1",
                order.getDrugId(), tenantId());
        if (drugs.isEmpty()) {
            return;
        }
        Object flag = drugs.get(0).get("skin_test_flag");
        if (flag == null || ((Number) flag).intValue() != 1) {
            return;
        }
        String drugName = String.valueOf(drugs.get(0).get("generic_name"));
        List<Long> patientIds = jdbcTemplate.queryForList(
                "SELECT patient_id FROM his_inp_visit WHERE id = ? AND tenant_id = ? AND deleted = 0 LIMIT 1",
                Long.class, order.getInpVisitId(), tenantId());
        if (patientIds.isEmpty() || patientIds.get(0) == null) {
            throw new BizException("药品[" + drugName + "]需做皮试，尚未开具皮试医嘱");
        }
        List<Integer> results = jdbcTemplate.queryForList(
                "SELECT st.result FROM his_skin_test st"
                        + " JOIN his_nurse_exec ne ON st.exec_id = ne.id AND ne.deleted = 0"
                        + " WHERE st.drug_id = ? AND ne.patient_id = ? AND st.deleted = 0 AND st.tenant_id = ?"
                        + " ORDER BY st.create_time DESC, st.id DESC LIMIT 1",
                Integer.class, order.getDrugId(), patientIds.get(0), tenantId());
        if (results.isEmpty()) {
            throw new BizException("药品[" + drugName + "]需做皮试，尚未开具皮试医嘱");
        }
        int result = results.get(0) == null ? 0 : results.get(0);
        if (result == 0) {
            throw new BizException("药品[" + drugName + "]皮试观察中，请等待结果");
        }
        if (result == 2) {
            throw new BizException("药品[" + drugName + "]皮试阳性，禁止执行");
        }
        if (result == 3) {
            throw new BizException("药品[" + drugName + "]皮试结果可疑，请医生确认后再执行");
        }
        /* result == 1 阴性 → 放行 */
    }

    /**
     * 双人核对校验(T36): 核对护士不得为执行护士本人(同 userId/staffId 均视为本人, 宁拒勿放),
     * 且须通过其账号密码验证(T34 SignatureService, BCrypt 与登录同口径)。
     */
    private void verifyDoubleCheck(Long verifyNurseId, String password, Long execNurseStaffId) {
        LoginUser cur = UserContext.get();
        Long curUserId = cur == null ? null : cur.getUserId();
        if (verifyNurseId.equals(curUserId) || verifyNurseId.equals(execNurseStaffId)) {
            throw new BizException(403, "双人核对不能由执行护士本人完成，请另一名护士核对");
        }
        if (!signatureService.verifyPasswordForUser(verifyNurseId, password)) {
            throw new BizException(403, "核对护士密码验证失败，请核对工号与密码后重试");
        }
        log.info("双人核对通过: 执行护士={}, 核对护士={}", execNurseStaffId, verifyNurseId);
    }

    /** flag 列是否为 1(null 安全) */
    private static boolean isFlag(Integer flag) {
        return flag != null && flag == 1;
    }

    /** 从当前 HTTP 请求读取 query 参数(无请求上下文返回 null); 双人核对凭据经 query 传入(执行接口 body 为裸数组) */
    private static String requestParam(String name) {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return null;
            }
            String v = attrs.getRequest().getParameter(name);
            return (v == null || v.trim().isEmpty()) ? null : v.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** Long 解析(null 安全, 非数字返回 null) */
    private static Long parseLongOrNull(String v) {
        if (v == null) {
            return null;
        }
        try {
            return Long.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 护士审核前的药审拦截(T35): 查询本批医嘱中未通过药师审核的药品医嘱,
     * 存在则整体拒绝并附药品清单(待审/驳回均拦截, 待医生等待药师处理或重新开立)。
     * id 列表先过 Long 过滤再拼接(Long.toString 无注入面), 与项目原生 SQL 惯例一致。
     */
    private void requirePharmAudited(List<Long> orderIds) {
        String ids = orderIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        if (ids.isEmpty()) {
            return;
        }
        List<Map<String, Object>> blocked = jdbcTemplate.queryForList(
                "SELECT o.id, IFNULL(d.generic_name, o.order_content) AS drugName"
                        + " FROM his_inp_order o"
                        + " LEFT JOIN his_drug_catalog d ON o.drug_id = d.id AND d.deleted = 0"
                        + " WHERE o.id IN (" + ids + ") AND o.drug_id IS NOT NULL"
                        + " AND IFNULL(o.pharm_audit_status, 0) NOT IN (0, 2)"
                        + " AND o.deleted = 0 AND o.tenant_id = ?"
                        + " ORDER BY o.id",
                tenantId());
        if (!blocked.isEmpty()) {
            String names = blocked.stream()
                    .map(r -> String.valueOf(r.get("drugName")))
                    .collect(java.util.stream.Collectors.joining("、"));
            throw new BizException("以下药品医嘱尚未通过药师审核，请等待药师审核后再操作: " + names);
        }
    }

    /** 病区必读校验: 存在 + 归属当前登录机构(平台超管放行)。 */
    private HisWard requireWard(Long wardId) {
        if (wardId == null) {
            throw new BizException(400, "病区ID不能为空");
        }
        HisWard ward = wardMapper.selectById(wardId);
        if (ward == null) {
            throw new BizException(404, "病区不存在");
        }
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        if (!u.hasRole(Roles.SUPER_ADMIN) && ward.getOrgId() != null && u.getOrgId() != null
                && !ward.getOrgId().equals(u.getOrgId())) {
            throw new BizException(403, "该病区不属于当前登录机构, 无权操作");
        }
        return ward;
    }

    /** 写操作机构校验: 业务行归属机构须与当前登录机构一致(防跨机构误操作)。 */
    private void requireSameOrg(Long orgId) {
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        if (u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法操作护士站数据");
        }
        if (orgId != null && !orgId.equals(u.getOrgId()) && !u.hasRole(Roles.SUPER_ADMIN)) {
            throw new BizException(403, "该数据不属于当前登录机构, 无权操作");
        }
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
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

    private static String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return lu.getRealName() != null && !lu.getRealName().isEmpty() ? lu.getRealName() : lu.getUsername();
    }
}
