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
