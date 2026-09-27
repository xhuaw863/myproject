package com.yb.hi.service.treatment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.treatment.HisTreatmentExec;
import com.yb.hi.entity.treatment.HisTreatmentPlan;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
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
 * 治疗执行服务(待执行工作台): 排队查询 / 患者签到 / 开始 / 完成 / 取消 + 治疗记录查询。
 * 说明:
 * 1) his_treatment_plan / his_treatment_exec 为机构级业务表(MP 租户插件自动注入 tenant_id),
 *    Mapper 查询自动带租户与逻辑删; JdbcTemplate 跨表查询显式带 tenant_id = TenantContext.get();
 * 2) 状态机: exec_status 0待执行 -> 1执行中 -> 2已完成; 0/1 -> 3已取消。患者签到单独落 checkin_time
 *    (不改执行状态), 工作台按"签到优先"排队(已签到按签到时间升序, 未签到排后);
 * 3) 待执行列表仅显示已收费(order.paid_flag=1)且计划执行中(plan.status=0)的单;
 * 4) 完成联动(乐观锁): exec 1->2 后 plan.completed_sessions+1(带 < total_sessions 上限),
 *    满疗程则 plan.status 0->1 并在医嘱单下无其他执行中计划时回写 his_order.exec_status=2;
 *    疗程未满且无在途执行单则自动预生成下一次, 保证工作台连续可见;
 * 5) 取消后若疗程未满同样补建下一次(该次需补做); 已终止计划不再补建;
 * 6) 单号: ZX + yyyyMMdd + 4位序号, synchronized 生成 + DB 回读当日最大序号兜底重启防撞号。
 */
@Slf4j
@Service
public class TreatmentExecService {

    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 执行状态: 0待执行 1执行中 2已完成 3已取消 */
    public static final int ST_PENDING = 0;
    public static final int ST_RUNNING = 1;
    public static final int ST_FINISHED = 2;
    public static final int ST_CANCELLED = 3;

    private final HisTreatmentExecMapper execMapper;
    private final HisTreatmentPlanMapper planMapper;
    private final JdbcTemplate jdbcTemplate;

    /** 单号内存序号(synchronized 唯一; 跨日重置时从DB回读当日最大序号) */
    private String seqDate;
    private int seqNo = 0;

