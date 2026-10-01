package com.yb.hi.service.doctor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.yb.hi.entity.doctor.HisVisit;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 门诊病历「结构化正文 → SOAP 视图」读侧派生器(方案 B 收敛, B2 下游直接读 structure)。
 * 单一真源是 his_visit.structure(模板 fields 的 fieldKey→值 JSON); his_visit 上的 SOAP 文本列
 * 仅作历史只读兼容(旧病历), 新结构化病历不再回写这些列, 改由各下游(完成病历 S/O/A/P、医保2203 主诉、
 * 打印、历史列表、详情回显)统一调用本派生器按 fieldKey 对齐取值。
 * 取值优先 structure 中与 SOAP 语义对齐的 fieldKey(chiefComplaint/presentIllness/...), 缺失时回退旧列;
 * 门诊自定义模板可能使用非对齐 key, 故为尽力而为, 完整字段级检索/上报留待 Phase C his_emr_element。
 */
public final class EmrStructureReader {

    private EmrStructureReader() {
    }

    /** 就诊的 SOAP 视图: 有 structure 走派生(缺失项回退旧列), 无 structure 全量回退旧 SOAP 列(历史病历)。 */
    public static Map<String, String> read(HisVisit v) {
        Map<String, String> out = new LinkedHashMap<>();
        if (v == null) {
            return out;
        }
        JSONObject s = parse(v.getStructure());
        out.put("chiefComplaint", pick(s, "chiefComplaint", v.getChiefComplaint()));
        out.put("presentIllness", pick(s, "presentIllness", v.getPresentIllness()));
        out.put("pastHistory", pick(s, "pastHistory", v.getPastHistory()));
        out.put("allergyHistory", pick(s, "allergyHistory", v.getAllergyHistory()));
        out.put("auxExam", pick(s, "auxExam", v.getAuxExam()));
        out.put("treatmentOpinion", pick(s, "treatmentOpinion", v.getTreatmentOpinion()));
        out.put("followupNote", pick(s, "followupNote", v.getFollowupNote()));
        // 体格检查: 结构化可拆为 vitals(生命体征) + physicalExam(查体), 合并成可读文本
        String vitals = s == null ? null : text(s.get("vitals"));
        String peLegacy = v.getPhysicalExam();
        String peStruct = s == null ? null : text(s.get("physicalExam"));
        out.put("vitals", vitals == null ? extractVitalsFromLegacy(peLegacy) : vitals);
        out.put("physicalExam", composePhysicalExam(vitals, peStruct, peLegacy));
        // 诊断: 结构化字段可能为字符串或对象/数组, 取名称拼接; 缺省回退既有诊断(由诊断表维护, 此处仅展示)
        out.put("diagnosis", s == null ? "" : extractDiagnosis(s.get("diagnosis")));
        return out;
    }

    /** 组装门诊病历 S(主观): 主诉/现病史/既往史/过敏史。 */
    public static String subjectiveText(Map<String, String> soap) {
        return joinLabeled("主诉:", get(soap, "chiefComplaint"), "现病史:", get(soap, "presentIllness"),
                "既往史:", get(soap, "pastHistory"), "过敏史:", get(soap, "allergyHistory"));
    }

    /** 组装门诊病历 O(客观): 生命体征/体格检查/辅助检查。 */
    public static String objectiveText(Map<String, String> soap) {
        String vitals = get(soap, "vitals");
        String pe = get(soap, "physicalExam");
        String merged = StringUtils.hasText(vitals)
                ? (StringUtils.hasText(pe) ? vitals + "\n" + pe : vitals)
                : pe;
        return joinLabeled("体格检查:", merged, "辅助检查:", get(soap, "auxExam"));
    }

    /** 门诊病历 P(计划): 处理意见 + 随访。 */
    public static String planText(Map<String, String> soap) {
        return joinLabeled("处理意见:", get(soap, "treatmentOpinion"), "随访建议:", get(soap, "followupNote"));
    }

    /* ================= 内部工具 ================= */

    private static JSONObject parse(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JSONObject o = JSON.parseObject(json);
            return (o == null || o.isEmpty()) ? null : o;
        } catch (Exception e) {
            return null;
        }
    }

    /** structure 有值取 structure, 否则回退 legacy 列。 */
    private static String pick(JSONObject s, String key, String legacy) {
        if (s != null) {
            String v = text(s.get(key));
            if (StringUtils.hasText(v)) {
                return v;
            }
        }
        return legacy == null ? "" : legacy;
    }

    /** 值 → 文本: 标量直接转串; 对象/数组尽力取名称字段。 */
    private static String text(Object val) {
        if (val == null) {
            return "";
        }
        if (val instanceof String) {
            return ((String) val).trim();
        }
        if (val instanceof JSONObject) {
            JSONObject o = (JSONObject) val;
            String n = firstNonEmpty(o, "name", "diagName", "label", "text", "value", "code");
            return n != null ? n : o.toJSONString();
        }
        if (val instanceof JSONArray) {
            JSONArray a = (JSONArray) val;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.size(); i++) {
                String t = text(a.get(i));
                if (StringUtils.hasText(t)) {
                    if (sb.length() > 0) {
                        sb.append("、");
                    }
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        if (val instanceof Iterable) {
            StringBuilder sb = new StringBuilder();
            for (Object o : (Iterable<?>) val) {
                String t = text(o);
                if (StringUtils.hasText(t)) {
                    if (sb.length() > 0) {
                        sb.append("、");
                    }
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        return String.valueOf(val);
    }

    private static String firstNonEmpty(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = text(o.get(k));
            if (StringUtils.hasText(v)) {
                return v;
            }
        }
        return null;
    }

    /** 诊断字段专解: 字符串直接用; 对象/数组取 diagName/name; 兜底空串。 */
    private static String extractDiagnosis(Object val) {
        return text(val);
    }

    /** 从旧 physicalExam 文本里抽出 "T:.. P:.. R:.. BP:.." 生命体征行。 */
    private static String extractVitalsFromLegacy(String legacy) {
        if (!StringUtils.hasText(legacy)) {
            return "";
        }
        String first = legacy.split("\\R", 2)[0].trim();
        return first.matches("(?i).*T[:：].*BP[:：].*") ? first : "";
    }

    /** 组装查体可读文本: 结构化 physicalExam 优先; 无 structure 时回退旧列(已含生命体征行)。 */
    private static String composePhysicalExam(String vitalsStruct, String peStruct, String peLegacy) {
        if (StringUtils.hasText(peStruct)) {
            return peStruct;
        }
        if (StringUtils.hasText(vitalsStruct)) {
            // 结构化仅有生命体征无独立查体字段, 用旧列兜底或返回生命体征文本
            return StringUtils.hasText(peLegacy) ? peLegacy : vitalsStruct;
        }
        return peLegacy == null ? "" : peLegacy;
    }

    private static String joinLabeled(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < parts.length; i += 2) {
            if (StringUtils.hasText(parts[i + 1])) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(parts[i]).append(" ").append(parts[i + 1].trim());
            }
        }
        return sb.toString();
    }

    private static String get(Map<String, String> m, String k) {
        return m == null ? "" : (m.get(k) == null ? "" : m.get(k));
    }
}
