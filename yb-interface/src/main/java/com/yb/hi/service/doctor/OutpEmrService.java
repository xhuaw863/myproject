package com.yb.hi.service.doctor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.entity.doctor.HisDiagnosis;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.entity.inpatient.HisEmrMacro;
import com.yb.hi.entity.inpatient.HisEmrTemplate;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.mapper.basedata.HisDeptMapper;
import com.yb.hi.mapper.basedata.HisStaffMapper;
import com.yb.hi.mapper.doctor.HisVisitMapper;
import com.yb.hi.mapper.inpatient.HisEmrMacroMapper;
import com.yb.hi.mapper.inpatient.HisEmrTemplateMapper;
import com.yb.hi.mapper.outpatient.HisPatientMapper;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 门诊结构化病历引擎服务(Phase B): 复用住院侧 EMR 引擎(his_emr_template scope=2 / EmrField / his_emr_version scope=2),
 * 提供门诊专用的两项后端能力——
 * 1) 完整性质控: 依据模板 fields 中 required 字段(跳过 section)对 structure 取值逐字段判定, 输出 0~100 完整性评分与缺失清单;
 *    轻量即时计算, 不污染住院 his_emr_quality_rule 体系, 也不落住院病历表。
 * 2) 门诊宏解析: 以患者/就诊/诊断三源为主(patient_name/gender/age/id_card/dept_name/doctor_name/visit_date/
 *    main_diag/allergy_info/current_date/current_time/hospital_name), 自定义宏按 dataSource+sourceField 反射回落;
 *    住院特有的医嘱/检验/体征源在门诊返回空串。
 * 就诊归属判权由控制层复用 HisVisitService.requireVisitScope, 本服务只负责按已校验的 visitId 取数。
 */
@Slf4j
@Service
public class OutpEmrService {

    private final HisVisitMapper visitMapper;
    private final HisEmrTemplateMapper templateMapper;
    private final HisPatientMapper patientMapper;
    private final HisStaffMapper staffMapper;
    private final HisDeptMapper deptMapper;
    private final HisEmrMacroMapper macroMapper;
    private final HisDiagnosisService diagnosisService;

    public OutpEmrService(HisVisitMapper visitMapper, HisEmrTemplateMapper templateMapper,
                          HisPatientMapper patientMapper, HisStaffMapper staffMapper,
                          HisDeptMapper deptMapper, HisEmrMacroMapper macroMapper,
                          HisDiagnosisService diagnosisService) {
        this.visitMapper = visitMapper;
        this.templateMapper = templateMapper;
        this.patientMapper = patientMapper;
        this.staffMapper = staffMapper;
        this.deptMapper = deptMapper;
        this.macroMapper = macroMapper;
        this.diagnosisService = diagnosisService;
    }

    /* ================= 完整性质控 ================= */

