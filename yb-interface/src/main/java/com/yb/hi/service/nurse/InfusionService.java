package com.yb.hi.service.nurse;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.nurse.HisInfusionRecord;
import com.yb.hi.entity.nurse.HisNurseExec;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.nurse.HisInfusionRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输液服务(座位/滴速/穿刺/巡视/拔针全流程):
 * 说明:
 * 1) 流程: 待配液(无输液记录) -> 配液(createInfusion, 联动执行单 0->1) -> 穿刺(recordPuncture)
 *    -> 输液中(巡视 addPatrol 追加JSON) -> 拔针(recordRemove, 联动执行单完成 finishExec);
 * 2) 巡视记录 patrol_records 为 JSON 数组文本(读旧值追加, 逐次留痕时间/滴速/患者状态/备注);
 * 3) 拔针乐观锁 remove_time IS NULL -> NOW(), 防双人重复拔针; 穿刺同理 puncture_time IS NULL;
 * 4) 预计结束时间: 从溶液容量(如"250ml")按 15滴/mL 与滴速折算预计输注秒数, 到点行归"待拔针"页签;
 *    容量/滴速缺失不折算(归输液中, 不误催);
 * 5) listInfusions 四页签分页: prepare/done 走 SQL 分页, infusing/ready_remove 同源(在途穿刺未拔)
 *    由服务端计算 overdue 后内存分流分页(门诊同时在输量有限, 上限500行)。
 */
@Slf4j
@Service
public class InfusionService {

    /** 输液页签: prepare待配液 infusing输液中 ready_remove待拔针 done已完成 */
    public static final String STAGE_PREPARE = "prepare";
    public static final String STAGE_INFUSING = "infusing";
    public static final String STAGE_READY_REMOVE = "ready_remove";
    public static final String STAGE_DONE = "done";

    /** 液体换算: 1mL ≈ 15滴(常规输液器口径) */
    private static final int DROPS_PER_ML = 15;
    private static final Pattern VOLUME_PATTERN = Pattern.compile("(\\d+)\\s*m[lL]");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HisInfusionRecordMapper infusionMapper;
    private final NurseExecService nurseExecService;
    private final JdbcTemplate jdbcTemplate;

