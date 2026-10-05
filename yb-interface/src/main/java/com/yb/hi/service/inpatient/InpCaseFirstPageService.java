package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 住院病案首页服务(T38/T34基座): 按就诊聚合生成首页(患者/入院/出院/诊断/手术/费用分项/过敏),
 * 草稿(1)→已提交(2, 医师签名)→已审核(3, 质控评分+签名)三级流转;
 * 生成幂等(visit_id+tenant_id 唯一键, ON DUPLICATE KEY UPDATE 兜底并发),
 * 仅草稿可编辑保存, 提交/审核用乐观更新(状态未变才流转); 首页归属跟随就诊机构。
 * <p>说明: JdbcTemplate 手写 SQL 不走 MyBatis-Plus 租户插件, 全部显式带 tenant_id AND deleted=0;
 * his_inp_visit 的列名为 admit_date(非 admission_date), 诊断/手术外键为 inp_visit_id。
 */
@Slf4j
@Service
public class InpCaseFirstPageService {

    /** 麻醉类型名称(his_anesthesia.anesthesia_type): 1全麻 2局麻 3椎管内 4神经阻滞 5复合 6其他 */
    private static final Map<Integer, String> ANESTHESIA_TYPES = new LinkedHashMap<>();

    static {
        ANESTHESIA_TYPES.put(1, "全身麻醉");
        ANESTHESIA_TYPES.put(2, "局部麻醉");
        ANESTHESIA_TYPES.put(3, "椎管内麻醉");
        ANESTHESIA_TYPES.put(4, "神经阻滞麻醉");
        ANESTHESIA_TYPES.put(5, "复合麻醉");
        ANESTHESIA_TYPES.put(6, "其他");
    }

    /** 就诊+患者+科室/医师/床位上下文(生成与查询共用, 一次 JOIN 取齐) */
    private static final String VISIT_CONTEXT_SQL =
            "SELECT v.id AS visitId, v.org_id AS orgId, v.patient_id AS patientId, v.inp_no AS inpNo,"
                    + " v.visit_status AS visitStatus, v.admit_date AS admissionDate, v.discharge_date AS dischargeDate,"
                    + " v.admit_diag AS admitDiag, v.blood_type AS visitBloodType,"
                    + " v.dept_id AS deptId, d.dept_name AS deptName,"
                    + " v.doctor_id AS doctorId, ds.staff_name AS doctorName,"
                    + " v.nurse_id AS nurseId, ns.staff_name AS nurseName,"
                    + " v.bed_id AS bedId, b.bed_no AS bedNo, b.room_no AS roomNo, w.ward_name AS wardName,"
                    + " p.name AS patientName, p.gender AS gender, p.gender_name AS genderName,"
                    + " p.birth_date AS birthDate, p.age AS age, p.id_card AS idCard,"
                    + " p.marital_status AS maritalStatusCode, p.marital_status_name AS maritalStatus,"
                    + " p.occupation AS occupationCode, p.occupation_name AS occupation,"
                    + " p.contact_name AS contactName, p.contact_phone AS contactPhone,"
                    + " p.contact_relation_name AS contactRelation, p.household_addr AS householdAddr,"
                    + " CONCAT(IFNULL(p.present_prov_name,''), IFNULL(p.present_city_name,''),"
                    + " IFNULL(p.present_county_name,''), IFNULL(p.present_town_name,''),"
                    + " IFNULL(p.present_detail,'')) AS presentAddr"
                    + " FROM his_inp_visit v"
                    + " LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                    + " LEFT JOIN his_dept d ON d.id = v.dept_id"
                    + " LEFT JOIN his_staff ds ON ds.id = v.doctor_id AND ds.deleted = 0"
                    + " LEFT JOIN his_staff ns ON ns.id = v.nurse_id AND ns.deleted = 0"
                    + " LEFT JOIN his_bed b ON b.id = v.bed_id"
                    + " LEFT JOIN his_ward w ON w.id = v.ward_id"
                    + " WHERE v.id = ? AND v.tenant_id = ? AND v.deleted = 0";

    /** 病案首页行(显式列+驼峰别名, 前端直接消费) */
    private static final String FRONT_PAGE_SQL =
            "SELECT id, visit_id AS visitId, patient_id AS patientId,"
                    + " admission_date AS admissionDate, discharge_date AS dischargeDate, los_days AS losDays,"
                    + " admission_dept_id AS admissionDeptId, discharge_dept_id AS dischargeDeptId,"
                    + " admission_diag_code AS admissionDiagCode, admission_diag_name AS admissionDiagName,"
                    + " discharge_main_diag_code AS dischargeMainDiagCode,"
                    + " discharge_main_diag_name AS dischargeMainDiagName, main_diag_id AS mainDiagId,"
                    + " discharge_other_diags AS dischargeOtherDiags, pathology_diag AS pathologyDiag,"
                    + " injury_poison_code AS injuryPoisonCode, operation_records AS operationRecords,"
                    + " blood_type AS bloodType, rh, allergy_drugs AS allergyDrugs, autopsy,"
                    + " total_cost AS totalCost, drug_cost AS drugCost, exam_cost AS examCost,"
                    + " treatment_cost AS treatmentCost, bed_cost AS bedCost, nursing_cost AS nursingCost,"
                    + " material_cost AS materialCost, other_cost AS otherCost, self_pay AS selfPay,"
                    + " insurance_pay AS insurancePay, quality_score AS qualityScore,"
                    + " qc_doctor_id AS qcDoctorId, qc_time AS qcTime, status,"
                    + " doctor_sign_img AS doctorSignImg, nurse_sign_img AS nurseSignImg, qc_sign_img AS qcSignImg,"
                    + " discharge_mode AS dischargeMode, trans_inst AS transInst, treat_result AS treatResult,"
                    + " readmit_plan AS readmitPlan, readmit_purpose AS readmitPurpose, main_diag_admit_cond AS mainDiagAdmitCond,"
                    + " outp_diag_code AS outpDiagCode, outp_diag_name AS outpDiagName,"
                    + " injury_poison_name AS injuryPoisonName, pathology_code AS pathologyCode, pathology_no AS pathologyNo,"
                    + " allergy_flag AS allergyFlag, coma_before AS comaBefore, coma_after AS comaAfter,"
                    + " newborn_birth_weight AS newbornBirthWeight, newborn_admit_weight AS newbornAdmitWeight,"
                    + " native_place AS nativePlace, mr_grade AS mrGrade,"
                    + " chief_doctor AS chiefDoctor, resident_doctor AS residentDoctor, qc_nurse AS qcNurse,"
                    + " cost_class_detail AS costClassDetail,"
                    + " attending_doctor AS attendingDoctor, coder, trainee_doctor AS traineeDoctor,"
                    + " intern_doctor AS internDoctor, duty_nurse AS dutyNurse,"
                    + " admission_ward AS admissionWard, discharge_ward AS dischargeWard, bed_no AS bedNo,"
                    + " transfer_depts AS transferDepts, vent_use_time AS ventUseTime,"
                    + " diag_fit_code AS diagFitCode, infection_flag AS infectionFlag, infection_site AS infectionSite,"
                    + " unplanned_reop AS unplannedReop, newborn_apgar AS newbornApgar, blood_transfusion AS bloodTransfusion,"
                    + " create_time AS createTime, update_time AS updateTime"
                    + " FROM his_case_front_page WHERE visit_id = ? AND tenant_id = ? AND deleted = 0 LIMIT 1";

