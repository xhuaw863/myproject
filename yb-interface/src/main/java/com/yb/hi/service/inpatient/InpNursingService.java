package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.dto.inpatient.InpNursingDTO;
import com.yb.hi.entity.inpatient.HisInpNursingRecord;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisInpNursingRecordMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 住院护理记录服务: 五类记录(体温单/评估/计划/措施/总结)的增删改查 + 体温单图表数据。
 * 说明:
 * 1) 记录按 inpVisitId 归属就诊, 写操作校验就诊归属机构与当前登录机构一致(平台超管放行);
 * 2) content 为 JSON 文本, 体温单(recordType=1)结构约定
 *    {time, temperature, pulse, respiration, systolicBp, diastolicBp}(兼容 blood_pressure "120/80");
 * 3) 体温单数据解析失败的单条跳过(容错, 不阻断整图)。
 */
@Slf4j
@Service
public class InpNursingService {

    /** 记录类型: 1体温单 2护理评估 3护理计划 4护理措施 5护理总结 */
    public static final int TYPE_TEMPERATURE = 1;
    public static final int TYPE_ASSESSMENT = 2;
    public static final int TYPE_MAX = 5;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final HisInpNursingRecordMapper recordMapper;
    private final HisInpVisitMapper visitMapper;
    private final JdbcTemplate jdbcTemplate;

