package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.dto.inpatient.EmrMacroDTO;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.entity.inpatient.HisBed;
import com.yb.hi.entity.inpatient.HisEmrMacro;
import com.yb.hi.entity.inpatient.HisInpAllergy;
import com.yb.hi.entity.inpatient.HisInpDiagnosis;
import com.yb.hi.entity.inpatient.HisInpMedicalRecord;
import com.yb.hi.entity.inpatient.HisInpNursingRecord;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisSurgery;
import com.yb.hi.entity.inpatient.HisWard;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.basedata.HisDeptMapper;
import com.yb.hi.mapper.basedata.HisStaffMapper;
import com.yb.hi.mapper.inpatient.HisBedMapper;
import com.yb.hi.mapper.inpatient.HisEmrMacroMapper;
import com.yb.hi.mapper.inpatient.HisInpAllergyMapper;
import com.yb.hi.mapper.inpatient.HisInpDiagnosisMapper;
import com.yb.hi.mapper.inpatient.HisInpMedicalRecordMapper;
import com.yb.hi.mapper.inpatient.HisInpNursingRecordMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.inpatient.HisSurgeryMapper;
import com.yb.hi.mapper.inpatient.HisWardMapper;
import com.yb.hi.mapper.outpatient.HisPatientMapper;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.platform.entity.SysTenant;
import com.yb.hi.platform.mapper.SysOrgMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.platform.service.SysTenantService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 病历宏变量服务: 维护患者/就诊/诊断/医嘱/检验/体征六源宏变量(30 个标准种子), 书写时按 {macroCode}
 * 占位符自动替换; 启动时按租户幂等播种(@Order(9))。
 *
 * 编码约定: his_emr_macro 唯一键 uk_macro_code(macro_code, deleted) 不含租户 —— 标准编码为全租户共享的
 * 全局主数据(tenant_id=1 优先获得标准编码); 播种前用 JdbcTemplate 全局查重(绕开租户插件), 编码已被其他
 * 租户占用则跳过, 各租户可自建扩展宏。createMacro 对重复编码返回友好 400。
 *
 * 特殊解析(非简单字段直取): admit_diag/discharge_diag/main_diag 查诊断表(主诊断优先), allergy_info 汇总
 * 有效过敏(无则"无"), vital_signs 取最新体温单, surgery_* 取最近未取消手术, past_history 回溯病历
 * structureData(患者表无既往史字段, 无则"无"), inp_days 按入院至出院/当前计算(含首日);
 * 自定义宏按 dataSource+sourceField 反射通用解析。
 */
@Slf4j
@Order(9)
@Service
public class EmrMacroService implements ApplicationRunner {

    /** {macroCode} 占位符 */
    private static final Pattern MACRO_PATTERN = Pattern.compile("\\{([A-Za-z0-9_]+)\\}");
    private static final String DEFAULT_DT = "yyyy-MM-dd HH:mm";
    private static final String DEFAULT_DATE = "yyyy-MM-dd";