    private final JdbcTemplate jdbcTemplate;
    private final OrgAccessGuard guard;

    public InpCaseFirstPageService(JdbcTemplate jdbcTemplate, OrgAccessGuard guard) {
        this.jdbcTemplate = jdbcTemplate;
        this.guard = guard;
    }

    /* ==================== 生成(聚合) ==================== */

    /**
     * 聚合生成病案首页: 患者/入院/出院/诊断/手术/费用分项/过敏史一次聚合落库;
     * 在院期间即可预填草稿(1~4 均可生成, 出院相关字段暂空由医生补录); 已取消(5)拒绝;
     * 已提交/已审核的首页拒绝重新生成; 草稿重复生成按唯一键幂等覆盖(签名与质控字段保留)。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> generateFirstPage(Long visitId) {
        Long tid = TenantContext.require();
        Map<String, Object> ctx = loadVisitContext(visitId, tid);
        int vs = intVal(ctx.get("visitStatus"), 0);
        if (vs < 1 || vs > 4) {
            throw new BizException(400, "该就诊已取消或状态异常, 不可生成病案首页");
        }
        Map<String, Object> exist = loadFrontPage(visitId, tid);
        if (exist != null && intVal(exist.get("status"), 1) >= 2) {
            throw new BizException(400, "病案首页已提交或已审核, 不可重新生成");
        }

        Long patientId = longVal(ctx.get("patientId"));

        // 1) 入院诊断(diag_type=1): 主诊断优先, 取第一条
        Map<String, Object> admDiag = queryFirst(
                "SELECT diag_code AS code, diag_name AS name FROM his_inp_diagnosis"
                        + " WHERE inp_visit_id = ? AND diag_type = 1 AND tenant_id = ? AND deleted = 0"
                        + " ORDER BY is_main DESC, sort_no ASC, id ASC LIMIT 1",
                visitId, tid);
        // 2) 出院诊断(diag_type=4): 主诊断取第一条, 其余组装 JSON 数组(含 diagId 双向同步关联键)
        List<Map<String, Object>> disDiags = queryDischargeDiags(visitId, tid);
        Map<String, Object> mainDiag = disDiags.isEmpty() ? null : disDiags.get(0);
        JSONArray otherDiags = buildOtherDiags(disDiags);

        // 3) 手术记录: [{name, code, date, surgeon, surgeonId, anesthesia, anesthesiaDoctor, firstAssistant, surgeryLevel, asaGrade, incisionType, healLevel}]
        JSONArray operationRecords = new JSONArray();
        for (Map<String, Object> s : jdbcTemplate.queryForList(
                "SELECT s.surgery_code AS surgery_code, s.surgery_name AS surgery_name,"
                        + " s.schedule_date AS schedule_date, s.surgeon_id AS surgeon_id,"
                        + " st.staff_name AS surgeon_name, s.incision_type AS incision_type,"
                        + " s.surgery_level AS surgery_level, s.asa_grade AS asa_grade,"
                        + " fa.staff_name AS first_assistant_name, an.staff_name AS anesthesiologist_name,"
                        + " a.anesthesia_type AS anesthesia_type, a.anesthesia_method AS anesthesia_method"
                        + " FROM his_surgery s"
                        + " LEFT JOIN his_staff st ON st.id = s.surgeon_id AND st.deleted = 0"
                        + " LEFT JOIN his_staff fa ON fa.id = s.first_assistant_id AND fa.deleted = 0"
                        + " LEFT JOIN his_staff an ON an.id = s.anesthesiologist_id AND an.deleted = 0"
                        + " LEFT JOIN his_anesthesia a ON a.surgery_id = s.id AND a.deleted = 0"
                        + " WHERE s.inp_visit_id = ? AND s.tenant_id = ? AND s.deleted = 0"
                        + " ORDER BY s.schedule_date ASC, s.id ASC",
                visitId, tid)) {
            JSONObject o = new JSONObject();
            o.put("name", strVal(s.get("surgery_name")));
            o.put("code", strVal(s.get("surgery_code")));
            o.put("date", dateText(s.get("schedule_date")));
            o.put("surgeon", strVal(s.get("surgeon_name")));
            o.put("surgeonId", longVal(s.get("surgeon_id")));
            String method = strVal(s.get("anesthesia_method"));
            Integer anesType = intOrNull(s.get("anesthesia_type"));
            o.put("anesthesia", StringUtils.hasText(method) ? method
                    : (anesType == null ? null : ANESTHESIA_TYPES.getOrDefault(anesType, "其他")));
            o.put("anesthesiaDoctor", strVal(s.get("anesthesiologist_name")));
            o.put("firstAssistant", strVal(s.get("first_assistant_name")));
            o.put("surgeryLevel", intOrNull(s.get("surgery_level")));
            o.put("asaGrade", intOrNull(s.get("asa_grade")));
            o.put("incisionType", anestInt(s.get("incision_type")));
            o.put("healLevel", "");
            operationRecords.add(o);
        }

        // 4) 费用分项: GROUP BY fee_type(1西药 2中药 3检查 4检验 5治疗 6护理 7材料 8床位 9其他)
        //    映射: 药品=西药+中药; 检查=检查+检验; 其余一一对应; total=全部正常明细合计
        BigDecimal drugCost = BigDecimal.ZERO;
        BigDecimal examCost = BigDecimal.ZERO;
        BigDecimal treatmentCost = BigDecimal.ZERO;
        BigDecimal bedCost = BigDecimal.ZERO;
        BigDecimal nursingCost = BigDecimal.ZERO;
        BigDecimal materialCost = BigDecimal.ZERO;
        BigDecimal otherCost = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT fee_type, COALESCE(SUM(amount), 0) AS total_amount FROM his_inp_charge_detail"
                        + " WHERE inp_visit_id = ? AND status = 1 AND deleted = 0 AND tenant_id = ?"
                        + " GROUP BY fee_type",
                visitId, tid)) {
            BigDecimal amt = decVal(row.get("total_amount"));
            if (amt == null) {
                amt = BigDecimal.ZERO;
            }
            totalCost = totalCost.add(amt);
            Integer ft = intOrNull(row.get("fee_type"));
            if (ft == null) {
                otherCost = otherCost.add(amt);
                continue;
            }
            switch (ft) {
                case 1:
                case 2:
                    drugCost = drugCost.add(amt);
                    break;
                case 3:
                case 4:
                    examCost = examCost.add(amt);
                    break;
                case 5:
                    treatmentCost = treatmentCost.add(amt);
                    break;
                case 6:
                    nursingCost = nursingCost.add(amt);
                    break;
                case 7:
                    materialCost = materialCost.add(amt);
                    break;
                case 8:
                    bedCost = bedCost.add(amt);
                    break;
                default:
                    otherCost = otherCost.add(amt);
                    break;
            }
        }

        // 4b) 病案首页费用分项: 按 his_charge_item.mr_cost_class 归并聚合(std_mr_cost_class 值域), 未维护归"未归类"
        JSONArray costClassDetail = new JSONArray();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT ci.mr_cost_class AS cls, COALESCE(SUM(d.amount), 0) AS amt FROM his_inp_charge_detail d"
                        + " LEFT JOIN his_charge_item ci ON ci.id = d.charge_item_id"
                        + " WHERE d.inp_visit_id = ? AND d.status = 1 AND d.deleted = 0 AND d.tenant_id = ?"
                        + " GROUP BY ci.mr_cost_class ORDER BY amt DESC",
                visitId, tid)) {
            JSONObject o = new JSONObject();
            String cls = strVal(row.get("cls"));
            o.put("cls", StringUtils.hasText(cls) ? cls : "未归类");
            o.put("amount", nvl(decVal(row.get("amt"))));
            costClassDetail.add(o);
        }
        String costClassJson = costClassDetail.isEmpty() ? null : JSON.toJSONString(costClassDetail);

        // 5) 自付/医保支付: 取最新一条结算(出院/中途); 未结算按 0
        Map<String, Object> settle = queryFirst(
                "SELECT self_pay, fund_pay, acct_pay FROM his_inp_settle"
                        + " WHERE inp_visit_id = ? AND tenant_id = ? AND deleted = 0"
                        + " AND settle_type IN (1, 2) ORDER BY id DESC LIMIT 1",
                visitId, tid);
        BigDecimal selfPay = settle == null ? BigDecimal.ZERO : nvl(decVal(settle.get("self_pay")));
        BigDecimal insurancePay = settle == null ? BigDecimal.ZERO
                : nvl(decVal(settle.get("fund_pay"))).add(nvl(decVal(settle.get("acct_pay"))));

        // 6) 过敏史: 有效过敏原拼接(逗号分隔, 截断至列宽 500)
        StringBuilder allergySb = new StringBuilder();
        if (patientId != null) {
            for (Map<String, Object> a : jdbcTemplate.queryForList(
                    "SELECT allergen_name FROM his_patient_allergy"
                            + " WHERE patient_id = ? AND tenant_id = ? AND deleted = 0 AND is_active = 1"
                            + " ORDER BY id ASC",
                    patientId, tid)) {
                String name = strVal(a.get("allergen_name"));
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                if (allergySb.length() > 0) {
                    allergySb.append(",");
                }
                allergySb.append(name.trim());
            }
        }
        String allergyText = allergySb.length() == 0 ? null
                : (allergySb.length() > 500 ? allergySb.substring(0, 500) : allergySb.toString());

        // 7) 住院天数: 出院日期未落时按当前日期计
        long losDays = losDays(ctx.get("admissionDate"), ctx.get("dischargeDate"));

        // 8) 入库: 无行则 INSERT(唯一键兜底并发), 有草稿行则 UPDATE(保留状态与签名列)
        Object admissionDate = ctx.get("admissionDate");
        Object dischargeDate = ctx.get("dischargeDate");
        Long deptId = longVal(ctx.get("deptId"));
        String admDiagCode = admDiag == null ? null : strVal(admDiag.get("code"));
        String admDiagName = admDiag == null ? null : strVal(admDiag.get("name"));
        String mainDiagCode = mainDiag == null ? null : strVal(mainDiag.get("code"));
        String mainDiagName = mainDiag == null ? null : strVal(mainDiag.get("name"));
        Long mainDiagId = mainDiag == null ? null : longVal(mainDiag.get("id"));
        String otherDiagsJson = otherDiags.isEmpty() ? null : JSON.toJSONString(otherDiags);
        String operationsJson = operationRecords.isEmpty() ? null : JSON.toJSONString(operationRecords);
        String bloodType = strVal(ctx.get("visitBloodType"));
        String bedNo = strVal(ctx.get("bedNo"));
        String wardName = strVal(ctx.get("wardName"));

        if (exist == null) {
            jdbcTemplate.update("INSERT INTO his_case_front_page"
                            + " (visit_id, patient_id, admission_date, discharge_date, los_days,"
                            + " admission_dept_id, discharge_dept_id, admission_diag_code, admission_diag_name,"
                            + " discharge_main_diag_code, discharge_main_diag_name, main_diag_id, discharge_other_diags,"
                            + " operation_records, blood_type, allergy_drugs, autopsy,"
                            + " total_cost, drug_cost, exam_cost, treatment_cost, bed_cost, nursing_cost,"
                            + " material_cost, other_cost, self_pay, insurance_pay, cost_class_detail,"
                            + " bed_no, admission_ward, discharge_ward,"
                            + " status, tenant_id, deleted, update_time)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, 0, NOW())"
                            + " ON DUPLICATE KEY UPDATE admission_date = VALUES(admission_date),"
                            + " discharge_date = VALUES(discharge_date), los_days = VALUES(los_days),"
                            + " admission_dept_id = VALUES(admission_dept_id), discharge_dept_id = VALUES(discharge_dept_id),"
                            + " admission_diag_code = VALUES(admission_diag_code), admission_diag_name = VALUES(admission_diag_name),"
                            + " discharge_main_diag_code = VALUES(discharge_main_diag_code),"
                            + " discharge_main_diag_name = VALUES(discharge_main_diag_name), main_diag_id = VALUES(main_diag_id),"
                            + " discharge_other_diags = VALUES(discharge_other_diags),"
                            + " operation_records = VALUES(operation_records), blood_type = VALUES(blood_type),"
                            + " allergy_drugs = VALUES(allergy_drugs), total_cost = VALUES(total_cost),"
                            + " drug_cost = VALUES(drug_cost), exam_cost = VALUES(exam_cost),"
                            + " treatment_cost = VALUES(treatment_cost), bed_cost = VALUES(bed_cost),"
                            + " nursing_cost = VALUES(nursing_cost), material_cost = VALUES(material_cost),"
                            + " other_cost = VALUES(other_cost), self_pay = VALUES(self_pay),"
                            + " insurance_pay = VALUES(insurance_pay), cost_class_detail = VALUES(cost_class_detail),"
                            + " bed_no = VALUES(bed_no), admission_ward = VALUES(admission_ward),"
                            + " discharge_ward = VALUES(discharge_ward),"
                            + " update_time = NOW()",
                    visitId, patientId, admissionDate, dischargeDate, losDays,
                    deptId, deptId, admDiagCode, admDiagName,
                    mainDiagCode, mainDiagName, mainDiagId, otherDiagsJson,
                    operationsJson, bloodType, allergyText,
                    totalCost, drugCost, examCost, treatmentCost, bedCost, nursingCost,
                    materialCost, otherCost, selfPay, insurancePay, costClassJson,
                    bedNo, wardName, wardName, tid);
        } else {
            jdbcTemplate.update("UPDATE his_case_front_page SET"
                            + " admission_date = ?, discharge_date = ?, los_days = ?,"
                            + " admission_dept_id = ?, discharge_dept_id = ?,"
                            + " admission_diag_code = ?, admission_diag_name = ?,"
                            + " discharge_main_diag_code = ?, discharge_main_diag_name = ?, main_diag_id = ?,"
                            + " discharge_other_diags = ?, operation_records = ?, blood_type = ?, allergy_drugs = ?,"
                            + " total_cost = ?, drug_cost = ?, exam_cost = ?, treatment_cost = ?,"
                            + " bed_cost = ?, nursing_cost = ?, material_cost = ?, other_cost = ?,"
                            + " self_pay = ?, insurance_pay = ?, cost_class_detail = ?,"
                            + " bed_no = ?, admission_ward = ?, discharge_ward = ?, update_time = NOW()"
                            + " WHERE id = ? AND status = 1 AND tenant_id = ? AND deleted = 0",
                    admissionDate, dischargeDate, losDays,
                    deptId, deptId,
                    admDiagCode, admDiagName,
                    mainDiagCode, mainDiagName, mainDiagId,
                    otherDiagsJson, operationsJson, bloodType, allergyText,
                    totalCost, drugCost, examCost, treatmentCost,
                    bedCost, nursingCost, materialCost, otherCost,
                    selfPay, insurancePay, costClassJson,
                    bedNo, wardName, wardName, longVal(exist.get("id")), tid);
        }
        log.info("生成病案首页: visitId={}, 状态={}, 费用合计={}, 手术数={}",
                visitId, exist == null ? "新建" : "覆盖草稿", totalCost, operationRecords.size());
        return getFirstPage(visitId);
    }

    /* ==================== 查询 ==================== */