    public TreatmentExecService(HisTreatmentExecMapper execMapper,
                                HisTreatmentPlanMapper planMapper,
                                JdbcTemplate jdbcTemplate) {
        this.execMapper = execMapper;
        this.planMapper = planMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 待执行工作台 ================= */

    /**
     * 待执行治疗单(工作台): 已收费(paid_flag=1)且计划执行中的 待执行/执行中 单。
     * 排队口径: 已签到按签到时间升序在前, 未签到按生成顺序随后; deptId 为空不过滤
     * (执行科室未指定时回退按开单科室过滤); 限 200 条防误配炸页面。
     */
    public List<Map<String, Object>> listPendingExecs(Long orgId, Long deptId) {
        Long oid = requireOrg(orgId);
        StringBuilder sql = new StringBuilder(
                "SELECT e.id, e.exec_no, e.plan_id, e.session_index, e.exec_status, e.patient_id,"
                        + " e.exec_therapist_id, e.equipment_code,"
                        + " DATE_FORMAT(e.checkin_time, '%Y-%m-%d %H:%i:%s') AS checkin_time,"
                        + " (e.checkin_time IS NOT NULL) AS checked_in,"
                        + " DATE_FORMAT(e.create_time, '%Y-%m-%d %H:%i:%s') AS create_time,"
                        + " DATE_FORMAT(e.exec_date, '%Y-%m-%d') AS exec_date,"
                        + " p.plan_no, p.item_name, p.item_code, p.category, p.total_sessions, p.completed_sessions, p.frequency,"
                        + " o.order_no, o.visit_id, o.diag_name, o.dept_name AS order_dept_name,"
                        + " d.dept_name AS exec_dept_name,"
                        + " pat.name AS patient_name, pat.gender, pat.age, pat.patient_no,"
                        + " ds.staff_name AS doctor_name, ts.staff_name AS therapist_name, eq.equip_name"
                        + " FROM his_treatment_exec e"
                        + " JOIN his_treatment_plan p ON p.id = e.plan_id AND p.deleted = 0"
                        + " JOIN his_order o ON o.id = p.order_id AND o.deleted = 0 AND o.paid_flag = 1"
                        + " LEFT JOIN his_dept d ON d.id = o.exec_dept_id AND d.deleted = 0"
                        + " LEFT JOIN his_patient pat ON pat.id = e.patient_id AND pat.deleted = 0"
                        + " LEFT JOIN his_staff ds ON ds.id = p.doctor_id AND ds.deleted = 0"
                        + " LEFT JOIN his_staff ts ON ts.id = e.exec_therapist_id AND ts.deleted = 0"
                        + " LEFT JOIN his_treatment_equipment eq ON eq.equip_code = e.equipment_code"
                        + " AND eq.org_id = e.org_id AND eq.deleted = 0"
                        + " WHERE e.deleted = 0 AND e.tenant_id = ? AND e.exec_status IN (0, 1) AND p.status = 0 AND p.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(oid);
        if (deptId != null) {
            sql.append(" AND (o.exec_dept_id = ? OR (o.exec_dept_id IS NULL AND o.dept_id = ?))");
            args.add(deptId);
            args.add(deptId);
        }
        sql.append(" ORDER BY (e.checkin_time IS NULL) ASC, e.checkin_time ASC, e.id ASC LIMIT 200");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /* ================= 执行流转 ================= */

    /** 患者签到(排队): 仅落 checkin_time 不改执行状态; 幂等(重复签到返回原时间) */
    @Transactional
    public Map<String, Object> checkinPatient(Long execId) {
        HisTreatmentExec exec = requireExec(execId);
        requireSameOrg(exec.getOrgId());
        if (exec.getExecStatus() == null || exec.getExecStatus() != ST_PENDING) {
            throw new BizException(409, "当前治疗单状态不可签到(需为待执行)");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("execId", execId);
        if (exec.getCheckinTime() != null) {
            out.put("checkinTime", exec.getCheckinTime());
            out.put("already", true);
            return out;
        }
        int n = jdbcTemplate.update(
                "UPDATE his_treatment_exec SET checkin_time = NOW(), update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND exec_status = 0 AND checkin_time IS NULL AND tenant_id = ? AND deleted = 0",
                currentUserName(), execId, tenantId());
        if (n == 0) {
            throw new BizException(409, "签到失败, 治疗单状态已变更, 请刷新后重试");
        }
        // 注意: 上方更新走 JdbcTemplate, MyBatis 一级缓存不会失效, 必须用 JdbcTemplate 回读最新签到时间
        String checkinTime = jdbcTemplate.queryForObject(
                "SELECT DATE_FORMAT(checkin_time, '%Y-%m-%d %H:%i:%s') FROM his_treatment_exec"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0",
                String.class, execId, tenantId());
        out.put("checkinTime", checkinTime);
        out.put("already", false);
        return out;
    }

    /**
     * 开始治疗: 乐观锁 exec_status 0->1, 记录治疗师/设备/执行日期。
     * equipCode 非空时校验设备存在且启用(本机构); 治疗师允许为空(待补录)。
     */
    @Transactional
    public Map<String, Object> startExec(Long execId, Long therapistId, String equipCode) {
        HisTreatmentExec exec = requireExec(execId);
        requireSameOrg(exec.getOrgId());
        if (exec.getExecStatus() == null || exec.getExecStatus() != ST_PENDING) {
            throw new BizException(409, "当前治疗单状态不可开始(需为待执行)");
        }
        String code = StringUtils.hasText(equipCode) ? equipCode.trim() : null;
        if (code != null) {
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM his_treatment_equipment"
                            + " WHERE equip_code = ? AND org_id = ? AND status = 1 AND tenant_id = ? AND deleted = 0",
                    Integer.class, code, exec.getOrgId(), tenantId());
            if (cnt == null || cnt == 0) {
                throw new BizException(400, "设备不存在或已停用: " + code);
            }
        }
        int n = jdbcTemplate.update(
                "UPDATE his_treatment_exec SET exec_status = 1, exec_therapist_id = ?, equipment_code = ?,"
                        + " exec_date = CURDATE(), update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND exec_status = 0 AND tenant_id = ? AND deleted = 0",
                therapistId, code, currentUserName(), execId, tenantId());
        if (n == 0) {
            throw new BizException(409, "治疗单状态已变更, 请刷新后重试");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("execId", execId);
        out.put("execStatus", ST_RUNNING);
        out.put("therapistId", therapistId);
        out.put("equipmentCode", code);
        return out;
    }

    /**
     * 完成治疗: 乐观锁 exec_status 1->2, 记录时长/参数/患者反应;
     * 疗程进度 +1(带 completed < total 上限), 满疗程则计划 0->1 并回写医嘱执行状态;
     * 疗程未满且无在途执行单时自动补建下一次。
     */
    @Transactional
    public Map<String, Object> finishExec(Long execId, Integer durationMin, String params, String response) {
        HisTreatmentExec exec = requireExec(execId);
        requireSameOrg(exec.getOrgId());
        if (exec.getExecStatus() == null || exec.getExecStatus() != ST_RUNNING) {
            throw new BizException(409, "当前治疗单状态不可完成(需为执行中)");
        }
        if (durationMin != null && durationMin < 0) {
            throw new BizException(400, "治疗时长不能为负数");
        }
        int n = jdbcTemplate.update(
                "UPDATE his_treatment_exec SET exec_status = 2, duration_min = ?, parameters = ?, patient_response = ?,"
                        + " update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND exec_status = 1 AND tenant_id = ? AND deleted = 0",
                durationMin, trimToNull(params), trimToNull(response), currentUserName(), execId, tenantId());
        if (n == 0) {
            throw new BizException(409, "治疗单状态已变更, 请刷新后重试");
        }
        // 疗程进度 +1(带上限, 防止超额; 0行=已满不阻断, 记录日志)
        int progress = jdbcTemplate.update(
                "UPDATE his_treatment_plan SET completed_sessions = completed_sessions + 1, update_time = NOW()"
                        + " WHERE id = ? AND completed_sessions < total_sessions AND tenant_id = ? AND deleted = 0",
                exec.getPlanId(), tenantId());
        if (progress == 0) {
            log.warn("治疗计划 {} 已完成次数已达总次数, 本次完成未累加进度(exec={})", exec.getPlanId(), execId);
        }
        Map<String, Object> planState = planProgress(exec.getPlanId());
        Integer completed = numToInt(planState.get("completedSessions"));
        Integer total = numToInt(planState.get("totalSessions"));
        Integer planStatus = numToInt(planState.get("status"));
        boolean planFinished = false;
        boolean orderWritten = false;
        boolean nextCreated = false;
        if (planStatus != null && planStatus == 0 && completed != null && total != null) {
            if (completed >= total) {
                int done = jdbcTemplate.update(
                        "UPDATE his_treatment_plan SET status = 1, update_by = ?, update_time = NOW()"
                                + " WHERE id = ? AND status = 0 AND tenant_id = ? AND deleted = 0",
                        currentUserName(), exec.getPlanId(), tenantId());
                if (done > 0) {
                    planFinished = true;
                    orderWritten = tryFinishOrder(planState.get("orderId"));
                }
            } else if (countInFlight(exec.getPlanId()) == 0) {
                // 疗程未满且无在途执行单: 预生成下一次(工作台连续可见)
                HisTreatmentPlan plan = planMapper.selectById(exec.getPlanId());
                if (plan != null) {
                    createExec(plan, null);
                    nextCreated = true;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("execId", execId);
        out.put("execStatus", ST_FINISHED);
        out.put("completedSessions", completed);
        out.put("totalSessions", total);
        out.put("planFinished", planFinished);
        out.put("orderWritten", orderWritten);
        out.put("nextCreated", nextCreated);
        return out;
    }

    /**
     * 取消治疗单: 0/1 -> 3, 记录取消原因; 计划执行中且疗程未满时补建下一次(该次需补做),
     * 已终止/已完成计划不补建。
     */
    @Transactional
    public Map<String, Object> cancelExec(Long execId, String reason) {
        HisTreatmentExec exec = requireExec(execId);
        requireSameOrg(exec.getOrgId());
        Integer st = exec.getExecStatus();
        if (st == null || (st != ST_PENDING && st != ST_RUNNING)) {
            throw new BizException(409, "当前治疗单状态不可取消(已完成/已取消)");
        }
        int n = jdbcTemplate.update(
                "UPDATE his_treatment_exec SET exec_status = 3, cancel_reason = ?, update_by = ?, update_time = NOW()"
                        + " WHERE id = ? AND exec_status = ? AND tenant_id = ? AND deleted = 0",
                trimToNull(reason), currentUserName(), execId, st, tenantId());
        if (n == 0) {
            throw new BizException(409, "治疗单状态已变更, 请刷新后重试");
        }
        boolean nextCreated = false;
        HisTreatmentPlan plan = planMapper.selectById(exec.getPlanId());
        if (plan != null && plan.getStatus() != null && plan.getStatus() == 0) {
            int completed = nvl(plan.getCompletedSessions());
            int total = nvl(plan.getTotalSessions());
            if (completed < total && countInFlight(plan.getId()) == 0) {
                createExec(plan, null);
                nextCreated = true;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("execId", execId);
        out.put("execStatus", ST_CANCELLED);
        out.put("nextCreated", nextCreated);
        return out;
    }

    /* ================= 执行单生成(供计划服务/流转补建调用) ================= */

    /**
     * 生成下一次执行单(在途一条策略): session_index 取该计划历史最大序次+1(含已取消, 防序号复用),
     * orderItemId 为空时按"医嘱单+项目编码"回查首条医嘱明细补齐留痕。
     */
    @Transactional
    public HisTreatmentExec createExec(HisTreatmentPlan plan, Long orderItemId) {
        if (plan == null || plan.getId() == null) {
            throw new BizException(400, "治疗计划不存在, 无法生成执行单");
        }
        Integer maxIdx = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(session_index), 0) FROM his_treatment_exec"
                        + " WHERE plan_id = ? AND tenant_id = ? AND deleted = 0",
                Integer.class, plan.getId(), tenantId());
        int next = (maxIdx == null ? 0 : maxIdx) + 1;
        if (orderItemId == null && plan.getOrderId() != null && StringUtils.hasText(plan.getItemCode())) {
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM his_order_item WHERE order_id = ? AND item_code = ? AND tenant_id = ?"
                            + " AND deleted = 0 ORDER BY id LIMIT 1",
                    Long.class, plan.getOrderId(), plan.getItemCode(), tenantId());
            if (!ids.isEmpty()) {
                orderItemId = ids.get(0);
            }
        }
        HisTreatmentExec exec = new HisTreatmentExec();
        exec.setOrgId(plan.getOrgId());
        exec.setExecNo(generateExecNo());
        exec.setPlanId(plan.getId());
        exec.setOrderId(plan.getOrderId());
        exec.setOrderItemId(orderItemId);
        exec.setPatientId(plan.getPatientId());
        exec.setSessionIndex(next);
        exec.setExecStatus(ST_PENDING);
        execMapper.insert(exec);
        return exec;
    }

    /* ================= 治疗记录查询 ================= */

    /** 治疗记录分页(实际发生过的执行: 执行中/已完成/已取消; 日期按执行日期回退创建日期, 区间闭界) */
    public Page<Map<String, Object>> listExecLog(Long orgId, Long therapistId, String category,
                                                 String startDate, String endDate, long page, long size) {
        Long oid = requireOrg(orgId);
        long p = safePage(page);
        long s = safeSize(size);
        StringBuilder where = new StringBuilder(
                " WHERE e.deleted = 0 AND e.tenant_id = ? AND p.org_id = ? AND e.exec_status IN (1, 2, 3)");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(oid);
        if (therapistId != null) {
            where.append(" AND e.exec_therapist_id = ?");
            args.add(therapistId);
        }
        if (StringUtils.hasText(category)) {
            where.append(" AND p.category = ?");
            args.add(category.trim());
        }
        if (StringUtils.hasText(startDate)) {
            where.append(" AND IFNULL(e.exec_date, DATE(e.create_time)) >= ?");
            args.add(startDate.trim());
        }
        if (StringUtils.hasText(endDate)) {
            where.append(" AND IFNULL(e.exec_date, DATE(e.create_time)) <= ?");
            args.add(endDate.trim());
        }
        String joins = " FROM his_treatment_exec e"
                + " JOIN his_treatment_plan p ON p.id = e.plan_id AND p.deleted = 0"
                + " LEFT JOIN his_patient pat ON pat.id = e.patient_id AND pat.deleted = 0"
                + " LEFT JOIN his_order o ON o.id = p.order_id AND o.deleted = 0"
                + " LEFT JOIN his_staff ts ON ts.id = e.exec_therapist_id AND ts.deleted = 0"
                + " LEFT JOIN his_staff ds ON ds.id = p.doctor_id AND ds.deleted = 0"
                + " LEFT JOIN his_treatment_equipment eq ON eq.equip_code = e.equipment_code"
                + " AND eq.org_id = e.org_id AND eq.deleted = 0";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + joins + where, Long.class, args.toArray());
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        String cols = "SELECT e.id, e.exec_no, e.plan_id, e.session_index, e.exec_status,"
                + " DATE_FORMAT(e.exec_date, '%Y-%m-%d') AS exec_date,"
                + " DATE_FORMAT(e.checkin_time, '%Y-%m-%d %H:%i:%s') AS checkin_time,"
                + " DATE_FORMAT(e.create_time, '%Y-%m-%d %H:%i:%s') AS create_time,"
                + " e.duration_min, e.parameters, e.patient_response, e.cancel_reason,"
                + " e.exec_therapist_id, ts.staff_name AS therapist_name,"
                + " e.equipment_code, eq.equip_name,"
                + " p.plan_no, p.item_name, p.item_code, p.category, p.total_sessions, p.completed_sessions, p.frequency,"
                + " o.order_no, o.diag_name, ds.staff_name AS doctor_name,"
                + " pat.name AS patient_name, pat.gender, pat.age, pat.patient_no";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                cols + joins + where + " ORDER BY IFNULL(e.exec_date, DATE(e.create_time)) DESC, e.id DESC LIMIT ?, ?",
                dataArgs.toArray());
        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setTotal(total == null ? 0L : total);
        result.setRecords(rows);
        return result;
    }

    /* ================= 内部工具 ================= */

    /** 该计划在途(待执行/执行中)执行单数量 */
    private int countInFlight(Long planId) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_treatment_exec"
                        + " WHERE plan_id = ? AND exec_status IN (0, 1) AND tenant_id = ? AND deleted = 0",
                Integer.class, planId, tenantId());
        return cnt == null ? 0 : cnt;
    }

    /** 计划进度快照(completed/total/status/orderId) */
    private Map<String, Object> planProgress(Long planId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT completed_sessions, total_sessions, status, order_id FROM his_treatment_plan"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0",
                planId, tenantId());
        Map<String, Object> out = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            return out;
        }
        Map<String, Object> r = rows.get(0);
        out.put("completedSessions", r.get("completed_sessions"));
        out.put("totalSessions", r.get("total_sessions"));
        out.put("status", r.get("status"));
        out.put("orderId", r.get("order_id"));
        return out;
    }

