package com.yb.hi.service.treatment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.doctor.HisOrder;
import com.yb.hi.entity.doctor.HisOrderItem;
import com.yb.hi.entity.treatment.HisTreatmentExec;
import com.yb.hi.entity.treatment.HisTreatmentPlan;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.doctor.HisOrderItemMapper;
import com.yb.hi.mapper.doctor.HisOrderMapper;
import com.yb.hi.mapper.treatment.HisTreatmentExecMapper;
import com.yb.hi.mapper.treatment.HisTreatmentPlanMapper;
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
 * 治疗计划服务(疗程管理): 医嘱转计划 / 计划分页 / 计划详情 / 调整总次数 / 终止计划。
 * 说明:
 * 1) his_treatment_plan 为机构级业务表(MP 租户插件自动注入 tenant_id), 单表读写走 Mapper,
 *    跨表查询走 JdbcTemplate 并显式带 tenant_id = TenantContext.get();
 * 2) 状态机: plan.status 0执行中 -> 1已完成(疗程做满自动) / 2已终止(人工);
 *    流转均用乐观锁 UPDATE ... WHERE status = 预期前值;
 * 3) 从医嘱单生成计划: 每个治疗类医嘱明细生成一条计划(单次治疗 total_sessions=1)并预生成第1条
 *    执行单; 幂等(同 order_id + item_code 已存在则跳过, 重复调用不重复建);
 * 4) 调整总次数: 增大时若无在途执行单则补建; 调整为 <=已完成次数 时计划自动完成并取消多余在途单;
 *    调整留痕追加到 remark(【调整 日期】旧→新, 原因);
 * 5) 终止计划: 乐观锁 0->2 记录 terminate_reason, 同时取消在途执行单; 计划无执行中后回写医嘱单执行状态;
 * 6) 单号: ZL + yyyyMMdd + 4位序号, synchronized 生成 + DB 回读当日最大序号兜底重启防撞号。
 */
@Slf4j
@Service
public class TreatmentPlanService {

    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 计划状态: 0执行中 1已完成 2已终止 */
    public static final int PLAN_RUNNING = 0;
    public static final int PLAN_FINISHED = 1;
    public static final int PLAN_TERMINATED = 2;

    /** 中医传统治疗关键词(项目名归类) */
    private static final String[] TCM_KEYWORDS = {"针灸", "推拿", "拔罐", "艾灸", "刮痧", "耳穴", "穴位", "中药", "熏"};
    /** 康复治疗关键词(项目名归类) */
    private static final String[] REHAB_KEYWORDS = {"康复", "训练", "作业", "运动", "评定"};

    private final HisTreatmentPlanMapper planMapper;
    private final HisTreatmentExecMapper execMapper;
    private final HisOrderMapper orderMapper;
    private final HisOrderItemMapper orderItemMapper;
    private final TreatmentExecService execService;
    private final JdbcTemplate jdbcTemplate;

    /** 单号内存序号(synchronized 唯一; 跨日重置时从DB回读当日最大序号) */
    private String seqDate;
    private int seqNo = 0;