    /** 首页详情(含患者/科室/医师名称与存在标志 exists; 未生成时仅返回就诊上下文) */
    public Map<String, Object> getFirstPage(Long visitId) {
        Long tid = TenantContext.require();
        Map<String, Object> ctx = loadVisitContext(visitId, tid);
        return assemble(ctx, loadFrontPage(visitId, tid));
    }

    /** 打印数据: 在查询基础上补打印标题与质控医师姓名(打印模板直接消费); 出院闸门: 仅已出院(4)可打印 */
    public Map<String, Object> getFirstPageForPrint(Long visitId) {
        Long ptid = TenantContext.require();
        Map<String, Object> pctx = loadVisitContext(visitId, ptid);
        if (intVal(pctx.get("visitStatus"), 0) != 4) {
            throw new BizException(400, "患者尚未出院, 病案首页须出院后方可打印");
        }
        Map<String, Object> data = getFirstPage(visitId);
        data.put("printTitle", "住院病案首页");
        data.put("printTime", LocalDateTime.now().toString());
        return data;
    }

    /** 定向同步出院诊断: 按病历现行出院诊断(diag_type=4)覆盖首页诊断区(仅草稿, 不动手术/费用/其他字段) */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> syncDiagFromRecord(Long visitId) {
        Long tid = TenantContext.require();
        loadVisitContext(visitId, tid);
        Map<String, Object> fp = loadFrontPage(visitId, tid);
        if (fp == null) {
            throw new BizException(400, "病案首页尚未生成, 请先点击「生成」");
        }
        if (intVal(fp.get("status"), 0) != 1) {
            throw new BizException(400, "病案首页已提交或已审核, 不可同步出院诊断");
        }
        List<Map<String, Object>> disDiags = queryDischargeDiags(visitId, tid);
        Map<String, Object> mainDiag = disDiags.isEmpty() ? null : disDiags.get(0);
        JSONArray others = buildOtherDiags(disDiags);
        int affected = jdbcTemplate.update("UPDATE his_case_front_page SET"
                        + " discharge_main_diag_code = ?, discharge_main_diag_name = ?, main_diag_id = ?, discharge_other_diags = ?,"
                        + " update_time = NOW()"
                        + " WHERE visit_id = ? AND status = 1 AND tenant_id = ? AND deleted = 0",
                mainDiag == null ? null : strVal(mainDiag.get("code")),
                mainDiag == null ? null : strVal(mainDiag.get("name")),
                mainDiag == null ? null : longVal(mainDiag.get("id")),
                others.isEmpty() ? null : JSON.toJSONString(others),
                visitId, tid);
        if (affected == 0) {
            throw new BizException(400, "病案首页状态已变化, 同步未生效, 请刷新后重试");
        }
        log.info("首页同步出院诊断: visitId={}, 出院诊断行数={}", visitId, disDiags.size());
        return getFirstPage(visitId);
    }