    /** 种子宏变量: {编码, 名称, dataSource(1患者 2就诊 3诊断 4医嘱 5检验 6体征), 来源字段, 格式化, 说明} */
    private static final String[][] MACRO_SEEDS = {
            {"patient_name", "患者姓名", "1", "name", "", "患者基本信息姓名"},
            {"gender", "性别", "1", "genderName", "", "患者性别(按医保字典回填名称)"},
            {"age", "年龄", "1", "age", "", "患者年龄"},
            {"id_card", "身份证号", "1", "idCard", "", "患者身份证号"},
            {"admit_date", "入院日期", "2", "admitDate", "yyyy-MM-dd HH:mm", "住院就诊入院日期"},
            {"discharge_date", "出院日期", "2", "dischargeDate", "yyyy-MM-dd HH:mm", "住院就诊出院日期(未出院为空)"},
            {"inp_no", "住院号", "2", "inpNo", "", "住院就诊唯一住院号"},
            {"bed_no", "床位号", "2", "bedNo", "", "当前床位号(经his_bed解析)"},
            {"ward_name", "病区名称", "2", "wardName", "", "当前病区名称(经his_ward解析)"},
            {"dept_name", "科室名称", "2", "deptName", "", "住院科室名称(经his_dept解析)"},
            {"doctor_name", "主治医生", "2", "doctorId", "", "就诊主治医生姓名"},
            {"nurse_name", "责任护士", "2", "nurseId", "", "就诊责任护士姓名"},
            {"admit_diag", "入院诊断", "3", "admitDiag", "", "入院诊断(diag_type=1, 主诊断优先, 兜底就诊入院诊断)"},
            {"discharge_diag", "出院诊断", "3", "dischargeDiag", "", "出院诊断(diag_type=4, 主诊断优先)"},
            {"main_diag", "主要诊断", "3", "mainDiag", "", "主诊断(优先is_main=1)"},
            {"nursing_level", "护理等级", "2", "nursingLevel", "", "护理等级(1特级 2一级 3二级 4三级)"},
            {"diet_type", "饮食类型", "2", "dietType", "", "饮食类型"},
            {"blood_type", "血型", "2", "bloodType", "", "患者血型"},
            {"allergy_info", "过敏信息", "2", "allergyList", "", "有效过敏记录汇总(无则\"无\")"},
            {"vital_signs", "生命体征", "6", "temperatureSheet", "", "最新体温单生命体征(T/P/R/BP)"},
            {"condition_level", "病情等级", "2", "conditionLevel", "", "病情等级(1危 2重 3一般)"},
            {"surgery_name", "手术名称", "2", "surgeryName", "", "最近一次未取消手术名称"},
            {"surgery_date", "手术日期", "2", "surgeryDate", "yyyy-MM-dd", "最近手术日期(优先实际开始时间)"},
            {"surgeon_name", "术者", "2", "surgeonId", "", "最近手术主刀医师姓名"},
            {"attending_doctor", "主管医师", "2", "doctorId", "", "就诊主治医师姓名"},
            {"hospital_name", "医院名称", "2", "hospitalName", "", "就诊机构名称"},
            {"current_date", "当前日期", "2", "currentDate", "yyyy-MM-dd", "书写时系统当前日期"},
            {"current_time", "当前时间", "2", "currentTime", "HH:mm", "书写时系统当前时间"},
            {"inp_days", "住院天数", "2", "inpDays", "", "住院天数(入院至出院/当前, 含首日)"},
            {"past_history", "既往史", "1", "pastHistory", "", "既往史(回溯最近病历记载, 无则\"无\")"}
    };

    private final HisEmrMacroMapper macroMapper;
    private final HisInpVisitMapper visitMapper;
    private final HisPatientMapper patientMapper;
    private final HisStaffMapper staffMapper;
    private final HisDeptMapper deptMapper;
    private final HisWardMapper wardMapper;
    private final HisBedMapper bedMapper;
    private final SysOrgMapper sysOrgMapper;
    private final HisInpDiagnosisMapper diagnosisMapper;
    private final HisSurgeryMapper surgeryMapper;
    private final HisInpNursingRecordMapper nursingRecordMapper;
    private final HisInpAllergyMapper allergyMapper;
    private final HisInpMedicalRecordMapper medicalRecordMapper;
    private final OrgAccessGuard guard;
    private final SysTenantService tenantService;
    private final JdbcTemplate jdbcTemplate;

