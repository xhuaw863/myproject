package com.yb.hi.service.emr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.entity.doctor.HisDiagnosis;
import com.yb.hi.entity.doctor.HisOrder;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.entity.inpatient.HisEmrElement;
import com.yb.hi.entity.inpatient.HisInpDiagnosis;
import com.yb.hi.entity.inpatient.HisInpMedicalRecord;
import com.yb.hi.entity.inpatient.HisInpOrder;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.basedata.HisDeptMapper;
import com.yb.hi.mapper.basedata.HisStaffMapper;
import com.yb.hi.mapper.doctor.HisDiagnosisMapper;
import com.yb.hi.mapper.doctor.HisOrderMapper;
import com.yb.hi.mapper.doctor.HisVisitMapper;
import com.yb.hi.mapper.inpatient.HisEmrElementMapper;
import com.yb.hi.mapper.inpatient.HisInpDiagnosisMapper;
import com.yb.hi.mapper.inpatient.HisInpMedicalRecordMapper;
import com.yb.hi.mapper.inpatient.HisInpOrderMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.outpatient.HisPatientMapper;
import com.yb.hi.service.doctor.EmrStructureReader;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 患者360°时间轴聚合服务: 把门诊(his_visit)与住院(his_inp_visit)两类就诊事件按患者维度
 * 聚合成统一时间轴(倒序, 默认最近100条), 支撑患者全景视图/就诊历史回溯。
 *
 * 数据口径(与 EmrExportService/EmrSignatureService 一致):
 *  - scope 1=住院(visitId 即 his_inp_visit.id) / 2=门诊(visitId 即 his_visit.id);
 *  - 门诊主诉/现病史等经 {@link EmrStructureReader} 自 his_visit.structure 派生(缺失回退 SOAP 文本列);
 *  - 关键病历数据元(主诉/诊断类 fieldKey)取自 his_emr_element(结构化抽取轨, 按 scope+visitId 定位);
 *  - 门诊诊断走 his_diagnosis(vali_flag=0 剔除), 住院诊断走 his_inp_diagnosis(主诊断优先)。
 *
 * 隔离与容错:
 *  - 读路径全部经 MyBatis-Plus Mapper, 租户插件自动注入 tenant_id, 无需显式机构条件;
 *  - 已取消就诊不进时间轴(门诊 visit_status=4 / 住院 visit_status=5);
 *  - 时间缺省回退: 门诊 visit_time→work_date→create_time, 住院 admit_date→create_time;
 *  - 诊断/医嘱/病历/数据元缺失时返回空集合而非报错, 聚合为尽力而为。
 */
@Slf4j
@Service
public class EmrTimelineService {

    /** scope 口径: 1住院 2门诊(与 EmrExportService/EmrSignatureService 对齐) */
    public static final int SCOPE_INP = 1;
    public static final int SCOPE_OUTP = 2;
    /** 时间轴事件类型(types 过滤参数取值) */
    public static final String TYPE_OUTPATIENT = "outpatient";
    public static final String TYPE_INPATIENT = "inpatient";

    private static final int DEFAULT_LIMIT = 100;
    private static final int SUMMARY_MAX = 120;
    private static final int DETAIL_TEXT_MAX = 160;
    private static final int KEY_ELEMENT_MAX = 6;
    private static final int KEY_ELEMENT_TEXT_MAX = 100;
    private static final int RECORD_LIST_MAX = 20;
    private static final int ORDER_LIST_MAX = 50;
    private static final int LATEST_RECORD_TITLES = 5;
    private static final DateTimeFormatter D_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /* ---------- 状态/枚举文本(口径取自各实体注释) ---------- */
    private static final Map<Integer, String> OUTP_STATUS_TEXT = statusText(1, "候诊", 2, "接诊中", 3, "已完成", 4, "已取消");
    private static final Map<Integer, String> INP_STATUS_TEXT = statusText(1, "待入院", 2, "在院", 3, "出院办理中", 4, "已出院", 5, "已取消");
    private static final Map<Integer, String> INP_DIAG_TYPE_TEXT = statusText(1, "入院诊断", 2, "补充诊断", 3, "术后诊断", 4, "出院诊断");
    private static final Map<Integer, String> INP_RECORD_TYPE_TEXT = statusText(1, "入院记录", 2, "首次病程", 3, "日常病程", 4, "查房记录",
            5, "术前小结", 6, "手术记录", 7, "术后病程", 8, "出院小结", 9, "死亡记录");
    private static final Map<Integer, String> INP_RECORD_STATUS_TEXT = statusText(1, "草稿", 2, "已提交", 3, "已审核");
    private static final Map<Integer, String> INP_ORDER_CATEGORY_TEXT = statusText(1, "药品", 2, "检查", 3, "检验", 4, "治疗",
            5, "护理", 6, "膳食", 7, "其他");
    private static final Map<Integer, String> INP_ORDER_STATUS_TEXT = statusText(1, "新开", 2, "已审核", 3, "执行中", 4, "已完成", 5, "已停止", 6, "已作废");
    private static final Map<Integer, String> OUTP_ORDER_STATUS_TEXT = statusText(1, "已开", 2, "已执行", 3, "已退");
    private static final Map<Integer, String> CONDITION_LEVEL_TEXT = statusText(1, "危", 2, "重", 3, "一般");

    private final HisVisitMapper visitMapper;
    private final HisInpVisitMapper inpVisitMapper;
    private final HisPatientMapper patientMapper;
    private final HisDiagnosisMapper diagnosisMapper;
    private final HisInpDiagnosisMapper inpDiagnosisMapper;
    private final HisOrderMapper orderMapper;
    private final HisInpOrderMapper inpOrderMapper;
    private final HisEmrElementMapper elementMapper;
    private final HisInpMedicalRecordMapper inpRecordMapper;
    private final HisDeptMapper deptMapper;
    private final HisStaffMapper staffMapper;

