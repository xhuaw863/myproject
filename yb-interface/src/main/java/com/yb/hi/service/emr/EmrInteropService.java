package com.yb.hi.service.emr;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yb.hi.entity.emr.HisEmrWebhookSubscription;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.emr.HisEmrWebhookSubscriptionMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.bind.DatatypeConverter;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 病历互操作服务(P7b-1): 对外批量查询 + 病历事件 Webhook 推送引擎。
 *
 * RESTful 批量查询(包装 {@link EmrExportService} 的单文档导出能力):
 * - 按患者(queryByPatient)/按就诊(queryByVisit)/按类别(queryByCategory)三入口,
 *   format=json(行摘要) / fhir(逐条导出 FHIR Document Bundle 并包装为 collection Bundle) / cda(逐条导出 CDA/XML);
 * - 扁平化查询(flatElementQuery)读 v_emr_element_flat 视图(病历×就诊×患者×科室×医生联查),
 *   视图未建成时内联 JOIN 兜底(口径与视图定义一致)。
 * - JdbcTemplate 手写 SQL 显式携带 tenant_id + 机构范围(非牵头锁定本机构), LIMIT 上限防全量拉取。
 *
 * Webhook 推送引擎:
 * - @EventListener 监听 EmrEvent, 仅转发归档生命周期事件(RECORD_ARCHIVED/SIGNED/RECALLED/SEALED/UNSEALED),
 *   经自注入代理转 @Async 异步投递(租户上下文由调用线程捕获后在本线程恢复, 参考 EmrTemplateService 范式);
 * - 投递报文 JSON: {eventType, displayName, timestamp, data}, Header X-Webhook-Signature = HMAC-SHA256(body, secret_key) 十六进制;
 * - 超时 5s, 单订阅独立 try-catch; 成功清零 fail_count 并回写 last_push_time, 连续失败 ≥5 自动禁用(status=0)熔断;
 * - 订阅 CRUD + 同步连通性测试(testWebhook)。
 */
@Slf4j
@Service
public class EmrInteropService {

    private static final int INP = 1;
    private static final int OUTP = 2;