    public InfusionService(HisInfusionRecordMapper infusionMapper, NurseExecService nurseExecService,
                           JdbcTemplate jdbcTemplate) {
        this.infusionMapper = infusionMapper;
        this.nurseExecService = nurseExecService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 配液 ================= */

    /**
     * 配液(创建输液记录): 座位号/溶液/滴速; 同执行单已有输液记录则拒绝;
     * 联动执行单 0->1(配液即开始执行, 穿刺时间另记)。
     */
    @Transactional
    public HisInfusionRecord createInfusion(Long execId, String seatNo, String solution, Integer dripRate) {
        HisNurseExec exec = nurseExecService.requireExec(execId);
        nurseExecService.requireSameOrg(exec.getOrgId());
        if (!NurseExecService.TYPE_INFUSION.equals(exec.getExecType())) {
            throw new BizException(400, "该执行单非输液类医嘱, 不能配液");
        }
        Integer st = exec.getExecStatus();
        if (st != null && (st == NurseExecService.ST_FINISHED || st == NurseExecService.ST_CANCELLED)) {
            throw new BizException(409, "该执行单已完成/已取消, 不能配液");
        }
        if (!StringUtils.hasText(solution)) {
            throw new BizException(400, "溶液(液体名称与容量)不能为空");
        }
        if (dripRate != null && (dripRate < 1 || dripRate > 300)) {
            throw new BizException(400, "滴速须在 1-300 滴/分之间");
        }
        Long existed = infusionMapper.selectCount(Wrappers.<HisInfusionRecord>lambdaQuery()
                .eq(HisInfusionRecord::getExecId, execId));
        if (existed != null && existed > 0) {
            throw new BizException(400, "该执行单已有输液记录, 请在输液中/已完成页签查看");
        }
        // 用药安全守门(三查七对的后端可验部分): 配液前核对该患者过敏档案
        String allergyHit = activeAllergyHint(exec.getPatientId());
        if (allergyHit != null) {
            throw new BizException(409, "患者存在在用过敏史(" + allergyHit + "), 需医师重新评估开药后才能配液");
        }
        HisInfusionRecord rec = new HisInfusionRecord();
        rec.setExecId(execId);
        rec.setSeatNo(StringUtils.hasText(seatNo) ? seatNo.trim() : null);
        rec.setSolution(solution.trim());
        rec.setDripRate(dripRate);
        infusionMapper.insert(rec);
        if (exec.getExecStatus() != null && exec.getExecStatus() == NurseExecService.ST_PENDING) {
            nurseExecService.startExec(execId, NurseExecService.currentStaffId());
        }
        log.info("输液配液: execId={}, 座位={}, 溶液={}, 滴速={}", execId, rec.getSeatNo(), rec.getSolution(), dripRate);
        return infusionMapper.selectById(rec.getId());
    }

    /**
     * 患者在用过敏档案提示串(过敏原以、拼接), 无过敏史时 null。
     * 仅做后端可验的硬拦截; 药品层面的皮试阴性核对仍在护士界面(三查七对)完成。
     */
    private String activeAllergyHint(Long patientId) {
        if (patientId == null) {
            return null;
        }
        Long tid = com.yb.hi.framework.tenant.TenantContext.get();
        List<String> names = jdbcTemplate.queryForList(
                "SELECT allergen_name FROM his_patient_allergy"
                        + " WHERE patient_id = ? AND is_active = 1 AND deleted = 0"
                        + " AND (? IS NULL OR tenant_id = ?) ORDER BY record_time DESC LIMIT 5",
                String.class, patientId, tid, tid);
        if (names == null || names.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (n == null || n.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(n.trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /* ================= 穿刺 ================= */

    /** 穿刺记录: 首次穿刺生效(乐观锁 puncture_time IS NULL), 记录部位与穿刺护士 */
    @Transactional
    public HisInfusionRecord recordPuncture(Long infusionId, String site, Long nurseId) {
        HisInfusionRecord rec = requireInfusion(infusionId);
        if (rec.getPunctureTime() != null) {
            throw new BizException(400, "该输液已记录穿刺, 不能重复穿刺");
        }
        if (!StringUtils.hasText(site)) {
            throw new BizException(400, "穿刺部位不能为空(如左手背)");
        }
        int n = jdbcTemplate.update(
                "UPDATE his_infusion_record SET puncture_time = NOW(), puncture_site = ?, puncture_nurse_id = ?,"
                        + " update_time = NOW()"
                        + " WHERE id = ? AND puncture_time IS NULL AND tenant_id = ? AND deleted = 0",
                site.trim(), nurseId, infusionId, tenantId());
        if (n == 0) {
            throw new BizException(409, "穿刺记录已被写入(可能他人已操作), 请刷新后重试");
        }
        return infusionMapper.selectById(infusionId);
    }

    /* ================= 巡视 ================= */

    /**
     * 追加巡视记录: patrolJson 为前端组装的对象({dripRate滴速, status患者状态, note备注}),
     * 服务端补巡视时间后追加到 patrol_records JSON 数组(读旧值追加, 全程留痕);
     * 仅已穿刺未拔针的输液可巡视。
     */
    @Transactional
    public Map<String, Object> addPatrol(Long infusionId, String patrolJson) {
        HisInfusionRecord rec = requireInfusion(infusionId);
        if (rec.getPunctureTime() == null) {
            throw new BizException(400, "该输液尚未穿刺, 无法巡视");
        }
        if (rec.getRemoveTime() != null) {
            throw new BizException(400, "该输液已拔针, 无需巡视");
        }
        if (!StringUtils.hasText(patrolJson)) {
            throw new BizException(400, "巡视内容不能为空");
        }
        JSONObject entry;
        try {
            entry = JSON.parseObject(patrolJson.trim());
        } catch (Exception e) {
            throw new BizException(400, "巡视内容格式不正确(须为JSON对象)");
        }
        if (entry == null || entry.isEmpty()) {
            throw new BizException(400, "巡视内容不能为空");
        }
        entry.put("time", LocalDateTime.now().format(TS_FMT));
        // 读旧值追加(保留历史巡视), 空白/损坏旧值容错为新数组
        JSONArray arr = new JSONArray();
        if (StringUtils.hasText(rec.getPatrolRecords())) {
            try {
                JSONArray old = JSON.parseArray(rec.getPatrolRecords());
                if (old != null) {
                    arr = old;
                }
            } catch (Exception e) {
                log.warn("输液 {} 巡视旧值解析失败, 按新数组重建: {}", infusionId, rec.getPatrolRecords());
            }
        }
        arr.add(entry);
        int n = jdbcTemplate.update(
                "UPDATE his_infusion_record SET patrol_records = ?, update_time = NOW()"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0",
                arr.toJSONString(), infusionId, tenantId());
        if (n == 0) {
            throw new BizException(409, "巡视记录写入失败, 请刷新后重试");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("infusionId", infusionId);
        out.put("patrolCount", arr.size());
        out.put("patrolRecords", arr);
        return out;
    }

    /* ================= 拔针 ================= */

    /**
     * 拔针: 乐观锁 remove_time IS NULL -> NOW(), 记录拔针护士;
     * 联动 NurseExecService.finishExec 完成执行单(同医嘱单全完成则回写 his_order.exec_status=2)。
     */
    @Transactional
    public Map<String, Object> recordRemove(Long infusionId, Long nurseId) {
        HisInfusionRecord rec = requireInfusion(infusionId);
        if (rec.getRemoveTime() != null) {
            throw new BizException(400, "该输液已拔针, 不能重复操作");
        }
        if (rec.getPunctureTime() == null) {
            throw new BizException(400, "该输液尚未穿刺, 不能拔针");
        }
        int n = jdbcTemplate.update(
                "UPDATE his_infusion_record SET remove_time = NOW(), remove_nurse_id = ?, update_time = NOW()"
                        + " WHERE id = ? AND remove_time IS NULL AND tenant_id = ? AND deleted = 0",
                nurseId, infusionId, tenantId());
        if (n == 0) {
            throw new BizException(409, "拔针记录已被写入(可能他人已操作), 请刷新后重试");
        }
        // 联动: 完成执行单(患者反应记拔针摘要, 含巡视次数)
        int patrolCount = countPatrol(rec.getPatrolRecords());
        String response = "输液完成拔针" + (patrolCount > 0 ? ", 巡视" + patrolCount + "次" : "");
        Map<String, Object> finishOut = nurseExecService.finishExec(rec.getExecId(), nurseId, response);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("infusionId", infusionId);
        out.put("execId", rec.getExecId());
        out.put("removeTime", LocalDateTime.now().format(TS_FMT));
        out.put("patrolCount", patrolCount);
        out.put("execStatus", finishOut.get("execStatus"));
        out.put("orderFinished", finishOut.get("orderFinished"));
        return out;
    }

    /* ================= 输液列表(四页签) ================= */

    /**
     * 输液分页(四页签): prepare待配液(未穿刺含未配液) / infusing输液中(穿刺未拔未到预计结束)
     * / ready_remove待拔针(穿刺未拔且已到/超预计结束) / done已完成(已拔针或执行单终态);
     * 行含派生字段: elapsed_seconds已输秒数, est_seconds预计输注秒数, est_end_time预计结束时间,
     * overdue是否超时(用于前端"待拔针"高亮)。
     */
    public Page<Map<String, Object>> listInfusions(Long orgId, String stage, long page, long size) {
        Long oid = requireOrg(orgId);
        long p = safePage(page);
        long s = safeSize(size);
        String stageParam = StringUtils.hasText(stage) ? stage.trim() : null;
        if (stageParam != null && !STAGE_PREPARE.equals(stageParam) && !STAGE_INFUSING.equals(stageParam)
                && !STAGE_READY_REMOVE.equals(stageParam) && !STAGE_DONE.equals(stageParam)) {
            throw new BizException(400, "stage 参数非法(仅支持 prepare/infusing/ready_remove/done)");
        }
        StringBuilder where = new StringBuilder(
                " WHERE e.deleted = 0 AND e.tenant_id = ? AND e.org_id = ? AND e.exec_type = 'infusion'");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(oid);
        if (STAGE_PREPARE.equals(stageParam)) {
            where.append(" AND (ir.id IS NULL OR ir.puncture_time IS NULL) AND e.exec_status IN (0, 1)");
        } else if (STAGE_INFUSING.equals(stageParam) || STAGE_READY_REMOVE.equals(stageParam)) {
            // 输液中/待拔针同源: 在途穿刺未拔, 服务端计算 overdue 分流后内存分页
            where.append(" AND ir.puncture_time IS NOT NULL AND ir.remove_time IS NULL AND e.exec_status = 1");
        } else if (STAGE_DONE.equals(stageParam)) {
            where.append(" AND (ir.remove_time IS NOT NULL OR e.exec_status IN (2, -1))");
        }
        String joins = baseJoins();
        List<Map<String, Object>> rows;
        long total;
        if (STAGE_INFUSING.equals(stageParam) || STAGE_READY_REMOVE.equals(stageParam)) {
            // 在途输液全量(上限500), Java 分流 overdue 后内存分页
            List<Object> listArgs = new ArrayList<>(args);
            listArgs.add(500);
            rows = jdbcTemplate.queryForList(baseCols() + joins + where
                    + " ORDER BY ir.puncture_time ASC, e.id ASC LIMIT ?", listArgs.toArray());
            boolean ready = STAGE_READY_REMOVE.equals(stageParam);
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                enrichRow(row);
                boolean overdue = Boolean.TRUE.equals(row.get("overdue"));
                if (ready == overdue) {
                    filtered.add(row);
                }
            }
            total = filtered.size();
            int from = (int) Math.min((p - 1) * s, total);
            int to = (int) Math.min(from + s, total);
            Page<Map<String, Object>> result = new Page<>(p, s);
            result.setTotal(total);
            result.setRecords(new ArrayList<>(filtered.subList(from, to)));
            return result;
        }
        Long cnt = jdbcTemplate.queryForObject("SELECT COUNT(*)" + joins + where, Long.class, args.toArray());
        total = cnt == null ? 0L : cnt;
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        rows = jdbcTemplate.queryForList(baseCols() + joins + where
                + " ORDER BY COALESCE(ir.puncture_time, e.create_time) DESC, e.id DESC LIMIT ?, ?",
                dataArgs.toArray());
        for (Map<String, Object> row : rows) {
            enrichRow(row);
        }
        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setTotal(total);
        result.setRecords(rows);
        return result;
    }

    /* ================= 内部工具 ================= */

    private String baseJoins() {
        return " FROM his_nurse_exec e"
                + " JOIN his_visit v ON v.id = e.visit_id AND v.deleted = 0"
                + " LEFT JOIN his_patient p ON p.id = e.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_order o ON o.id = e.order_id AND o.deleted = 0"
                + " LEFT JOIN his_order_item oi ON oi.id = e.order_item_id AND oi.deleted = 0"
                + " LEFT JOIN his_infusion_record ir ON ir.exec_id = e.id AND ir.deleted = 0"
                + " LEFT JOIN his_staff pn ON pn.id = ir.puncture_nurse_id AND pn.deleted = 0"
                + " LEFT JOIN his_staff rn ON rn.id = ir.remove_nurse_id AND rn.deleted = 0"
                + " LEFT JOIN his_staff en ON en.id = e.exec_nurse_id AND en.deleted = 0";
    }

    private String baseCols() {
        return "SELECT ir.id AS infusionId, ir.exec_id AS execId, ir.seat_no AS seatNo, ir.solution, ir.drip_rate AS dripRate,"
                + " DATE_FORMAT(ir.puncture_time, '%Y-%m-%d %H:%i:%s') AS punctureTime,"
                + " ir.puncture_site AS punctureSite, ir.puncture_nurse_id AS punctureNurseId, pn.staff_name AS punctureNurseName,"
                + " DATE_FORMAT(ir.remove_time, '%Y-%m-%d %H:%i:%s') AS removeTime,"
                + " ir.remove_nurse_id AS removeNurseId, rn.staff_name AS removeNurseName, ir.patrol_records AS patrolRecords,"
                + " e.exec_no AS execNo, e.exec_status AS execStatus, e.exec_nurse_id AS execNurseId, en.staff_name AS nurseName,"
                + " e.patient_id AS patientId, p.name AS patientName, p.patient_no AS patientNo,"
                + " CASE p.gender WHEN '1' THEN '男' WHEN '2' THEN '女' ELSE IFNULL(p.gender, '-') END AS genderName,"
                + " p.age, oi.item_name AS itemName, oi.spec, oi.quantity, oi.unit,"
                + " o.order_no AS orderNo, o.dr_name AS doctorName, o.dept_name AS orderDeptName,"
                + " DATE_FORMAT(e.create_time, '%Y-%m-%d %H:%i:%s') AS createTime";
    }

    /**
     * 派生字段计算: elapsed_seconds(已输秒) / est_seconds(预计输注秒, 容量*15滴/mL/滴速)
     * / est_end_time(预计结束) / overdue(已到预计结束) / patrol_count(巡视次数) / stage(行阶段)。
     */
    private void enrichRow(Map<String, Object> row) {
        Long elapsed = null;
        String punctureTime = (String) row.get("punctureTime");
        if (StringUtils.hasText(punctureTime)) {
            LocalDateTime pt = LocalDateTime.parse(punctureTime, TS_FMT);
            elapsed = java.time.Duration.between(pt, LocalDateTime.now()).getSeconds();
            if (elapsed < 0) {
                elapsed = 0L;
            }
        }
        row.put("elapsedSeconds", elapsed);
        Integer est = estimateSeconds((String) row.get("solution"), toInt(row.get("dripRate")));
        row.put("estSeconds", est);
        if (elapsed != null && est != null) {
            row.put("estEndTime", LocalDateTime.now().plusSeconds(Math.max(est - elapsed, 0)).format(TS_FMT));
            row.put("overdue", elapsed >= est);
        } else {
            row.put("estEndTime", null);
            row.put("overdue", false);
        }
        row.put("patrolCount", countPatrol((String) row.get("patrolRecords")));
        String stage;
        if (row.get("removeTime") != null || (toInt(row.get("execStatus")) != null
                && (toInt(row.get("execStatus")) == 2 || toInt(row.get("execStatus")) == -1))) {
            stage = STAGE_DONE;
        } else if (elapsed == null) {
            stage = STAGE_PREPARE;
        } else if (Boolean.TRUE.equals(row.get("overdue"))) {
            stage = STAGE_READY_REMOVE;
        } else {
            stage = STAGE_INFUSING;
        }
        row.put("stage", stage);
    }

    /** 预计输注秒数: 溶液容量(mL, 如"250ml") * 15滴/mL / 滴速(滴/分) * 60; 容量/滴速缺失返回 null */
    static Integer estimateSeconds(String solution, Integer dripRate) {
        if (solution == null || dripRate == null || dripRate <= 0) {
            return null;
        }
        Matcher m = VOLUME_PATTERN.matcher(solution);
        if (!m.find()) {
            return null;
        }
        try {
            int ml = Integer.parseInt(m.group(1));
            if (ml <= 0) {
                return null;
            }
            return (int) Math.round(ml * DROPS_PER_ML * 60.0 / dripRate);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 巡视次数(JSON数组长度, 解析失败返回0) */
    private static int countPatrol(String patrolRecords) {
        if (!StringUtils.hasText(patrolRecords)) {
            return 0;
        }
        try {
            JSONArray arr = JSON.parseArray(patrolRecords);
            return arr == null ? 0 : arr.size();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 输液记录必读(MP 自动租户过滤 + 逻辑删), 不存在抛 404 */
    private HisInfusionRecord requireInfusion(Long infusionId) {
        if (infusionId == null) {
            throw new BizException(400, "输液记录ID不能为空");
        }
        HisInfusionRecord rec = infusionMapper.selectById(infusionId);
        if (rec == null) {
            throw new BizException(404, "输液记录不存在");
        }
        return rec;
    }

    private static Long requireOrg(Long orgId) {
        if (orgId != null) {
            return orgId;
        }
        LoginUser u = UserContext.get();
        if (u != null && u.getOrgId() != null) {
            return u.getOrgId();
        }
        throw new BizException(403, "当前账号未归属任何机构, 无法查询输液数据");
    }

    private static Integer toInt(Object v) {
        return v == null ? null : ((Number) v).intValue();
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
}
