package com.yb.hi.service.doctor;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.doctor.HisDiagnosis;
import com.yb.hi.entity.doctor.HisDiseaseReport;
import com.yb.hi.entity.doctor.HisDiseaseReportDetail;
import com.yb.hi.entity.doctor.HisDiseaseReportSkip;
import com.yb.hi.entity.doctor.HisDiseaseReportTriggerRule;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.doctor.HisDiseaseReportDetailMapper;
import com.yb.hi.mapper.doctor.HisDiseaseReportMapper;
import com.yb.hi.mapper.doctor.HisDiseaseReportSkipMapper;
import com.yb.hi.service.outpatient.HisPatientService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 疾病报卡服务: 下达诊断时提示报卡并落库留痕。
 * P1 慢性病报告卡(严重精神障碍/恶性肿瘤): 患者区从 his_patient 自动带出、分型硬校验、
 * 状态机(待报/已报/已审核/退回)、初次-订正链、card_no 国标编号, 类型专有字段落 his_disease_report_detail。
 */
@Service
public class HisDiseaseReportService extends ServiceImpl<HisDiseaseReportMapper, HisDiseaseReport> {

    private final HisPatientService patientService;
    private final HisDiseaseReportDetailMapper detailMapper;
    private final DiseaseReportDict dictProvider;
    private final HisDiagnosisService diagnosisService;
    private final HisDiseaseReportSkipMapper skipMapper;
    private final HisDiseaseReportTriggerRuleService triggerRuleService;

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    public HisDiseaseReportService(HisPatientService patientService,
                                   HisDiseaseReportDetailMapper detailMapper,
                                   DiseaseReportDict dictProvider,
                                   HisDiagnosisService diagnosisService,
                                   HisDiseaseReportSkipMapper skipMapper,
                                   HisDiseaseReportTriggerRuleService triggerRuleService) {
        this.patientService = patientService;
        this.detailMapper = detailMapper;
        this.dictProvider = dictProvider;
        this.diagnosisService = diagnosisService;
        this.skipMapper = skipMapper;
        this.triggerRuleService = triggerRuleService;
    }

    /** 查询某次就诊的报卡列表(附各卡明细, 供回显) */
    public List<HisDiseaseReport> listByVisit(Long visitId) {
        List<HisDiseaseReport> list = lambdaQuery().eq(HisDiseaseReport::getVisitId, visitId)
                .orderByDesc(HisDiseaseReport::getId).list();
        for (HisDiseaseReport r : list) {
            r.setDetail(detailOf(r.getId()));
        }
        return list;
    }

