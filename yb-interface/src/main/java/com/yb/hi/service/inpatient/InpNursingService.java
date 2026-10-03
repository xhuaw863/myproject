package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
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
import com.yb.hi.service.emr.EmrAuditService;
import com.yb.hi.service.emr.EmrDocumentService;
import com.yb.hi.service.emr.EmrElementService;
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
 * 3) 体温单数据解析失败的单条跳过(容错, 不阻断整图);
 * 4) P4a-5 富文本双轨: content 为 Tiptap 文档(顶层 type=doc)时服务端 AES-GCM 加密落库(密文轨) + 派生
 *    扁平 structure_data(fieldKey→值, 向后兼容), 同步 his_emr_element(scope=3)与审计留痕(scope=3);
 *    列表返回前自动解密回明文, 存量明文记录原样透传。
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
    private final EmrDocumentService emrDocumentService;
    private final EmrAuditService emrAuditService;
    private final EmrElementService emrElementService;

    public InpNursingService(HisInpNursingRecordMapper recordMapper, HisInpVisitMapper visitMapper,
                             JdbcTemplate jdbcTemplate,
                             EmrDocumentService emrDocumentService,
                             EmrAuditService emrAuditService,
                             EmrElementService emrElementService) {
        this.recordMapper = recordMapper;
        this.visitMapper = visitMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.emrDocumentService = emrDocumentService;
        this.emrAuditService = emrAuditService;
        this.emrElementService = emrElementService;
    }

    /* ================= 列表 / CRUD ================= */

    /**
     * 护理记录列表(按就诊): 可选 recordType 筛选, 记录时间倒序(最新在前);
     * 附带返回记录内容原文(content JSON 由前端按类型解析渲染; Tiptap 密文轨已解密回明文)。
     * Tiptap 记录附 textSummary(纯文本摘要)供前端列表/引用展示, 避免裸 JSON。
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
            String content = decryptQuietly(r.getId(), r.getContent());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("inpVisitId", r.getInpVisitId());
            row.put("recordType", r.getRecordType());
            row.put("content", content);
            row.put("recordTime", r.getRecordTime() == null ? null
                    : TIME_FMT.format(r.getRecordTime()));
            row.put("nurseId", r.getNurseId());
            row.put("createBy", r.getCreateBy());
            row.put("createTime", r.getCreateTime() == null ? null
                    : TIME_FMT.format(r.getCreateTime()));
            /* P4a-5 富文本轨: 附纯文本摘要(前端列表优先展示, 避免裸 JSON) */
            if (isTiptapDocument(content)) {
                String summary = tiptapSummary(content);
                if (StringUtils.hasText(summary)) {
                    row.put("textSummary", summary);
                }
            }
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
        /* P4a-5 富文本双轨: Tiptap 文档 → AES-GCM 密文落 content + 派生扁平 structure_data(前端缺省时); 其余原文口径不变 */
        boolean tiptap = isTiptapDocument(dto.getContent());
        if (tiptap) {
            rec.setContent(emrDocumentService.encrypt(dto.getContent()));
            rec.setTemplateId(dto.getTemplateId());
            rec.setStructureData(deriveStructure(dto.getContent(), dto.getStructure()));
        } else {
            rec.setContent(dto.getContent());
        }
        rec.setRecordTime(LocalDateTime.now());
        rec.setNurseId(nurseId);
        recordMapper.insert(rec);
        if (tiptap) {
            auditQuietly(rec.getId(), "CREATE", null);
            /* 要素同步须在插入后(按 recordId 先删后插); 失败不影响主流程 */
            emrElementService.syncFromTiptap(EmrAuditService.SCOPE_NURSING, rec.getId(), dto.getContent());
        }

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
        Integer recordType = dto.getRecordType() != null ? dto.getRecordType() : rec.getRecordType();
        if (!StringUtils.hasText(dto.getContent()) && !StringUtils.hasText(rec.getContent())) {
            throw new BizException(400, "记录内容不能为空");
        }
        int n;
        if (StringUtils.hasText(dto.getContent())) {
            String content = dto.getContent();
            validateContentJson(recordType, content);
            if (isTiptapDocument(content)) {
                /* P4a-5 富文本双轨: 覆写密文轨 + 重派生 structure_data(前端缺省时), 附审计与要素同步 */
                n = recordMapper.update(null, Wrappers.<HisInpNursingRecord>lambdaUpdate()
                        .eq(HisInpNursingRecord::getId, id)
                        .set(HisInpNursingRecord::getRecordType, recordType)
                        .set(HisInpNursingRecord::getContent, emrDocumentService.encrypt(content))
                        .set(HisInpNursingRecord::getStructureData, deriveStructure(content, dto.getStructure()))
                        .set(dto.getTemplateId() != null, HisInpNursingRecord::getTemplateId, dto.getTemplateId())
                        .set(HisInpNursingRecord::getUpdateBy, currentUserName()));
                if (n == 0) {
                    throw new BizException(409, "记录已变更或不存在, 请刷新后重试");
                }
                auditQuietly(id, "UPDATE", null);
                emrElementService.syncFromTiptap(EmrAuditService.SCOPE_NURSING, id, content);
            } else {
                n = recordMapper.update(null, Wrappers.<HisInpNursingRecord>lambdaUpdate()
                        .eq(HisInpNursingRecord::getId, id)
                        .set(HisInpNursingRecord::getRecordType, recordType)
                        .set(HisInpNursingRecord::getContent, content)
                        .set(HisInpNursingRecord::getUpdateBy, currentUserName()));
                if (n == 0) {
                    throw new BizException(409, "记录已变更或不存在, 请刷新后重试");
                }
            }
        } else {
            /* dto 未携带内容(仅改类型): 库中原文(含存量密文轨)原样保留 */
            n = recordMapper.update(null, Wrappers.<HisInpNursingRecord>lambdaUpdate()
                    .eq(HisInpNursingRecord::getId, id)
                    .set(HisInpNursingRecord::getRecordType, recordType)
                    .set(HisInpNursingRecord::getUpdateBy, currentUserName()));
            if (n == 0) {
                throw new BizException(409, "记录已变更或不存在, 请刷新后重试");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("recordType", recordType);
        return out;
    }

    /** 删除护理记录(逻辑删除; P4a-5 附 DELETE 审计留痕)。 */
    public void delete(Long id) {
        if (id == null) {
            throw new BizException(400, "记录ID不能为空");
        }
        requireRecord(id);
        recordMapper.deleteById(id);
        auditQuietly(id, "DELETE", null);
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

    /* ================= 富文本双轨(P4a-5) ================= */

    /** Tiptap/ProseMirror 文档判定: JSON 解析后顶层 type=doc(与住院病历/门诊同口径) */
    private static boolean isTiptapDocument(String json) {
        if (!StringUtils.hasText(json)) {
            return false;
        }
        String s = json.trim();
        if (s.isEmpty() || s.charAt(0) != '{') {
            return false;
        }
        try {
            JSONObject obj = JSON.parseObject(s);
            return obj != null && "doc".equals(String.valueOf(obj.get("type")));
        } catch (Exception e) {
            return false;
        }
    }

    /** 富文本双轨派生扁平结构: 优先采用前端提交的 structure, 缺省由 Tiptap 文档服务端抽取(fieldKey→值) */
    private String deriveStructure(String tiptapJson, String submittedStructure) {
        if (StringUtils.hasText(submittedStructure)) {
            return submittedStructure;
        }
        return JSON.toJSONString(emrDocumentService.extractFieldMap(tiptapJson));
    }

    /** 内容解密(静默): 密文轨(格式探测)解密回明文; 失败/密钥缺失仅告警并返回原文, 不阻断列表 */
    private String decryptQuietly(Long recordId, String content) {
        if (!StringUtils.hasText(content)) {
            return content;
        }
        String s = content.trim();
        char c = s.charAt(0);
        if (c == '<' || c == '{' || c == '[' || !looksLikeEncrypted(s)) {
            return content;
        }
        try {
            return emrDocumentService.decrypt(content);
        } catch (Exception e) {
            log.warn("护理记录内容解密失败(原样返回): recordId={}, err={}", recordId, e.getMessage());
            return content;
        }
    }

    /** 密文特征预检: 仅含 Base64 字符集且长度≥24(IV12+密文+tag16 的 Base64 最短 40 字符), 避免明文误入解密告警 */
    private static boolean looksLikeEncrypted(String s) {
        if (s == null || s.length() < 24) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=' || c == '\n' || c == '\r';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** 审计留痕(静默, scope=3 护理文书): 失败仅告警, 不阻断护理记录主流程 */
    private void auditQuietly(Long recordId, String action, String detail) {
        try {
            emrAuditService.log(EmrAuditService.SCOPE_NURSING, recordId, action, detail);
        } catch (Exception e) {
            log.warn("护理记录审计留痕失败(不影响主流程): recordId={}, action={}, err={}", recordId, action, e.getMessage());
        }
    }

    /** Tiptap 文档纯文本摘要(递归收集 text 节点与 emrField 值, 归一空白并截断 120 字符) */
    private static String tiptapSummary(String tiptapJson) {
        StringBuilder sb = new StringBuilder();
        try {
            collectTiptapText(JSON.parseObject(tiptapJson), sb);
        } catch (Exception e) {
            return null;
        }
        String s = sb.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    /** 递归收集 Tiptap 节点树文本: text 节点取 text, emrField 取 attrs.value 标量 */
    private static void collectTiptapText(Object node, StringBuilder sb) {
        if (node instanceof JSONArray) {
            for (Object child : (JSONArray) node) {
                collectTiptapText(child, sb);
            }
            return;
        }
        if (!(node instanceof JSONObject)) {
            return;
        }
        JSONObject o = (JSONObject) node;
        String type = o.getString("type");
        if ("text".equals(type)) {
            appendSummary(sb, o.getString("text"));
        } else if ("emrField".equals(type)) {
            JSONObject attrs = o.getJSONObject("attrs");
            if (attrs != null) {
                appendSummary(sb, scalarText(attrs.get("value")));
            }
        }
        collectTiptapText(o.getJSONArray("content"), sb);
    }

    /** 标量值文本(数组以「、」连接; 对象取 name/label/text/value 首个非空) */
    private static String scalarText(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            for (Object item : (JSONArray) v) {
                String t = scalarText(item);
                if (!StringUtils.hasText(t)) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("、");
                }
                sb.append(t);
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            for (String k : new String[]{"name", "label", "text", "value"}) {
                String t = scalarText(o.get(k));
                if (StringUtils.hasText(t)) {
                    return t;
                }
            }
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /** 摘要追加(空白片段跳过, 以空格分词) */
    private static void appendSummary(StringBuilder sb, String part) {
        if (!StringUtils.hasText(part)) {
            return;
        }
        String t = part.trim();
        if (t.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(t);
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