    public TreatmentPlanService(HisTreatmentPlanMapper planMapper,
                                HisTreatmentExecMapper execMapper,
                                HisOrderMapper orderMapper,
                                HisOrderItemMapper orderItemMapper,
                                TreatmentExecService execService,
                                JdbcTemplate jdbcTemplate) {
        this.planMapper = planMapper;
        this.execMapper = execMapper;
        this.orderMapper = orderMapper;
        this.orderItemMapper = orderItemMapper;
        this.execService = execService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 医嘱转治疗计划 ================= */

    /**
     * 从治疗类医嘱单生成治疗计划(每个明细一条, 单次治疗 total_sessions=1)并预生成第1条执行单。
     * 幂等: 同单据+项目编码已有计划则跳过; 医嘱已退单(status=3)拒绝。返回 created/skipped 汇总。
     */
    @Transactional
    public Map<String, Object> createPlanFromOrder(Long orderId) {
        if (orderId == null) {
            throw new BizException(400, "医嘱单ID不能为空");
        }
        HisOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(404, "医嘱单不存在");
        }
        if (!"治疗".equals(order.getOrderType())) {
            throw new BizException(400, "仅治疗类医嘱单可生成治疗计划(当前类型: " + order.getOrderType() + ")");
        }
        if (order.getStatus() != null && order.getStatus() == 3) {
            throw new BizException(400, "医嘱单已退单, 无法生成治疗计划");
        }
        Long orgId = orderOrgId(order);
        requireSameOrg(orgId);
        Long effOrgId = orgId != null ? orgId : loginOrgId();

        List<HisOrderItem> items = orderItemMapper.selectList(Wrappers.<HisOrderItem>lambdaQuery()
                .eq(HisOrderItem::getOrderId, orderId)
                .orderByAsc(HisOrderItem::getId));
        if (items.isEmpty()) {
            throw new BizException(400, "医嘱单无明细, 无法生成治疗计划");
        }

        List<Long> planIds = new ArrayList<>();
        int skipped = 0;
        for (HisOrderItem item : items) {
            if (planExists(orderId, item)) {
                skipped++;
                continue;
            }
            // 疗程次数取医生开单的治疗次数(his_order_item.quantity, 开单面板按"治疗次数"录入); 无数量或非法时回退单次
            int sessions = item.getQuantity() == null ? 0 : item.getQuantity().intValue();
            // 双轨去重: 护士站四类(皮试/输液/注射/换药)单次处置归护士执行台账, 不进治疗计划;
            // 仅多次疗程类(如换药×3, 皮试除外)由治疗站按疗程管理, 避免同一明细双执行单(双执行/双收费风险)
            String nurseType = com.yb.hi.service.nurse.NurseExecService.resolveExecType(item.getItemName(), item.getExecDept());
            if (nurseType != null && (sessions <= 1 || com.yb.hi.service.nurse.NurseExecService.TYPE_SKIN_TEST.equals(nurseType))) {
                skipped++;
                continue;
            }
            HisTreatmentPlan plan = new HisTreatmentPlan();
            plan.setOrgId(effOrgId);
            plan.setPlanNo(generatePlanNo());
            plan.setVisitId(order.getVisitId());
            plan.setPatientId(order.getPatientId());
            plan.setDoctorId(order.getDrId());
            plan.setOrderId(orderId);
            plan.setItemCode(item.getItemCode());
            plan.setItemName(item.getItemName());
            plan.setCategory(classifyCategory(item.getItemName()));
            plan.setTotalSessions(sessions > 0 ? sessions : 1);
            plan.setCompletedSessions(0);
            plan.setFrequency(sessions > 1 ? "按疗程" : "单次");
            plan.setStartDate(LocalDate.now());
            plan.setStatus(PLAN_RUNNING);
            planMapper.insert(plan);
            // 预生成第1条执行单(在途一条策略: 待收费后在工作台可见)
            execService.createExec(plan, item.getId());
            planIds.add(plan.getId());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", orderId);
        out.put("orderNo", order.getOrderNo());
        out.put("created", planIds.size());
        out.put("skipped", skipped);
        out.put("planIds", planIds);
        log.info("医嘱单 {} 生成治疗计划: created={}, skipped={}, operator={}", order.getOrderNo(),
                planIds.size(), skipped, currentUserName());
        return out;
    }

    /* ================= 计划列表 / 详情 ================= */

    /** 治疗计划分页(机构/患者/关键字/状态), JOIN 患者与医师信息; keyword 匹配患者姓名或病历号 */
    public Page<Map<String, Object>> listPlans(Long orgId, Long patientId, String keyword, Integer status,
                                               long page, long size) {
        Long oid = requireOrg(orgId);
        long p = safePage(page);
        long s = safeSize(size);
        StringBuilder where = new StringBuilder(
                " WHERE p.deleted = 0 AND p.tenant_id = ? AND p.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(oid);
        if (patientId != null) {
            where.append(" AND p.patient_id = ?");
            args.add(patientId);
        }
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (pat.name LIKE ? OR pat.patient_no LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (status != null) {
            where.append(" AND p.status = ?");
            args.add(status);
        }
        String joins = " FROM his_treatment_plan p"
                + " LEFT JOIN his_patient pat ON pat.id = p.patient_id AND pat.deleted = 0"
                + " LEFT JOIN his_staff ds ON ds.id = p.doctor_id AND ds.deleted = 0"
                + " LEFT JOIN his_order o ON o.id = p.order_id AND o.deleted = 0";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + joins + where, Long.class, args.toArray());
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        String cols = "SELECT p.id, p.plan_no, p.visit_id, p.patient_id, p.doctor_id, p.order_id,"
                + " p.item_code, p.item_name, p.category, p.total_sessions, p.completed_sessions, p.frequency,"
                + " DATE_FORMAT(p.start_date, '%Y-%m-%d') AS start_date,"
                + " DATE_FORMAT(p.expire_date, '%Y-%m-%d') AS expire_date,"
                + " p.status, p.terminate_reason, p.remark,"
                + " DATE_FORMAT(p.create_time, '%Y-%m-%d %H:%i:%s') AS create_time,"
                + " o.order_no, o.diag_name, o.paid_flag,"
                + " pat.name AS patient_name, pat.gender, pat.age, pat.patient_no,"
                + " ds.staff_name AS doctor_name";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                cols + joins + where + " ORDER BY p.id DESC LIMIT ?, ?", dataArgs.toArray());
        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setTotal(total == null ? 0L : total);
        result.setRecords(rows);
        return result;
    }

    /** 计划详情: 计划(含患者/医嘱/医师) + 关联执行单明细(按序次升序) */
    public Map<String, Object> getPlanDetail(Long planId) {
        HisTreatmentPlan plan = requirePlan(planId);
        String planSql = "SELECT p.id, p.plan_no, p.visit_id, p.patient_id, p.doctor_id, p.order_id,"
                + " p.item_code, p.item_name, p.category, p.total_sessions, p.completed_sessions, p.frequency,"
                + " DATE_FORMAT(p.start_date, '%Y-%m-%d') AS start_date,"
                + " DATE_FORMAT(p.expire_date, '%Y-%m-%d') AS expire_date,"
                + " p.status, p.terminate_reason, p.remark, p.org_id,"
                + " DATE_FORMAT(p.create_time, '%Y-%m-%d %H:%i:%s') AS create_time,"
                + " o.order_no, o.order_type, o.diag_name, o.paid_flag, o.exec_status AS order_exec_status,"
                + " o.dept_name AS order_dept_name,"
                + " pat.name AS patient_name, pat.gender, pat.age, pat.patient_no, pat.phone,"
                + " ds.staff_name AS doctor_name"
                + " FROM his_treatment_plan p"
                + " LEFT JOIN his_order o ON o.id = p.order_id AND o.deleted = 0"
                + " LEFT JOIN his_patient pat ON pat.id = p.patient_id AND pat.deleted = 0"
                + " LEFT JOIN his_staff ds ON ds.id = p.doctor_id AND ds.deleted = 0"
                + " WHERE p.id = ? AND p.tenant_id = ? AND p.deleted = 0";
        List<Map<String, Object>> planRows = jdbcTemplate.queryForList(planSql, planId, tenantId());
        Map<String, Object> planRow = planRows.isEmpty() ? new LinkedHashMap<>() : planRows.get(0);

        String execSql = "SELECT e.id, e.exec_no, e.plan_id, e.session_index, e.exec_status,"
                + " DATE_FORMAT(e.exec_date, '%Y-%m-%d') AS exec_date,"
                + " DATE_FORMAT(e.checkin_time, '%Y-%m-%d %H:%i:%s') AS checkin_time,"
                + " DATE_FORMAT(e.create_time, '%Y-%m-%d %H:%i:%s') AS create_time,"
                + " e.duration_min, e.parameters, e.patient_response, e.cancel_reason,"
                + " e.exec_therapist_id, ts.staff_name AS therapist_name,"
                + " e.equipment_code, eq.equip_name"
                + " FROM his_treatment_exec e"
                + " LEFT JOIN his_staff ts ON ts.id = e.exec_therapist_id AND ts.deleted = 0"
                + " LEFT JOIN his_treatment_equipment eq ON eq.equip_code = e.equipment_code"
                + " AND eq.org_id = e.org_id AND eq.deleted = 0"
                + " WHERE e.plan_id = ? AND e.tenant_id = ? AND e.deleted = 0"
                + " ORDER BY e.session_index ASC, e.id ASC";
        List<Map<String, Object>> execs = jdbcTemplate.queryForList(execSql, planId, tenantId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plan", planRow);
        out.put("execs", execs);
        out.put("planId", plan.getId());
        return out;
    }

    /* ================= 调整 / 终止 ================= */

    /**
     * 调整计划总次数: 乐观锁(status=0)更新; 调整为 <=已完成次数 时计划自动完成并取消多余在途单;
     * 未满且无在途执行单时补建; 调整留痕追加到 remark。
     */
    @Transactional
    public Map<String, Object> adjustPlan(Long planId, Integer newTotal, String reason) {
        HisTreatmentPlan plan = requirePlan(planId);
        requireSameOrg(plan.getOrgId());
        if (plan.getStatus() == null || plan.getStatus() != PLAN_RUNNING) {
            throw new BizException(409, "仅执行中的治疗计划可调整(当前状态不可调整)");
        }
        if (newTotal == null || newTotal < 1) {
            throw new BizException(400, "新总次数必须大于0");
        }
        int completed = nvl(plan.getCompletedSessions());
        if (newTotal < completed) {
            throw new BizException(400, "新总次数不能小于已完成次数(" + completed + ")");
        }
        int oldTotal = nvl(plan.getTotalSessions());
        String note = "【调整 " + LocalDate.now() + "】总次数 " + oldTotal + "→" + newTotal
                + (StringUtils.hasText(reason) ? ", 原因: " + reason.trim() : "");
        String oldRemark = StringUtils.hasText(plan.getRemark()) ? plan.getRemark() : "";
        String remark = oldRemark.isEmpty() ? note : oldRemark + " | " + note;
        int n = jdbcTemplate.update(
                "UPDATE his_treatment_plan SET total_sessions = ?, remark = ?, update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND status = 0 AND tenant_id = ? AND deleted = 0",
                newTotal, remark, currentUserName(), planId, tenantId());
        if (n == 0) {
            throw new BizException(409, "计划状态已变更, 请刷新后重试");
        }
        boolean planFinished = false;
        boolean orderWritten = false;
        boolean nextCreated = false;
        int cancelledExecs = 0;
        if (completed >= newTotal) {
            // 调整后疗程已满: 完成计划并取消多余在途执行单
            planFinished = markPlanFinished(planId);
            if (planFinished) {
                cancelledExecs = jdbcTemplate.update(
                        "UPDATE his_treatment_exec SET exec_status = 3, cancel_reason = '计划次数调整后疗程已满',"
                                + " update_by = ?, update_time = NOW()"
                                + " WHERE plan_id = ? AND exec_status IN (0, 1) AND tenant_id = ? AND deleted = 0",
                        currentUserName(), planId, tenantId());
                orderWritten = tryFinishOrder(plan.getOrderId());
            }
        } else if (countInFlight(planId) == 0) {
            // 未满且无在途执行单: 补建下一次(调整后继续可执行)
            plan.setTotalSessions(newTotal);
            plan.setRemark(remark);
            execService.createExec(plan, null);
            nextCreated = true;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", planId);
        out.put("oldTotal", oldTotal);
        out.put("totalSessions", newTotal);
        out.put("completedSessions", completed);
        out.put("planFinished", planFinished);
        out.put("orderWritten", orderWritten);
        out.put("nextCreated", nextCreated);
        out.put("cancelledExecs", cancelledExecs);
        out.put("remark", remark);
        return out;
    }

    /**
     * 终止计划: 乐观锁 status 0->2, 记录 terminate_reason; 同步取消在途执行单(0/1->3);
     * 该医嘱单下无其他执行中计划时回写 his_order.exec_status=2。
     */
    @Transactional
    public Map<String, Object> terminatePlan(Long planId, String reason) {
        HisTreatmentPlan plan = requirePlan(planId);
        requireSameOrg(plan.getOrgId());
        if (plan.getStatus() == null || plan.getStatus() != PLAN_RUNNING) {
            throw new BizException(409, "仅执行中的治疗计划可终止(当前状态不可终止)");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BizException(400, "终止原因不能为空");
        }
        int n = jdbcTemplate.update(
                "UPDATE his_treatment_plan SET status = 2, terminate_reason = ?, update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND status = 0 AND tenant_id = ? AND deleted = 0",
                reason.trim(), currentUserName(), planId, tenantId());
        if (n == 0) {
            throw new BizException(409, "计划状态已变更, 请刷新后重试");
        }
        int cancelledExecs = jdbcTemplate.update(
                "UPDATE his_treatment_exec SET exec_status = 3, cancel_reason = '治疗计划已终止',"
                        + " update_by = ?, update_time = NOW()"
                        + " WHERE plan_id = ? AND exec_status IN (0, 1) AND tenant_id = ? AND deleted = 0",
                currentUserName(), planId, tenantId());
        boolean orderWritten = tryFinishOrder(plan.getOrderId());
        log.info("治疗计划 {} 终止: reason={}, 取消在途执行单={}, operator={}", plan.getPlanNo(), reason,
                cancelledExecs, currentUserName());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("planId", planId);
        out.put("status", PLAN_TERMINATED);
        out.put("terminateReason", reason.trim());
        out.put("cancelledExecs", cancelledExecs);
        out.put("orderWritten", orderWritten);
        return out;
    }

    /* ================= 内部工具 ================= */

    /** 医嘱单+项目编码是否已有计划(幂等判重; 编码为空退回按项目名) */
    private boolean planExists(Long orderId, HisOrderItem item) {
        String sql = "SELECT COUNT(*) FROM his_treatment_plan"
                + " WHERE order_id = ? AND tenant_id = ? AND deleted = 0";
        Object arg;
        if (StringUtils.hasText(item.getItemCode())) {
            sql += " AND item_code = ?";
            arg = item.getItemCode().trim();
        } else {
            sql += " AND item_name = ?";
            arg = item.getItemName();
        }
        Integer cnt = jdbcTemplate.queryForObject(sql, Integer.class, orderId, tenantId(), arg);
        return cnt != null && cnt > 0;
    }

    /** 医嘱单归属机构(实体/表均未映射 org_id, 经开单科室 his_dept.org_id 解析; 解析不到返回 null) */
    private Long orderOrgId(HisOrder order) {
        if (order == null || order.getDeptId() == null) {
            return null;
        }
        List<Long> orgs = jdbcTemplate.queryForList(
                "SELECT org_id FROM his_dept WHERE id = ? AND tenant_id = ? AND deleted = 0",
                Long.class, order.getDeptId(), tenantId());
        for (Long o : orgs) {
            if (o != null) {
                return o;
            }
        }
        return null;
    }

    /** 该计划在途(待执行/执行中)执行单数量 */
    private int countInFlight(Long planId) {
        Long cnt = execMapper.selectCount(Wrappers.<HisTreatmentExec>lambdaQuery()
                .eq(HisTreatmentExec::getPlanId, planId)
                .in(HisTreatmentExec::getExecStatus, TreatmentExecService.ST_PENDING, TreatmentExecService.ST_RUNNING));
        return cnt == null ? 0 : cnt.intValue();
    }

    /** 计划置完成(乐观锁 0->1) */
    private boolean markPlanFinished(Long planId) {
        int n = jdbcTemplate.update(
                "UPDATE his_treatment_plan SET status = 1, update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND status = 0 AND tenant_id = ? AND deleted = 0",
                currentUserName(), planId, tenantId());
        return n > 0;
    }

    /** 医嘱单回写执行状态: 该医嘱单下无执行中(status=0)的计划时置 his_order.exec_status=2 */
    private boolean tryFinishOrder(Object orderIdObj) {
        Long orderId = orderIdObj == null ? null : ((Number) orderIdObj).longValue();
        if (orderId == null) {
            return false;
        }
        Integer remain = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_treatment_plan WHERE order_id = ? AND status = 0"
                        + " AND tenant_id = ? AND deleted = 0",
                Integer.class, orderId, tenantId());
        if (remain == null || remain > 0) {
            return false;
        }
        int n = jdbcTemplate.update(
                "UPDATE his_order SET exec_status = 2, update_time = NOW() WHERE id = ? AND tenant_id = ? AND deleted = 0",
                orderId, tenantId());
        return n > 0;
    }

    /** 项目名归类: 中医传统(tcm) / 康复(rehab) / 理疗(physiotherapy, 默认) */
    private static String classifyCategory(String itemName) {
        if (!StringUtils.hasText(itemName)) {
            return "physiotherapy";
        }
        for (String k : TCM_KEYWORDS) {
            if (itemName.contains(k)) {
                return "tcm";
            }
        }
        for (String k : REHAB_KEYWORDS) {
            if (itemName.contains(k)) {
                return "rehab";
            }
        }
        return "physiotherapy";
    }

    /** 计划必读(MP 自动租户过滤 + 逻辑删), 不存在抛 404 */
    private HisTreatmentPlan requirePlan(Long planId) {
        if (planId == null) {
            throw new BizException(400, "治疗计划ID不能为空");
        }
        HisTreatmentPlan plan = planMapper.selectById(planId);
        if (plan == null) {
            throw new BizException(404, "治疗计划不存在");
        }
        return plan;
    }

    /** 写操作机构校验: 计划/医嘱归属机构须与当前登录机构一致(防跨机构误操作) */
    private void requireSameOrg(Long orgId) {
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        if (u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法操作治疗计划");
        }
        if (orgId != null && !orgId.equals(u.getOrgId())) {
            throw new BizException(403, "该治疗数据不属于当前登录机构, 无权操作");
        }
    }

    /** 当前登录机构ID(为空抛 403) */
    private static Long loginOrgId() {
        LoginUser u = UserContext.get();
        if (u == null || u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法操作治疗计划");
        }
        return u.getOrgId();
    }

    /** 查询机构作用域: 入参为空回退当前登录机构 */
    private static Long requireOrg(Long orgId) {
        if (orgId != null) {
            return orgId;
        }
        return loginOrgId();
    }

    private static int nvl(Integer v) {
        return v == null ? 0 : v;
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    private static String currentUserName() {
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

    /* ================= 单号生成 ================= */

    /** 计划号生成: ZL + yyyyMMdd + 4位序号, synchronized 唯一, 跨日重置时 DB 回读当日最大序号兜底重启 */
    private synchronized String generatePlanNo() {
        String today = LocalDate.now().format(NO_DAY);
        if (!today.equals(seqDate)) {
            seqDate = today;
            seqNo = maxSeqFromDb(today);
        }
        seqNo++;
        String no = "ZL" + today + String.format("%04d", seqNo);
        while (noExists(no)) {
            seqNo++;
            no = "ZL" + today + String.format("%04d", seqNo);
        }
        return no;
    }

    /** 查当日已有计划号最大序号(重启后防撞号) */
    private int maxSeqFromDb(String today) {
        String like = "ZL" + today;
        HisTreatmentPlan one = planMapper.selectOne(Wrappers.<HisTreatmentPlan>lambdaQuery()
                .likeRight(HisTreatmentPlan::getPlanNo, like)
                .orderByDesc(HisTreatmentPlan::getPlanNo)
                .last("LIMIT 1"));
        if (one == null || one.getPlanNo() == null || one.getPlanNo().length() < like.length() + 4) {
            return 0;
        }
        try {
            return Integer.parseInt(one.getPlanNo().substring(like.length()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 单号是否已存在 */
    private boolean noExists(String no) {
        return planMapper.selectCount(Wrappers.<HisTreatmentPlan>lambdaQuery()
                .eq(HisTreatmentPlan::getPlanNo, no)) > 0;
    }
}