    /** 取报卡明细 */
    public HisDiseaseReportDetail detailOf(Long reportId) {
        return detailMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HisDiseaseReportDetail>()
                .eq(HisDiseaseReportDetail::getReportId, reportId).last("LIMIT 1"));
    }

    /** 患者区自动带出预览(供前端报卡弹窗预填, 仅返回档案已有非空字段) */
    public Map<String, Object> patientSection(Long patientId) {
        Map<String, Object> form = new LinkedHashMap<>();
        if (patientId == null) {
            return form;
        }
        HisPatient p = patientService.getById(patientId);
        if (p != null) {
            applyPatientSection(form, p);
        }
        return form;
    }

    /**
     * 新建报卡(P1 全链路): 归一报卡大类 -> 患者区自动带出 -> 分型硬校验 -> 生成 card_no ->
     * 落主表 -> 落明细(form JSON + typed 列)。订正报告(reportForm=2)不改原卡, 新增子卡并链回 correct_prev_no。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisDiseaseReport create(HisDiseaseReport report) {
        if (report == null || report.getVisitId() == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        LoginUser user = UserContext.get();
        if (user != null && !StringUtils.hasText(report.getReporter())) {
            report.setReporter(user.getRealName());
        }
        // 报卡大类归一: 显式 reportCategory 优先, 否则由旧 reportType 映射(1传染病/2慢病->2/3其他->9)
        Integer cat = report.getReportCategory();
        if (cat == null) {
            cat = mapLegacyCategory(report.getReportType());
            report.setReportCategory(cat);
        }
        if (report.getReportType() == null) {
            report.setReportType(cat != null && cat == 1 ? 1 : (cat != null && cat >= 2 && cat <= 5 ? 2 : 3));
        }
        if (report.getReportForm() == null) {
            report.setReportForm(1);
        }
        if (report.getReportStatus() == null) {
            report.setReportStatus(0);
        }
        if (report.getReportTime() == null) {
            report.setReportTime(LocalDateTime.now());
        }

        // 患者区自动带出: 若提交 form 未含某项, 从 his_patient 回填(不覆盖医生已改值)
        Map<String, Object> form = report.getForm();
        if (form == null) {
            form = new LinkedHashMap<>();
        }
        HisPatient patient = report.getPatientId() != null ? patientService.getById(report.getPatientId()) : null;
        if (patient != null) {
            applyPatientSection(form, patient);
            if (report.getAutoFilled() == null) {
                report.setAutoFilled(1);
            }
        } else if (report.getAutoFilled() == null) {
            report.setAutoFilled(0);
        }
        report.setForm(form);
        // 冗余患者区列到主表, 供审核列表检索/展示(从自动带出的 form 取)
        report.setPatientName(str(form.get("name")));
        report.setPatientIdcard(str(form.get("idCard")));

        // 分型硬校验
        List<String> missing = validate(cat, report, form, patient);
        if (!missing.isEmpty()) {
            throw new BizException(400, "报卡必填项缺失: " + String.join("、", missing));
        }

        // card_no: 初次报告按 前缀+机构+yyyyMM+当月流水; 订正链校验原卡存在
        if (!StringUtils.hasText(report.getCardNo())) {
            report.setCardNo(genCardNo(cat, report, user));
        }
        if (Integer.valueOf(2).equals(report.getReportForm()) && StringUtils.hasText(report.getCorrectPrevNo())) {
            long prev = lambdaQuery().eq(HisDiseaseReport::getCardNo, report.getCorrectPrevNo()).count();
            if (prev == 0) {
                throw new BizException(400, "订正报告指向的原卡不存在: " + report.getCorrectPrevNo());
            }
        }
        if (!StringUtils.hasText(report.getReportNo())) {
            report.setReportNo(genReportNo(report.getReportType()));
        }

        save(report);

        // 落明细(form -> formData JSON + typed 列)
        HisDiseaseReportDetail detail = buildDetail(report, form);
        detailMapper.insert(detail);
        report.setDetail(detail);
        return report;
    }

    /** 状态: 标记已报(0->1) */
    public HisDiseaseReport markReported(Long id) {
        return transit(id, 0, 1, null);
    }

    /** 状态: 审核通过(1->2) */
    public HisDiseaseReport audit(Long id) {
        return transit(id, 1, 2, null);
    }

    /** 状态: 退回(携带退卡原因, 可重报) -> -1 */
    public HisDiseaseReport returnCard(Long id, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BizException(400, "退卡必须填写退卡原因");
        }
        return transit(id, null, -1, reason);
    }

    private HisDiseaseReport transit(Long id, Integer expectFrom, int to, String reason) {
        HisDiseaseReport r = getById(id);
        if (r == null) {
            throw new BizException(404, "报卡不存在: " + id);
        }
        if (expectFrom != null && !expectFrom.equals(r.getReportStatus())) {
            throw new BizException(409, "当前状态不允许该操作(需状态=" + expectFrom + ", 实际=" + r.getReportStatus() + ")");
        }
        r.setReportStatus(to);
        if (reason != null) {
            r.setReturnReason(reason);
        }
        updateById(r);
        return r;
    }

    /* ================= 内部: 自动带出 / 校验 / 明细 ================= */

    private Integer mapLegacyCategory(Integer reportType) {
        if (reportType == null) {
            return 1;
        }
        if (reportType == 1) {
            return 1; // 传染病
        }
        if (reportType == 2) {
            return 2; // 旧"慢性病"归入精障之外的慢病大类, 由前端显式细分
        }
        return 9;
    }

    /** 从患者档案回填"患者基本信息区"(仅填空, 不覆盖医生已提交值) */
    private void applyPatientSection(Map<String, Object> form, HisPatient p) {
        fill(form, "name", p.getName());
        fill(form, "gender", p.getGender());
        fill(form, "genderName", p.getGenderName());
        fill(form, "birthDate", p.getBirthDate() == null ? null : p.getBirthDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        fill(form, "age", p.getAge());
        fill(form, "idCard", p.getIdCard());
        fill(form, "phone", p.getPhone());
        fill(form, "nation", p.getNation());
        fill(form, "nationName", p.getNationName());
        fill(form, "nationality", p.getNationality());
        fill(form, "nationalityName", p.getNationalityName());
        fill(form, "maritalStatus", p.getMaritalStatus());
        fill(form, "maritalStatusName", p.getMaritalStatusName());
        fill(form, "eduLevel", p.getEduLevel());
        fill(form, "eduLevelName", p.getEduLevelName());
        fill(form, "occupation", p.getOccupation());
        fill(form, "occupationName", p.getOccupationName());
        fill(form, "employer", p.getEmployer());
        fill(form, "presentProv", p.getPresentProv());
        fill(form, "presentCity", p.getPresentCity());
        fill(form, "presentCounty", p.getPresentCounty());
        fill(form, "presentTown", p.getPresentTown());
        fill(form, "presentProvName", p.getPresentProvName());
        fill(form, "presentCityName", p.getPresentCityName());
        fill(form, "presentCountyName", p.getPresentCountyName());
        fill(form, "presentTownName", p.getPresentTownName());
        fill(form, "presentDetail", StringUtils.hasText(p.getPresentDetail()) ? p.getPresentDetail() : p.getAddress());
        fill(form, "contactName", p.getContactName());
        fill(form, "contactPhone", p.getContactPhone());
    }

    private void fill(Map<String, Object> form, String key, Object val) {
        if (val == null) {
            return;
        }
        Object cur = form.get(key);
        boolean blank = cur == null || (cur instanceof String && !StringUtils.hasText((String) cur));
        if (blank) {
            form.put(key, val);
        }
    }

    private boolean hasText(Map<String, Object> form, String key) {
        Object v = form.get(key);
        if (v == null) {
            return false;
        }
        return v instanceof String ? StringUtils.hasText((String) v) : true;
    }

    /** 分型硬校验: 返回缺失项中文标签列表(空=通过) */
    private List<String> validate(Integer cat, HisDiseaseReport r, Map<String, Object> form, HisPatient patient) {
        List<String> miss = new ArrayList<>();
        // 通用必填(所有卡型)
        requireForm(miss, form, "name", "患者姓名");
        requireForm(miss, form, "idCard", "有效证件号码");
        requireForm(miss, form, "gender", "性别");
        if (!hasText(form, "birthDate") && !hasText(form, "age")) {
            miss.add("出生日期或实足年龄");
        }
        requireForm(miss, form, "presentDetail", "现住址(详细地址)");
        requireForm(miss, form, "phone", "联系电话");
        if (!hasText(form, "occupation") && !hasText(form, "occupationName")) {
            miss.add("人群分类/职业");
        }
        if (r.getDiagTime() == null && !hasText(form, "diagTime")) {
            miss.add("诊断时间");
        }
        if (!StringUtils.hasText(r.getReporter())) {
            miss.add("填卡医生");
        }
        if (cat == null) {
            return miss;
        }
        if (cat == 1) {
            // 法定传染病: 疾病名称∈法定目录 + 病例分类 + 发病日期 + 诊断时间(通用必填已在上方)
            String dc = str(form.get("diseaseCode"));
            if (!StringUtils.hasText(dc) || !infectiousCodes().contains(dc)) {
                miss.add("疾病名称(法定传染病目录)");
            }
            if (!hasText(form, "caseType")) {
                miss.add("病例分类");
            }
            if (r.getOnsetDate() == null && !hasText(form, "onsetDate")) {
                miss.add("发病日期");
            }
        } else if (cat == 2) {
            // 严重精神障碍: 6 病之一 + 危险等级 + 发病时间
            String dc = str(form.get("diseaseCode"));
            if (!StringUtils.hasText(dc) || !smiCodes().contains(dc)) {
                miss.add("疾病名称(限严重精神障碍6种)");
            }
            if (r.getOnsetDate() == null && !hasText(form, "onsetDate")) {
                miss.add("发病/首次症状时间");
            }
            Object rl = form.get("riskLevel");
            if (rl == null || !StringUtils.hasText(str(rl))) {
                miss.add("危险等级(0-5)");
            }
            // 未成年或危险等级>=3: 须监护人
            boolean minor = isMinor(form, patient);
            boolean highRisk = rl != null && parseInt(str(rl)) >= 3;
            if (minor || highRisk) {
                if (!hasText(form, "guardianName") || !hasText(form, "guardianPhone")) {
                    miss.add(minor ? "监护人姓名及电话(未满18岁)" : "监护人姓名及电话(危险等级≥3级)");
                }
            }
        } else if (cat == 3) {
            // 恶性肿瘤: ICD-O-3 部位 + 诊断依据
            if (!hasText(form, "tumorTopo") && !hasText(form, "diseaseCode")) {
                miss.add("肿瘤部位(ICD-O-3)");
            }
            if (!hasText(form, "dxBasis")) {
                miss.add("诊断依据");
            }
        }
        return miss;
    }

    private void requireForm(List<String> miss, Map<String, Object> form, String key, String label) {
        if (!hasText(form, key)) {
            miss.add(label);
        }
    }

    private java.util.Set<String> smiCodes() {
        return dictProvider.dicts().get("smiDisease") == null ? java.util.Collections.emptySet()
                : ((List<Map<String, Object>>) dictProvider.dicts().get("smiDisease")).stream()
                    .map(m -> str(m.get("code"))).filter(StringUtils::hasText).collect(Collectors.toSet());
    }

    /** 法定传染病目录代表码集(供校验与触发匹配, 归一大写) */
    private java.util.Set<String> infectiousCodes() {
        return dictProvider.dicts().get("infectiousDisease") == null ? java.util.Collections.emptySet()
                : ((List<Map<String, Object>>) dictProvider.dicts().get("infectiousDisease")).stream()
                    .map(m -> str(m.get("code"))).filter(StringUtils::hasText).map(String::toUpperCase).collect(Collectors.toSet());
    }

    private boolean isMinor(Map<String, Object> form, HisPatient patient) {
        Integer age = parseInt(str(form.get("age")));
        if (age == null && patient != null) {
            age = patient.getAge();
        }
        return age != null && age < 18;
    }

    private Integer parseInt(String s) {
        try {
            return StringUtils.hasText(s) ? Integer.parseInt(s.trim()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** 构建明细: form 序列化进 formData, 并抽取 typed 列 */
    private HisDiseaseReportDetail buildDetail(HisDiseaseReport r, Map<String, Object> form) {
        HisDiseaseReportDetail d = new HisDiseaseReportDetail();
        d.setReportId(r.getId());
        d.setFormData(JSON.toJSONString(form));
        d.setDiseaseCode(pick(form, "diseaseCode", "tumorTopo"));
        d.setDiseaseName(pick(form, "diseaseName", "tumorTopoName"));
        d.setIcdCode(pick(form, "icdCode", "tumorTopo"));
        Integer risk = parseInt(str(form.get("riskLevel")));
        if (risk != null) {
            d.setRiskLevel(risk);
        }
        d.setStage(str(form.get("stage")));
        d.setTumorSite(pick(form, "tumorTopoName", "tumorSite"));
        return d;
    }

    private String pick(Map<String, Object> form, String... keys) {
        for (String k : keys) {
            String v = str(form.get(k));
            if (StringUtils.hasText(v)) {
                return v;
            }
        }
        return null;
    }

    /** card_no = 类型前缀(INF传染/SMI精障/TUM肿瘤/其)+yyyyMM+6位当月流水(租户级) */
    private String genCardNo(Integer cat, HisDiseaseReport r, LoginUser user) {
        String prefix = cat == null ? "BK" : (cat == 1 ? "INF" : cat == 2 ? "SMI" : cat == 3 ? "TUM" : "CH" + cat);
        String ym = LocalDateTime.now().format(YM);
        long seq = countMonth(cat) + 1;
        return prefix + ym + String.format("%06d", seq);
    }

    private long countMonth(Integer cat) {
        LocalDateTime monthStart = LocalDateTime.now().withDayOfMonth(1).toLocalDate().atStartOfDay();
        return lambdaQuery()
                .eq(cat != null, HisDiseaseReport::getReportCategory, cat)
                .ge(HisDiseaseReport::getCreateTime, monthStart)
                .count();
    }

    /** 本地报卡编号: BK + 类型 + 时间戳(占位, 后续对接上报通道 upload-center) */
    private String genReportNo(Integer type) {
        return "BK" + (type == null ? 1 : type) + System.currentTimeMillis();
    }

    /* ================= 报卡全流程: 触发判定 / 漏报留痕 / 审核分页 / 统计 / 导出 ================= */

    /** 某就诊需报卡清单: 逐诊断按触发字典判定大类; 已存在同 visit+diag 报卡或已"暂不报卡"则不再提示 */
    public List<Map<String, Object>> checkTrigger(Long visitId) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (visitId == null) {
            return out;
        }
        List<HisDiagnosis> diags = diagnosisService.listByVisit(visitId);
        if (diags == null || diags.isEmpty()) {
            return out;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        // 触发规则查表(启用集按 priority 升序一次加载): 维护界面增删改启停即时生效, 不再依赖硬编码
        List<HisDiseaseReportTriggerRule> rules = triggerRuleService.listEnabledOrdered();
        for (HisDiagnosis d : diags) {
            String code = d.getDiagCode();
            if (!StringUtils.hasText(code)) {
                continue;
            }
            Integer cat = matchCat(code, d.getDiagClass(), rules);
            if (cat == null || !seen.add(code + "#" + cat)) {
                continue;
            }
            long reported = lambdaQuery().eq(HisDiseaseReport::getVisitId, visitId)
                    .eq(HisDiseaseReport::getDiagCode, code)
                    .eq(HisDiseaseReport::getReportCategory, cat).count();
            if (reported > 0) {
                continue;
            }
            Long skipped = skipMapper.selectCount(new LambdaQueryWrapper<HisDiseaseReportSkip>()
                    .eq(HisDiseaseReportSkip::getVisitId, visitId)
                    .eq(HisDiseaseReportSkip::getDiagCode, code)
                    .eq(HisDiseaseReportSkip::getReportCategory, cat));
            if (skipped != null && skipped > 0) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("diagCode", code);
            m.put("diagName", d.getDiagName());
            m.put("reportCategory", cat);
            m.put("cardLabel", cardLabel(cat));
            m.put("mandatory", true);
            out.add(m);
        }
        return out;
    }

    /** 触发判定(查表): 启用规则按 priority 升序首个命中即定大类; prefix=诊断码前缀, exact=诊断码精确, class=诊断类别等值; 全不中返回 null */
    private Integer matchCat(String diagCode, String diagClass, List<HisDiseaseReportTriggerRule> rules) {
        String c = diagCode == null ? "" : diagCode.trim().toUpperCase();
        for (HisDiseaseReportTriggerRule r : rules) {
            String p = r.getCodePattern() == null ? "" : r.getCodePattern().trim();
            if (p.isEmpty()) {
                continue;
            }
            String mt = r.getMatchType() == null ? "prefix" : r.getMatchType().trim().toLowerCase();
            if ("class".equals(mt)) {
                if (StringUtils.hasText(diagClass) && diagClass.trim().equalsIgnoreCase(p)) {
                    return r.getReportCategory();
                }
            } else if ("exact".equals(mt)) {
                if (!c.isEmpty() && c.equals(p.toUpperCase())) {
                    return r.getReportCategory();
                }
            } else {
                if (!c.isEmpty() && c.startsWith(p.toUpperCase())) {
                    return r.getReportCategory();
                }
            }
        }
        return null;
    }

    private String cardLabel(Integer cat) {
        if (cat == null) {
            return "疾病报告卡";
        }
        switch (cat) {
            case 1: return "法定传染病报告卡";
            case 2: return "严重精神障碍发病报告卡";
            case 3: return "恶性肿瘤病例报告卡";
            case 4: return "高血压报告卡";
            case 5: return "糖尿病报告卡";
            default: return "疾病报告卡";
        }
    }

    /** 暂不报卡留痕(落 his_disease_report_skip, 供漏报监控) */
    public HisDiseaseReportSkip saveSkip(HisDiseaseReportSkip skip) {
        if (skip == null || skip.getVisitId() == null || skip.getReportCategory() == null) {
            throw new BizException(400, "暂不报卡需就诊ID与应报类别");
        }
        LoginUser user = UserContext.get();
        if (user != null && !StringUtils.hasText(skip.getSkipBy())) {
            skip.setSkipBy(user.getRealName());
        }
        if (skip.getSkipTime() == null) {
            skip.setSkipTime(LocalDateTime.now());
        }
        skipMapper.insert(skip);
        return skip;
    }

    public List<HisDiseaseReportSkip> listSkipped(Long visitId) {
        return skipMapper.selectList(new LambdaQueryWrapper<HisDiseaseReportSkip>()
                .eq(HisDiseaseReportSkip::getVisitId, visitId).orderByDesc(HisDiseaseReportSkip::getId));
    }

    /** 审核工作台分页 */
    public IPage<HisDiseaseReport> auditPage(List<Integer> cats, Integer status, String reporter, String keyword,
                                             LocalDateTime from, LocalDateTime to, long pageNo, long pageSize) {
        LambdaQueryWrapper<HisDiseaseReport> w = new LambdaQueryWrapper<>();
        w.in(cats != null && !cats.isEmpty(), HisDiseaseReport::getReportCategory, cats);
        w.eq(status != null, HisDiseaseReport::getReportStatus, status);
        w.eq(StringUtils.hasText(reporter), HisDiseaseReport::getReporter, reporter);
        if (StringUtils.hasText(keyword)) {
            w.and(x -> x.like(HisDiseaseReport::getPatientName, keyword)
                    .or().like(HisDiseaseReport::getPatientIdcard, keyword)
                    .or().like(HisDiseaseReport::getCardNo, keyword));
        }
        w.ge(from != null, HisDiseaseReport::getReportTime, from);
        w.le(to != null, HisDiseaseReport::getReportTime, to);
        w.orderByDesc(HisDiseaseReport::getId);
        return page(new Page<>(pageNo, pageSize), w);
    }

    /** 审核统计: 各状态计数 + 本月新增 + 迟报 + 漏报(暂不报卡留痕) */
    public Map<String, Object> stats(List<Integer> cats, LocalDateTime from, LocalDateTime to) {
        boolean hasCats = cats != null && !cats.isEmpty();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pending", countStatus(cats, from, to, 0));
        out.put("reported", countStatus(cats, from, to, 1));
        out.put("audited", countStatus(cats, from, to, 2));
        out.put("returned", countStatus(cats, from, to, -1));
        out.put("total", countStatus(cats, from, to, null));
        LocalDateTime monthStart = LocalDateTime.now().withDayOfMonth(1).toLocalDate().atStartOfDay();
        out.put("monthNew", lambdaQuery().in(hasCats, HisDiseaseReport::getReportCategory, cats)
                .ge(HisDiseaseReport::getCreateTime, monthStart).count());
        long late = 0;
        List<HisDiseaseReport> range = lambdaQuery()
                .in(hasCats, HisDiseaseReport::getReportCategory, cats)
                .isNotNull(HisDiseaseReport::getReportTime)
                .ge(from != null, HisDiseaseReport::getReportTime, from)
                .le(to != null, HisDiseaseReport::getReportTime, to)
                .list();
        for (HisDiseaseReport r : range) {
            LocalDateTime start = r.getOnsetDate() != null ? r.getOnsetDate() : r.getDiagTime();
            if (start == null) {
                continue;
            }
            long hours = java.time.Duration.between(start, r.getReportTime()).toHours();
            long threshold = Integer.valueOf(1).equals(r.getReportCategory()) ? 24 : 24 * 7;
            if (hours > threshold) {
                late++;
            }
        }
        out.put("late", late);
        Long miss = skipMapper.selectCount(new LambdaQueryWrapper<HisDiseaseReportSkip>()
                .in(hasCats, HisDiseaseReportSkip::getReportCategory, cats)
                .ge(from != null, HisDiseaseReportSkip::getSkipTime, from)
                .le(to != null, HisDiseaseReportSkip::getSkipTime, to));
        out.put("missed", miss == null ? 0 : miss);
        return out;
    }

    private long countStatus(List<Integer> cats, LocalDateTime from, LocalDateTime to, Integer status) {
        return lambdaQuery()
                .in(cats != null && !cats.isEmpty(), HisDiseaseReport::getReportCategory, cats)
                .eq(status != null, HisDiseaseReport::getReportStatus, status)
                .ge(from != null, HisDiseaseReport::getReportTime, from)
                .le(to != null, HisDiseaseReport::getReportTime, to)
                .count();
    }

    /** 按国标固定列导出报卡 CSV(单大类): cat=1传染病/2精障/3肿瘤/其余通用; 前置 UTF-8 BOM 供 Excel */
    public String exportCsv(Integer cat, LocalDateTime from, LocalDateTime to) {
        List<HisDiseaseReport> list = lambdaQuery()
                .eq(cat != null, HisDiseaseReport::getReportCategory, cat)
                .ge(from != null, HisDiseaseReport::getReportTime, from)
                .le(to != null, HisDiseaseReport::getReportTime, to)
                .orderByAsc(HisDiseaseReport::getId).list();
        String[] headers;
        if (Integer.valueOf(1).equals(cat)) {
            headers = new String[]{"卡片编号", "姓名", "性别", "出生日期", "证件号", "电话", "现住址", "疾病名称", "疾病ICD", "病例分类", "发病日期", "诊断日期", "填卡医生", "报告日期", "状态"};
        } else if (Integer.valueOf(2).equals(cat)) {
            headers = new String[]{"卡片编号", "姓名", "性别", "出生日期", "证件号", "电话", "现住址", "疾病名称", "ICD", "危险等级", "发病日期", "诊断日期", "监护人", "监护人电话", "填卡医生", "报告日期", "状态"};
        } else if (Integer.valueOf(3).equals(cat)) {
            headers = new String[]{"卡片编号", "姓名", "性别", "出生日期", "证件号", "电话", "现住址", "肿瘤部位", "ICD-O-3", "形态学", "行为", "侧别", "分化", "临床分期", "诊断依据", "诊断日期", "填卡医生", "报告日期", "状态"};
        } else {
            headers = new String[]{"卡片编号", "姓名", "性别", "出生日期", "证件号", "电话", "现住址", "诊断", "报卡大类", "诊断日期", "填卡医生", "报告日期", "状态"};
        }
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF');
        sb.append(joinCsv(Arrays.asList(headers))).append("\r\n");
        for (HisDiseaseReport r : list) {
            Map<String, Object> fd = parseForm(r);
            List<String> cells = new ArrayList<>();
            cells.add(r.getCardNo());
            cells.add(str(fd.get("name")));
            cells.add(fd.get("genderName") != null ? str(fd.get("genderName")) : str(fd.get("gender")));
            cells.add(str(fd.get("birthDate")));
            cells.add(str(fd.get("idCard")));
            cells.add(str(fd.get("phone")));
            cells.add(str(fd.get("presentDetail")));
            if (Integer.valueOf(1).equals(cat)) {
                cells.add(str(fd.get("diseaseName")));
                cells.add(str(fd.get("diseaseCode")));
                cells.add(str(fd.get("caseType")));
                cells.add(r.getOnsetDate() == null ? "" : r.getOnsetDate().toString());
                cells.add(r.getDiagTime() == null ? "" : r.getDiagTime().toString());
            } else if (Integer.valueOf(2).equals(cat)) {
                cells.add(str(fd.get("diseaseName")));
                cells.add(str(fd.get("icdCode")));
                cells.add(str(fd.get("riskLevel")));
                cells.add(r.getOnsetDate() == null ? "" : r.getOnsetDate().toString());
                cells.add(r.getDiagTime() == null ? "" : r.getDiagTime().toString());
                cells.add(str(fd.get("guardianName")));
                cells.add(str(fd.get("guardianPhone")));
            } else if (Integer.valueOf(3).equals(cat)) {
                cells.add(str(fd.get("tumorTopoName")));
                cells.add(str(fd.get("tumorTopo")));
                cells.add(str(fd.get("tumorMorph")));
                cells.add(str(fd.get("behavior")));
                cells.add(str(fd.get("laterality")));
                cells.add(str(fd.get("differentiation")));
                cells.add(str(fd.get("stage")));
                cells.add(str(fd.get("dxBasis")));
                cells.add(r.getDiagTime() == null ? "" : r.getDiagTime().toString());
            } else {
                cells.add(r.getDiagName());
                cells.add(str(r.getReportCategory()));
                cells.add(r.getDiagTime() == null ? "" : r.getDiagTime().toString());
            }
            cells.add(r.getReporter());
            cells.add(r.getReportTime() == null ? "" : r.getReportTime().toString());
            cells.add(statusLabel(r.getReportStatus()));
            sb.append(joinCsv(cells)).append("\r\n");
        }
        return sb.toString();
    }

    private Map<String, Object> parseForm(HisDiseaseReport r) {
        HisDiseaseReportDetail d = detailOf(r.getId());
        if (d == null || !StringUtils.hasText(d.getFormData())) {
            return new LinkedHashMap<>();
        }
        try {
            return JSON.parseObject(d.getFormData());
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String joinCsv(List<String> cells) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                s.append(',');
            }
            String v = cells.get(i);
            v = v == null ? "" : v.replace("\"", "\"\"");
            s.append('"').append(v).append('"');
        }
        return s.toString();
    }

    private String statusLabel(Integer st) {
        if (st == null) {
            return "";
        }
        switch (st) {
            case 0: return "待报";
            case 1: return "已报";
            case 2: return "已审核";
            case -1: return "退回";
            default: return String.valueOf(st);
        }
    }
}