    /**
     * 医嘱单回写执行状态: 该医嘱单下已无执行中(status=0)的计划时置 his_order.exec_status=2。
     * 多明细治疗单任一计划未完成则不回写, 保证"整单执行完毕"语义。
     */
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

    /** 执行单必读(MP 自动租户过滤 + 逻辑删), 不存在抛 404 */
    private HisTreatmentExec requireExec(Long execId) {
        if (execId == null) {
            throw new BizException(400, "治疗执行单ID不能为空");
        }
        HisTreatmentExec exec = execMapper.selectById(execId);
        if (exec == null) {
            throw new BizException(404, "治疗执行单不存在");
        }
        return exec;
    }

    /** 写操作机构校验: 执行单归属机构须与当前登录机构一致(防跨机构误操作) */
    private void requireSameOrg(Long orgId) {
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        if (u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法操作治疗单");
        }
        if (orgId != null && !orgId.equals(u.getOrgId())) {
            throw new BizException(403, "该治疗单不属于当前登录机构, 无权操作");
        }
    }

    /** 查询机构作用域: 入参为空回退当前登录机构 */
    private static Long requireOrg(Long orgId) {
        if (orgId != null) {
            return orgId;
        }
        LoginUser u = UserContext.get();
        if (u != null && u.getOrgId() != null) {
            return u.getOrgId();
        }
        throw new BizException(403, "当前账号未归属任何机构, 无法查询治疗数据");
    }