    public EmrMacroService(HisEmrMacroMapper macroMapper, HisInpVisitMapper visitMapper,
                           HisPatientMapper patientMapper, HisStaffMapper staffMapper,
                           HisDeptMapper deptMapper, HisWardMapper wardMapper, HisBedMapper bedMapper,
                           SysOrgMapper sysOrgMapper, HisInpDiagnosisMapper diagnosisMapper,
                           HisSurgeryMapper surgeryMapper, HisInpNursingRecordMapper nursingRecordMapper,
                           HisInpAllergyMapper allergyMapper, HisInpMedicalRecordMapper medicalRecordMapper,
                           OrgAccessGuard guard, SysTenantService tenantService, JdbcTemplate jdbcTemplate) {
        this.macroMapper = macroMapper;
        this.visitMapper = visitMapper;
        this.patientMapper = patientMapper;
        this.staffMapper = staffMapper;
        this.deptMapper = deptMapper;
        this.wardMapper = wardMapper;
        this.bedMapper = bedMapper;
        this.sysOrgMapper = sysOrgMapper;
        this.diagnosisMapper = diagnosisMapper;
        this.surgeryMapper = surgeryMapper;
        this.nursingRecordMapper = nursingRecordMapper;
        this.allergyMapper = allergyMapper;
        this.medicalRecordMapper = medicalRecordMapper;
        this.guard = guard;
        this.tenantService = tenantService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 启动播种 ================= */

    @Override
    public void run(ApplicationArguments args) {
        List<SysTenant> tenants;
        try {
            tenants = tenantService.listAll();
        } catch (Exception e) {
            log.warn("病历宏变量种子跳过(租户表未就绪): {}", e.getMessage());
            return;
        }
        if (tenants == null) {
            return;
        }
        tenants.sort(Comparator.comparing(SysTenant::getId));
        for (SysTenant t : tenants) {
            if (t == null || t.getId() == null) {
                continue;
            }
            if (SysTenantService.PLATFORM_TENANT_CODE.equals(t.getTenantCode())) {
                continue; // 平台运营方租户无医院业务数据
            }
            Long prev = TenantContext.get();
            try {
                TenantContext.set(t.getId());
                seedTenant(t.getId());
            } catch (Exception e) {
                log.warn("租户[{}] 病历宏变量种子跳过(可能表未建): {}", t.getId(), e.getMessage());
            } finally {
                if (prev == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.set(prev);
                }
            }
        }
    }

    /** 按租户播种缺失的标准宏(逐码判空, 部分缺失可补种; 全局编码被其他租户占用则跳过) */
    private void seedTenant(Long tenantId) {
        Long orgId = resolveLeadOrgId(tenantId);
        if (orgId == null) {
            return; // 机构未建(RbacInitializer 未跑完), 下次启动补种
        }
        int added = 0;
        int occupied = 0;
        for (String[] def : MACRO_SEEDS) {
            String code = def[0];
            boolean localExists = !macroMapper.selectList(Wrappers.<HisEmrMacro>lambdaQuery()
                    .eq(HisEmrMacro::getMacroCode, code)).isEmpty();
            if (localExists) {
                continue;
            }
            if (existsGlobal(code)) {
                occupied++; // 标准编码已被其他租户占用(全局唯一), 本租户跳过
                continue;
            }
            try {
                HisEmrMacro m = new HisEmrMacro();
                m.setOrgId(orgId);
                m.setMacroCode(code);
                m.setMacroName(def[1]);
                m.setDataSource(Integer.valueOf(def[2]));
                m.setSourceField(StringUtils.hasText(def[3]) ? def[3] : null);
                m.setFormatPattern(StringUtils.hasText(def[4]) ? def[4] : null);
                m.setDescription(def[5]);
                macroMapper.insert(m);
                added++;
            } catch (DuplicateKeyException e) {
                occupied++;
            }
        }
        if (added > 0) {
            log.info("租户[{}] 病历宏变量初始化完成(新增{}个)", tenantId, added);
        }
        if (occupied > 0) {
            log.info("租户[{}] 标准宏编码已由其他租户占用, 跳过{}个(标准编码全租户共享, 可自建扩展宏)", tenantId, occupied);
        }
    }

    /** 解析租户牵头机构: 优先 is_lead=1, 兜底最小 id(his_emr_macro.org_id 非空) */
    private Long resolveLeadOrgId(Long tenantId) {
        List<Long> lead = jdbcTemplate.query(
                "SELECT id FROM sys_org WHERE tenant_id = ? AND deleted = 0 AND is_lead = 1 ORDER BY id LIMIT 1",
                (rs, i) -> rs.getLong(1), tenantId);
        if (!lead.isEmpty()) {
            return lead.get(0);
        }
        List<Long> any = jdbcTemplate.query(
                "SELECT id FROM sys_org WHERE tenant_id = ? AND deleted = 0 ORDER BY id LIMIT 1",
                (rs, i) -> rs.getLong(1), tenantId);
        return any.isEmpty() ? null : any.get(0);
    }

    /** 全局(跨租户)编码查重: JdbcTemplate 直查绕开租户插件, 与 uk_macro_code 口径一致 */
    private boolean existsGlobal(String code) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_emr_macro WHERE macro_code = ? AND deleted = 0", Integer.class, code);
        return n != null && n > 0;
    }