    /**
     * 门诊结构化病历完整性评分: 逐 required 字段(跳过 section 分节符)判定 structure 是否填值。
     * 返回 {visitId, hasStructure, templateId, totalRequired, filledRequired, score, missing:[{fieldKey,label}]}。
     * score = filledRequired / totalRequired * 100 (无 required 字段视为 100)。
     */
    public Map<String, Object> evaluateCompleteness(Long visitId) {
        HisVisit v = visitId == null ? null : visitMapper.selectById(visitId);
        if (v == null) {
            throw new BizException(400, "就诊记录不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("visitId", visitId);
        result.put("templateId", v.getEmrTemplateId());
        if (v.getEmrTemplateId() == null || !StringUtils.hasText(v.getStructure())) {
            result.put("hasStructure", false);
            result.put("totalRequired", 0);
            result.put("filledRequired", 0);
            result.put("score", null);
            result.put("missing", new ArrayList<>());
            return result;
        }
        result.put("hasStructure", true);
        HisEmrTemplate tpl = templateMapper.selectById(v.getEmrTemplateId());
        JSONArray fields = tpl == null ? new JSONArray() : parseArraySafe(tpl.getFields());
        JSONObject structure = parseObjectSafe(v.getStructure());
        int totalRequired = 0;
        int filledRequired = 0;
        List<Map<String, Object>> missing = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            JSONObject f = fields.getJSONObject(i);
            if (f == null) {
                continue;
            }
            String type = text(f.get("type"));
            if ("section".equals(type)) {
                continue; // 分节符无取值, 不参与完整性
            }
            if (!Boolean.TRUE.equals(asBoolean(f.get("required")))) {
                continue;
            }
            totalRequired++;
            String key = text(f.get("fieldKey"));
            if (isFilled(structure.get(key))) {
                filledRequired++;
            } else {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("fieldKey", key);
                m.put("label", text(f.get("label")));
                missing.add(m);
            }
        }
        BigDecimal score = totalRequired == 0
                ? new BigDecimal("100")
                : BigDecimal.valueOf(filledRequired * 100L)
                        .divide(BigDecimal.valueOf(totalRequired), 0, RoundingMode.HALF_UP);
        result.put("totalRequired", totalRequired);
        result.put("filledRequired", filledRequired);
        result.put("score", score);
        result.put("missing", missing);
        return result;
    }

    /* ================= 门诊宏解析 ================= */

    /** 批量解析门诊宏变量: macroCodes 为空则解析全部启用宏; 返回 {macroCode: 值}。 */
    public R<Map<String, String>> resolveMacros(Long visitId, List<String> macroCodes) {
        HisVisit v = visitId == null ? null : visitMapper.selectById(visitId);
        if (v == null) {
            throw new BizException(400, "就诊记录不存在");
        }
        List<HisEmrMacro> macros;
        if (macroCodes == null || macroCodes.isEmpty()) {
            macros = macroMapper.selectList(Wrappers.<HisEmrMacro>lambdaQuery()
                    .orderByAsc(HisEmrMacro::getDataSource)
                    .orderByAsc(HisEmrMacro::getId));
        } else {
            macros = macroMapper.selectList(Wrappers.<HisEmrMacro>lambdaQuery()
                    .in(HisEmrMacro::getMacroCode, macroCodes));
        }
        Ctx ctx = new Ctx(v);
        Map<String, String> result = new LinkedHashMap<>();
        for (HisEmrMacro m : macros) {
            result.put(m.getMacroCode(), resolveOne(m, ctx));
        }
        return R.ok(result);
    }

    /** 单宏解析: 门诊已知编码直取, 其余按 dataSource(1患者/2就诊/3诊断) 反射回落; 住院特有源返回空。 */
    private String resolveOne(HisEmrMacro m, Ctx c) {
        String code = m.getMacroCode();
        if (!StringUtils.hasText(code)) {
            return "";
        }
        HisVisit v = c.visit;
        switch (code) {
            case "patient_name":
                return StringUtils.hasText(v.getPatientName()) ? v.getPatientName() : c.patientName();
            case "gender":
                if (c.patient != null && StringUtils.hasText(c.patient.getGenderName())) {
                    return c.patient.getGenderName();
                }
                return text(v.getGender());
            case "age":
                if (c.patient != null && c.patient.getAge() != null) {
                    return String.valueOf(c.patient.getAge());
                }
                return v.getAge() == null ? "" : String.valueOf(v.getAge());
            case "id_card":
                return c.patient == null ? "" : text(c.patient.getIdCard());
            case "dept_name":
                return StringUtils.hasText(v.getDeptName()) ? v.getDeptName() : c.deptName();
            case "doctor_name":
            case "attending_doctor":
                return StringUtils.hasText(v.getDrName()) ? v.getDrName() : c.staffName(v.getStaffId());
            case "visit_date":
                return fmtDateTime(v.getVisitTime(), m.getFormatPattern());
            case "main_diag":
            case "admit_diag":
                return c.mainDiag();
            case "allergy_info":
                return StringUtils.hasText(v.getAllergyHistory()) ? v.getAllergyHistory() : "无";
            case "past_history":
                return StringUtils.hasText(v.getPastHistory()) ? v.getPastHistory() : "无";
            case "hospital_name": {
                LoginUser u = UserContext.get();
                return u == null || !StringUtils.hasText(u.getTenantName()) ? "" : u.getTenantName();
            }
            case "current_date":
                return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            case "current_time":
                return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
            case "condition_level":
            case "nursing_level":
            case "diet_type":
            case "bed_no":
            case "ward_name":
            case "inp_no":
            case "inp_days":
            case "surgery_name":
            case "vital_signs":
                return ""; // 住院特有源, 门诊不适用
            default:
                return resolveBySource(m, c);
        }
    }

    /** 自定义宏通用解析: dataSource 1患者 2就诊 3诊断(主诊断兜底), sourceField 反射取值。 */
    private String resolveBySource(HisEmrMacro m, Ctx c) {
        String field = m.getSourceField();
        if (!StringUtils.hasText(field)) {
            return "";
        }
        Integer ds = m.getDataSource();
        Object target = (ds != null && ds == 1) ? c.patient : c.visit;
        Object val = reflect(target, field);
        if (val == null) {
            val = reflect(c.visit, field);
        }
        if (val == null) {
            val = reflect(c.patient, field);
        }
        if (val instanceof LocalDateTime) {
            return fmtDateTime((LocalDateTime) val, m.getFormatPattern());
        }
        if (val instanceof LocalDate) {
            return ((LocalDate) val).format(DateTimeFormatter.ofPattern(
                    StringUtils.hasText(m.getFormatPattern()) ? m.getFormatPattern() : "yyyy-MM-dd"));
        }
        return val == null ? "" : String.valueOf(val);
    }

    private Object reflect(Object target, String field) {
        if (target == null || !StringUtils.hasText(field)) {
            return null;
        }
        String f = field;
        int dot = f.lastIndexOf('.');
        if (dot >= 0) {
            f = f.substring(dot + 1);
        }
        if (f.isEmpty()) {
            return null;
        }
        String getter = "get" + Character.toUpperCase(f.charAt(0)) + f.substring(1);
        try {
            Method mth = target.getClass().getMethod(getter);
            return mth.invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    /* ================= 解析上下文 ================= */

    /** 门诊解析上下文: 就诊 + 懒加载患者/科室名/医师名/主诊断 */
    private class Ctx {
        private final HisVisit visit;
        private HisPatient patient;
        private boolean patientLoaded;
        private String mainDiag;
        private boolean diagLoaded;

        Ctx(HisVisit visit) {
            this.visit = visit;
        }

        HisPatient patient() {
            if (!patientLoaded) {
                patientLoaded = true;
                patient = visit.getPatientId() == null ? null : patientMapper.selectById(visit.getPatientId());
            }
            return patient;
        }

        String patientName() {
            HisPatient p = patient();
            return p == null ? "" : text(p.getName());
        }

        String deptName() {
            if (visit.getDeptId() == null) {
                return "";
            }
            HisDept d = deptMapper.selectById(visit.getDeptId());
            return d == null ? "" : text(d.getDeptName());
        }

        String staffName(Long id) {
            if (id == null) {
                return "";
            }
            HisStaff s = staffMapper.selectById(id);
            return s == null ? "" : text(s.getStaffName());
        }

        String mainDiag() {
            if (!diagLoaded) {
                diagLoaded = true;
                List<HisDiagnosis> list = diagnosisService.listByVisit(visit.getId());
                if (list != null && !list.isEmpty()) {
                    for (HisDiagnosis d : list) {
                        if ("1".equals(d.getMaindiagFlag()) && StringUtils.hasText(d.getDiagName())) {
                            mainDiag = d.getDiagName();
                            break;
                        }
                    }
                    if (!StringUtils.hasText(mainDiag)) {
                        mainDiag = text(list.get(0).getDiagName());
                    }
                }
            }
            return mainDiag == null ? "" : mainDiag;
        }
    }

    /* ================= 工具 ================= */

    /** 字段是否算已填: 非空字符串/非空数组/非空对象/数字0均算; null/空串/空集合算未填 */
    private static boolean isFilled(Object val) {
        if (val == null) {
            return false;
        }
        if (val instanceof String) {
            return StringUtils.hasText((String) val);
        }
        if (val instanceof JSONArray) {
            return !((JSONArray) val).isEmpty();
        }
        if (val instanceof JSONObject) {
            return !((JSONObject) val).isEmpty();
        }
        if (val instanceof Iterable) {
            return ((Iterable<?>) val).iterator().hasNext();
        }
        return StringUtils.hasText(String.valueOf(val));
    }

    private static Boolean asBoolean(Object v) {
        if (v == null) {
            return Boolean.FALSE;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        String s = String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static String fmtDateTime(LocalDateTime dt, String pattern) {
        if (dt == null) {
            return "";
        }
        String p = StringUtils.hasText(pattern) ? pattern : "yyyy-MM-dd HH:mm";
        try {
            return dt.format(DateTimeFormatter.ofPattern(p));
        } catch (Exception e) {
            return dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
    }

    private static JSONArray parseArraySafe(String json) {
        if (!StringUtils.hasText(json)) {
            return new JSONArray();
        }
        try {
            JSONArray arr = JSON.parseArray(json);
            return arr == null ? new JSONArray() : arr;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static JSONObject parseObjectSafe(String json) {
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

    private static String text(Object v) {
        return v == null ? "" : (v instanceof String ? (String) v : String.valueOf(v));
    }
}