    /* ==================== 保存(草稿) ==================== */

    /**
     * 保存首页(仅草稿可编辑): 白名单字段部分更新 —
     * 文本(诊断/病理/损伤中毒/血型/Rh)、JSON(其他诊断/手术操作, List 或 JSON 字符串)、
     * 数值(10 项费用 + 尸检标志); 空串文本按清空处理。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveFirstPage(Long visitId, Map<String, Object> data) {
        Long tid = TenantContext.require();
        loadVisitContext(visitId, tid);
        if (data == null || data.isEmpty()) {
            throw new BizException(400, "保存内容不能为空");
        }
        Map<String, Object> fp = loadFrontPage(visitId, tid);
        if (fp == null) {
            throw new BizException(400, "病案首页尚未生成, 请先点击「生成」");
        }
        if (intVal(fp.get("status"), 0) != 1) {
            throw new BizException(400, "病案首页已提交或已审核, 仅草稿状态可编辑保存");
        }

        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        // 文本列
        String[][] textCols = {
                {"admissionDiagCode", "admission_diag_code"},
                {"admissionDiagName", "admission_diag_name"},
                {"dischargeMainDiagCode", "discharge_main_diag_code"},
                {"dischargeMainDiagName", "discharge_main_diag_name"},
                {"pathologyDiag", "pathology_diag"},
                {"injuryPoisonCode", "injury_poison_code"},
                {"bloodType", "blood_type"},
                {"rh", "rh"},
                {"outpDiagCode", "outp_diag_code"},
                {"outpDiagName", "outp_diag_name"},
                {"injuryPoisonName", "injury_poison_name"},
                {"pathologyCode", "pathology_code"},
                {"pathologyNo", "pathology_no"},
                {"transInst", "trans_inst"},
                {"readmitPurpose", "readmit_purpose"},
                {"comaBefore", "coma_before"},
                {"comaAfter", "coma_after"},
                {"nativePlace", "native_place"},
                {"chiefDoctor", "chief_doctor"},
                {"attendingDoctor", "attending_doctor"},
                {"residentDoctor", "resident_doctor"},
                {"traineeDoctor", "trainee_doctor"},
                {"internDoctor", "intern_doctor"},
                {"dutyNurse", "duty_nurse"},
                {"coder", "coder"},
                {"admissionWard", "admission_ward"},
                {"dischargeWard", "discharge_ward"},
                {"bedNo", "bed_no"},
                {"ventUseTime", "vent_use_time"},
                {"infectionSite", "infection_site"},
                {"qcNurse", "qc_nurse"}
        };
        for (String[] col : textCols) {
            if (!data.containsKey(col[0])) {
                continue;
            }
            Object v = data.get(col[0]);
            String s = v == null ? null : String.valueOf(v).trim();
            sets.add(col[1] + " = ?");
            args.add(s == null || s.isEmpty() ? null : s);
        }
        // JSON 列(前端传数组或 JSON 字符串, 保存前校验合法性)
        String[][] jsonCols = {
                {"dischargeOtherDiags", "discharge_other_diags"},
                {"operationRecords", "operation_records"},
                {"costClassDetail", "cost_class_detail"},
                {"transferDepts", "transfer_depts"},
                {"bloodTransfusion", "blood_transfusion"}
        };
        for (String[] col : jsonCols) {
            if (!data.containsKey(col[0])) {
                continue;
            }
            Object v = data.get(col[0]);
            String json = null;
            if (v instanceof String) {
                String s = ((String) v).trim();
                if (!s.isEmpty()) {
                    try {
                        JSON.parse(s);
                    } catch (Exception e) {
                        throw new BizException(400, col[0] + " 不是合法的 JSON 内容");
                    }
                    json = s;
                }
            } else if (v != null) {
                json = JSON.toJSONString(v);
            }
            sets.add(col[1] + " = ?");
            args.add(json);
        }
        // 数值列
        String[][] numCols = {
                {"totalCost", "total_cost"}, {"drugCost", "drug_cost"}, {"examCost", "exam_cost"},
                {"treatmentCost", "treatment_cost"}, {"bedCost", "bed_cost"}, {"nursingCost", "nursing_cost"},
                {"materialCost", "material_cost"}, {"otherCost", "other_cost"},
                {"selfPay", "self_pay"}, {"insurancePay", "insurance_pay"},
                {"autopsy", "autopsy"}
        };
        for (String[] col : numCols) {
            if (!data.containsKey(col[0])) {
                continue;
            }
            Object v = data.get(col[0]);
            if (v == null || String.valueOf(v).trim().isEmpty()) {
                sets.add(col[1] + " = 0");
                continue;
            }
            BigDecimal d = decVal(v);
            if (d == null) {
                throw new BizException(400, col[0] + " 必须为数字");
            }
            sets.add(col[1] + " = ?");
            args.add(d);
        }
        // 编码/整型列(P0/P1 关键项): 空串置 NULL 而非 0, 以便提交硬校验能识别未填(0 对过敏/再住院为合法值)
        String[][] codeCols = {
                {"dischargeMode", "discharge_mode"}, {"treatResult", "treat_result"},
                {"readmitPlan", "readmit_plan"}, {"mainDiagAdmitCond", "main_diag_admit_cond"},
                {"allergyFlag", "allergy_flag"}, {"mrGrade", "mr_grade"},
                {"newbornBirthWeight", "newborn_birth_weight"}, {"newbornAdmitWeight", "newborn_admit_weight"},
                {"diagFitCode", "diag_fit_code"}, {"infectionFlag", "infection_flag"},
                {"unplannedReop", "unplanned_reop"}, {"newbornApgar", "newborn_apgar"}
        };
        for (String[] col : codeCols) {
            if (!data.containsKey(col[0])) {
                continue;
            }
            Object v = data.get(col[0]);
            if (v == null || String.valueOf(v).trim().isEmpty()) {
                sets.add(col[1] + " = NULL");
                continue;
            }
            Integer iv = intOrNull(v);
            if (iv == null) {
                throw new BizException(400, col[0] + " 必须为整数");
            }
            sets.add(col[1] + " = ?");
            args.add(iv);
        }
        if (sets.isEmpty()) {
            throw new BizException(400, "无可保存的字段");
        }
        sets.add("update_time = NOW()");
        args.add(visitId);
        args.add(tid);
        jdbcTemplate.update("UPDATE his_case_front_page SET " + String.join(", ", sets)
                        + " WHERE visit_id = ? AND status = 1 AND tenant_id = ? AND deleted = 0",
                args.toArray());
        log.info("保存病案首页草稿: visitId={}, 字段数={}", visitId, sets.size() - 1);
        /* 诊断双向联动: payload 含诊断相关字段时回写病历出院诊断(同事务, 失败整体回滚) */
        if (data.containsKey("dischargeMainDiagCode") || data.containsKey("dischargeMainDiagName")
                || data.containsKey("dischargeOtherDiags") || data.containsKey("mainDiagId")) {
            syncDischargeDiags(visitId, tid, data);
        }
    }

    /* ==================== 提交 / 质控审核 ==================== */

    /** 提交(草稿→已提交): 出院闸门(仅已出院可提交) + 需医师电子签名图 URL, 乐观更新仅 status=1 生效 */
    @Transactional(rollbackFor = Exception.class)
    public void submitFirstPage(Long visitId, String doctorSignImg) {
        Long tid = TenantContext.require();
        Map<String, Object> sctx = loadVisitContext(visitId, tid);
        if (intVal(sctx.get("visitStatus"), 0) != 4) {
            throw new BizException(400, "患者尚未出院, 病案首页可暂存草稿, 出院后方可提交");
        }
        if (!StringUtils.hasText(doctorSignImg)) {
            throw new BizException(400, "医师签名不能为空, 请完成电子签名后提交");
        }
        // 提交前硬校验: 国考/DRG 对首页质量要求, P0 关键字段缺失禁止提交并列出缺项
        Map<String, Object> fp = loadFrontPage(visitId, tid);
        if (fp == null) {
            throw new BizException(400, "病案首页尚未生成, 请先点击「生成」");
        }
        if (intVal(fp.get("status"), 0) != 1) {
            throw new BizException(400, "病案首页已提交或已审核, 不可重复提交");
        }
        List<String> miss = new ArrayList<>();
        if (intOrNull(fp.get("dischargeMode")) == null) {
            miss.add("离院方式");
        }
        if (intOrNull(fp.get("treatResult")) == null) {
            miss.add("治疗转归");
        }
        if (intOrNull(fp.get("readmitPlan")) == null) {
            miss.add("31天内再住院计划");
        } else if (intVal(fp.get("readmitPlan"), 0) == 1 && !StringUtils.hasText(strVal(fp.get("readmitPurpose")))) {
            miss.add("再住院目的");
        }
        if (intOrNull(fp.get("mainDiagAdmitCond")) == null) {
            miss.add("主要诊断入院病情");
        }
        if (intVal(fp.get("infectionFlag"), 0) == 1 && !StringUtils.hasText(strVal(fp.get("infectionSite")))) {
            miss.add("医院感染部位");
        }
        if (!StringUtils.hasText(strVal(fp.get("dischargeMainDiagCode")))
                || !StringUtils.hasText(strVal(fp.get("dischargeMainDiagName")))) {
            miss.add("出院主要诊断(编码与名称均必填)");
        }
        // 其他诊断逐行成对校验(对齐 HQMS: 有编码必有名称, 反之亦然)
        List<Object> otherDiagRows = parseJsonArray(fp.get("dischargeOtherDiags"));
        for (int i = 0; i < otherDiagRows.size(); i++) {
            Object item = otherDiagRows.get(i);
            if (!(item instanceof JSONObject)) {
                continue;
            }
            JSONObject o = (JSONObject) item;
            boolean hasCode = StringUtils.hasText(strVal(o.get("code")));
            boolean hasName = StringUtils.hasText(strVal(o.get("name")));
            if (hasCode != hasName) {
                miss.add("其他诊断第" + (i + 1) + "条缺" + (hasCode ? "名称" : "编码"));
            }
        }
        // HQMS 条件必填: 肿瘤/新生物(C或D00-D48)须病理诊断编码; 损伤中毒(S/T)须外部原因编码
        String mainCode = strVal(fp.get("dischargeMainDiagCode"));
        String mainStem = mainCode == null ? "" : mainCode.trim().toUpperCase().replace(".", "");
        if (mainStem.length() >= 3) {
            char c0 = mainStem.charAt(0);
            String num2 = mainStem.substring(1, 3);
            boolean tumor = c0 == 'C'
                    || (c0 == 'D' && num2.compareTo("00") >= 0 && num2.compareTo("48") <= 0);
            if (tumor && !StringUtils.hasText(strVal(fp.get("pathologyCode")))) {
                miss.add("病理诊断编码(肿瘤/新生物类主要诊断必填)");
            }
            if ((c0 == 'S' || c0 == 'T')
                    && !StringUtils.hasText(strVal(fp.get("injuryPoisonCode")))) {
                miss.add("损伤中毒外部原因编码(S/T类主要诊断必填)");
            }
        }
        if (!miss.isEmpty()) {
            throw new BizException(400, "病案首页缺少必填项: " + String.join("、", miss));
        }
        int affected = jdbcTemplate.update(
                "UPDATE his_case_front_page SET status = 2, doctor_sign_img = ?, update_time = NOW()"
                        + " WHERE visit_id = ? AND status = 1 AND tenant_id = ? AND deleted = 0",
                doctorSignImg.trim(), visitId, tid);
        if (affected == 0) {
            requireDraftReason(visitId, tid, 1);
            throw new BizException(400, "病案首页状态已变化, 提交未生效, 请刷新后重试");
        }
        log.info("提交病案首页: visitId={}", visitId);
    }

    /** 质控审核(已提交→已审核): 评分 0-100 + 质控签名图 URL, 记录质控医师与时间 */
    @Transactional(rollbackFor = Exception.class)
    public void auditFirstPage(Long visitId, Integer score, String qcSignImg) {
        Long tid = TenantContext.require();
        loadVisitContext(visitId, tid);
        if (score == null || score < 0 || score > 100) {
            throw new BizException(400, "质控评分必须为 0-100 的整数");
        }
        if (!StringUtils.hasText(qcSignImg)) {
            throw new BizException(400, "质控签名不能为空, 请完成电子签名后审核");
        }
        LoginUser cur = UserContext.get();
        Long qcDoctorId = cur == null ? null : cur.getStaffId();
        int affected = jdbcTemplate.update(
                "UPDATE his_case_front_page SET status = 3, quality_score = ?, qc_doctor_id = ?,"
                        + " qc_time = NOW(), qc_sign_img = ?, update_time = NOW()"
                        + " WHERE visit_id = ? AND status = 2 AND tenant_id = ? AND deleted = 0",
                score, qcDoctorId, qcSignImg.trim(), visitId, tid);
        if (affected == 0) {
            requireDraftReason(visitId, tid, 2);
            throw new BizException(400, "病案首页状态已变化, 审核未生效, 请刷新后重试");
        }
        log.info("质控审核病案首页: visitId={}, score={}, qcDoctorId={}", visitId, score, qcDoctorId);
    }

    /* ==================== 内部实现 ==================== */

    /** 就诊上下文加载 + 机构访问校验: 不存在报404; 非牵头机构仅可访问本机构就诊 */
    private Map<String, Object> loadVisitContext(Long visitId, Long tid) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(VISIT_CONTEXT_SQL, visitId, tid);
        if (rows.isEmpty()) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        Map<String, Object> ctx = rows.get(0);
        Long orgId = longVal(ctx.get("orgId"));
        Long scope = guard.scopeOrgId(orgId);
        if (scope == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法访问住院数据");
        }
        if (orgId != null && !scope.equals(orgId)) {
            throw new BizException(403, "无权访问其他机构的住院就诊数据");
        }
        return ctx;
    }

    /** 查询首页行(不存在返回 null) */
    private Map<String, Object> loadFrontPage(Long visitId, Long tid) {
        return queryFirst(FRONT_PAGE_SQL, visitId, tid);
    }

    /** 组装返回数据: 就诊上下文 + 首页行(存在标志/JSON解析/质控医师名) */
    private Map<String, Object> assemble(Map<String, Object> ctx, Map<String, Object> fp) {
        Map<String, Object> result = new LinkedHashMap<>(ctx);
        if (fp == null) {
            result.put("exists", false);
            result.put("status", null);
            return result;
        }
        result.put("exists", true);
        result.putAll(fp);
                /* 雪花ID超 JS 安全整数: mainDiagId 出口强制字符串化, 前端原样回传 */
                result.put("mainDiagId", fp.get("mainDiagId") == null ? null : String.valueOf(fp.get("mainDiagId")));
        result.put("dischargeOtherDiags", parseJsonArray(fp.get("dischargeOtherDiags")));
        result.put("operationRecords", parseJsonArray(fp.get("operationRecords")));
        result.put("costClassDetail", parseJsonArray(fp.get("costClassDetail")));
        result.put("transferDepts", parseJsonArray(fp.get("transferDepts")));
        result.put("bloodTransfusion", parseJsonArray(fp.get("bloodTransfusion")));
        Long qcDoctorId = longVal(fp.get("qcDoctorId"));
        if (qcDoctorId != null) {
            Map<String, Object> qc = queryFirst(
                    "SELECT staff_name FROM his_staff WHERE id = ? AND tenant_id = ? AND deleted = 0 LIMIT 1",
                    qcDoctorId, TenantContext.require());
            result.put("qcDoctorName", qc == null ? null : strVal(qc.get("staff_name")));
        } else {
            result.put("qcDoctorName", null);
        }
        return result;
    }

    /* ==================== 诊断双向联动 ==================== */

    /** 病历出院诊断行集(diag_type=4, 主诊断优先): id/code/name */
    private List<Map<String, Object>> queryDischargeDiags(Long visitId, Long tid) {
        return jdbcTemplate.queryForList(
                "SELECT id, diag_code AS code, diag_name AS name FROM his_inp_diagnosis"
                        + " WHERE inp_visit_id = ? AND diag_type = 4 AND tenant_id = ? AND deleted = 0"
                        + " ORDER BY is_main DESC, sort_no ASC, id ASC",
                visitId, tid);
    }

    /** 其他诊断 JSON(除首行外): diagId 字符串化防雪花ID在 JS 丢精度 */
    private static JSONArray buildOtherDiags(List<Map<String, Object>> disDiags) {
        JSONArray arr = new JSONArray();
        for (int i = 1; i < disDiags.size(); i++) {
            Object id = disDiags.get(i).get("id");
            JSONObject o = new JSONObject();
            o.put("diagId", id == null ? "" : String.valueOf(id));
            o.put("code", strVal(disDiags.get(i).get("code")));
            o.put("name", strVal(disDiags.get(i).get("name")));
            arr.add(o);
        }
        return arr;
    }

    /**
     * 首页诊断行回写病历出院诊断(diag_type=4): 按 diagId 对既有行更新/无键新插/未引用逻辑删除,
     * 主诊断置 is_main=1 其余置0; 同事务把关联键回写首页(main_diag_id + 刷新 discharge_other_diags 的 diagId)。
     * 口径: 首页诊断全集即病历出院诊断全集; 名称空而有编码时以编码充名(diag_name NOT NULL);
     * 入院病情(admitCondition)与首页 main_diag_admit_cond 值域不同, 不互写。
     */
    @SuppressWarnings("unchecked")
    private void syncDischargeDiags(Long visitId, Long tid, Map<String, Object> data) {
        Map<String, Object> ctx = loadVisitContext(visitId, tid);
        Long orgId = longVal(ctx.get("orgId"));
        Long deptId = longVal(ctx.get("deptId"));
        Long doctorId = InpOrderService.currentDoctorId();
        // 1) 首页行序列: 主要诊断(is_main=1)在前, 其他依次
        List<Object[]> rows = new ArrayList<>(); // [diagId(Long|null), code, name, isMain, admitCond]
        String mainCode = trimOrNull(data.get("dischargeMainDiagCode"));
        String mainName = trimOrNull(data.get("dischargeMainDiagName"));
        if (mainCode != null || mainName != null) {
            rows.add(new Object[]{longVal(data.get("mainDiagId")), mainCode, mainName, 1,
                    intOrNull(data.get("mainDiagAdmitCond"))});
        }
        Object others = data.get("dischargeOtherDiags");
        List<Object> otherList;
        if (others instanceof String) {
            otherList = parseJsonArray(others);
        } else if (others instanceof List) {
            otherList = (List<Object>) others;
        } else {
            otherList = new ArrayList<>();
        }
        for (Object item : otherList) {
            JSONObject o;
            if (item instanceof JSONObject) {
                o = (JSONObject) item;
            } else if (item instanceof Map) {
                o = new JSONObject((Map<String, Object>) item);
            } else {
                continue;
            }
            String code = trimOrNull(o.get("code"));
            String name = trimOrNull(o.get("name"));
            if (code == null && name == null) {
                continue; // 占位空行兜底跳过
            }
            rows.add(new Object[]{longVal(o.get("diagId")), code, name, 0, intOrNull(o.get("admitCond"))});
        }
        // 2) 病历现行集对账
        Map<Long, Boolean> existMap = new LinkedHashMap<>();
        for (Map<String, Object> r : queryDischargeDiags(visitId, tid)) {
            Long id = longVal(r.get("id"));
            if (id != null) {
                existMap.put(id, Boolean.TRUE);
            }
        }
        Set<Long> used = new HashSet<>();
        Long linkedMainId = null;
        JSONArray rebuiltOthers = new JSONArray();
        int sort = 1;
        for (Object[] row : rows) {
            Long diagId = (Long) row[0];
            String code = (String) row[1];
            String name = (String) row[2];
            int isMain = (Integer) row[3];
            Object admitCond = row[4];
            String effName = name != null ? name : code;
            Long assigned;
            if (diagId != null && existMap.containsKey(diagId) && !used.contains(diagId)) {
                jdbcTemplate.update("UPDATE his_inp_diagnosis SET diag_code = ?, diag_name = ?, is_main = ?, sort_no = ?,"
                                + " update_time = NOW() WHERE id = ? AND tenant_id = ? AND deleted = 0",
                        code, effName, isMain, sort, diagId, tid);
                assigned = diagId;
            } else {
                assigned = insertDiagRow(tid, orgId, visitId, code, effName, isMain, deptId, doctorId, sort);
            }
            if (assigned != null) {
                used.add(assigned);
            }
            if (isMain == 1) {
                linkedMainId = assigned;
            } else {
                JSONObject j = new JSONObject();
                j.put("diagId", assigned == null ? "" : String.valueOf(assigned));
                j.put("code", code == null ? "" : code);
                j.put("name", effName == null ? "" : effName);
                if (admitCond != null) {
                    j.put("admitCond", admitCond);
                }
                rebuiltOthers.add(j);
            }
            sort++;
        }
        // 3) 首页未引用的病历出院诊断行 → 逻辑删除(首页删行=病历删行)
        for (Long id : existMap.keySet()) {
            if (!used.contains(id)) {
                jdbcTemplate.update("UPDATE his_inp_diagnosis SET deleted = 1, update_time = NOW()"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0", id, tid);
            }
        }
        // 4) 关联键回写首页(含新插行 diagId), 前端 reload 即带键
        jdbcTemplate.update("UPDATE his_case_front_page SET main_diag_id = ?, discharge_other_diags = ?,"
                        + " update_time = NOW() WHERE visit_id = ? AND status = 1 AND tenant_id = ? AND deleted = 0",
                linkedMainId, rebuiltOthers.isEmpty() ? null : JSON.toJSONString(rebuiltOthers), visitId, tid);
        log.info("首页诊断回写出院诊断: visitId={}, 首页诊断行数={}, 主要诊断ID={}", visitId, rows.size(), linkedMainId);
    }

    /** 新插病历出院诊断行(diag_type=4), 返回生成主键; 列集合对齐 InpDiagnosisService.save */
    private Long insertDiagRow(final Long tid, final Long orgId, final Long visitId, final String code,
                               final String name, final int isMain, final Long deptId, final Long doctorId,
                               final int sortNo) {
        final String sql = "INSERT INTO his_inp_diagnosis"
                + " (tenant_id, org_id, inp_visit_id, diag_type, diag_code, diag_name, is_main,"
                + " diag_dept_id, diag_doctor_id, diag_time, sort_no, create_time, update_time, deleted)"
                + " VALUES (?, ?, ?, 4, ?, ?, ?, ?, ?, NOW(), ?, NOW(), NOW(), 0)";
        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tid);
            ps.setObject(2, orgId);
            ps.setLong(3, visitId);
            ps.setString(4, code);
            ps.setString(5, name);
            ps.setInt(6, isMain);
            ps.setObject(7, deptId);
            ps.setObject(8, doctorId);
            ps.setInt(9, sortNo);
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? null : key.longValue();
    }

    /** 去空白后取非空串, 空/null 返回 null */
    private static String trimOrNull(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /** 乐观更新失败原因诊断: 无记录报"请先生成", 状态不符报当前状态文案 */
    private void requireDraftReason(Long visitId, Long tid, int expectStatus) {
        Map<String, Object> fp = loadFrontPage(visitId, tid);
        if (fp == null) {
            throw new BizException(400, "病案首页尚未生成, 请先点击「生成」");
        }
        int status = intVal(fp.get("status"), 0);
        String expect = expectStatus == 1 ? "草稿" : "已提交";
        throw new BizException(400, "仅" + expect + "状态可执行该操作, 当前状态: "
                + (status == 1 ? "草稿" : status == 2 ? "已提交" : status == 3 ? "已审核" : "未知"));
    }

    private Map<String, Object> queryFirst(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** JSON 数组解析(存储为 JSON 字符串; 非法/空返回空列表, 前端按数组消费) */
    @SuppressWarnings("unchecked")
    private static List<Object> parseJsonArray(Object v) {
        if (v == null) {
            return new ArrayList<>();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            Object parsed = JSON.parse(s);
            if (parsed instanceof List) {
                return (List<Object>) parsed;
            }
            return new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 住院天数: DATEDIFF 口径(日期差); 出院日期未落按当日, 入院日期缺失返回 0 */
    private static long losDays(Object admissionDate, Object dischargeDate) {
        LocalDate admit = toLocalDate(admissionDate);
        if (admit == null) {
            return 0;
        }
        LocalDate disch = toLocalDate(dischargeDate);
        if (disch == null) {
            disch = LocalDate.now();
        }
        return Math.max(ChronoUnit.DAYS.between(admit, disch), 0);
    }

    private static LocalDate toLocalDate(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDate) {
            return (LocalDate) v;
        }
        if (v instanceof LocalDateTime) {
            return ((LocalDateTime) v).toLocalDate();
        }
        if (v instanceof java.sql.Date) {
            return ((java.sql.Date) v).toLocalDate();
        }
        if (v instanceof Timestamp) {
            return ((Timestamp) v).toLocalDateTime().toLocalDate();
        }
        if (v instanceof java.util.Date) {
            return new Timestamp(((java.util.Date) v).getTime()).toLocalDateTime().toLocalDate();
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return s.length() >= 10 ? LocalDate.parse(s.replace('T', ' ').substring(0, 10)) : LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 日期文本(yyyy-MM-dd), 无法解析返回 null */
    private static String dateText(Object v) {
        LocalDate d = toLocalDate(v);
        return d == null ? null : d.toString();
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static Long longVal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer intOrNull(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static int intVal(Object v, int def) {
        Integer i = intOrNull(v);
        return i == null ? def : i;
    }

    /** 手术切口类型值(1-4)透传, 非法返回 null */
    private static Integer anestInt(Object v) {
        Integer i = intOrNull(v);
        return i != null && i >= 1 && i <= 4 ? i : null;
    }

    private static String strVal(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static BigDecimal decVal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal) {
            return (BigDecimal) v;
        }
        if (v instanceof Number) {
            return new BigDecimal(v.toString());
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
