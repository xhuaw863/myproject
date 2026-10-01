package com.yb.hi.service.emr;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.entity.inpatient.HisEmrElement;
import com.yb.hi.entity.inpatient.HisInpMedicalRecord;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.doctor.HisVisitMapper;
import com.yb.hi.mapper.inpatient.HisEmrElementMapper;
import com.yb.hi.mapper.inpatient.HisInpMedicalRecordMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 病历互操作导出服务(Phase D): 把结构化病历(数据元 his_emr_element + 病历/就诊抬头)导出为两种标准格式,
 * 供跨机构/平台互操作与归档, 二者共用同一份抬头与要素读取口径。
 *
 * - FHIR R4: Document {@code Bundle} → {@code Composition}(叙事/小节) + {@code Patient}/{@code Practitioner}
 *   引用 + 每个数据元一条 {@code SimpleObserver}(离散观测, 值按 text/quantity/date 分形态; 术语以院内码占位)。
 * - WS/T 500(CDA): {@code ClinicalDocument}(记录目标/作者/就诊/结构化正文), 每个数据元一个 {@code observation}。
 *
 * 输出均为良构文本(FHIR 走 fastjson2 序列化, CDA 走 DOM+Transformer), 便于 d8 样例校验。
 */
@Slf4j
@Service
public class EmrExportService {

    private static final int INP = 1;
    private static final int OUTP = 2;
    private static final DateTimeFormatter FHIR_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter CDA_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final HisEmrElementMapper elementMapper;
    private final HisInpMedicalRecordMapper inpRecordMapper;
    private final HisInpVisitMapper inpVisitMapper;
    private final HisVisitMapper visitMapper;
    private final JdbcTemplate jdbcTemplate;