    /** json 格式下 content/structureData 摘要截断长度(全量正文走导出/详情通道) */
    private static final int DIGEST_LEN = 200;
    /** 批量查询单次返回上限(防全量拉取) */
    private static final int MAX_RECORDS = 200;
    /** Webhook 推送连接/读取超时(毫秒) */
    private static final int WEBHOOK_TIMEOUT_MS = 5000;
    /** 连续失败熔断阈值: 达到后自动禁用订阅 */
    private static final int FAIL_DISABLE_THRESHOLD = 5;
    /** 日期参数格式(yyyy-MM-dd) */
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 参与 Webhook 外发的病历事件(归档生命周期 + 签署完成); 其余 SSE 内部事件(质控提醒等)不外发 */
    private static final Set<EmrEventType> WEBHOOK_EVENTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            EmrEventType.RECORD_ARCHIVED, EmrEventType.RECORD_SIGNED, EmrEventType.RECORD_RECALLED,
            EmrEventType.RECORD_SEALED, EmrEventType.RECORD_UNSEALED)));

    /**
     * 住院病历联查基础 SQL(by-patient/by-visit/by-category 共用):
     * 与 EmrExportService.loadContext 的抬头口径一致(患者/科室/医生名称回查),
     * tenant/org/条件以追加片段携带, 行键 camelCase。
     */
    private static final String RECORD_SELECT_BASE =
            "SELECT r.id AS recordId, r.inp_visit_id AS visitId, v.patient_id AS patientId, "
                    + "p.name AS patientName, v.dept_id AS deptId, d.dept_name AS deptName, "
                    + "r.record_type AS recordType, r.title, r.status, r.record_time AS recordTime, "
                    + "r.audit_time AS auditTime, r.archive_time AS archiveTime, r.doctor_id AS doctorId, "
                    + "s.staff_name AS doctorName, r.content, r.structure_data AS structureData, r.org_id AS orgId "
                    + "FROM his_inp_medical_record r "
                    + "JOIN his_inp_visit v ON r.inp_visit_id = v.id AND v.deleted = 0 "
                    + "LEFT JOIN his_patient p ON v.patient_id = p.id "
                    + "LEFT JOIN his_dept d ON v.dept_id = d.id "
                    + "LEFT JOIN his_staff s ON r.doctor_id = s.id "
                    + "WHERE r.deleted = 0 ";

    private final JdbcTemplate jdbcTemplate;
    private final HisEmrWebhookSubscriptionMapper webhookMapper;
    private final OrgAccessGuard guard;
    /** 自注入代理: @Async 投递须经代理调用才异步(同类内直调不走代理) */
    private final ObjectProvider<EmrInteropService> selfProvider;
    /** Webhook 专用 REST 客户端(连接/读取各 5s 超时, 与共享 Bean 隔离) */
    private final RestTemplate restTemplate = buildRestTemplate();

    /** FHIR/CDA 导出服务(required=false 防御启动时序; 为 null 时 fhir/cda 请求降级返回 json 摘要) */
    @Autowired(required = false)
    private EmrExportService exportService;

    public EmrInteropService(JdbcTemplate jdbcTemplate,
                             HisEmrWebhookSubscriptionMapper webhookMapper,
                             OrgAccessGuard guard,
                             ObjectProvider<EmrInteropService> selfProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.webhookMapper = webhookMapper;
        this.guard = guard;
        this.selfProvider = selfProvider;
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(WEBHOOK_TIMEOUT_MS);
        f.setReadTimeout(WEBHOOK_TIMEOUT_MS);
        return new RestTemplate(f);
    }

    /* ==================== RESTful 批量查询 ==================== */

    /**
     * 按患者查询其全部住院病历。format=json(摘要行)/fhir(FHIR collection Bundle)/cda(CDA 集合)。
     * 返回信封: {query, format, count, records|fhirBundle|documents[, degraded]}。
     */
    public Map<String, Object> queryByPatient(Long patientId, String format) {
        if (patientId == null) {
            throw new BizException(400, "patientId 不能为空");
        }
        Long tenantId = TenantContext.require();
        Long scope = readScopeOrg();
        StringBuilder sql = new StringBuilder(RECORD_SELECT_BASE);
        List<Object> params = new ArrayList<>();
        sql.append(" AND r.tenant_id = ? AND v.patient_id = ? ");
        params.add(tenantId);
        params.add(patientId);
        if (scope != null) {
            sql.append(" AND r.org_id = ? ");
            params.add(scope);
        }
        sql.append(" ORDER BY r.record_time DESC, r.id DESC LIMIT ").append(MAX_RECORDS);
        List<Map<String, Object>> records = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> recordRow(rs), params.toArray());
        return envelope("by-patient", format, patientId, records);
    }

    /**
     * 按就诊查询。scope=1 住院(inp_visit_id 定位, 返回该就诊全部病历);
     * scope=2 门诊(his_visit.id 定位, 就诊级单文档: 摘要含数据元列表 / FHIR/CDA 单文档集合)。
     */
    public Map<String, Object> queryByVisit(Long visitId, Integer scope, String format) {
        if (visitId == null) {
            throw new BizException(400, "visitId 不能为空");
        }
        int sc = scope == null ? INP : scope;
        if (sc != INP && sc != OUTP) {
            throw new BizException(400, "scope 必须为 1(住院) 或 2(门诊)");
        }
        Long tenantId = TenantContext.require();
        Long orgScope = readScopeOrg();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", "by-visit");
        out.put("format", normalizeFormat(format));
        out.put("visitId", visitId);
        out.put("scope", sc);
        if (sc == INP) {
            StringBuilder sql = new StringBuilder(RECORD_SELECT_BASE);
            List<Object> params = new ArrayList<>();
            sql.append(" AND r.tenant_id = ? AND r.inp_visit_id = ? ");
            params.add(tenantId);
            params.add(visitId);
            if (orgScope != null) {
                sql.append(" AND r.org_id = ? ");
                params.add(orgScope);
            }
            sql.append(" ORDER BY r.record_type ASC, r.record_time ASC LIMIT ").append(MAX_RECORDS);
            List<Map<String, Object>> records = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> recordRow(rs), params.toArray());
            fillPayload(out, normalizeFormat(format), records);
            return out;
        }
        /* 门诊: 就诊为文档单位 */
        Map<String, Object> visitRow = outpatientVisitRow(visitId, tenantId, orgScope);
        out.put("patientName", visitRow == null ? null : visitRow.get("patientName"));
        List<Map<String, Object>> elements = outpatientElements(visitId, tenantId, orgScope);
        out.put("count", 1);
        String fmt = normalizeFormat(format);
        if ("fhir".equals(fmt)) {
            out.put("fhirBundle", exportOutpFhir(visitId, out));
            out.put("records", elements);
        } else if ("cda".equals(fmt)) {
            List<Map<String, Object>> docs = new ArrayList<>();
            if (exportService != null) {
                try {
                    Map<String, Object> doc = new LinkedHashMap<>();
                    doc.put("visitId", visitId);
                    doc.put("cda", exportService.exportCda(OUTP, visitId));
                    docs.add(doc);
                } catch (Exception e) {
                    log.warn("门诊 CDA 导出失败, 降级返回摘要: visitId={}, err={}", visitId, e.getMessage());
                    out.put("degraded", true);
                }
            } else {
                out.put("degraded", true);
            }
            out.put("documents", docs);
            out.put("records", elements);
        } else {
            out.put("records", elements);
        }
        return out;
    }

    /**
     * 按类别查询(科室 + 病历类型 + 记录时间区间, 条件均可选, 至少携带租户/机构范围):
     * 多条件 AND 组合, 记录时间倒序, LIMIT 上限防全量拉取。
     */
    public Map<String, Object> queryByCategory(Long deptId, Integer recordType,
                                               String startDate, String endDate, String format) {
        Long tenantId = TenantContext.require();
        Long scope = readScopeOrg();
        StringBuilder sql = new StringBuilder(RECORD_SELECT_BASE);
        List<Object> params = new ArrayList<>();
        sql.append(" AND r.tenant_id = ? ");
        params.add(tenantId);
        if (scope != null) {
            sql.append(" AND r.org_id = ? ");
            params.add(scope);
        }
        if (deptId != null) {
            sql.append(" AND v.dept_id = ? ");
            params.add(deptId);
        }
        if (recordType != null) {
            sql.append(" AND r.record_type = ? ");
            params.add(recordType);
        }
        LocalDateTime from = parseDayStart(startDate);
        LocalDateTime to = parseDayEnd(endDate);
        if (from != null) {
            sql.append(" AND r.record_time >= ? ");
            params.add(from);
        }
        if (to != null) {
            sql.append(" AND r.record_time <= ? ");
            params.add(to);
        }
        sql.append(" ORDER BY r.record_time DESC, r.id DESC LIMIT ").append(MAX_RECORDS);
        List<Map<String, Object>> records = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> recordRow(rs), params.toArray());
        return envelope("by-category", format, null, records);
    }

    /**
     * 扁平化查询: 读 v_emr_element_flat 视图(视图定义见 DictSchemaMigration.ensureEmrArchiveTables)。
     * 视图未建成(建库期 VIEW 权限受限等)时 BadSqlGrammarException → 内联 JOIN 兜底(口径与视图一致)。
     */
    public List<Map<String, Object>> flatElementQuery(Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "visitId 不能为空");
        }
        Long tenantId = TenantContext.require();
        Long scope = readScopeOrg();
        List<Object> params = new ArrayList<>();
        params.add(visitId);
        params.add(tenantId);
        StringBuilder cond = new StringBuilder(" WHERE visit_id = ? AND tenant_id = ? ");
        if (scope != null) {
            cond.append(" AND org_id = ? ");
            params.add(scope);
        }
        String sql = "SELECT record_id, visit_id, patient_id, patient_name, dept_name, doctor_name, "
                + "record_type, title, status, content, structure_data, record_time, sign_time, archive_time, "
                + "tenant_id, org_id FROM v_emr_element_flat"
                + cond + " ORDER BY record_time DESC, record_id DESC LIMIT " + MAX_RECORDS;
        try {
            return jdbcTemplate.query(sql, (rs, rowNum) -> flatRow(rs), params.toArray());
        } catch (BadSqlGrammarException e) {
            log.warn("v_emr_element_flat 视图查询失败, 内联 JOIN 兜底: visitId={}, err={}", visitId, e.getMessage());
            return flatFallbackQuery(visitId, tenantId, scope);
        }
    }

    /** 视图兜底查询: 与视图定义同一联查口径(建表脚本改动时须两侧同步)。 */
    private List<Map<String, Object>> flatFallbackQuery(Long visitId, Long tenantId, Long scope) {
        StringBuilder sql = new StringBuilder(
                "SELECT r.id AS record_id, r.inp_visit_id AS visit_id, v.patient_id, p.name AS patient_name, "
                        + "d.dept_name, s.staff_name AS doctor_name, r.record_type, r.title, r.status, r.content, "
                        + "r.structure_data, r.record_time, r.audit_time AS sign_time, r.archive_time, "
                        + "r.tenant_id, r.org_id "
                        + "FROM his_inp_medical_record r "
                        + "LEFT JOIN his_inp_visit v ON r.inp_visit_id = v.id AND v.deleted = 0 "
                        + "LEFT JOIN his_patient p ON v.patient_id = p.id "
                        + "LEFT JOIN his_dept d ON v.dept_id = d.id "
                        + "LEFT JOIN his_staff s ON r.doctor_id = s.id "
                        + "WHERE r.deleted = 0 AND r.inp_visit_id = ? AND r.tenant_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(visitId);
        params.add(tenantId);
        if (scope != null) {
            sql.append(" AND r.org_id = ? ");
            params.add(scope);
        }
        sql.append(" ORDER BY r.record_time DESC, r.id DESC LIMIT ").append(MAX_RECORDS);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> flatRow(rs), params.toArray());
    }

    /* ==================== 查询内部助手 ==================== */

    /** 统一信封: {query, format, key, count, payload} + fhir/cda 降级处理。 */
    private Map<String, Object> envelope(String query, String format, Long key, List<Map<String, Object>> records) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query);
        if (key != null) {
            out.put(query.equals("by-patient") ? "patientId" : "key", key);
        }
        fillPayload(out, normalizeFormat(format), records);
        return out;
    }

    /** 按格式装配载荷: json=摘要行 / fhir=collection Bundle / cda=文档集合(导出服务缺失时降级 json 摘要)。 */
    private void fillPayload(Map<String, Object> out, String fmt, List<Map<String, Object>> records) {
        if ("fhir".equals(fmt) || "cda".equals(fmt)) {
            if (exportService == null) {
                out.put("degraded", true);
                out.put("records", toDigestRecords(records));
                out.put("count", records.size());
                return;
            }
            if ("fhir".equals(fmt)) {
                out.put("fhirBundle", buildFhirCollection(records));
            } else {
                out.put("documents", buildCdaDocuments(records));
            }
            out.put("count", records.size());
            return;
        }
        out.put("records", toDigestRecords(records));
        out.put("count", records.size());
    }

    /** 逐条导出 FHIR Document Bundle 并包装为 FHIR collection Bundle(type=collection)。 */
    private JSONObject buildFhirCollection(List<Map<String, Object>> records) {
        JSONObject coll = new JSONObject();
        coll.put("resourceType", "Bundle");
        coll.put("type", "collection");
        coll.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));
        JSONArray entry = new JSONArray();
        for (Map<String, Object> r : records) {
            Long recordId = asLong(r.get("recordId"));
            try {
                JSONObject doc = JSON.parseObject(exportService.exportFhir(INP, recordId));
                JSONObject e = new JSONObject();
                e.put("fullUrl", "urn:yb:emr:record:" + recordId);
                e.put("resource", doc);
                entry.add(e);
            } catch (Exception e) {
                log.warn("FHIR 批量导出单条失败(跳过): recordId={}, err={}", recordId, e.getMessage());
            }
        }
        coll.put("entry", entry);
        coll.put("total", entry.size());
        return coll;
    }

    /** 逐条导出 CDA/XML, 返回 [{recordId, recordType, title, cda}]; 单条失败跳过不阻断。 */
    private List<Map<String, Object>> buildCdaDocuments(List<Map<String, Object>> records) {
        List<Map<String, Object>> docs = new ArrayList<>();
        for (Map<String, Object> r : records) {
            Long recordId = asLong(r.get("recordId"));
            try {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("recordId", recordId);
                doc.put("recordType", r.get("recordType"));
                doc.put("title", r.get("title"));
                doc.put("cda", exportService.exportCda(INP, recordId));
                docs.add(doc);
            } catch (Exception e) {
                log.warn("CDA 批量导出单条失败(跳过): recordId={}, err={}", recordId, e.getMessage());
            }
        }
        return docs;
    }

    /** 门诊就诊 FHIR 单文档集合包装(就诊即文档; 导出失败降级 null 由调用方标记 degraded)。 */
    private JSONObject exportOutpFhir(Long visitId, Map<String, Object> out) {
        try {
            JSONObject coll = new JSONObject();
            coll.put("resourceType", "Bundle");
            coll.put("type", "collection");
            JSONObject doc = JSON.parseObject(exportService.exportFhir(OUTP, visitId));
            JSONObject e = new JSONObject();
            e.put("fullUrl", "urn:yb:emr:visit:" + visitId);
            e.put("resource", doc);
            JSONArray entry = new JSONArray();
            entry.add(e);
            coll.put("entry", entry);
            coll.put("total", 1);
            return coll;
        } catch (Exception e) {
            log.warn("门诊 FHIR 导出失败, 降级返回摘要: visitId={}, err={}", visitId, e.getMessage());
            out.put("degraded", true);
            return null;
        }
    }

    /** json 摘要行: 截断 content/structureData(保留行键/时间/状态等元信息)。 */
    private List<Map<String, Object>> toDigestRecords(List<Map<String, Object>> records) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("recordId", r.get("recordId"));
            row.put("visitId", r.get("visitId"));
            row.put("patientId", r.get("patientId"));
            row.put("patientName", r.get("patientName"));
            row.put("deptId", r.get("deptId"));
            row.put("deptName", r.get("deptName"));
            row.put("recordType", r.get("recordType"));
            row.put("title", r.get("title"));
            row.put("status", r.get("status"));
            row.put("doctorId", r.get("doctorId"));
            row.put("doctorName", r.get("doctorName"));
            row.put("recordTime", r.get("recordTime"));
            row.put("auditTime", r.get("auditTime"));
            row.put("archiveTime", r.get("archiveTime"));
            row.put("orgId", r.get("orgId"));
            row.put("contentDigest", digest((String) r.get("content")));
            row.put("structureDataDigest", digest((String) r.get("structureData")));
            out.add(row);
        }
        return out;
    }

    private String digest(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        return t.length() <= DIGEST_LEN ? t : t.substring(0, DIGEST_LEN) + "...";
    }

    /** 门诊就诊摘要行(his_visit): 存在性 + 机构范围校验, 不存在抛 404。 */
    private Map<String, Object> outpatientVisitRow(Long visitId, Long tenantId, Long scope) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, patient_id AS patientId, patient_name AS patientName, dept_id AS deptId, "
                        + "dept_name AS deptName, staff_id AS doctorId, dr_name AS doctorName, visit_time AS visitTime "
                        + "FROM his_visit WHERE id = ? AND tenant_id = ? AND deleted = 0 ");
        List<Object> params = new ArrayList<>();
        params.add(visitId);
        params.add(tenantId);
        if (scope != null) {
            sql.append(" AND org_id = ? ");
            params.add(scope);
        }
        sql.append(" LIMIT 1");
        List<Map<String, Object>> rows = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> flatRow(rs), params.toArray());
        if (rows.isEmpty()) {
            throw new BizException(404, "门诊就诊不存在: " + visitId);
        }
        return rows.get(0);
    }

    /** 门诊数据元行(his_emr_element scope=2): 字段级扁平列表(供 json 摘要)。 */
    private List<Map<String, Object>> outpatientElements(Long visitId, Long tenantId, Long scope) {
        StringBuilder sql = new StringBuilder(
                "SELECT id AS recordId, field_key AS fieldKey, field_label AS fieldLabel, term_code AS termCode, "
                        + "value_text AS valueText, value_num AS valueNum, value_unit AS valueUnit, "
                        + "value_date AS valueDate, sort_no AS sortNo, record_type AS recordType "
                        + "FROM his_emr_element WHERE scope = ? AND visit_id = ? AND tenant_id = ? AND deleted = 0 ");
        List<Object> params = new ArrayList<>();
        params.add(OUTP);
        params.add(visitId);
        params.add(tenantId);
        if (scope != null) {
            sql.append(" AND org_id = ? ");
            params.add(scope);
        }
        sql.append(" ORDER BY field_key ASC, sort_no ASC LIMIT ").append(MAX_RECORDS);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> flatRow(rs), params.toArray());
    }

    /** 病历行映射(camelCase, 空值安全)。 */
    private Map<String, Object> recordRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("recordId", optLong(rs, "recordId"));
        row.put("visitId", optLong(rs, "visitId"));
        row.put("patientId", optLong(rs, "patientId"));
        row.put("patientName", rs.getString("patientName"));
        row.put("deptId", optLong(rs, "deptId"));
        row.put("deptName", rs.getString("deptName"));
        row.put("recordType", optInt(rs, "recordType"));
        row.put("title", rs.getString("title"));
        row.put("status", optInt(rs, "status"));
        row.put("doctorId", optLong(rs, "doctorId"));
        row.put("doctorName", rs.getString("doctorName"));
        row.put("recordTime", optTime(rs, "recordTime"));
        row.put("auditTime", optTime(rs, "auditTime"));
        row.put("archiveTime", optTime(rs, "archiveTime"));
        row.put("orgId", optLong(rs, "orgId"));
        row.put("content", rs.getString("content"));
        row.put("structureData", rs.getString("structureData"));
        return row;
    }

    /** 扁平行映射(视图/兜底/数据元共用, 下划线列名 → camelCase 键)。 */
    private Map<String, Object> flatRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        flatPut(row, rs, "record_id", "recordId", true);
        flatPut(row, rs, "visit_id", "visitId", true);
        flatPut(row, rs, "patient_id", "patientId", true);
        flatPut(row, rs, "patient_name", "patientName", false);
        flatPut(row, rs, "dept_name", "deptName", false);
        flatPut(row, rs, "doctor_name", "doctorName", false);
        flatPut(row, rs, "record_type", "recordType", false);
        flatPut(row, rs, "title", "title", false);
        flatPut(row, rs, "status", "status", false);
        flatPut(row, rs, "content", "content", false);
        flatPut(row, rs, "structure_data", "structureData", false);
        flatPut(row, rs, "record_time", "recordTime", false);
        flatPut(row, rs, "sign_time", "signTime", false);
        flatPut(row, rs, "archive_time", "archiveTime", false);
        flatPut(row, rs, "tenant_id", "tenantId", true);
        flatPut(row, rs, "org_id", "orgId", true);
        /* 数据元列(仅门诊 element 查询命中, 其余场景标签不存在时忽略) */
        putIfLabel(row, rs, "fieldKey");
        putIfLabel(row, rs, "fieldLabel");
        putIfLabel(row, rs, "termCode");
        putIfLabel(row, rs, "valueText");
        putIfLabel(row, rs, "valueNum");
        putIfLabel(row, rs, "valueUnit");
        putIfLabel(row, rs, "valueDate");
        putIfLabel(row, rs, "sortNo");
        putIfLabel(row, rs, "doctorId");
        putIfLabel(row, rs, "deptId");
        putIfLabel(row, rs, "visitTime");
        return row;
    }

    private void flatPut(Map<String, Object> row, ResultSet rs, String label, String key, boolean numericId)
            throws SQLException {
        try {
            Object v = numericId ? optLong(rs, label) : rs.getObject(label);
            row.put(key, v instanceof Timestamp ? ((Timestamp) v).toLocalDateTime() : v);
        } catch (SQLException ignore) {
            /* 列不存在(复用 mapper 场景)静默跳过 */
        }
    }

    private void putIfLabel(Map<String, Object> row, ResultSet rs, String key) {
        try {
            Object v = rs.getObject(key);
            row.put(key, v instanceof Timestamp ? ((Timestamp) v).toLocalDateTime() : v);
        } catch (SQLException ignore) {
            /* 列不存在静默跳过 */
        }
    }

    private Long optLong(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : rs.getLong(col);
    }

    private Integer optInt(ResultSet rs, String col) throws SQLException {
        Object v = rs.getObject(col);
        return v == null ? null : rs.getInt(col);
    }

    private LocalDateTime optTime(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private Long asLong(Object v) {
        if (v == null) {
            return null;
        }
        return v instanceof Number ? ((Number) v).longValue() : Long.valueOf(String.valueOf(v));
    }

    private String normalizeFormat(String format) {
        if (!StringUtils.hasText(format)) {
            return "json";
        }
        String f = format.trim().toLowerCase();
        if (!"json".equals(f) && !"fhir".equals(f) && !"cda".equals(f)) {
            throw new BizException(400, "format 必须为 json/fhir/cda");
        }
        return f;
    }

    private LocalDateTime parseDayStart(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim(), DAY_FMT).atStartOfDay();
        } catch (Exception e) {
            throw new BizException(400, "日期格式须为 yyyy-MM-dd: " + s);
        }
    }

    private LocalDateTime parseDayEnd(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim(), DAY_FMT).atTime(23, 59, 59);
        } catch (Exception e) {
            throw new BizException(400, "日期格式须为 yyyy-MM-dd: " + s);
        }
    }

    /** 读隔离: 牵头/超管全量(null), 非牵头锁定本机构(与 EmrArchiveService.readScopeOrg 同口径)。 */
    private Long readScopeOrg() {
        LoginUser u = UserContext.get();
        Long scope = guard.scopeOrgId(null);
        if (scope == null && u != null && !u.hasRole(Roles.SUPER_ADMIN)
                && !guard.isLead(u) && u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法查询互操作数据");
        }
        return scope;
    }

    /* ==================== Webhook 推送引擎 ==================== */

    /**
     * 监听病历领域事件 → 匹配归档生命周期事件 → 经代理转异步推送。
     * 同步段仅做过滤与载荷组装(此时处于发布线程, 租户上下文可用), 异常一律吞掉不外泄。
     */
    @EventListener
    public void onEmrEvent(EmrEvent event) {
        String eventName = null;
        try {
            if (event == null || event.getEventType() == null || !WEBHOOK_EVENTS.contains(event.getEventType())) {
                return;
            }
            eventName = event.getEventName();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventType", eventName);
            payload.put("displayName", event.getEventType().getDisplayName());
            payload.put("timestamp", event.getTimestamp());
            payload.put("data", event.getData());
            selfProvider.getObject().pushEventAsync(eventName, payload, TenantContext.get());
        } catch (Exception e) {
            log.error("Webhook 事件监听异常(不外泄): type={}, err={}", eventName, e.getMessage(), e);
        }
    }

    /** 异步投递入口(@Async 经代理调用): 恢复租户上下文后执行推送, 失败仅记日志。 */
    @Async
    public void pushEventAsync(String eventType, Map<String, Object> payload, Long tenantId) {
        Long prev = TenantContext.get();
        try {
            if (tenantId != null) {
                TenantContext.set(tenantId);
            }
            pushEvent(eventType, payload);
        } catch (Exception e) {
            log.error("Webhook 异步推送失败: type={}, err={}", eventType, e.getMessage(), e);
        } finally {
            if (prev == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(prev);
            }
        }
    }

    /**
     * 推送事件给当前租户全部匹配订阅(同步版, 供异步入口与外部调用):
     * 匹配口径 = status=1 且 event_types 逗号分隔 token 精确等于 eventType(避免 LIKE 误匹配);
     * 逐条独立 try-catch, 单订阅失败不阻断, 返回投递成功条数。
     */
    public int pushEvent(String eventType, Map<String, Object> payload) {
        if (!StringUtils.hasText(eventType)) {
            return 0;
        }
        List<HisEmrWebhookSubscription> subs = webhookMapper.selectList(
                new LambdaQueryWrapper<HisEmrWebhookSubscription>()
                        .eq(HisEmrWebhookSubscription::getStatus, 1)
                        .orderByAsc(HisEmrWebhookSubscription::getId));
        int ok = 0;
        int matched = 0;
        for (HisEmrWebhookSubscription sub : subs) {
            if (!matchesEvent(sub, eventType)) {
                continue;
            }
            matched++;
            try {
                if (deliver(sub, eventType, payload)) {
                    ok++;
                }
            } catch (Exception e) {
                log.warn("Webhook 投递异常(不阻断): subId={}, url={}, err={}",
                        sub.getId(), sub.getCallbackUrl(), e.getMessage());
            }
        }
        if (matched > 0) {
            log.info("Webhook 推送完成: type={}, 匹配订阅={}, 投递成功={}", eventType, matched, ok);
        }
        return ok;
    }

    /** event_types 逗号分隔 token 精确匹配(内存比对, 规避 SQL LIKE 子串误命中)。 */
    private boolean matchesEvent(HisEmrWebhookSubscription sub, String eventType) {
        if (!StringUtils.hasText(sub.getEventTypes())) {
            return false;
        }
        for (String token : sub.getEventTypes().split(",")) {
            if (eventType.equals(token.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单条投递: 报文 {eventType, displayName, timestamp, data} → HMAC-SHA256 签名头 → POST。
     * 成功: last_push_time=now 且 fail_count 清零; 失败: fail_count+1, 达阈值自动禁用(status=0)。
     * 返回是否成功; HTTP/网络异常在内部消化(测试通道需要结果而非异常)。
     */
    private boolean deliver(HisEmrWebhookSubscription sub, String eventType, Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventType", eventType);
        if (payload != null && payload.containsKey("displayName")) {
            envelope.put("displayName", payload.get("displayName"));
        }
        envelope.put("timestamp", System.currentTimeMillis());
        envelope.put("data", payload == null ? Collections.emptyMap() : payload);
        String body = JSON.toJSONString(envelope);
        long begin = System.currentTimeMillis();
        boolean success = false;
        String error = null;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
            if (StringUtils.hasText(sub.getSecretKey())) {
                headers.set("X-Webhook-Signature", hmacSha256Hex(sub.getSecretKey(), body));
            }
            ResponseEntity<String> resp = restTemplate.postForEntity(
                    sub.getCallbackUrl(), new HttpEntity<>(body, headers), String.class);
            success = resp.getStatusCode().is2xxSuccessful();
            if (!success) {
                error = "HTTP " + resp.getStatusCodeValue();
            }
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        long cost = System.currentTimeMillis() - begin;
        if (success) {
            webhookMapper.update(null, new LambdaUpdateWrapper<HisEmrWebhookSubscription>()
                    .set(HisEmrWebhookSubscription::getLastPushTime, LocalDateTime.now())
                    .set(HisEmrWebhookSubscription::getFailCount, 0)
                    .eq(HisEmrWebhookSubscription::getId, sub.getId()));
        } else {
            int fails = (sub.getFailCount() == null ? 0 : sub.getFailCount()) + 1;
            boolean disable = fails >= FAIL_DISABLE_THRESHOLD;
            LambdaUpdateWrapper<HisEmrWebhookSubscription> uw = new LambdaUpdateWrapper<HisEmrWebhookSubscription>()
                    .set(HisEmrWebhookSubscription::getFailCount, fails)
                    .eq(HisEmrWebhookSubscription::getId, sub.getId());
            if (disable) {
                uw.set(HisEmrWebhookSubscription::getStatus, 0);
            }
            webhookMapper.update(null, uw);
            if (disable) {
                log.warn("Webhook 连续失败{}次, 自动禁用: subId={}, url={}", fails, sub.getId(), sub.getCallbackUrl());
            }
        }
        log.info("Webhook 投递: subId={}, type={}, 成功={}, 耗时={}ms, err={}",
                sub.getId(), eventType, success, cost, error);
        return success;
    }

    /** HMAC-SHA256 十六进制签名(Java 8 标准库, DatatypeConverter 输出大写 HEX)。 */
    private String hmacSha256Hex(String secretKey, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return DatatypeConverter.printHexBinary(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BizException("Webhook 签名失败: " + e.getMessage());
        }
    }

    /* ==================== Webhook 订阅 CRUD ==================== */

    /** 订阅列表(机构范围收口: 牵头/超管全量, 非牵头仅本机构); id 倒序。 */
    public List<HisEmrWebhookSubscription> listWebhooks() {
        Long scope = readScopeOrg();
        return webhookMapper.selectList(new LambdaQueryWrapper<HisEmrWebhookSubscription>()
                .eq(scope != null, HisEmrWebhookSubscription::getOrgId, scope)
                .orderByDesc(HisEmrWebhookSubscription::getId));
    }

    /** 新建订阅: 校验名称/回调地址/事件类型; org_id 机构范围落库(牵头可代建, 非牵头锁定本机构);
     *  密钥缺省自动生成; status/fail_count 缺省 1/0。 */
    public HisEmrWebhookSubscription createWebhook(HisEmrWebhookSubscription sub) {
        if (sub == null || !StringUtils.hasText(sub.getSubscriberName())) {
            throw new BizException(400, "订阅方名称不能为空");
        }
        if (!StringUtils.hasText(sub.getCallbackUrl())) {
            throw new BizException(400, "回调地址不能为空");
        }
        validateEventTypes(sub.getEventTypes());
        Long org = guard.scopeOrgId(sub.getOrgId());
        if (org == null) {
            org = guard.currentOrgId();
        }
        sub.setOrgId(org);
        sub.setSubscriberName(sub.getSubscriberName().trim());
        sub.setCallbackUrl(sub.getCallbackUrl().trim());
        sub.setEventTypes(sub.getEventTypes().trim());
        if (sub.getStatus() == null) {
            sub.setStatus(1);
        }
        if (sub.getFailCount() == null) {
            sub.setFailCount(0);
        }
        if (!StringUtils.hasText(sub.getSecretKey())) {
            sub.setSecretKey(UUID.randomUUID().toString().replace("-", ""));
        }
        webhookMapper.insert(sub);
        log.info("Webhook 订阅创建: id={}, subscriber={}, org={}", sub.getId(), sub.getSubscriberName(), org);
        return sub;
    }

    /** 更新订阅(存在 + 机构归属校验); 仅覆写入参提供的字段, last_push_time/fail_count 由投递引擎维护。 */
    public HisEmrWebhookSubscription updateWebhook(Long id, HisEmrWebhookSubscription in) {
        HisEmrWebhookSubscription existing = requireWebhook(id);
        if (in == null) {
            throw new BizException(400, "请求体不能为空");
        }
        if (StringUtils.hasText(in.getSubscriberName())) {
            existing.setSubscriberName(in.getSubscriberName().trim());
        }
        if (StringUtils.hasText(in.getCallbackUrl())) {
            existing.setCallbackUrl(in.getCallbackUrl().trim());
        }
        if (in.getEventTypes() != null) {
            validateEventTypes(in.getEventTypes());
            existing.setEventTypes(in.getEventTypes().trim());
        }
        if (StringUtils.hasText(in.getSecretKey())) {
            existing.setSecretKey(in.getSecretKey().trim());
        }
        if (in.getStatus() != null) {
            existing.setStatus(in.getStatus());
        }
        webhookMapper.updateById(existing);
        log.info("Webhook 订阅更新: id={}", id);
        return existing;
    }

    /** 删除订阅(逻辑删除, 存在 + 机构归属校验)。 */
    public void deleteWebhook(Long id) {
        requireWebhook(id);
        webhookMapper.deleteById(id);
        log.info("Webhook 订阅删除: id={}", id);
    }

    /**
     * 连通性测试(同步, 供前端即时反馈): 投递 eventType=TEST 的测试报文(不校验订阅事件类型),
     * 失败计数照常累计(真实反映链路健康); 返回 {success, error, callbackUrl, failCount, status, elapsedMs}。
     */
    public Map<String, Object> testWebhook(Long id) {
        HisEmrWebhookSubscription sub = requireWebhook(id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", "yb-interface Webhook 连通性测试");
        payload.put("webhookId", sub.getId());
        payload.put("subscriberName", sub.getSubscriberName());
        long begin = System.currentTimeMillis();
        boolean success;
        try {
            success = deliver(sub, "TEST", payload);
        } catch (Exception e) {
            log.warn("Webhook 测试投递异常: subId={}, err={}", sub.getId(), e.getMessage());
            success = false;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("callbackUrl", sub.getCallbackUrl());
        result.put("elapsedMs", System.currentTimeMillis() - begin);
        HisEmrWebhookSubscription fresh = webhookMapper.selectById(id);
        if (fresh != null) {
            result.put("failCount", fresh.getFailCount());
            result.put("status", fresh.getStatus());
        }
        return result;
    }

    /** 可订阅事件类型清单(供前端下拉): [{name, displayName}]。 */
    public List<Map<String, Object>> eventTypes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EmrEventType t : EmrEventType.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", t.name());
            row.put("displayName", t.getDisplayName());
            out.add(row);
        }
        return out;
    }

    /** 存在 + 机构归属校验: 牵头机构/平台超管可跨机构管理, 其余仅本机构(403)。 */
    private HisEmrWebhookSubscription requireWebhook(Long id) {
        if (id == null) {
            throw new BizException(400, "id 不能为空");
        }
        HisEmrWebhookSubscription sub = webhookMapper.selectById(id);
        if (sub == null) {
            throw new BizException(404, "Webhook 订阅不存在: " + id);
        }
        LoginUser lu = UserContext.get();
        boolean privileged = guard.isLead() || (lu != null && lu.hasRole(Roles.SUPER_ADMIN));
        if (!privileged && sub.getOrgId() != null) {
            Long cur = guard.currentOrgId();
            if (!sub.getOrgId().equals(cur)) {
                throw new BizException(403, "无权操作其他机构的 Webhook 订阅");
            }
        }
        return sub;
    }

    /** 事件类型合法性校验(逗号分隔 token 须为 EmrEventType 枚举名)。 */
    private void validateEventTypes(String eventTypes) {
        if (!StringUtils.hasText(eventTypes)) {
            throw new BizException(400, "订阅事件类型不能为空");
        }
        List<String> known = new ArrayList<>();
        for (EmrEventType t : EmrEventType.values()) {
            known.add(t.name());
        }
        for (String token : eventTypes.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (!known.contains(t)) {
                throw new BizException(400, "未知的病历事件类型: " + t + ", 可选: " + String.join("/", known));
            }
        }
    }
}