    public InpNursingService(HisInpNursingRecordMapper recordMapper, HisInpVisitMapper visitMapper,
                             JdbcTemplate jdbcTemplate) {
        this.recordMapper = recordMapper;
        this.visitMapper = visitMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 列表 / CRUD ================= */

    /**
     * 护理记录列表(按就诊): 可选 recordType 筛选, 记录时间倒序(最新在前);
     * 附带返回记录内容原文(content JSON 由前端按类型解析渲染)。
     */
    public List<Map<String, Object>> listByVisit(Long visitId, Integer recordType) {
        HisInpVisit visit = requireVisit(visitId);
        List<HisInpNursingRecord> recs = recordMapper.selectList(Wrappers.<HisInpNursingRecord>lambdaQuery()
                .eq(HisInpNursingRecord::getInpVisitId, visit.getId())
                .eq(recordType != null, HisInpNursingRecord::getRecordType, recordType)
                .orderByDesc(HisInpNursingRecord::getRecordTime)
                .orderByDesc(HisInpNursingRecord::getId));
        List<Map<String, Object>> out = new ArrayList<>(recs.size());
        for (HisInpNursingRecord r : recs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("inpVisitId", r.getInpVisitId());
            row.put("recordType", r.getRecordType());
            row.put("content", r.getContent());
            row.put("recordTime", r.getRecordTime() == null ? null
                    : TIME_FMT.format(r.getRecordTime()));
            row.put("nurseId", r.getNurseId());
            row.put("createBy", r.getCreateBy());
            row.put("createTime", r.getCreateTime() == null ? null
                    : TIME_FMT.format(r.getCreateTime()));
            out.add(row);
        }
        return out;
    }

    /** 新建护理记录: 校验就诊归属 + 类型/内容必填, 记录时间取当前, 护士取当前登录职工。 */
    public Map<String, Object> create(InpNursingDTO dto, Long nurseId) {
        if (dto == null || dto.getInpVisitId() == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        if (dto.getRecordType() == null || dto.getRecordType() < 1 || dto.getRecordType() > TYPE_MAX) {
            throw new BizException(400, "记录类型无效(1体温单 2护理评估 3护理计划 4护理措施 5护理总结)");
        }
        if (!StringUtils.hasText(dto.getContent())) {
            throw new BizException(400, "记录内容不能为空");
        }
        validateContentJson(dto.getRecordType(), dto.getContent());
        HisInpVisit visit = requireVisit(dto.getInpVisitId());

        HisInpNursingRecord rec = new HisInpNursingRecord();
        rec.setOrgId(visit.getOrgId());
        rec.setInpVisitId(visit.getId());
        rec.setRecordType(dto.getRecordType());
        rec.setContent(dto.getContent());
        rec.setRecordTime(LocalDateTime.now());
        rec.setNurseId(nurseId);
        recordMapper.insert(rec);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", rec.getId());
        out.put("inpVisitId", rec.getInpVisitId());
        out.put("recordType", rec.getRecordType());
        out.put("recordTime", TIME_FMT.format(rec.getRecordTime()));
        out.put("nurseId", rec.getNurseId());
        return out;
    }

    /** 编辑护理记录: 仅可改 recordType/content(签名护士与记录时间不变, 保证原始留痕)。 */
    public Map<String, Object> update(Long id, InpNursingDTO dto) {
        if (id == null) {
            throw new BizException(400, "记录ID不能为空");
        }
        if (dto == null) {
            throw new BizException(400, "请求体不能为空");
        }
        HisInpNursingRecord rec = requireRecord(id);
        if (dto.getRecordType() != null) {
            if (dto.getRecordType() < 1 || dto.getRecordType() > TYPE_MAX) {
                throw new BizException(400, "记录类型无效(1-5)");
            }
        }
        String content = StringUtils.hasText(dto.getContent()) ? dto.getContent() : rec.getContent();
        if (!StringUtils.hasText(content)) {
            throw new BizException(400, "记录内容不能为空");
        }
        Integer recordType = dto.getRecordType() != null ? dto.getRecordType() : rec.getRecordType();
        validateContentJson(recordType, content);

        int n = recordMapper.update(null, Wrappers.<HisInpNursingRecord>lambdaUpdate()
                .eq(HisInpNursingRecord::getId, id)
                .set(HisInpNursingRecord::getRecordType, recordType)
                .set(HisInpNursingRecord::getContent, content)
                .set(HisInpNursingRecord::getUpdateBy, currentUserName()));
        if (n == 0) {
            throw new BizException(409, "记录已变更或不存在, 请刷新后重试");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("recordType", recordType);
        return out;
    }

    /** 删除护理记录(逻辑删除)。 */
    public void delete(Long id) {
        if (id == null) {
            throw new BizException(400, "记录ID不能为空");
        }
        requireRecord(id);
        recordMapper.deleteById(id);
    }

    /* ================= 体温单 ================= */

    /**
     * 体温单图表数据(recordType=1): 解析每条 content JSON 提取
     * {time, temperature, pulse, respiration, systolicBp, diastolicBp},
     * 按 time 升序返回(供前端绘制体温/脉搏/呼吸/血压折线图)。
     * 兼容: time 缺失回退 record_time; systolicBp/diastolicBp 缺失时拆 blood_pressure "120/80"。
     */
    public List<Map<String, Object>> getTemperatureData(Long visitId) {
        HisInpVisit visit = requireVisit(visitId);
        List<HisInpNursingRecord> recs = recordMapper.selectList(Wrappers.<HisInpNursingRecord>lambdaQuery()
                .eq(HisInpNursingRecord::getInpVisitId, visit.getId())
                .eq(HisInpNursingRecord::getRecordType, TYPE_TEMPERATURE)
                .orderByAsc(HisInpNursingRecord::getRecordTime));
        List<Map<String, Object>> out = new ArrayList<>(recs.size());
        for (HisInpNursingRecord r : recs) {
            JSONObject json;
            try {
                json = JSON.parseObject(r.getContent());
            } catch (Exception e) {
                log.warn("体温单记录 content 非法 JSON, 跳过: recordId={}", r.getId());
                continue;
            }
            if (json == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            String time = json.getString("time");
            row.put("time", StringUtils.hasText(time) ? time
                    : (r.getRecordTime() == null ? null : TIME_FMT.format(r.getRecordTime())));
            row.put("recordTime", r.getRecordTime() == null ? null : TIME_FMT.format(r.getRecordTime()));
            row.put("temperature", json.get("temperature"));
            row.put("pulse", json.get("pulse"));
            row.put("respiration", json.get("respiration"));
            Object systolic = json.get("systolicBp");
            Object diastolic = json.get("diastolicBp");
            if (systolic == null && diastolic == null) {
                String bp = json.getString("blood_pressure");
                if (StringUtils.hasText(bp) && bp.contains("/")) {
                    String[] parts = bp.split("/");
                    try {
                        row.put("systolicBp", Long.parseLong(parts[0].trim()));
                        row.put("diastolicBp", Long.parseLong(parts[1].trim()));
                    } catch (NumberFormatException ignore) {
                        // 血压格式非法则不输出
                    }
                }
            } else {
                row.put("systolicBp", systolic);
                row.put("diastolicBp", diastolic);
            }
            out.add(row);
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("time")),
                Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    /* ================= 看板待评估计数 ================= */

    /**
     * 待评估患者计数(看板复用): 在院(visit_status=2)且近7天内无护理评估记录(record_type=2)的患者数,
     * 覆盖"入院后从未评估"与"评估超7天未更新"两种待办。wardId 可选过滤。
     */
    public int countPendingAssessments(Long wardId) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM his_inp_visit v"
                        + " WHERE v.visit_status = 2 AND v.deleted = 0 AND v.tenant_id = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM his_inp_nursing_record r"
                        + "  WHERE r.inp_visit_id = v.id AND r.record_type = " + TYPE_ASSESSMENT
                        + "   AND r.deleted = 0 AND r.record_time >= DATE_SUB(NOW(), INTERVAL 7 DAY))");
        Long tenant = TenantContext.get();
        Long n;
        if (wardId != null) {
            sql.append(" AND v.ward_id = ?");
            n = jdbcTemplate.queryForObject(sql.toString(), Long.class,
                    tenant == null ? 0L : tenant, wardId);
        } else {
            n = jdbcTemplate.queryForObject(sql.toString(), Long.class,
                    tenant == null ? 0L : tenant);
        }
        return n == null ? 0 : n.intValue();
    }

    /* ================= 给药记录(护士站只读面板) ================= */

    /**
     * 给药执行记录: 指定就诊+日期的药品类医嘱(order_category=1)执行流水, 按执行时间升序。
     * 口径: 执行单 exec_status=2(已执行) 且 exec_time 非 NULL(由 DATE(exec_time)=? 保证);
     * 联表取医嘱内容/规格/剂量/频次与执行护士姓名; 已停止(5)/已作废(6)医嘱的历史执行流水
     * 仍纳入(已发生的事实给药, 供护理追溯)。
     */
    public List<Map<String, Object>> getMedicationAdminRecord(Long visitId, String date) {
        HisInpVisit visit = requireVisit(visitId);
        String day = requireDate(date);
        return jdbcTemplate.queryForList(
                "SELECT e.id AS execId, DATE_FORMAT(e.exec_time, '%Y-%m-%d %H:%i') AS execTime,"
                        + " o.order_content AS orderName, o.spec, o.dosage, o.dosage_unit AS dosageUnit,"
                        + " o.freq_code AS frequency, s.staff_name AS execNurse, e.exec_remark AS execRemark"
                        + " FROM his_inp_order_exec e"
                        + " JOIN his_inp_order o ON e.order_id = o.id AND o.deleted = 0"
                        + " LEFT JOIN his_staff s ON e.exec_nurse_id = s.id AND s.deleted = 0"
                        + " WHERE e.inp_visit_id = ? AND DATE(e.exec_time) = ? AND e.exec_status = 2"
                        + " AND o.order_category = 1 AND e.deleted = 0 AND e.tenant_id = ?"
                        + " ORDER BY e.exec_time ASC, e.id ASC",
                visit.getId(), day, tenantId());
    }

    /* ================= 内部工具 ================= */

    /** 就诊必读校验: 存在 + 归属当前登录机构(平台超管放行)。 */
    private HisInpVisit requireVisit(Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        HisInpVisit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        if (!u.hasRole(Roles.SUPER_ADMIN) && visit.getOrgId() != null && u.getOrgId() != null
                && !visit.getOrgId().equals(u.getOrgId())) {
            throw new BizException(403, "该就诊不属于当前登录机构, 无权操作");
        }
        return visit;
    }

    private HisInpNursingRecord requireRecord(Long id) {
        HisInpNursingRecord rec = recordMapper.selectById(id);
        if (rec == null) {
            throw new BizException(404, "护理记录不存在");
        }
        requireSameOrg(rec.getOrgId());
        return rec;
    }

    /** 写操作机构校验: 记录归属机构须与当前登录机构一致(平台超管放行)。 */
    private void requireSameOrg(Long orgId) {
        LoginUser u = UserContext.get();
        if (u == null) {
            throw new BizException(401, "未登录");
        }
        if (u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法操作护士站数据");
        }
        if (orgId != null && !orgId.equals(u.getOrgId()) && !u.hasRole(Roles.SUPER_ADMIN)) {
            throw new BizException(403, "该护理记录不属于当前登录机构, 无权操作");
        }
    }

    /** content 必须为合法 JSON 对象(体温单另校验体温字段存在, 不校验具体数值类型)。 */
    private void validateContentJson(Integer recordType, String content) {
        JSONObject json;
        try {
            json = JSON.parseObject(content);
        } catch (Exception e) {
            throw new BizException(400, "记录内容必须为合法 JSON");
        }
        if (json == null) {
            throw new BizException(400, "记录内容必须为 JSON 对象");
        }
        if (recordType != null && recordType == TYPE_TEMPERATURE
                && json.get("temperature") == null && json.get("time") == null) {
            throw new BizException(400, "体温单记录须包含 time/temperature 等生命体征字段");
        }
    }

    /** 日期参数校验(yyyy-MM-dd, 非法抛 400)。 */
    private static String requireDate(String date) {
        if (!StringUtils.hasText(date)) {
            throw new BizException(400, "日期不能为空(yyyy-MM-dd)");
        }
        try {
            return LocalDate.parse(date.trim()).toString();
        } catch (Exception e) {
            throw new BizException(400, "日期格式不正确(应为 yyyy-MM-dd)");
        }
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
        return lu.getRealName() != null && !lu.getRealName().isEmpty() ? lu.getRealName() : lu.getUsername();
    }
}