    /* ================= 宏维护 ================= */

    /** 宏列表(dataSource 可选筛选) */
    public R<List<HisEmrMacro>> listMacros(Integer dataSource) {
        List<HisEmrMacro> list = macroMapper.selectList(Wrappers.<HisEmrMacro>lambdaQuery()
                .eq(dataSource != null, HisEmrMacro::getDataSource, dataSource)
                .orderByAsc(HisEmrMacro::getDataSource)
                .orderByAsc(HisEmrMacro::getId));
        return R.ok(list);
    }

    /** 创建宏变量(编码全局唯一预检, 重复返回友好 400) */
    public R<HisEmrMacro> createMacro(EmrMacroDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getMacroCode())) {
            throw new BizException(400, "宏变量编码不能为空");
        }
        if (!StringUtils.hasText(dto.getMacroName())) {
            throw new BizException(400, "宏变量名称不能为空");
        }
        String code = dto.getMacroCode().trim();
        ensureCodeAvailable(code);
        HisEmrMacro m = new HisEmrMacro();
        m.setOrgId(guard.currentOrgId());
        m.setMacroCode(code);
        m.setMacroName(dto.getMacroName().trim());
        m.setDataSource(dto.getDataSource());
        m.setSourceField(dto.getSourceField());
        m.setFormatPattern(dto.getFormatPattern());
        m.setDescription(dto.getDescription());
        try {
            macroMapper.insert(m);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "宏变量编码已存在: " + code);
        }
        log.info("新建病历宏变量: id={}, code={}, name={}", m.getId(), code, m.getMacroName());
        return R.ok(macroMapper.selectById(m.getId()));
    }

    /** 更新宏变量 */
    public R<Void> updateMacro(Long id, EmrMacroDTO dto) {
        HisEmrMacro exist = id == null ? null : macroMapper.selectById(id);
        if (exist == null) {
            throw new BizException(400, "宏变量不存在");
        }
        if (dto == null) {
            throw new BizException(400, "宏变量内容不能为空");
        }
        if (StringUtils.hasText(dto.getMacroCode())) {
            String code = dto.getMacroCode().trim();
            if (!code.equals(exist.getMacroCode())) {
                ensureCodeAvailable(code);
                exist.setMacroCode(code);
            }
        }
        if (StringUtils.hasText(dto.getMacroName())) {
            exist.setMacroName(dto.getMacroName().trim());
        }
        if (dto.getDataSource() != null) {
            exist.setDataSource(dto.getDataSource());
        }
        if (dto.getSourceField() != null) {
            exist.setSourceField(dto.getSourceField());
        }
        if (dto.getFormatPattern() != null) {
            exist.setFormatPattern(dto.getFormatPattern());
        }
        if (dto.getDescription() != null) {
            exist.setDescription(dto.getDescription());
        }
        try {
            macroMapper.updateById(exist);
        } catch (DuplicateKeyException e) {
            throw new BizException(400, "宏变量编码已存在: " + exist.getMacroCode());
        }
        log.info("更新病历宏变量: id={}, code={}", id, exist.getMacroCode());
        return R.ok();
    }

    /** 删除宏变量(逻辑删除) */
    public R<Void> removeMacro(Long id) {
        if (id == null || macroMapper.selectById(id) == null) {
            throw new BizException(400, "宏变量不存在");
        }
        macroMapper.deleteById(id);
        log.info("删除病历宏变量: id={}", id);
        return R.ok();
    }

    /** 编码可用性校验: 本租户重复 + 全局唯一键预检 */
    private void ensureCodeAvailable(String code) {
        boolean localDup = !macroMapper.selectList(Wrappers.<HisEmrMacro>lambdaQuery()
                .eq(HisEmrMacro::getMacroCode, code)).isEmpty();
        if (localDup) {
            throw new BizException(400, "宏变量编码已存在: " + code);
        }
        if (existsGlobal(code)) {
            throw new BizException(400, "宏变量编码已被占用(标准编码全租户共享): " + code);
        }
    }

    /* ================= 宏解析 ================= */

    /** 批量解析就诊宏变量: macroCodes 为空则解析全部; 返回 {macroCode: 值} */
    public R<Map<String, String>> resolveMacros(Long visitId, List<String> macroCodes) {
        HisInpVisit visit = loadVisitChecked(visitId);
        List<HisEmrMacro> macros;
        if (macroCodes == null || macroCodes.isEmpty()) {
            macros = macroMapper.selectList(Wrappers.<HisEmrMacro>lambdaQuery()
                    .orderByAsc(HisEmrMacro::getDataSource)
                    .orderByAsc(HisEmrMacro::getId));
        } else {
            macros = macroMapper.selectList(Wrappers.<HisEmrMacro>lambdaQuery()
                    .in(HisEmrMacro::getMacroCode, macroCodes));
        }
        Ctx ctx = new Ctx(visit);
        Map<String, String> result = new LinkedHashMap<>();
        for (HisEmrMacro m : macros) {
            result.put(m.getMacroCode(), resolveOne(m, ctx));
        }
        return R.ok(result);
    }

    /** 解析文本中的 {macroCode} 占位符(未知宏保留原样) */
    public R<String> resolveText(Long visitId, String text) {
        if (!StringUtils.hasText(text)) {
            return R.ok(text);
        }
        HisInpVisit visit = loadVisitChecked(visitId);
        Set<String> codes = new LinkedHashSet<>();
        Matcher mt = MACRO_PATTERN.matcher(text);
        while (mt.find()) {
            codes.add(mt.group(1));
        }
        if (codes.isEmpty()) {
            return R.ok(text);
        }
        Map<String, String> values = new LinkedHashMap<>();
        List<HisEmrMacro> macros = macroMapper.selectList(Wrappers.<HisEmrMacro>lambdaQuery()
                .in(HisEmrMacro::getMacroCode, codes));
        Ctx ctx = new Ctx(visit);
        for (HisEmrMacro m : macros) {
            values.put(m.getMacroCode(), resolveOne(m, ctx));
        }
        Matcher m2 = MACRO_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m2.find()) {
            String code = m2.group(1);
            String val = values.get(code);
            m2.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val : "{" + code + "}"));
        }
        m2.appendTail(sb);
        return R.ok(sb.toString());
    }

    /** 就诊加载 + 机构访问校验(镜像 InpMedRecordService.requireVisit): 越权 403 */
    private HisInpVisit loadVisitChecked(Long visitId) {
        HisInpVisit v = visitId == null ? null : visitMapper.selectById(visitId);
        if (v == null) {
            throw new BizException(400, "住院就诊不存在");
        }
        Long scope = guard.scopeOrgId(v.getOrgId());
        if (scope == null || !scope.equals(v.getOrgId())) {
            throw new BizException(403, "无权访问该住院就诊");
        }
        return v;
    }

    /** 单宏解析: 标准宏走专用逻辑, 自定义宏按 dataSource+sourceField 反射解析 */
    private String resolveOne(HisEmrMacro m, Ctx c) {
        String code = m.getMacroCode();
        if (!StringUtils.hasText(code)) {
            return "";
        }
        HisInpVisit v = c.visit;
        switch (code) {
            case "patient_name":
                return c.patient == null ? "" : text(c.patient.getName());
            case "gender":
                if (c.patient == null) {
                    return "";
                }
                return StringUtils.hasText(c.patient.getGenderName())
                        ? c.patient.getGenderName() : text(c.patient.getGender());
            case "age":
                return c.patient == null || c.patient.getAge() == null
                        ? "" : String.valueOf(c.patient.getAge());
            case "id_card":
                return c.patient == null ? "" : text(c.patient.getIdCard());
            case "admit_date":
                return fmtDateTime(v.getAdmitDate(), m.getFormatPattern(), DEFAULT_DT);
            case "discharge_date":
                return fmtDateTime(v.getDischargeDate(), m.getFormatPattern(), DEFAULT_DT);
            case "inp_no":
                return text(v.getInpNo());
            case "bed_no": {
                if (v.getBedId() == null) {
                    return "";
                }
                HisBed bed = bedMapper.selectById(v.getBedId());
                return bed == null ? "" : text(bed.getBedNo());
            }
            case "ward_name": {
                if (v.getWardId() == null) {
                    return "";
                }
                HisWard w = wardMapper.selectById(v.getWardId());
                return w == null ? "" : text(w.getWardName());
            }
            case "dept_name": {
                if (v.getDeptId() == null) {
                    return "";
                }
                HisDept d = deptMapper.selectById(v.getDeptId());
                return d == null ? "" : text(d.getDeptName());
            }
            case "doctor_name":
                return c.staffName(v.getDoctorId());
            case "nurse_name":
                return c.staffName(v.getNurseId());
            case "admit_diag": {
                String s = diagText(c, 1);
                return StringUtils.hasText(s) ? s : text(v.getAdmitDiag());
            }
            case "discharge_diag":
                return diagText(c, 4);
            case "main_diag": {
                for (HisInpDiagnosis d : c.diagnoses()) {
                    if (Integer.valueOf(1).equals(d.getIsMain())) {
                        return text(d.getDiagName());
                    }
                }
                String s = diagText(c, 1);
                return StringUtils.hasText(s) ? s : text(v.getAdmitDiag());
            }
            case "nursing_level":
                return nursingLevelText(v.getNursingLevel());
            case "diet_type":
                return text(v.getDietType());
            case "blood_type":
                return text(v.getBloodType());
            case "allergy_info":
                return allergyInfo(c);
            case "vital_signs":
                return vitalSigns(c);
            case "condition_level":
                return conditionLevelText(v.getConditionLevel());
            case "surgery_name": {
                HisSurgery s = c.surgery();
                return s == null ? "" : text(s.getSurgeryName());
            }
            case "surgery_date": {
                HisSurgery s = c.surgery();
                if (s == null) {
                    return "";
                }
                if (s.getStartTime() != null) {
                    return fmtDateTime(s.getStartTime(), m.getFormatPattern(), DEFAULT_DATE);
                }
                return fmtDate(s.getScheduleDate(), m.getFormatPattern(), DEFAULT_DATE);
            }
            case "surgeon_name": {
                HisSurgery s = c.surgery();
                return s == null ? "" : c.staffName(s.getSurgeonId());
            }
            case "attending_doctor":
                return c.staffName(v.getDoctorId());
            case "hospital_name": {
                if (v.getOrgId() == null) {
                    return "";
                }
                SysOrg org = sysOrgMapper.selectById(v.getOrgId());
                return org == null ? "" : text(org.getOrgName());
            }
            case "current_date":
                return LocalDate.now().format(patternOf(m.getFormatPattern(), DEFAULT_DATE));
            case "current_time":
                return LocalTime.now().format(patternOf(m.getFormatPattern(), "HH:mm"));
            case "inp_days":
                return inpDays(v);
            case "past_history":
                return pastHistory(c);
            default:
                return resolveBySource(m, c);
        }
    }

    /** 自定义宏通用解析: 按 dataSource 选目标对象, sourceField 反射取值(支持 "表.字段" 前缀) */
    private String resolveBySource(HisEmrMacro m, Ctx c) {
        String field = m.getSourceField();
        if (!StringUtils.hasText(field)) {
            return "";
        }
        Integer ds = m.getDataSource();
        Object target = (ds != null && ds == 1) ? c.patient : c.visit;
        Object v = reflect(target, field);
        if (v == null) {
            v = reflect(c.visit, field); // 兜底就诊
        }
        if (v == null) {
            v = reflect(c.patient, field); // 兜底患者
        }
        if (v instanceof LocalDateTime) {
            return fmtDateTime((LocalDateTime) v, m.getFormatPattern(), DEFAULT_DT);
        }
        if (v instanceof LocalDate) {
            return fmtDate((LocalDate) v, m.getFormatPattern(), DEFAULT_DATE);
        }
        return v == null ? "" : String.valueOf(v);
    }

    private Object reflect(Object target, String field) {
        if (target == null) {
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

    /** 诊断取值: 指定 diag_type 中主诊断优先, 其次排序号最小(his_inp_diagnosis.sort_no asc) */
    private String diagText(Ctx c, int diagType) {
        HisInpDiagnosis candidate = null;
        for (HisInpDiagnosis d : c.diagnoses()) {
            if (!Integer.valueOf(diagType).equals(d.getDiagType())) {
                continue;
            }
            if (candidate == null) {
                candidate = d;
            }
            if (Integer.valueOf(1).equals(d.getIsMain())) {
                candidate = d;
                break;
            }
        }
        return candidate == null ? "" : text(candidate.getDiagName());
    }

    /** 过敏信息: 有效过敏记录(状态=1)以顿号拼接, 无则"无" */
    private String allergyInfo(Ctx c) {
        List<HisInpAllergy> list = c.allergies();
        StringBuilder sb = new StringBuilder();
        for (HisInpAllergy a : list) {
            String name = text(a.getAllergenName());
            if (!StringUtils.hasText(name)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append(name);
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    /** 生命体征: 最新体温单(content JSON)提取 T/P/R/BP, 无记录或无法识别回退原文 */
    private String vitalSigns(Ctx c) {
        HisInpNursingRecord rec = c.tempRecord();
        if (rec == null || !StringUtils.hasText(rec.getContent())) {
            return "未记录";
        }
        JSONObject o = parseObjectSafe(rec.getContent());
        if (o.isEmpty()) {
            return rec.getContent();
        }
        String t = firstValue(o, "temperature", "temp", "T", "temperatureValue");
        String p = firstValue(o, "pulse", "heartRate", "heart_rate", "P", "pulseValue");
        String r = firstValue(o, "respiration", "breath", "breathRate", "R", "respirationValue");
        String bp = firstValue(o, "bp", "bloodPressure", "blood_pressure", "BP", "bloodPressureValue");
        if (!StringUtils.hasText(bp)) {
            String sys = firstValue(o, "systolic", "sbp", "systolicPressure", "highPressure");
            String dia = firstValue(o, "diastolic", "dbp", "diastolicPressure", "lowPressure");
            if (StringUtils.hasText(sys) && StringUtils.hasText(dia)) {
                bp = sys + "/" + dia;
            } else if (StringUtils.hasText(sys)) {
                bp = sys;
            } else if (StringUtils.hasText(dia)) {
                bp = dia;
            }
        }
        StringBuilder sb = new StringBuilder();
        appendPart(sb, StringUtils.hasText(t) ? "T " + t + "℃" : null);
        appendPart(sb, StringUtils.hasText(p) ? "P " + p + "次/分" : null);
        appendPart(sb, StringUtils.hasText(r) ? "R " + r + "次/分" : null);
        appendPart(sb, StringUtils.hasText(bp) ? "BP " + bp + "mmHg" : null);
        return sb.length() == 0 ? rec.getContent() : sb.toString();
    }

    /** 既往史: 患者表无既往史字段, 回溯最近10份病历 structureData.pastHistory, 无则"无" */
    private String pastHistory(Ctx c) {
        if (c.pastHistory != null) {
            return c.pastHistory;
        }
        c.pastHistory = "无";
        List<HisInpMedicalRecord> list = medicalRecordMapper.selectList(Wrappers.<HisInpMedicalRecord>lambdaQuery()
                .eq(HisInpMedicalRecord::getInpVisitId, c.visit.getId())
                .orderByDesc(HisInpMedicalRecord::getId)
                .last("LIMIT 10"));
        for (HisInpMedicalRecord rec : list) {
            String v = text(parseObjectSafe(rec.getStructureData()).get("pastHistory"));
            if (StringUtils.hasText(v)) {
                c.pastHistory = v;
                break;
            }
        }
        return c.pastHistory;
    }

    /** 住院天数: 入院日期至出院/当前(含首日), 最小 1 天 */
    private static String inpDays(HisInpVisit v) {
        if (v.getAdmitDate() == null) {
            return "";
        }
        LocalDateTime end = v.getDischargeDate() != null ? v.getDischargeDate() : LocalDateTime.now();
        long days = ChronoUnit.DAYS.between(v.getAdmitDate().toLocalDate(), end.toLocalDate()) + 1;
        if (days < 1) {
            days = 1;
        }
        return String.valueOf(days);
    }

    private static String nursingLevelText(Integer level) {
        if (level == null) {
            return "";
        }
        switch (level) {
            case 1: return "特级护理";
            case 2: return "一级护理";
            case 3: return "二级护理";
            case 4: return "三级护理";
            default: return "";
        }
    }

    private static String conditionLevelText(Integer level) {
        if (level == null) {
            return "";
        }
        switch (level) {
            case 1: return "危";
            case 2: return "重";
            case 3: return "一般";
            default: return "";
        }
    }

    private static void appendPart(StringBuilder sb, String part) {
        if (part == null) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(" ");
        }
        sb.append(part);
    }

    private static String firstValue(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = text(o.get(k));
            if (StringUtils.hasText(v)) {
                return v;
            }
        }
        return null;
    }

    private static DateTimeFormatter patternOf(String pattern, String def) {
        String p = StringUtils.hasText(pattern) ? pattern : def;
        try {
            return DateTimeFormatter.ofPattern(p);
        } catch (Exception e) {
            return DateTimeFormatter.ofPattern(def);
        }
    }

    private static String fmtDateTime(LocalDateTime dt, String pattern, String def) {
        return dt == null ? "" : dt.format(patternOf(pattern, def));
    }

    private static String fmtDate(LocalDate d, String pattern, String def) {
        return d == null ? "" : d.format(patternOf(pattern, def));
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

    /* ================= 解析上下文 ================= */

    /** 解析上下文: 就诊+患者 + 懒加载缓存(人名/诊断/手术/体温单/过敏/既往史) */
    private class Ctx {

        private final HisInpVisit visit;
        private final HisPatient patient;
        private Map<Long, String> staffNameCache;
        private List<HisInpDiagnosis> diagnoses;
        private HisSurgery surgery;
        private boolean surgeryLoaded;
        private HisInpNursingRecord tempRecord;
        private boolean tempLoaded;
        private List<HisInpAllergy> allergies;
        private String pastHistory;

        Ctx(HisInpVisit visit) {
            this.visit = visit;
            this.patient = visit.getPatientId() == null ? null : patientMapper.selectById(visit.getPatientId());
        }

        String staffName(Long id) {
            if (id == null) {
                return "";
            }
            if (staffNameCache == null) {
                staffNameCache = new LinkedHashMap<>();
            }
            if (staffNameCache.containsKey(id)) {
                return staffNameCache.get(id);
            }
            HisStaff s = staffMapper.selectById(id);
            String name = s == null ? "" : text(s.getStaffName());
            staffNameCache.put(id, name);
            return name;
        }

        List<HisInpDiagnosis> diagnoses() {
            if (diagnoses == null) {
                diagnoses = diagnosisMapper.selectList(Wrappers.<HisInpDiagnosis>lambdaQuery()
                        .eq(HisInpDiagnosis::getInpVisitId, visit.getId())
                        .orderByAsc(HisInpDiagnosis::getSortNo)
                        .orderByAsc(HisInpDiagnosis::getId));
            }
            return diagnoses;
        }

        HisSurgery surgery() {
            if (!surgeryLoaded) {
                surgeryLoaded = true;
                List<HisSurgery> list = surgeryMapper.selectList(Wrappers.<HisSurgery>lambdaQuery()
                        .eq(HisSurgery::getInpVisitId, visit.getId())
                        .orderByDesc(HisSurgery::getId)
                        .last("LIMIT 5"));
                HisSurgery fallback = null;
                for (HisSurgery s : list) {
                    if (fallback == null) {
                        fallback = s;
                    }
                    if (s.getStatus() == null || s.getStatus() != 6) {
                        surgery = s;
                        break;
                    }
                }
                if (surgery == null) {
                    surgery = fallback;
                }
            }
            return surgery;
        }

        HisInpNursingRecord tempRecord() {
            if (!tempLoaded) {
                tempLoaded = true;
                List<HisInpNursingRecord> list = nursingRecordMapper.selectList(Wrappers.<HisInpNursingRecord>lambdaQuery()
                        .eq(HisInpNursingRecord::getInpVisitId, visit.getId())
                        .eq(HisInpNursingRecord::getRecordType, 1)
                        .orderByDesc(HisInpNursingRecord::getId)
                        .last("LIMIT 1"));
                tempRecord = list.isEmpty() ? null : list.get(0);
            }
            return tempRecord;
        }

        List<HisInpAllergy> allergies() {
            if (allergies == null) {
                allergies = allergyMapper.selectList(Wrappers.<HisInpAllergy>lambdaQuery()
                        .eq(HisInpAllergy::getInpVisitId, visit.getId())
                        .eq(HisInpAllergy::getStatus, 1)
                        .orderByAsc(HisInpAllergy::getId));
            }
            return allergies;
        }
    }
}
