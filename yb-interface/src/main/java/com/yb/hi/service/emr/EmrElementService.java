package com.yb.hi.service.emr;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.entity.inpatient.HisEmrElement;
import com.yb.hi.entity.inpatient.HisEmrTemplate;
import com.yb.hi.entity.inpatient.HisInpMedicalRecord;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.mapper.doctor.HisVisitMapper;
import com.yb.hi.mapper.inpatient.HisEmrElementMapper;
import com.yb.hi.mapper.inpatient.HisEmrTemplateMapper;
import com.yb.hi.mapper.inpatient.HisInpMedicalRecordMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.emr.EmrDocumentService.EmrFieldValue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 病历数据元(要素)抽取服务: 把结构化病历 his_visit.structure / his_inp_medical_record.structure_data
 * 按模板 fields 抽取为字段级 his_emr_element 记录(值分列 text/num/date + term_code), 支撑字段检索/统计/
 * 病案首页透视/上报数据集与逻辑性·规范性质控。
 *
 * 幂等: 同步以 (scope, recordId/visitId) 为粒度先删后插; 同字段多值(multiselect/checkbox/table)以 sort_no 保序。
 * 挂钩: 门诊 saveDraft/doFinishTx 调 {@link #syncOutpVisit}; 住院 createFromTemplate/create/update/submit 调 {@link #syncInpRecord}。
 * 双轨: Tiptap 结构化编辑器(EmrDocumentService 抽取 emrField)经 {@link #syncFromTiptap} 按文档全量替换同步。
 * 尽力而为: 单份同步异常不影响病历主流程(调用方 try/catch 或直接吞掉, 本服务内部也不抛业务异常)。
 */
@Slf4j
@Service
public class EmrElementService {

    private static final DateTimeFormatter DT_FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DT_MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final HisEmrElementMapper elementMapper;
    private final HisEmrTemplateMapper templateMapper;
    private final HisInpMedicalRecordMapper inpRecordMapper;
    private final HisInpVisitMapper inpVisitMapper;
    private final HisVisitMapper visitMapper;
    private final OrgAccessGuard guard;

    public EmrElementService(HisEmrElementMapper elementMapper,
                             HisEmrTemplateMapper templateMapper,
                             HisInpMedicalRecordMapper inpRecordMapper,
                             HisInpVisitMapper inpVisitMapper,
                             HisVisitMapper visitMapper,
                             OrgAccessGuard guard) {
        this.elementMapper = elementMapper;
        this.templateMapper = templateMapper;
        this.inpRecordMapper = inpRecordMapper;
        this.inpVisitMapper = inpVisitMapper;
        this.visitMapper = visitMapper;
        this.guard = guard;
    }

    /* ================= 对外同步入口 ================= */

    /** 门诊: 按 visitId 从 his_visit.structure + 模板 fields 同步数据元(scope=2, record_id 空) */
    public void syncOutpVisit(Long visitId) {
        if (visitId == null) {
            return;
        }
        HisVisit v = visitMapper.selectById(visitId);
        if (v == null || v.getEmrTemplateId() == null || !StringUtils.hasText(v.getStructure())) {
            return; // 无模板/无结构(历史纯文本病历), 不产数据元
        }
        doSync(2, resolveOrgId(null), null, v.getId(), v.getPatientId(), v.getDeptId(), v.getStaffId(),
                null, v.getEmrTemplateId(), v.getStructure());
    }

    /** 住院: 按 recordId 从 his_inp_medical_record.structure_data + 模板 fields 同步数据元(scope=1) */
    public void syncInpRecord(Long recordId) {
        if (recordId == null) {
            return;
        }
        HisInpMedicalRecord rec = inpRecordMapper.selectById(recordId);
        if (rec == null || rec.getTemplateId() == null || !StringUtils.hasText(rec.getStructureData())) {
            return;
        }
        HisInpVisit visit = rec.getInpVisitId() == null ? null : inpVisitMapper.selectById(rec.getInpVisitId());
        Long patientId = visit == null ? null : visit.getPatientId();
        Long deptId = visit == null ? null : visit.getDeptId();
        Long doctorId = rec.getDoctorId() != null ? rec.getDoctorId() : (visit == null ? null : visit.getDoctorId());
        doSync(1, resolveOrgId(visit == null ? null : visit.getOrgId()), rec.getId(),
                rec.getInpVisitId(), patientId, deptId, doctorId,
                rec.getRecordType(), rec.getTemplateId(), rec.getStructureData());
    }

    /* ================= Tiptap 结构化文档同步(双轨存储·字段轨) ================= */

    /** 便捷重载: 直接传 Tiptap/ProseMirror JSON(内部经 EmrDocumentService 抽取 emrField 后同步); 空 JSON 仅告警跳过 */
    public void syncFromTiptap(int scope, Long refId, String tiptapJson) {
        if (!StringUtils.hasText(tiptapJson)) {
            log.warn("病历数据元(Tiptap)同步跳过(JSON 为空): scope={}, refId={}", scope, refId);
            return;
        }
        syncFromTiptap(scope, refId, EmrDocumentService.parseTiptapFields(tiptapJson));
    }

    /**
     * Tiptap 抽取要素全量替换同步(先删后插, 幂等):
     *  删除 scope=1 按 record_id / scope=2 按 visit_id 的旧行, 再插入本次全部要素行(sort_no 按文档序递增)。
     * 上下文(机构/患者/科室/医生/recordType/templateId)自病历/就诊实体解析, org_id 缺失时跳过。
     * 尽力而为: 参数非法/要素为 null 仅告警跳过; 异常不影响病历主流程。
     *
     * @param scope  1住院(refId=his_inp_medical_record.id) 2门诊(refId=his_visit.id)
     * @param refId  住院病历ID / 门诊就诊ID
     * @param fields 抽取要素(null=数据缺失不删旧; 空列表=清空该文档要素)
     */
    public void syncFromTiptap(int scope, Long refId, List<EmrFieldValue> fields) {
        if (refId == null || (scope != 1 && scope != 2)) {
            log.warn("病历数据元(Tiptap)同步跳过(参数非法): scope={}, refId={}", scope, refId);
            return;
        }
        if (fields == null) {
            log.warn("病历数据元(Tiptap)同步跳过(要素为 null, 不删旧): scope={}, refId={}", scope, refId);
            return;
        }
        try {
            // 先删: 按 (scope, record_id)=住院 / (scope, visit_id)=门诊 幂等清旧
            elementMapper.delete(Wrappers.<HisEmrElement>lambdaQuery()
                    .eq(HisEmrElement::getScope, scope)
                    .eq(scope == 1, HisEmrElement::getRecordId, refId)
                    .eq(scope == 2, HisEmrElement::getVisitId, refId));
            if (fields.isEmpty()) {
                return;
            }
            SyncContext ctx = resolveSyncContext(scope, refId);
            if (ctx.orgId == null) {
                log.warn("病历数据元(Tiptap)同步跳过(org_id 缺失): scope={}, refId={}", scope, refId);
                return;
            }
            int sortNo = 0;
            for (EmrFieldValue f : fields) {
                if (f == null || !StringUtils.hasText(f.getFieldKey())) {
                    continue;
                }
                HisEmrElement e = new HisEmrElement();
                e.setScope(scope);
                e.setOrgId(ctx.orgId);
                e.setRecordId(scope == 1 ? refId : null);
                e.setVisitId(ctx.visitId);
                e.setPatientId(ctx.patientId);
                e.setDeptId(ctx.deptId);
                e.setDoctorId(ctx.doctorId);
                e.setRecordType(ctx.recordType);
                e.setTemplateId(ctx.templateId);
                e.setFieldKey(f.getFieldKey().trim());
                e.setFieldLabel(f.getFieldLabel());
                e.setTermCode(f.getTermCode());
                e.setDictSource(f.getDictSource());
                e.setValueText(f.getValueText());
                e.setValueNum(f.getValueNum());
                e.setValueDate(f.getValueDate());
                if (isBlankElement(e)) {
                    continue;
                }
                e.setSortNo(sortNo++);
                elementMapper.insert(e);
            }
            log.info("病历数据元(Tiptap)同步完成: scope={}, refId={}, 要素{}条", scope, refId, sortNo);
        } catch (Exception ex) {
            log.warn("病历数据元(Tiptap)同步失败(不影响主流程): scope={}, refId={}, err={}",
                    scope, refId, ex.getMessage());
        }
    }

    /* ================= 内部实现 ================= */

    private void doSync(int scope, Long orgId, Long recordId, Long visitId, Long patientId, Long deptId,
                        Long doctorId, Integer recordType, Long templateId, String structureJson) {
        if (orgId == null) {
            log.warn("病历数据元同步跳过(org_id 缺失): scope={}, visitId={}, recordId={}", scope, visitId, recordId);
            return;
        }
        try {
            // 先删: 按 (scope, record_id!=null?record_id:visit_id) 幂等清旧
            elementMapper.delete(Wrappers.<HisEmrElement>lambdaQuery()
                    .eq(HisEmrElement::getScope, scope)
                    .eq(recordId != null, HisEmrElement::getRecordId, recordId)
                    .eq(recordId == null, HisEmrElement::getVisitId, visitId));
            if (!StringUtils.hasText(structureJson)) {
                return;
            }
            HisEmrTemplate tpl = templateMapper.selectById(templateId);
            JSONArray fields = tpl == null ? new JSONArray() : parseArraySafe(tpl.getFields());
            JSONObject structure = parseObjectSafe(structureJson);
            if (structure.isEmpty()) {
                return;
            }
            List<HisEmrElement> rows = new ArrayList<>();
            for (int i = 0; i < fields.size(); i++) {
                JSONObject f = fields.getJSONObject(i);
                if (f == null) {
                    continue;
                }
                String key = str(f.get("fieldKey"));
                if (!StringUtils.hasText(key)) {
                    continue;
                }
                String type = str(f.get("type"));
                if (!StringUtils.hasText(type)) {
                    type = "text";
                }
                if ("section".equals(type) || "signature".equals(type)) {
                    continue; // 分节符无值; 签名为 base64 大图不入要素表
                }
                String label = str(f.get("label"));
                String dictSource = resolveDictSource(f, type);
                Object val = structure.get(key);
                extractInto(rows, scope, orgId, recordId, visitId, patientId, deptId, doctorId,
                        recordType, templateId, key, label, type, dictSource, val);
            }
            for (HisEmrElement e : rows) {
                elementMapper.insert(e);
            }
            log.info("病历数据元同步完成: scope={}, visitId={}, recordId={}, 要素{}条",
                    scope, visitId, recordId, rows.size());
        } catch (Exception ex) {
            log.warn("病历数据元同步失败(不影响主流程): scope={}, visitId={}, recordId={}, err={}",
                    scope, visitId, recordId, ex.getMessage());
        }
    }

    /** 按字段类型/值形态抽取为 1..n 行要素(数组多值以 sort_no 保序) */
    private void extractInto(List<HisEmrElement> rows, int scope, Long orgId, Long recordId, Long visitId,
                             Long patientId, Long deptId, Long doctorId, Integer recordType, Long templateId,
                             String key, String label, String type, String dictSource, Object val) {
        if (val == null) {
            return;
        }
        if (val instanceof JSONArray) {
            JSONArray arr = (JSONArray) val;
            for (int i = 0; i < arr.size(); i++) {
                addOne(rows, scope, orgId, recordId, visitId, patientId, deptId, doctorId, recordType, templateId,
                        key, label, type, dictSource, arr.get(i), i);
            }
        } else {
            addOne(rows, scope, orgId, recordId, visitId, patientId, deptId, doctorId, recordType, templateId,
                    key, label, type, dictSource, val, 0);
        }
    }

    private void addOne(List<HisEmrElement> rows, int scope, Long orgId, Long recordId, Long visitId,
                        Long patientId, Long deptId, Long doctorId, Integer recordType, Long templateId,
                        String key, String label, String type, String dictSource, Object v, int sortNo) {
        if (v == null) {
            return;
        }
        HisEmrElement e = new HisEmrElement();
        e.setScope(scope);
        e.setOrgId(orgId);
        e.setRecordId(recordId);
        e.setVisitId(visitId);
        e.setPatientId(patientId);
        e.setDeptId(deptId);
        e.setDoctorId(doctorId);
        e.setRecordType(recordType);
        e.setTemplateId(templateId);
        e.setFieldKey(key);
        e.setFieldLabel(label);
        e.setSortNo(sortNo);

        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            String code = firstStr(o, "code", "value", "termCode");
            String name = firstStr(o, "name", "label", "text", "diagName", "itemName");
            if (StringUtils.hasText(code) || StringUtils.hasText(name)) {
                e.setTermCode(code);
                e.setValueText(StringUtils.hasText(name) ? name : code);
                e.setDictSource(dictSource);
            } else {
                // 无 code/name 形态(如 table 行对象): 原样 JSON 落 value_text
                e.setValueText(truncate(o.toJSONString()));
            }
        } else {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) {
                return; // 空值不产要素
            }
            switch (type) {
                case "number":
                    BigDecimal num = toBigDecimal(s);
                    if (num != null) {
                        e.setValueNum(num);
                    }
                    e.setValueText(truncate(s));
                    break;
                case "date":
                    LocalDate d = parseDate(s);
                    if (d != null) {
                        e.setValueDate(d.atStartOfDay());
                    }
                    e.setValueText(truncate(s));
                    break;
                case "datetime":
                    LocalDateTime dt = parseDateTime(s);
                    if (dt != null) {
                        e.setValueDate(dt);
                    }
                    e.setValueText(truncate(s));
                    break;
                default:
                    e.setValueText(truncate(s));
                    if (StringUtils.hasText(dictSource)) {
                        e.setDictSource(dictSource);
                    }
                    break;
            }
        }
        if (isBlankElement(e)) {
            return;
        }
        rows.add(e);
    }

    /** 值域来源: field.dictRef.source 优先; diagnosis→diag, catalog→charge 兜底 */
    private String resolveDictSource(JSONObject f, String type) {
        JSONObject ref = f.getJSONObject("dictRef");
        if (ref != null && StringUtils.hasText(ref.getString("source"))) {
            return ref.getString("source").trim();
        }
        if ("diagnosis".equals(type)) {
            return "diag";
        }
        if ("catalog".equals(type)) {
            return "charge";
        }
        return null;
    }

    private Long resolveOrgId(Long fromEntity) {
        if (fromEntity != null) {
            return fromEntity;
        }
        try {
            return guard.currentOrgId();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isBlankElement(HisEmrElement e) {
        return !StringUtils.hasText(e.getValueText()) && e.getValueNum() == null
                && e.getValueDate() == null && !StringUtils.hasText(e.getTermCode());
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    private BigDecimal toBigDecimal(String s) {
        try {
            return new BigDecimal(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate parseDate(String s) {
        try {
            String v = s.trim();
            if (v.length() >= 10) {
                v = v.substring(0, 10);
            }
            return LocalDate.parse(v);
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime parseDateTime(String s) {
        String v = s.trim().replace('T', ' ');
        try {
            if (v.length() >= 19) {
                return LocalDateTime.parse(v.substring(0, 19), DT_FULL);
            }
            if (v.length() >= 16) {
                return LocalDateTime.parse(v.substring(0, 16), DT_MIN);
            }
            return LocalDateTime.parse(v, DT_FULL);
        } catch (Exception e) {
            LocalDate d = parseDate(s);
            return d == null ? null : d.atStartOfDay();
        }
    }

    private String firstStr(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.get(k);
            if (v != null && !(v instanceof JSONObject) && !(v instanceof JSONArray)) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    /** Tiptap 同步上下文: 自病历/就诊实体解析机构与归属信息, org 回退当前登录机构 */
    private SyncContext resolveSyncContext(int scope, Long refId) {
        SyncContext c = new SyncContext();
        c.visitId = scope == 2 ? refId : null;
        try {
            if (scope == 1) {
                HisInpMedicalRecord rec = inpRecordMapper.selectById(refId);
                if (rec != null) {
                    c.orgId = rec.getOrgId();
                    c.visitId = rec.getInpVisitId();
                    c.recordType = rec.getRecordType();
                    c.templateId = rec.getTemplateId();
                    c.doctorId = rec.getDoctorId();
                    if (rec.getInpVisitId() != null) {
                        HisInpVisit v = inpVisitMapper.selectById(rec.getInpVisitId());
                        if (v != null) {
                            if (c.orgId == null) {
                                c.orgId = v.getOrgId();
                            }
                            c.patientId = v.getPatientId();
                            c.deptId = v.getDeptId();
                            if (c.doctorId == null) {
                                c.doctorId = v.getDoctorId();
                            }
                        }
                    }
                }
            } else {
                HisVisit v = visitMapper.selectById(refId);
                if (v != null) {
                    c.patientId = v.getPatientId();
                    c.deptId = v.getDeptId();
                    c.doctorId = v.getStaffId();
                    c.templateId = v.getEmrTemplateId();
                }
            }
        } catch (Exception e) {
            log.warn("Tiptap数据元同步上下文解析失败: scope={}, refId={}, err={}", scope, refId, e.getMessage());
        }
        c.orgId = resolveOrgId(c.orgId);
        return c;
    }

    /** Tiptap 同步上下文(org/visit/patient/dept/doctor/recordType/templateId) */
    private static class SyncContext {
        Long orgId;
        Long visitId;
        Long patientId;
        Long deptId;
        Long doctorId;
        Integer recordType;
        Long templateId;
    }

    private JSONArray parseArraySafe(String json) {
        if (!StringUtils.hasText(json)) {
            return new JSONArray();
        }
        try {
            JSONArray a = JSON.parseArray(json);
            return a == null ? new JSONArray() : a;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private JSONObject parseObjectSafe(String json) {
        if (!StringUtils.hasText(json)) {
            return new JSONObject();
        }
        try {
            JSONObject o = JSON.parseObject(json);
            return o == null ? new JSONObject() : o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