    private static int nvl(Integer v) {
        return v == null ? 0 : v;
    }

    private static Integer numToInt(Object v) {
        return v == null ? null : ((Number) v).intValue();
    }

    private static String trimToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
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

    /** 单号生成: ZX + yyyyMMdd + 4位序号, synchronized 唯一, 跨日重置时 DB 回读当日最大序号兜底重启 */
    private synchronized String generateExecNo() {
        String today = LocalDate.now().format(NO_DAY);
        if (!today.equals(seqDate)) {
            seqDate = today;
            seqNo = maxSeqFromDb(today);
        }
        seqNo++;
        String no = "ZX" + today + String.format("%04d", seqNo);
        while (noExists(no)) {
            seqNo++;
            no = "ZX" + today + String.format("%04d", seqNo);
        }
        return no;
    }

    /** 查当日已有执行单号最大序号(重启后防撞号) */
    private int maxSeqFromDb(String today) {
        String like = "ZX" + today;
        HisTreatmentExec one = execMapper.selectOne(Wrappers.<HisTreatmentExec>lambdaQuery()
                .likeRight(HisTreatmentExec::getExecNo, like)
                .orderByDesc(HisTreatmentExec::getExecNo)
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

    /** 单号是否已存在 */
    private boolean noExists(String no) {
        return execMapper.selectCount(Wrappers.<HisTreatmentExec>lambdaQuery()
                .eq(HisTreatmentExec::getExecNo, no)) > 0;
    }
}