    public EmrExportService(HisEmrElementMapper elementMapper,
                            HisInpMedicalRecordMapper inpRecordMapper,
                            HisInpVisitMapper inpVisitMapper,
                            HisVisitMapper visitMapper,
                            JdbcTemplate jdbcTemplate) {
        this.elementMapper = elementMapper;
        this.inpRecordMapper = inpRecordMapper;
        this.inpVisitMapper = inpVisitMapper;
        this.visitMapper = visitMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ==================== 对外入口 ==================== */

    /** 导出为 FHIR R4 Document Bundle(JSON 字符串, PrettyFormat) */
    public String exportFhir(int scope, Long targetId) {
        Ctx c = loadContext(scope, targetId);
        return buildFhirBundle(c);
    }

    /** 导出为 WS/T 500 CDA(XML 字符串, 良构) */
    public String exportCda(int scope, Long targetId) {
        Ctx c = loadContext(scope, targetId);
        return buildCdaDocument(c);
    }

    /* ==================== 上下文装载 ==================== */

    private Ctx loadContext(int scope, Long targetId) {
        if (targetId == null) {
            throw new BizException(400, "病历/就诊ID不能为空");
        }
        if (scope != INP && scope != OUTP) {
            throw new BizException(400, "scope 必须为 1(住院) 或 2(门诊)");
        }
        Ctx c = new Ctx();
        c.scope = scope;
        c.targetId = targetId;
        if (scope == INP) {
            HisInpMedicalRecord rec = inpRecordMapper.selectById(targetId);
            if (rec == null) {
                throw new BizException(404, "住院病历不存在: " + targetId);
            }
            c.title = rec.getTitle();
            c.recordTime = rec.getRecordTime();
            c.doctorId = rec.getDoctorId();
            c.visitId = rec.getInpVisitId();
            if (rec.getInpVisitId() != null) {
                HisInpVisit v = inpVisitMapper.selectById(rec.getInpVisitId());
                if (v != null) {
                    c.patientId = v.getPatientId();
                    c.deptId = v.getDeptId();
                }
            }
            c.docTypeCode = "34117-1";
            c.docTypeText = StringUtils.hasText(rec.getTitle()) ? rec.getTitle() : "住院病历";
            c.patientName = safeLookup("his_patient", c.patientId);
            c.doctorName = safeLookup("his_staff", c.doctorId);
            c.deptName = safeLookup("his_dept", c.deptId);
        } else {
            HisVisit v = visitMapper.selectById(targetId);
            if (v == null) {
                throw new BizException(404, "门诊就诊不存在: " + targetId);
            }
            c.title = "门诊病历";
            c.recordTime = v.getVisitTime() != null ? v.getVisitTime() : LocalDateTime.now();
            c.patientId = v.getPatientId();
            c.patientName = v.getPatientName();
            c.gender = v.getGender();
            c.age = v.getAge();
            c.deptId = v.getDeptId();
            c.deptName = v.getDeptName();
            c.doctorId = v.getStaffId();
            c.doctorName = v.getDrName();
            c.visitId = v.getId();
            c.docTypeCode = "11488-4";
            c.docTypeText = "门诊病历";
        }
        c.elements = elementMapper.selectList(Wrappers.<HisEmrElement>lambdaQuery()
                .eq(HisEmrElement::getScope, scope)
                .eq(INP == scope, HisEmrElement::getRecordId, targetId)
                .eq(OUTP == scope, HisEmrElement::getVisitId, targetId)
                .orderByAsc(HisEmrElement::getFieldKey)
                .orderByAsc(HisEmrElement::getSortNo));
        return c;
    }

    /* ==================== FHIR R4 ==================== */

    private String buildFhirBundle(Ctx c) {
        String nowIso = (c.recordTime == null ? LocalDateTime.now() : c.recordTime).format(FHIR_TS);
        String pid = c.patientId == null ? "unknown" : String.valueOf(c.patientId);
        String docId = "urn:yb:emr:" + (c.scope == INP ? "record" : "visit") + ":" + c.targetId;

        JSONObject bundle = new JSONObject();
        bundle.put("resourceType", "Bundle");
        bundle.put("type", "document");
        JSONObject identifier = new JSONObject();
        identifier.put("use", "urn");
        identifier.put("value", docId);
        bundle.put("identifier", identifier);
        bundle.put("timestamp", nowIso);

        JSONArray entry = new JSONArray();

        // Composition
        JSONObject composition = new JSONObject();
        composition.put("resourceType", "Composition");
        composition.put("status", "final");
        composition.put("id", "composition-" + c.targetId);
        JSONObject compType = new JSONObject();
        JSONArray compCoding = new JSONArray();
        JSONObject typeCoding = new JSONObject();
        typeCoding.put("system", "http://loinc.org");
        typeCoding.put("code", c.docTypeCode);
        compCoding.add(typeCoding);
        compType.put("coding", compCoding);
        compType.put("text", c.docTypeText);
        composition.put("type", compType);
        composition.put("subject", ref("Patient", pid));
        composition.put("date", nowIso);
        composition.put("title", c.title);
        JSONArray author = new JSONArray();
        author.add(ref("Practitioner", c.doctorId == null ? "unknown" : String.valueOf(c.doctorId)));
        composition.put("author", author);
        // section: 汇总所有观测引用
        JSONArray sectionEntryRefs = new JSONArray();
        for (HisEmrElement e : c.elements) {
            sectionEntryRefs.add(ref("Observation", "obs-" + e.getId()));
        }
        JSONObject section = new JSONObject();
        section.put("title", "病历数据元(要素)");
        JSONObject secCode = new JSONObject();
        secCode.put("coding", oneCoding("http://loinc.org", "55109-5", "Discharge note narrative"));
        section.put("code", secCode);
        section.put("entry", sectionEntryRefs);
        JSONArray sections = new JSONArray();
        sections.add(section);
        composition.put("section", sections);
        entry.add(bundleEntry(docId + "/Composition/composition-" + c.targetId, composition));

        // Patient
        JSONObject patient = new JSONObject();
        patient.put("resourceType", "Patient");
        patient.put("id", pid);
        if (StringUtils.hasText(c.patientName)) {
            JSONArray pnames = new JSONArray();
            JSONObject pn = new JSONObject();
            pn.put("text", c.patientName);
            pnames.add(pn);
            patient.put("name", pnames);
        }
        if (StringUtils.hasText(c.gender)) {
            patient.put("gender", mapFhirGender(c.gender));
        }
        if (c.age != null) {
            patient.put("_age", c.age);
        }
        entry.add(bundleEntry(docId + "/Patient/" + pid, patient));

        // Practitioner
        JSONObject practitioner = new JSONObject();
        practitioner.put("resourceType", "Practitioner");
        practitioner.put("id", c.doctorId == null ? "unknown" : String.valueOf(c.doctorId));
        if (StringUtils.hasText(c.doctorName)) {
            JSONArray dnames = new JSONArray();
            JSONObject dn = new JSONObject();
            dn.put("text", c.doctorName);
            dnames.add(dn);
            practitioner.put("name", dnames);
        }
        entry.add(bundleEntry(docId + "/Practitioner/" + practitioner.getString("id"), practitioner));

        // Observations(每个数据元一条离散观测)
        for (HisEmrElement e : c.elements) {
            JSONObject obs = new JSONObject();
            obs.put("resourceType", "Observation");
            obs.put("id", "obs-" + e.getId());
            obs.put("status", "final");
            obs.put("category", oneCoding("http://terminology.hl7.org/CodeSystem/observation-category",
                    "document", "病历数据元"));
            JSONObject code = new JSONObject();
            JSONArray codeCoding = new JSONArray();
            codeCoding.add(fieldCoding("urn:yb:emr:field", e.getFieldKey()));
            if (StringUtils.hasText(e.getTermCode())) {
                codeCoding.add(fieldCoding(StringUtils.hasText(e.getDictSource())
                        ? "urn:yb:emr:term:" + e.getDictSource() : "urn:yb:emr:term", e.getTermCode()));
            }
            code.put("coding", codeCoding);
            code.put("text", StringUtils.hasText(e.getFieldLabel()) ? e.getFieldLabel() : e.getFieldKey());
            obs.put("code", code);
            obs.put("subject", ref("Patient", pid));
            applyObsValue(obs, e);
            entry.add(bundleEntry(docId + "/Observation/obs-" + e.getId(), obs));
        }

        bundle.put("entry", entry);
        return JSON.toJSONString(bundle, JSONWriter.Feature.PrettyFormat);
    }

    /** 按要素值形态落到 Observation 的 value[x] */
    private void applyObsValue(JSONObject obs, HisEmrElement e) {
        if (e.getValueDate() != null) {
            obs.put("valueDateTime", e.getValueDate().format(FHIR_TS));
            return;
        }
        if (e.getValueNum() != null) {
            JSONObject q = new JSONObject();
            q.put("value", e.getValueNum());
            if (StringUtils.hasText(e.getValueUnit())) {
                q.put("unit", e.getValueUnit());
            }
            obs.put("valueQuantity", q);
            return;
        }
        String text = StringUtils.hasText(e.getValueText()) ? e.getValueText()
                : (StringUtils.hasText(e.getTermCode()) ? e.getTermCode() : "");
        obs.put("valueString", text);
    }

    /* ==================== WS/T 500 CDA ==================== */

    private String buildCdaDocument(Ctx c) {
        try {
            DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = db.newDocument();
            doc.setXmlStandalone(true);
            Element root = doc.createElementNS("urn:hl7-org:v3", "ClinicalDocument");
            root.setAttribute("xmlns:v3", "urn:hl7-org:v3");
            root.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
            doc.appendChild(root);

            appendText(doc, root, "realmCode", "CN");
            Element typeId = doc.createElement("typeId");
            typeId.setAttribute("root", "2.16.840.1.113883.1.3");
            typeId.setAttribute("extension", "POCD_HD000040");
            root.appendChild(typeId);

            Element id = doc.createElement("id");
            id.setAttribute("root", "2.16.156.10131.1.1");
            id.setAttribute("extension", "EMR-" + (c.scope == INP ? "IP" : "OP") + "-" + c.targetId);
            root.appendChild(id);

            Element code = doc.createElement("code");
            code.setAttribute("code", c.docTypeCode);
            code.setAttribute("codeSystem", "2.16.840.1.113883.6.1");
            code.setAttribute("displayName", c.docTypeText);
            root.appendChild(code);

            appendText(doc, root, "title", c.title == null ? "" : c.title);
            Element eff = doc.createElement("effectiveTime");
            eff.setAttribute("value", (c.recordTime == null ? LocalDateTime.now() : c.recordTime).format(CDA_TS));
            root.appendChild(eff);
            Element conf = doc.createElement("confidentialityCode");
            conf.setAttribute("code", "N");
            conf.setAttribute("displayName", "normal");
            root.appendChild(conf);
            Element lang = doc.createElement("languageCode");
            lang.setAttribute("code", "zh-CN");
            root.appendChild(lang);

            // recordTarget / patientRole
            Element recordTarget = doc.createElement("recordTarget");
            Element patientRole = doc.createElement("patientRole");
            Element prId = doc.createElement("id");
            prId.setAttribute("root", "2.16.156.10131.1.1");
            prId.setAttribute("extension", c.patientId == null ? "unknown" : String.valueOf(c.patientId));
            patientRole.appendChild(prId);
            Element patient = doc.createElement("patient");
            Element pNameEl = doc.createElement("name");
            pNameEl.appendChild(doc.createTextNode(c.patientName == null ? "" : c.patientName));
            patient.appendChild(pNameEl);
            if (StringUtils.hasText(c.gender)) {
                Element admGender = doc.createElement("administrativeGender");
                admGender.setAttribute("code", "M".equals(mapFhirGender(c.gender)) ? "1" : "2");
                admGender.setAttribute("codeSystem", "2.16.840.1.113883.5.1");
                patient.appendChild(admGender);
            }
            patientRole.appendChild(patient);
            recordTarget.appendChild(patientRole);
            root.appendChild(recordTarget);

            // author
            Element author = doc.createElement("author");
            Element aTime = doc.createElement("time");
            aTime.setAttribute("value", (c.recordTime == null ? LocalDateTime.now() : c.recordTime).format(CDA_TS));
            author.appendChild(aTime);
            Element assigned = doc.createElement("assignedEntity");
            Element aId = doc.createElement("id");
            aId.setAttribute("root", "2.16.156.10131.1.1");
            aId.setAttribute("extension", c.doctorId == null ? "unknown" : String.valueOf(c.doctorId));
            assigned.appendChild(aId);
            Element aPerson = doc.createElement("assignedPerson");
            Element aName = doc.createElement("name");
            aName.appendChild(doc.createTextNode(c.doctorName == null ? "" : c.doctorName));
            aPerson.appendChild(aName);
            assigned.appendChild(aPerson);
            if (StringUtils.hasText(c.deptName)) {
                Element represented = doc.createElement("representedOrganization");
                Element orgName = doc.createElement("name");
                orgName.appendChild(doc.createTextNode(c.deptName));
                represented.appendChild(orgName);
                assigned.appendChild(represented);
            }
            author.appendChild(assigned);
            root.appendChild(author);

            // componentOf / encompassingEncounter
            Element componentOf = doc.createElement("componentOf");
            Element enc = doc.createElement("encompassingEncounter");
            Element encId = doc.createElement("id");
            encId.setAttribute("root", "2.16.156.10131.1.1");
            encId.setAttribute("extension", c.visitId == null ? String.valueOf(c.targetId) : String.valueOf(c.visitId));
            enc.appendChild(encId);
            Element encTime = doc.createElement("effectiveTime");
            encTime.setAttribute("value", (c.recordTime == null ? LocalDateTime.now() : c.recordTime).format(CDA_TS));
            enc.appendChild(encTime);
            componentOf.appendChild(enc);
            root.appendChild(componentOf);

            // component / structuredBody / sections(每个数据元一个 observation entry)
            Element component = doc.createElement("component");
            Element structuredBody = doc.createElement("structuredBody");
            Element section = doc.createElement("section");
            Element secCode = doc.createElement("code");
            secCode.setAttribute("code", "10210-3");
            secCode.setAttribute("codeSystem", "2.16.840.1.113883.6.1");
            secCode.setAttribute("displayName", "历史事件");
            section.appendChild(secCode);
            Element secTitle = doc.createElement("title");
            secTitle.appendChild(doc.createTextNode("病历数据元(要素)"));
            section.appendChild(secTitle);
            Element text = doc.createElement("text");
            text.appendChild(doc.createTextNode("共 " + c.elements.size() + " 项结构化数据元"));
            section.appendChild(text);
            for (HisEmrElement e : c.elements) {
                Element entry = doc.createElement("entry");
                Element observation = doc.createElement("observation");
                observation.setAttribute("classCode", "OBS");
                observation.setAttribute("moodCode", "EVN");
                Element oCode = doc.createElement("code");
                oCode.setAttribute("code", nullToEmpty(e.getFieldKey()));
                oCode.setAttribute("codeSystem", "urn:yb:emr:field");
                oCode.setAttribute("displayName", StringUtils.hasText(e.getFieldLabel()) ? e.getFieldLabel() : e.getFieldKey());
                observation.appendChild(oCode);
                Element oText = doc.createElement("text");
                oText.appendChild(doc.createTextNode(cdaValueText(e)));
                observation.appendChild(oText);
                Element value = doc.createElement("value");
                if (e.getValueNum() != null) {
                    value.setAttribute("xsi:type", "PQ");
                    value.setAttribute("value", e.getValueNum().toPlainString());
                    if (StringUtils.hasText(e.getValueUnit())) {
                        value.setAttribute("unit", e.getValueUnit());
                    }
                } else if (e.getValueDate() != null) {
                    value.setAttribute("xsi:type", "TS");
                    value.setAttribute("value", e.getValueDate().format(CDA_TS));
                } else {
                    value.setAttribute("xsi:type", "ST");
                    value.appendChild(doc.createTextNode(cdaValueText(e)));
                }
                observation.appendChild(value);
                entry.appendChild(observation);
                section.appendChild(entry);
            }
            structuredBody.appendChild(section);
            component.appendChild(structuredBody);
            root.appendChild(component);

            Transformer tf = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            tf.setOutputProperty(OutputKeys.INDENT, "yes");
            tf.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            StringWriter sw = new StringWriter();
            tf.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            throw new BizException("CDA 导出失败: " + e.getMessage());
        }
    }

    private String cdaValueText(HisEmrElement e) {
        if (StringUtils.hasText(e.getValueText())) {
            return e.getValueText();
        }
        if (e.getValueNum() != null) {
            return e.getValueNum().toPlainString();
        }
        if (e.getValueDate() != null) {
            return e.getValueDate().format(CDA_TS);
        }
        return nullToEmpty(e.getTermCode());
    }

    /* ==================== 私有助手 ==================== */

    private void appendText(Document doc, Element parent, String tag, String value) {
        Element el = doc.createElement(tag);
        el.appendChild(doc.createTextNode(nullToEmpty(value)));
        parent.appendChild(el);
    }

    private JSONObject ref(String type, String id) {
        JSONObject o = new JSONObject();
        o.put("reference", type + "/" + id);
        return o;
    }

    private JSONObject bundleEntry(String fullUrl, JSONObject resource) {
        JSONObject e = new JSONObject();
        e.put("fullUrl", fullUrl);
        e.put("resource", resource);
        return e;
    }

    private JSONObject oneCoding(String system, String code, String display) {
        JSONObject c = fieldCoding(system, code);
        if (StringUtils.hasText(display)) {
            c.put("display", display);
        }
        JSONArray arr = new JSONArray();
        arr.add(c);
        JSONObject holder = new JSONObject();
        holder.put("coding", arr);
        return holder;
    }

    private JSONObject fieldCoding(String system, String code) {
        JSONObject c = new JSONObject();
        c.put("system", system);
        c.put("code", nullToEmpty(code));
        return c;
    }

    private String mapFhirGender(String g) {
        if (g == null) {
            return "unknown";
        }
        String s = g.trim();
        if ("男".equals(s) || "1".equals(s) || "M".equalsIgnoreCase(s)) {
            return "male";
        }
        if ("女".equals(s) || "2".equals(s) || "F".equalsIgnoreCase(s)) {
            return "female";
        }
        return "unknown";
    }

    /** 机构内名称 best-effort 回查(his_patient/his_staff/his_dept 均有 name 列); 失败或无记录返回 null, 不影响导出主流程 */
    private String safeLookup(String table, Long id) {
        if (id == null) {
            return null;
        }
        try {
            List<String> names = jdbcTemplate.queryForList(
                    "SELECT name FROM " + table + " WHERE id = ? AND deleted = 0 LIMIT 1", String.class, id);
            return names.isEmpty() ? null : names.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 导出上下文(抬头 + 要素), 屏蔽 scope 差异供两种格式复用 */
    private static class Ctx {
        int scope;
        Long targetId;
        Long visitId;
        Long patientId;
        Long deptId;
        Long doctorId;
        String patientName;
        String gender;
        Integer age;
        String deptName;
        String doctorName;
        String title;
        String docTypeCode;
        String docTypeText;
        LocalDateTime recordTime;
        List<HisEmrElement> elements;
    }
}