    public EmrTimelineService(HisVisitMapper visitMapper, HisInpVisitMapper inpVisitMapper,
                              HisPatientMapper patientMapper, HisDiagnosisMapper diagnosisMapper,
                              HisInpDiagnosisMapper inpDiagnosisMapper, HisOrderMapper orderMapper,
                              HisInpOrderMapper inpOrderMapper, HisEmrElementMapper elementMapper,
                              HisInpMedicalRecordMapper inpRecordMapper,
                              HisDeptMapper deptMapper, HisStaffMapper staffMapper) {
        this.visitMapper = visitMapper;
        this.inpVisitMapper = inpVisitMapper;
        this.patientMapper = patientMapper;
        this.diagnosisMapper = diagnosisMapper;
        this.inpDiagnosisMapper = inpDiagnosisMapper;
        this.orderMapper = orderMapper;
        this.inpOrderMapper = inpOrderMapper;
        this.elementMapper = elementMapper;
        this.inpRecordMapper = inpRecordMapper;
        this.deptMapper = deptMapper;
        this.staffMapper = staffMapper;
    }

    /* ================= 患者360时间轴 ================= */

    /**
     * 患者全景时间轴: 门诊就诊 + 住院入院两类事件统一倒序(默认最近100条)。
     *
     * @param patientId 患者ID(his_patient.id)
     * @param startDate 起始日期 yyyy-MM-dd(含, 可空)
     * @param endDate   结束日期 yyyy-MM-dd(含, 可空; 闭区间语义)
     * @param types     事件类型过滤, 逗号分隔: outpatient,inpatient(空=全部)
     */
    public List<TimelineItem> getTimeline(Long patientId, String startDate, String endDate, String types) {
        HisPatient patient = requirePatient(patientId);
        Set<String> typeFilter = parseTypes(types);
        LocalDateTime[] range = parseRange(startDate, endDate);

        List<TimelineItem> items = new ArrayList<>();
        if (typeFilter.contains(TYPE_OUTPATIENT)) {
            items.addAll(outpatientItems(patient.getId(), range));
        }
        if (typeFilter.contains(TYPE_INPATIENT)) {
            items.addAll(inpatientItems(patient.getId(), range));
        }
        // 统一按事件时间倒序(时间缺省排最后), 时间相同按 id 稳定排序
        Comparator<TimelineItem> byTimeDesc = Comparator.comparing(TimelineItem::getTime,
                Comparator.nullsLast(Comparator.reverseOrder()));
        items.sort(byTimeDesc.thenComparing(TimelineItem::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        if (items.size() > DEFAULT_LIMIT) {
            items = new ArrayList<>(items.subList(0, DEFAULT_LIMIT));
        }
        log.debug("患者时间轴聚合完成: patientId={}, 区间=[{}, {}], 类型={}, 条目={}条(截断至{})",
                patientId, startDate, endDate, typeFilter, items.size(), DEFAULT_LIMIT);
        return items;
    }

    /** 门诊事件: his_visit(剔除已取消) → 时间轴条目; 批量装配诊断/医嘱/关键数据元避免逐条 N+1 */
    private List<TimelineItem> outpatientItems(Long patientId, LocalDateTime[] range) {
        LambdaQueryWrapper<HisVisit> qw = Wrappers.<HisVisit>lambdaQuery()
                .eq(HisVisit::getPatientId, patientId)
                .ne(HisVisit::getVisitStatus, 4)
                .orderByDesc(HisVisit::getVisitTime);
        if (range != null && range[0] != null) {
            // 时间列缺省(候诊未接诊)的行不因日期条件被 SQL 误排除, 留给内存口径按 work_date 兜底裁决
            qw.and(w -> w.isNull(HisVisit::getVisitTime).or().ge(HisVisit::getVisitTime, range[0]));
        }
        if (range != null && range[1] != null) {
            qw.and(w -> w.isNull(HisVisit::getVisitTime).or().lt(HisVisit::getVisitTime, range[1]));
        }
        List<HisVisit> visits = visitMapper.selectList(qw);
        if (visits.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> visitIds = visits.stream().map(HisVisit::getId).collect(Collectors.toList());
        Map<Long, List<HisDiagnosis>> diagByVisit = groupBy(diagnosisMapper.selectList(
                Wrappers.<HisDiagnosis>lambdaQuery()
                        .in(HisDiagnosis::getVisitId, visitIds)
                        .orderByAsc(HisDiagnosis::getDiagSrtNo)), HisDiagnosis::getVisitId);
        Map<Long, List<HisOrder>> orderByVisit = groupBy(orderMapper.selectList(
                Wrappers.<HisOrder>lambdaQuery().in(HisOrder::getVisitId, visitIds)), HisOrder::getVisitId);
        Map<Long, List<HisEmrElement>> elemByVisit = groupBy(elementMapper.selectList(
                Wrappers.<HisEmrElement>lambdaQuery()
                        .eq(HisEmrElement::getScope, SCOPE_OUTP)
                        .in(HisEmrElement::getVisitId, visitIds)
                        .orderByAsc(HisEmrElement::getSortNo)), HisEmrElement::getVisitId);

        List<TimelineItem> items = new ArrayList<>(visits.size());
        for (HisVisit v : visits) {
            LocalDateTime time = effectiveOutpTime(v);
            if (!inRange(time, range)) {
                continue;
            }
            Map<String, String> soap = EmrStructureReader.read(v);
            List<Map<String, Object>> diagnoses = outpDiagMaps(diagByVisit.get(v.getId()));
            String diagText = joinNames(diagnoses);
            List<HisOrder> orders = orderByVisit.getOrDefault(v.getId(), Collections.emptyList());

            TimelineItem t = new TimelineItem();
            t.setId(TYPE_OUTPATIENT + "-" + v.getId());
            t.setType(TYPE_OUTPATIENT);
            t.setTime(time);
            t.setVisitId(v.getId());
            t.setDeptName(v.getDeptName());
            t.setDoctorName(v.getDrName());
            t.setTitle("门诊就诊");
            t.setSummary(outpSummary(soap.get("chiefComplaint"), diagText));
            t.setDetails(row(
                    "patientName", v.getPatientName(), "gender", v.getGender(), "age", v.getAge(),
                    "visitStatus", v.getVisitStatus(), "visitStatusText", textOf(OUTP_STATUS_TEXT, v.getVisitStatus()),
                    "workDate", v.getWorkDate(), "finishTime", v.getFinishTime(), "medType", v.getMedType(),
                    "chiefComplaint", truncate(soap.get("chiefComplaint"), DETAIL_TEXT_MAX),
                    "presentIllness", truncate(soap.get("presentIllness"), DETAIL_TEXT_MAX),
                    "diagnosisText", diagText, "diagnoses", diagnoses,
                    "orderCount", orders.size(),
                    "orderTypes", orders.stream().map(HisOrder::getOrderType)
                            .filter(StringUtils::hasText).distinct().collect(Collectors.toList()),
                    "keyElements", keyElementMaps(elemByVisit.get(v.getId()))));
            items.add(t);
        }
        return items;
    }

    /** 住院事件: his_inp_visit(剔除已取消) → 时间轴条目; 科室/医师名称批量反查 */
    private List<TimelineItem> inpatientItems(Long patientId, LocalDateTime[] range) {
        LambdaQueryWrapper<HisInpVisit> qw = Wrappers.<HisInpVisit>lambdaQuery()
                .eq(HisInpVisit::getPatientId, patientId)
                .ne(HisInpVisit::getVisitStatus, 5)
                .orderByDesc(HisInpVisit::getAdmitDate);
        if (range != null && range[0] != null) {
            qw.and(w -> w.isNull(HisInpVisit::getAdmitDate).or().ge(HisInpVisit::getAdmitDate, range[0]));
        }
        if (range != null && range[1] != null) {
            qw.and(w -> w.isNull(HisInpVisit::getAdmitDate).or().lt(HisInpVisit::getAdmitDate, range[1]));
        }
        List<HisInpVisit> visits = inpVisitMapper.selectList(qw);
        if (visits.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> visitIds = visits.stream().map(HisInpVisit::getId).collect(Collectors.toList());
        Map<Long, List<HisInpDiagnosis>> diagByVisit = groupBy(inpDiagnosisMapper.selectList(
                Wrappers.<HisInpDiagnosis>lambdaQuery()
                        .in(HisInpDiagnosis::getInpVisitId, visitIds)
                        .orderByAsc(HisInpDiagnosis::getSortNo)), HisInpDiagnosis::getInpVisitId);
        Map<Long, List<HisInpOrder>> orderByVisit = groupBy(inpOrderMapper.selectList(
                Wrappers.<HisInpOrder>lambdaQuery()
                        .in(HisInpOrder::getInpVisitId, visitIds)
                        .orderByDesc(HisInpOrder::getStartTime)), HisInpOrder::getInpVisitId);
        Map<Long, List<HisEmrElement>> elemByVisit = groupBy(elementMapper.selectList(
                Wrappers.<HisEmrElement>lambdaQuery()
                        .eq(HisEmrElement::getScope, SCOPE_INP)
                        .in(HisEmrElement::getVisitId, visitIds)
                        .orderByAsc(HisEmrElement::getSortNo)), HisEmrElement::getVisitId);
        Map<Long, List<HisInpMedicalRecord>> recordByVisit = groupBy(inpRecordMapper.selectList(
                Wrappers.<HisInpMedicalRecord>lambdaQuery()
                        .select(HisInpMedicalRecord::getId, HisInpMedicalRecord::getInpVisitId,
                                HisInpMedicalRecord::getRecordType, HisInpMedicalRecord::getTitle,
                                HisInpMedicalRecord::getRecordTime)
                        .in(HisInpMedicalRecord::getInpVisitId, visitIds)
                        .orderByDesc(HisInpMedicalRecord::getRecordTime)), HisInpMedicalRecord::getInpVisitId);
        Map<Long, String> deptNames = deptNames(visits.stream()
                .map(HisInpVisit::getDeptId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, String> staffNames = staffNames(visits.stream()
                .map(HisInpVisit::getDoctorId).filter(Objects::nonNull).collect(Collectors.toSet()));

        List<TimelineItem> items = new ArrayList<>(visits.size());
        for (HisInpVisit v : visits) {
            LocalDateTime time = effectiveInpTime(v);
            if (!inRange(time, range)) {
                continue;
            }
            List<Map<String, Object>> diagnoses = inpDiagMaps(diagByVisit.get(v.getId()));
            String diagText = joinNames(diagnoses);
            List<HisInpOrder> activeOrders = orderByVisit.getOrDefault(v.getId(), Collections.emptyList())
                    .stream().filter(o -> o.getOrderStatus() == null || o.getOrderStatus() != 6)
                    .collect(Collectors.toList());
            List<HisInpMedicalRecord> records = recordByVisit.getOrDefault(v.getId(), Collections.emptyList());

            TimelineItem t = new TimelineItem();
            t.setId(TYPE_INPATIENT + "-" + v.getId());
            t.setType(TYPE_INPATIENT);
            t.setTime(time);
            t.setVisitId(v.getId());
            t.setDeptName(v.getDeptId() == null ? null : deptNames.get(v.getDeptId()));
            t.setDoctorName(v.getDoctorId() == null ? null : staffNames.get(v.getDoctorId()));
            t.setTitle(StringUtils.hasText(v.getInpNo()) ? "住院(" + v.getInpNo() + ")" : "住院");
            t.setSummary(inpSummaryText(v.getAdmitDiag(), diagText,
                    v.getDischargeDate() != null ? "已出院" : textOf(INP_STATUS_TEXT, v.getVisitStatus())));
            t.setDetails(row(
                    "inpNo", v.getInpNo(), "admitDate", v.getAdmitDate(), "dischargeDate", v.getDischargeDate(),
                    "inDays", inDays(v), "visitStatus", v.getVisitStatus(),
                    "visitStatusText", textOf(INP_STATUS_TEXT, v.getVisitStatus()),
                    "admitDiag", truncate(v.getAdmitDiag(), DETAIL_TEXT_MAX),
                    "diagnosisText", diagText, "diagnoses", diagnoses,
                    "totalCost", v.getTotalCost(), "depositBalance", v.getDepositBalance(),
                    "orderCount", activeOrders.size(), "orderCategories", orderCategoryCounts(activeOrders),
                    "recordCount", records.size(),
                    "latestRecords", records.stream().limit(LATEST_RECORD_TITLES)
                            .map(EmrTimelineService::recordTitle).collect(Collectors.toList()),
                    "keyElements", keyElementMaps(elemByVisit.get(v.getId()))));
            items.add(t);
        }
        return items;
    }

    /* ================= 就诊摘要 ================= */

    /**
     * 就诊摘要: 主诉/诊断/关键医嘱/重要提示的结构化汇总。
     *
     * @param visitId 就诊ID(scope=2→his_visit.id; scope=1→his_inp_visit.id)
     * @param scope   1住院 2门诊
     */
    public Map<String, Object> getVisitSummary(Long visitId, int scope) {
        if (visitId == null) {
            throw new BizException(400, "visitId 不能为空");
        }
        if (scope != SCOPE_INP && scope != SCOPE_OUTP) {
            throw new BizException(400, "非法 scope: " + scope + "(允许: 1住院 2门诊)");
        }
        return scope == SCOPE_INP ? inpVisitSummary(visitId) : outpVisitSummary(visitId);
    }

    /** 门诊就诊摘要: SOAP(结构化派生) + 诊断 + 医嘱 + 数据元 */
    private Map<String, Object> outpVisitSummary(Long visitId) {
        HisVisit v = visitMapper.selectById(visitId);
        if (v == null) {
            throw new BizException(404, "门诊就诊不存在: " + visitId);
        }
        Map<String, String> soap = EmrStructureReader.read(v);
        List<Map<String, Object>> diagnoses = outpDiagMaps(diagnosisMapper.selectList(
                Wrappers.<HisDiagnosis>lambdaQuery()
                        .eq(HisDiagnosis::getVisitId, visitId)
                        .orderByAsc(HisDiagnosis::getDiagSrtNo)));
        List<HisOrder> orders = orderMapper.selectList(Wrappers.<HisOrder>lambdaQuery()
                .eq(HisOrder::getVisitId, visitId).orderByDesc(HisOrder::getId));
        List<Map<String, Object>> elements = elementMaps(elementMapper.selectList(
                Wrappers.<HisEmrElement>lambdaQuery()
                        .eq(HisEmrElement::getScope, SCOPE_OUTP)
                        .eq(HisEmrElement::getVisitId, visitId)
                        .orderByAsc(HisEmrElement::getFieldKey)
                        .orderByAsc(HisEmrElement::getSortNo)));
        return row(
                "scope", SCOPE_OUTP, "type", TYPE_OUTPATIENT, "visitId", visitId,
                "patient", patientBrief(v.getPatientId(), v.getPatientNo(),
                        v.getPatientName(), v.getGender(), v.getAge()),
                "deptId", v.getDeptId(), "deptName", v.getDeptName(), "doctorName", v.getDrName(),
                "visitTime", v.getVisitTime(), "finishTime", v.getFinishTime(),
                "visitStatus", v.getVisitStatus(), "visitStatusText", textOf(OUTP_STATUS_TEXT, v.getVisitStatus()),
                "chargeStatus", v.getChargeStatus(), "medType", v.getMedType(),
                "chiefComplaint", soap.get("chiefComplaint"), "presentIllness", soap.get("presentIllness"),
                "pastHistory", soap.get("pastHistory"), "allergyHistory", soap.get("allergyHistory"),
                "physicalExam", soap.get("physicalExam"), "vitals", soap.get("vitals"),
                "auxExam", soap.get("auxExam"), "treatmentOpinion", soap.get("treatmentOpinion"),
                "diagnoses", diagnoses, "diagnosisText", firstNonBlank(joinNames(diagnoses), soap.get("diagnosis")),
                "orderCount", orders.size(), "orders", outpOrderMaps(orders),
                "emrElementCount", elements.size(), "emrElements", elements,
                "importantNotes", outpImportantNotes(soap, v.getFollowupNote()));
    }

    /** 住院就诊摘要: 入院口径 + 四类诊断 + 病历文书 + 医嘱 + 数据元 */
    private Map<String, Object> inpVisitSummary(Long visitId) {
        HisInpVisit v = inpVisitMapper.selectById(visitId);
        if (v == null) {
            throw new BizException(404, "住院就诊不存在: " + visitId);
        }
        List<Map<String, Object>> diagnoses = inpDiagMaps(inpDiagnosisMapper.selectList(
                Wrappers.<HisInpDiagnosis>lambdaQuery()
                        .eq(HisInpDiagnosis::getInpVisitId, visitId)
                        .orderByAsc(HisInpDiagnosis::getSortNo)));
        List<HisInpMedicalRecord> records = inpRecordMapper.selectList(
                Wrappers.<HisInpMedicalRecord>lambdaQuery()
                        .eq(HisInpMedicalRecord::getInpVisitId, visitId)
                        .orderByDesc(HisInpMedicalRecord::getRecordTime));
        List<HisInpOrder> orders = inpOrderMapper.selectList(Wrappers.<HisInpOrder>lambdaQuery()
                .eq(HisInpOrder::getInpVisitId, visitId).orderByDesc(HisInpOrder::getStartTime));
        List<HisEmrElement> elements = elementMapper.selectList(Wrappers.<HisEmrElement>lambdaQuery()
                .eq(HisEmrElement::getScope, SCOPE_INP)
                .eq(HisEmrElement::getVisitId, visitId)
                .orderByAsc(HisEmrElement::getFieldKey)
                .orderByAsc(HisEmrElement::getSortNo));

        // 科室/主治/责任护士/文书书写人名称反查
        Set<Long> staffIds = new LinkedHashSet<>();
        if (v.getDoctorId() != null) {
            staffIds.add(v.getDoctorId());
        }
        if (v.getNurseId() != null) {
            staffIds.add(v.getNurseId());
        }
        for (HisInpMedicalRecord r : records) {
            if (r.getDoctorId() != null) {
                staffIds.add(r.getDoctorId());
            }
        }
        Map<Long, String> staffNames = staffNames(staffIds);
        return row(
                "scope", SCOPE_INP, "type", TYPE_INPATIENT, "visitId", visitId, "inpNo", v.getInpNo(),
                "patient", patientBrief(v.getPatientId(), null, null, null, null),
                "deptId", v.getDeptId(),
                "deptName", v.getDeptId() == null ? null
                        : deptNames(Collections.singleton(v.getDeptId())).get(v.getDeptId()),
                "doctorName", v.getDoctorId() == null ? null : staffNames.get(v.getDoctorId()),
                "nurseName", v.getNurseId() == null ? null : staffNames.get(v.getNurseId()),
                "admitDate", v.getAdmitDate(), "dischargeDate", v.getDischargeDate(), "inDays", inDays(v),
                "visitStatus", v.getVisitStatus(), "visitStatusText", textOf(INP_STATUS_TEXT, v.getVisitStatus()),
                "admitDiag", v.getAdmitDiag(),
                // 住院"主诉"取关键数据元(主诉类 fieldKey)拼接; 缺失为 null(前端按入院诊断兜底展示)
                "chiefComplaint", chiefComplaintText(elements),
                "conditionLevel", v.getConditionLevel(),
                "conditionLevelText", textOf(CONDITION_LEVEL_TEXT, v.getConditionLevel()),
                "totalCost", v.getTotalCost(), "depositBalance", v.getDepositBalance(),
                "diagnoses", diagnoses, "diagnosisText", joinNames(diagnoses),
                "recordCount", records.size(), "records", inpRecordMaps(records, staffNames, RECORD_LIST_MAX),
                "orderTotal", orders.size(), "orders", inpOrderMaps(orders, ORDER_LIST_MAX),
                "emrElementCount", elements.size(), "emrElements", elementMaps(elements),
                "importantNotes", inpImportantNotes(records, v.getConditionLevel()));
    }

    /** 门诊重要提示: 过敏史/处理意见/随访建议 */
    private static List<String> outpImportantNotes(Map<String, String> soap, String followupNote) {
        List<String> notes = new ArrayList<>();
        if (StringUtils.hasText(soap.get("allergyHistory"))) {
            notes.add("过敏史: " + soap.get("allergyHistory").trim());
        }
        if (StringUtils.hasText(soap.get("treatmentOpinion"))) {
            notes.add("处理意见: " + soap.get("treatmentOpinion").trim());
        }
        if (StringUtils.hasText(followupNote)) {
            notes.add("随访建议: " + followupNote.trim());
        }
        return notes;
    }

    /** 住院重要提示: 病情等级(危/重) + 关键文书(术前小结/手术记录/出院小结/死亡记录) */
    private static List<String> inpImportantNotes(List<HisInpMedicalRecord> records, Integer conditionLevel) {
        List<String> notes = new ArrayList<>();
        String cond = textOf(CONDITION_LEVEL_TEXT, conditionLevel);
        if ("危".equals(cond) || "重".equals(cond)) {
            notes.add("病情等级: " + cond);
        }
        for (HisInpMedicalRecord r : records) {
            Integer type = r.getRecordType();
            if (type != null && (type == 5 || type == 6 || type == 8 || type == 9)) {
                notes.add((StringUtils.hasText(r.getTitle()) ? r.getTitle()
                        : INP_RECORD_TYPE_TEXT.getOrDefault(type, "关键文书"))
                        + "(" + INP_RECORD_TYPE_TEXT.getOrDefault(type, "未知类型") + ")");
            }
        }
        return notes;
    }

    /* ================= 患者总览 ================= */

    /** 患者总览: 就诊总量(门诊+住院) / 最近一次就诊 / 最近就诊的有效诊断 / 当前在院。 */
    public Map<String, Object> getPatientOverview(Long patientId) {
        HisPatient p = requirePatient(patientId);
        Long outpCount = visitMapper.selectCount(Wrappers.<HisVisit>lambdaQuery()
                .eq(HisVisit::getPatientId, patientId).ne(HisVisit::getVisitStatus, 4));
        Long inpCount = inpVisitMapper.selectCount(Wrappers.<HisInpVisit>lambdaQuery()
                .eq(HisInpVisit::getPatientId, patientId).ne(HisInpVisit::getVisitStatus, 5));
        List<HisVisit> lastOutpList = visitMapper.selectList(Wrappers.<HisVisit>lambdaQuery()
                .eq(HisVisit::getPatientId, patientId).ne(HisVisit::getVisitStatus, 4)
                .orderByDesc(HisVisit::getVisitTime).last("LIMIT 1"));
        List<HisInpVisit> lastInpList = inpVisitMapper.selectList(Wrappers.<HisInpVisit>lambdaQuery()
                .eq(HisInpVisit::getPatientId, patientId).ne(HisInpVisit::getVisitStatus, 5)
                .orderByDesc(HisInpVisit::getAdmitDate).last("LIMIT 1"));
        HisVisit lastOutp = lastOutpList.isEmpty() ? null : lastOutpList.get(0);
        HisInpVisit lastInp = lastInpList.isEmpty() ? null : lastInpList.get(0);
        LocalDateTime outpTime = lastOutp == null ? null : effectiveOutpTime(lastOutp);
        LocalDateTime inpTime = lastInp == null ? null : effectiveInpTime(lastInp);

        Map<String, Object> lastVisit = null;
        List<Map<String, Object>> activeDiagnoses = Collections.emptyList();
        boolean outpLatest = lastInp == null || (lastOutp != null && inpTime == null)
                || (outpTime != null && inpTime != null && outpTime.isAfter(inpTime));
        if (outpLatest && lastOutp != null) {
            // 有效诊断(vali_flag 剔除)自最近一次门诊就诊取, 查一次复用
            List<Map<String, Object>> diags = outpDiagMaps(diagnosisMapper.selectList(
                    Wrappers.<HisDiagnosis>lambdaQuery()
                            .eq(HisDiagnosis::getVisitId, lastOutp.getId())
                            .orderByAsc(HisDiagnosis::getDiagSrtNo)));
            Map<String, String> soap = EmrStructureReader.read(lastOutp);
            lastVisit = row("type", TYPE_OUTPATIENT, "visitId", lastOutp.getId(), "time", outpTime,
                    "deptName", lastOutp.getDeptName(), "doctorName", lastOutp.getDrName(),
                    "summary", outpSummary(soap.get("chiefComplaint"), joinNames(diags)));
            activeDiagnoses = diags;
        } else if (lastInp != null) {
            List<Map<String, Object>> diags = inpDiagMaps(inpDiagnosisMapper.selectList(
                    Wrappers.<HisInpDiagnosis>lambdaQuery()
                            .eq(HisInpDiagnosis::getInpVisitId, lastInp.getId())
                            .orderByAsc(HisInpDiagnosis::getSortNo)));
            lastVisit = row("type", TYPE_INPATIENT, "visitId", lastInp.getId(), "inpNo", lastInp.getInpNo(),
                    "time", inpTime,
                    "deptName", lastInp.getDeptId() == null ? null
                            : deptNames(Collections.singleton(lastInp.getDeptId())).get(lastInp.getDeptId()),
                    "doctorName", lastInp.getDoctorId() == null ? null
                            : staffNames(Collections.singleton(lastInp.getDoctorId())).get(lastInp.getDoctorId()),
                    "summary", inpSummaryText(lastInp.getAdmitDiag(), joinNames(diags),
                            lastInp.getDischargeDate() != null ? "已出院"
                                    : textOf(INP_STATUS_TEXT, lastInp.getVisitStatus())));
            activeDiagnoses = diags;
        }

        // 当前在院(visit_status=2): 无则 null
        List<HisInpVisit> currentList = inpVisitMapper.selectList(Wrappers.<HisInpVisit>lambdaQuery()
                .eq(HisInpVisit::getPatientId, patientId)
                .eq(HisInpVisit::getVisitStatus, 2)
                .orderByDesc(HisInpVisit::getAdmitDate)
                .last("LIMIT 1"));
        Map<String, Object> currentInpatient = null;
        if (!currentList.isEmpty()) {
            HisInpVisit cur = currentList.get(0);
            currentInpatient = row(
                    "visitId", cur.getId(), "inpNo", cur.getInpNo(), "admitDate", cur.getAdmitDate(),
                    "inDays", inDays(cur),
                    "deptName", cur.getDeptId() == null ? null
                            : deptNames(Collections.singleton(cur.getDeptId())).get(cur.getDeptId()),
                    "doctorName", cur.getDoctorId() == null ? null
                            : staffNames(Collections.singleton(cur.getDoctorId())).get(cur.getDoctorId()),
                    "admitDiag", cur.getAdmitDiag(), "depositBalance", cur.getDepositBalance());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patient", row(
                "patientId", p.getId(), "patientNo", p.getPatientNo(), "name", p.getName(),
                "gender", p.getGender(), "genderName", p.getGenderName(), "age", p.getAge(),
                "birthDate", p.getBirthDate(), "phone", p.getPhone()));
        out.put("outpatientCount", outpCount == null ? 0L : outpCount);
        out.put("inpatientCount", inpCount == null ? 0L : inpCount);
        out.put("totalVisits", (outpCount == null ? 0L : outpCount) + (inpCount == null ? 0L : inpCount));
        out.put("lastVisit", lastVisit);
        out.put("activeDiagnoses", activeDiagnoses);
        out.put("currentInpatient", currentInpatient);
        return out;
    }

    /* ================= 参数解析与取值口径 ================= */

    private HisPatient requirePatient(Long patientId) {
        if (patientId == null) {
            throw new BizException(400, "patientId 不能为空");
        }
        HisPatient p = patientMapper.selectById(patientId);
        if (p == null) {
            throw new BizException(404, "患者不存在: " + patientId);
        }
        return p;
    }

    /** types 过滤参数: 空白=全部; 非法取值整体报 400 */
    private static Set<String> parseTypes(String types) {
        if (!StringUtils.hasText(types)) {
            return new LinkedHashSet<>(Arrays.asList(TYPE_OUTPATIENT, TYPE_INPATIENT));
        }
        Set<String> out = new LinkedHashSet<>();
        for (String t : types.split(",")) {
            String s = t.trim().toLowerCase();
            if (TYPE_OUTPATIENT.equals(s)) {
                out.add(TYPE_OUTPATIENT);
            } else if (TYPE_INPATIENT.equals(s)) {
                out.add(TYPE_INPATIENT);
            }
        }
        if (out.isEmpty()) {
            throw new BizException(400, "非法就诊类型: " + types + "(允许: outpatient,inpatient)");
        }
        return out;
    }

    /** 日期区间解析: yyyy-MM-dd 闭区间 → [start 当日零点, end+1日零点) 半开区间; 单边允许 */
    private static LocalDateTime[] parseRange(String startDate, String endDate) {
        if (!StringUtils.hasText(startDate) && !StringUtils.hasText(endDate)) {
            return null;
        }
        LocalDateTime start = null;
        LocalDateTime end = null;
        try {
            if (StringUtils.hasText(startDate)) {
                start = LocalDate.parse(startDate.trim(), D_DATE).atStartOfDay();
            }
            if (StringUtils.hasText(endDate)) {
                end = LocalDate.parse(endDate.trim(), D_DATE).plusDays(1).atStartOfDay();
            }
        } catch (Exception e) {
            throw new BizException(400, "日期格式非法(应为 yyyy-MM-dd): startDate=" + startDate + ", endDate=" + endDate);
        }
        if (start != null && end != null && start.isAfter(end)) {
            throw new BizException(400, "开始日期不能晚于结束日期");
        }
        return new LocalDateTime[]{start, end};
    }

    /** 事件时间是否落在区间(内存权威口径; 无有效时间且指定了区间则排除) */
    private static boolean inRange(LocalDateTime time, LocalDateTime[] range) {
        if (range == null) {
            return true;
        }
        if (time == null) {
            return false;
        }
        if (range[0] != null && time.isBefore(range[0])) {
            return false;
        }
        if (range[1] != null && !time.isBefore(range[1])) {
            return false;
        }
        return true;
    }

    /** 门诊事件时间: visit_time → work_date → create_time */
    private static LocalDateTime effectiveOutpTime(HisVisit v) {
        if (v.getVisitTime() != null) {
            return v.getVisitTime();
        }
        if (v.getWorkDate() != null) {
            return v.getWorkDate().atStartOfDay();
        }
        return v.getCreateTime();
    }

    /** 住院事件时间: admit_date → create_time */
    private static LocalDateTime effectiveInpTime(HisInpVisit v) {
        return v.getAdmitDate() != null ? v.getAdmitDate() : v.getCreateTime();
    }

    /** 住院天数: 入院至出院/当前, 含首日, 最小 1 天(与 EmrMacroService.inp_days 同口径) */
    private static Long inDays(HisInpVisit v) {
        if (v.getAdmitDate() == null) {
            return null;
        }
        LocalDateTime end = v.getDischargeDate() != null ? v.getDischargeDate() : LocalDateTime.now();
        return Math.max(ChronoUnit.DAYS.between(v.getAdmitDate().toLocalDate(), end.toLocalDate()) + 1, 1L);
    }

    /* ================= 装配辅助 ================= */

    /** 批量按 key 分组(跳过 null key, 避免 groupingBy NPE) */
    private static <K, V> Map<K, List<V>> groupBy(List<V> rows, Function<V, K> key) {
        Map<K, List<V>> out = new HashMap<>();
        if (rows == null) {
            return out;
        }
        for (V v : rows) {
            if (v == null) {
                continue;
            }
            K k = key.apply(v);
            if (k != null) {
                out.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
            }
        }
        return out;
    }

    private Map<Long, String> deptNames(Collection<Long> deptIds) {
        if (deptIds == null || deptIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return deptMapper.selectBatchIds(deptIds).stream().filter(Objects::nonNull)
                .collect(Collectors.toMap(HisDept::getId,
                        d -> StringUtils.hasText(d.getDeptName()) ? d.getDeptName() : "", (a, b) -> a));
    }

    private Map<Long, String> staffNames(Collection<Long> staffIds) {
        if (staffIds == null || staffIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return staffMapper.selectBatchIds(staffIds).stream().filter(Objects::nonNull)
                .collect(Collectors.toMap(HisStaff::getId,
                        s -> StringUtils.hasText(s.getStaffName()) ? s.getStaffName() : "", (a, b) -> a));
    }

    /** 患者概要: 档案缺失时以就诊冗余字段兜底 */
    private Map<String, Object> patientBrief(Long patientId, String fallbackNo, String fallbackName,
                                             String fallbackGender, Integer fallbackAge) {
        HisPatient p = patientId == null ? null : patientMapper.selectById(patientId);
        if (p != null) {
            return row("patientId", patientId, "patientNo", p.getPatientNo(), "name", p.getName(),
                    "gender", p.getGenderName() != null ? p.getGenderName() : p.getGender(),
                    "age", p.getAge(), "birthDate", p.getBirthDate(), "phone", p.getPhone());
        }
        return row("patientId", patientId, "patientNo", fallbackNo, "name", fallbackName,
                "gender", fallbackGender, "age", fallbackAge);
    }

    /** 门诊诊断视图: 主诊断优先, vali_flag=0 剔除 */
    private static List<Map<String, Object>> outpDiagMaps(List<HisDiagnosis> diags) {
        List<HisDiagnosis> eff = new ArrayList<>();
        if (diags != null) {
            for (HisDiagnosis d : diags) {
                if (d != null && !"0".equals(d.getValiFlag())) {
                    eff.add(d);
                }
            }
        }
        eff.sort(Comparator.comparing((HisDiagnosis d) -> !"1".equals(d.getMaindiagFlag()))
                .thenComparing(d -> d.getDiagSrtNo() == null ? Integer.MAX_VALUE : d.getDiagSrtNo()));
        List<Map<String, Object>> out = new ArrayList<>(eff.size());
        for (HisDiagnosis d : eff) {
            out.add(row("diagCode", d.getDiagCode(), "diagName", d.getDiagName(),
                    "isMain", "1".equals(d.getMaindiagFlag()), "diagType", d.getDiagType(),
                    "diagTime", d.getDiagTime()));
        }
        return out;
    }

    /** 住院诊断视图: 主诊断优先, 再按诊断类型(入院→补充→术后→出院)与排序号 */
    private static List<Map<String, Object>> inpDiagMaps(List<HisInpDiagnosis> diags) {
        List<HisInpDiagnosis> eff = diags == null ? new ArrayList<>() : new ArrayList<>(diags);
        eff.sort(Comparator.comparing((HisInpDiagnosis d) -> d.getIsMain() == null || d.getIsMain() != 1)
                .thenComparing(d -> d.getDiagType() == null ? 99 : d.getDiagType())
                .thenComparing(d -> d.getSortNo() == null ? Integer.MAX_VALUE : d.getSortNo()));
        List<Map<String, Object>> out = new ArrayList<>(eff.size());
        for (HisInpDiagnosis d : eff) {
            out.add(row("diagCode", d.getDiagCode(), "diagName", d.getDiagName(),
                    "isMain", d.getIsMain() != null && d.getIsMain() == 1,
                    "diagType", d.getDiagType(), "diagTypeText", textOf(INP_DIAG_TYPE_TEXT, d.getDiagType()),
                    "diagTime", d.getDiagTime()));
        }
        return out;
    }

    private static List<Map<String, Object>> outpOrderMaps(List<HisOrder> orders) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (orders == null) {
            return out;
        }
        for (HisOrder o : orders) {
            out.add(row("orderNo", o.getOrderNo(), "orderType", o.getOrderType(), "diagName", o.getDiagName(),
                    "status", o.getStatus(), "statusText", textOf(OUTP_ORDER_STATUS_TEXT, o.getStatus()),
                    "execStatus", o.getExecStatus(),
                    "execStatusText", o.getExecStatus() == null ? null : (o.getExecStatus() == 1 ? "已执行" : "未执行"),
                    "paidFlag", o.getPaidFlag(), "totalAmount", o.getTotalAmount()));
        }
        return out;
    }

    private static List<Map<String, Object>> inpOrderMaps(List<HisInpOrder> orders, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (orders == null) {
            return out;
        }
        for (HisInpOrder o : orders) {
            if (out.size() >= limit) {
                break;
            }
            out.add(row("orderType", o.getOrderType(),
                    "orderTypeText", o.getOrderType() == null ? null : (o.getOrderType() == 1 ? "长期" : o.getOrderType() == 2 ? "临时" : null),
                    "orderCategory", o.getOrderCategory(),
                    "orderCategoryText", textOf(INP_ORDER_CATEGORY_TEXT, o.getOrderCategory()),
                    "orderContent", o.getOrderContent(), "spec", o.getSpec(),
                    "dosage", o.getDosage() == null ? null
                            : o.getDosage() + (StringUtils.hasText(o.getDosageUnit()) ? o.getDosageUnit() : ""),
                    "startTime", o.getStartTime(), "stopTime", o.getStopTime(),
                    "orderStatus", o.getOrderStatus(),
                    "orderStatusText", textOf(INP_ORDER_STATUS_TEXT, o.getOrderStatus())));
        }
        return out;
    }

    private static List<Map<String, Object>> inpRecordMaps(List<HisInpMedicalRecord> records,
                                                           Map<Long, String> staffNames, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (records == null) {
            return out;
        }
        for (HisInpMedicalRecord r : records) {
            if (out.size() >= limit) {
                break;
            }
            out.add(row("id", r.getId(), "recordType", r.getRecordType(),
                    "recordTypeText", textOf(INP_RECORD_TYPE_TEXT, r.getRecordType()),
                    "title", r.getTitle(), "recordTime", r.getRecordTime(),
                    "status", r.getStatus(), "statusText", textOf(INP_RECORD_STATUS_TEXT, r.getStatus()),
                    "doctorName", r.getDoctorId() == null ? null : staffNames.get(r.getDoctorId())));
        }
        return out;
    }

    private static List<Map<String, Object>> elementMaps(List<HisEmrElement> elements) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (elements == null) {
            return out;
        }
        for (HisEmrElement e : elements) {
            if (e == null) {
                continue;
            }
            out.add(row("fieldKey", e.getFieldKey(), "fieldLabel", e.getFieldLabel(),
                    "termCode", e.getTermCode(), "dictSource", e.getDictSource(),
                    "valueText", e.getValueText(), "valueNum", e.getValueNum(),
                    "valueDate", e.getValueDate(), "sortNo", e.getSortNo()));
        }
        return out;
    }

    /** 关键数据元(主诉/诊断类 fieldKey)视图, 每次就诊最多 KEY_ELEMENT_MAX 条 */
    private static List<Map<String, Object>> keyElementMaps(List<HisEmrElement> elements) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (elements == null) {
            return out;
        }
        for (HisEmrElement e : elements) {
            if (e == null || out.size() >= KEY_ELEMENT_MAX) {
                break;
            }
            boolean chief = chiefComplaintLike(e.getFieldKey(), e.getFieldLabel());
            boolean diag = !chief && diagnosisLike(e.getFieldKey(), e.getFieldLabel());
            if (!chief && !diag) {
                continue;
            }
            out.add(row("fieldKey", e.getFieldKey(), "fieldLabel", e.getFieldLabel(),
                    "kind", chief ? "chiefComplaint" : "diagnosis",
                    "valueText", truncate(e.getValueText(), KEY_ELEMENT_TEXT_MAX),
                    "termCode", e.getTermCode()));
        }
        return out;
    }

    /** 住院主诉文本: 取主诉类数据元拼接 */
    private static String chiefComplaintText(List<HisEmrElement> elements) {
        if (elements == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (HisEmrElement e : elements) {
            if (e == null || !chiefComplaintLike(e.getFieldKey(), e.getFieldLabel())) {
                continue;
            }
            String t = e.getValueText();
            if (StringUtils.hasText(t)) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append(t.trim());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 主诉类字段识别: fieldKey 含 chief(去下划线) 或 label 含"主诉" */
    private static boolean chiefComplaintLike(String fieldKey, String fieldLabel) {
        String k = fieldKey == null ? "" : fieldKey.toLowerCase().replace("_", "");
        if (k.contains("chiefcomplaint") || k.contains("chief") || k.equals("cc")) {
            return true;
        }
        return fieldLabel != null && fieldLabel.contains("主诉");
    }

    /** 诊断类字段识别: fieldKey 含 diag/diagnosis 或 label 含"诊断" */
    private static boolean diagnosisLike(String fieldKey, String fieldLabel) {
        String k = fieldKey == null ? "" : fieldKey.toLowerCase().replace("_", "");
        if (k.contains("diagnosis") || k.contains("diag")) {
            return true;
        }
        return fieldLabel != null && fieldLabel.contains("诊断");
    }

    private static Map<String, Integer> orderCategoryCounts(List<HisInpOrder> orders) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (HisInpOrder o : orders) {
            if (o != null && o.getOrderCategory() != null) {
                out.merge(textOf(INP_ORDER_CATEGORY_TEXT, o.getOrderCategory()), 1, Integer::sum);
            }
        }
        return out;
    }

    private static String recordTitle(HisInpMedicalRecord r) {
        String text = textOf(INP_RECORD_TYPE_TEXT, r.getRecordType());
        if (StringUtils.hasText(r.getTitle())) {
            return r.getTitle() + (text == null ? "" : "(" + text + ")");
        }
        return text == null ? "病历文书" : text;
    }

    private static String outpSummary(String chiefComplaint, String diagText) {
        if (StringUtils.hasText(chiefComplaint)) {
            return truncate(chiefComplaint, SUMMARY_MAX);
        }
        if (StringUtils.hasText(diagText)) {
            return truncate("诊断: " + diagText, SUMMARY_MAX);
        }
        return "无主诉/诊断记录";
    }

    private static String inpSummaryText(String admitDiag, String diagText, String statusTail) {
        String base;
        if (StringUtils.hasText(admitDiag)) {
            base = "入院诊断: " + admitDiag.trim();
        } else if (StringUtils.hasText(diagText)) {
            base = "诊断: " + diagText;
        } else {
            base = "无入院诊断记录";
        }
        if (StringUtils.hasText(statusTail)) {
            base = base + " · " + statusTail;
        }
        return truncate(base, SUMMARY_MAX);
    }

    private static String joinNames(List<Map<String, Object>> diagMaps) {
        if (diagMaps == null || diagMaps.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : diagMaps) {
            Object name = m.get("diagName");
            if (name != null && StringUtils.hasText(String.valueOf(name))) {
                if (sb.length() > 0) {
                    sb.append("、");
                }
                sb.append(String.valueOf(name).trim());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String firstNonBlank(String a, String b) {
        return StringUtils.hasText(a) ? a : b;
    }

    private static String textOf(Map<Integer, String> dict, Integer key) {
        return key == null ? null : dict.getOrDefault(key, "未知");
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }

    /** 有序键值对 → LinkedHashMap(装配辅助, 保持 JSON 字段顺序稳定) */
    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** 状态文本字典构造: (code, text, ...) 成对传入 */
    private static Map<Integer, String> statusText(Object... kv) {
        Map<Integer, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((Integer) kv[i], (String) kv[i + 1]);
        }
        return m;
    }

    /* ================= DTO ================= */

    /** 时间轴条目: 门诊就诊/住院入院事件的统一视图 */
    @Data
    public static class TimelineItem {
        /** 条目唯一ID(type-visitId 前缀拼接, 规避两表自增/雪花ID撞号) */
        private String id;
        /** 事件类型: outpatient / inpatient */
        private String type;
        /** 事件时间(门诊 visit_time→work_date 兜底; 住院 admit_date) */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime time;
        /** 事件标题 */
        private String title;
        /** 一句话摘要(主诉/入院诊断 + 状态) */
        private String summary;
        /** 就诊ID(门诊 his_visit.id / 住院 his_inp_visit.id) */
        private Long visitId;
        /** 科室名称 */
        private String deptName;
        /** 医师姓名 */
        private String doctorName;
        /** 明细(状态/诊断/医嘱/关键数据元等) */
        private Map<String, Object> details;
    }
}
