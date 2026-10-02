package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yb.hi.dto.inpatient.NewbornDTO;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisNewborn;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.inpatient.HisNewbornMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.outpatient.HisPatientService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新生儿建档服务(P2d 产科分娩一体化): 分娩后为新生儿建患者档案(his_patient)+住院就诊(his_inp_visit, 随母亲科室在院),
 * 落 his_newborn 绑定关系; 医嘱复用住院医嘱链(挂新生儿 inp_visit)。
 * 分娩手术 complete 仅引导不自动建档(防脏数据), 由本服务显式 register 完成。
 * JdbcTemplate 手写 SQL 显式带 tenant_id AND deleted=0; 机构隔离走 OrgAccessGuard。
 */
@Slf4j
@Service
public class NewbornService {

    private static final DateTimeFormatter INP_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final HisNewbornMapper newbornMapper;
    private final HisInpVisitMapper visitMapper;
    private final HisPatientService patientService;
    private final OrgAccessGuard guard;
    private final JdbcTemplate jdbcTemplate;

    public NewbornService(HisNewbornMapper newbornMapper, HisInpVisitMapper visitMapper,
                          HisPatientService patientService, OrgAccessGuard guard, JdbcTemplate jdbcTemplate) {
        this.newbornMapper = newbornMapper;
        this.visitMapper = visitMapper;
        this.patientService = patientService;
        this.guard = guard;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 新生儿建档(register): 校验母亲住院就诊 -> 建新生儿患者档案 -> 建新生儿住院就诊(在院, 随母亲科室/病区) -> 落 his_newborn。
     * 回填 baby_patient_id / baby_inp_visit_id; 整批事务。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisNewborn register(NewbornDTO dto) {
        if (dto == null || dto.getMotherInpVisitId() == null) {
            throw new BizException(400, "母亲住院就诊ID不能为空");
        }
        if (!StringUtils.hasText(dto.getBabyName())) {
            throw new BizException(400, "新生儿姓名不能为空");
        }
        Map<String, Object> mother = requireMotherVisit(dto.getMotherInpVisitId());
        Long orgId = toLong(mother.get("org_id"));
        Long deptId = dto.getDeptId() != null ? dto.getDeptId() : toLong(mother.get("dept_id"));
        Long wardId = dto.getWardId() != null ? dto.getWardId() : toLong(mother.get("ward_id"));

        // 1) 建新生儿患者档案(复用建档口径: 自动生成 patientNo + 字典回填)
        HisPatient baby = new HisPatient();
        baby.setName(dto.getBabyName().trim());
        baby.setGender(dto.getBabySex() == null ? null : (dto.getBabySex() == 2 ? "2" : "1"));
        baby.setBirthDate(dto.getBirthTime() != null ? dto.getBirthTime() : LocalDateTime.now());
        baby.setAge(0);
        baby.setPhone(str(mother.get("contact_phone")));
        baby.setContactName(str(mother.get("mother_name")));
        baby.setContactRelation("母子");
        baby.setOrgId(orgId);
        baby.setStatus(1);
        HisPatient savedBaby = patientService.createPatient(baby);

        // 2) 建新生儿住院就诊(在院 status=2, 随母亲科室/病区, 无需占床)
        HisInpVisit babyVisit = new HisInpVisit();
        babyVisit.setOrgId(orgId);
        babyVisit.setInpNo(generateBabyInpNo(orgId));
        babyVisit.setPatientId(savedBaby.getId());
        babyVisit.setDeptId(deptId);
        babyVisit.setWardId(wardId);
        babyVisit.setAdmitDate(LocalDateTime.now());
        babyVisit.setVisitStatus(2);
        babyVisit.setAdmitDiag("新生儿");
        babyVisit.setTotalCost(BigDecimal.ZERO);
        babyVisit.setDepositBalance(BigDecimal.ZERO);
        babyVisit.setContactName(str(mother.get("mother_name")));
        babyVisit.setContactPhone(str(mother.get("contact_phone")));
        babyVisit.setContactRelation("母子");
        visitMapper.insert(babyVisit);

        // 3) 落 his_newborn 绑定
        HisNewborn nb = new HisNewborn();
        nb.setOrgId(orgId);
        nb.setMotherInpVisitId(dto.getMotherInpVisitId());
        nb.setSurgeryId(dto.getSurgeryId());
        nb.setBabyPatientId(savedBaby.getId());
        nb.setBabyInpVisitId(babyVisit.getId());
        nb.setBabyName(baby.getName());
        nb.setBabySex(dto.getBabySex());
        nb.setBirthTime(baby.getBirthDate());
        nb.setApgar1(dto.getApgar1());
        nb.setApgar5(dto.getApgar5());
        nb.setApgar10(dto.getApgar10());
        nb.setWeightG(dto.getWeightG());
        nb.setHeightCm(dto.getHeightCm());
        nb.setBirthType(dto.getBirthType() == null ? 1 : dto.getBirthType());
        nb.setStatus(1);
        nb.setRemark(trimOrNull(dto.getRemark()));
        newbornMapper.insert(nb);

        log.info("新生儿建档: newbornId={}, motherVisitId={}, babyPatientId={}, babyInpVisitId={}",
                nb.getId(), dto.getMotherInpVisitId(), savedBaby.getId(), babyVisit.getId());
        return newbornMapper.selectById(nb.getId());
    }

    /** 按母亲住院就诊查询新生儿列表(含新生儿患者/住院就诊号)。 */
    public List<Map<String, Object>> listByMother(Long motherInpVisitId) {
        if (motherInpVisitId == null) {
            throw new BizException(400, "母亲住院就诊ID不能为空");
        }
        requireMotherVisit(motherInpVisitId);
        StringBuilder sql = new StringBuilder(
                "SELECT n.id, n.org_id, n.mother_inp_visit_id, n.surgery_id, n.baby_patient_id, n.baby_inp_visit_id,"
                        + " n.baby_name, n.baby_sex, n.birth_time, n.apgar_1, n.apgar_5, n.apgar_10,"
                        + " n.weight_g, n.height_cm, n.birth_type, n.status, n.remark,"
                        + " v.inp_no baby_inp_no, p.patient_no baby_patient_no,"
                        + " mv.inp_no mother_inp_no, mb.bed_no mother_bed_no"
                        + " FROM his_newborn n"
                        + " LEFT JOIN his_inp_visit v ON v.id = n.baby_inp_visit_id AND v.deleted = 0"
                        + " LEFT JOIN his_patient p ON p.id = n.baby_patient_id AND p.deleted = 0"
                        + " LEFT JOIN his_inp_visit mv ON mv.id = n.mother_inp_visit_id AND mv.deleted = 0"
                        + " LEFT JOIN his_bed mb ON mb.id = mv.bed_id AND mb.deleted = 0"
                        + " WHERE n.deleted = 0 AND n.tenant_id = ? AND n.mother_inp_visit_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(motherInpVisitId);
        Long scope = guard.scopeOrgId(null);
        if (scope != null) {
            sql.append(" AND n.org_id = ?");
            args.add(scope);
        }
        sql.append(" ORDER BY n.id DESC");
        return jdbcTemplate.queryForList(sql.toString(), args.toArray());
    }

    /** 新生儿建档详情。 */
    public HisNewborn detail(Long id) {
        return requireNewborn(id);
    }

    /**
     * 手麻P4c 新生儿开嘱上下文: 按新生儿住院就诊ID回带体重/日龄/性别/母亲住院号与床号,
     * 供医嘱开立面板展示与按 mg/kg 核算。日龄以出生时间→当下推算(天)。
     */
    public Map<String, Object> orderContext(Long babyInpVisitId) {
        if (babyInpVisitId == null) {
            throw new BizException(400, "新生儿住院就诊ID不能为空");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT n.id newborn_id, n.org_id, n.baby_name, n.baby_sex, n.birth_time, n.weight_g, n.height_cm, n.status,"
                        + " mv.inp_no mother_inp_no, b.bed_no mother_bed_no"
                        + " FROM his_newborn n"
                        + " LEFT JOIN his_inp_visit mv ON mv.id = n.mother_inp_visit_id AND mv.deleted = 0"
                        + " LEFT JOIN his_bed b ON b.id = mv.bed_id AND b.deleted = 0"
                        + " WHERE n.deleted = 0 AND n.tenant_id = ? AND n.baby_inp_visit_id = ?",
                tenantId(), babyInpVisitId);
        if (rows.isEmpty()) {
            throw new BizException(404, "未找到该新生儿住院就诊对应的建档记录");
        }
        Map<String, Object> r = rows.get(0);
        Long orgId = toLong(r.get("org_id"));
        Long scope = guard.scopeOrgId(orgId);
        if (scope == null || !scope.equals(orgId)) {
            throw new BizException(403, "无权访问其他机构的新生儿数据");
        }
        LocalDateTime birthTime = toDateTime(r.get("birth_time"));
        Integer weightG = toInt(r.get("weight_g"));
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("babyInpVisitId", babyInpVisitId);
        ctx.put("newbornId", toLong(r.get("newborn_id")));
        ctx.put("babyName", str(r.get("baby_name")));
        ctx.put("babySex", toInt(r.get("baby_sex")));
        ctx.put("babySexName", sexName(toInt(r.get("baby_sex"))));
        ctx.put("birthTime", birthTime);
        ctx.put("ageDay", birthTime == null ? null : ChronoUnit.DAYS.between(birthTime.toLocalDate(), LocalDate.now()));
        ctx.put("ageText", ageDaysText(birthTime));
        ctx.put("weightG", weightG);
        ctx.put("weightKg", weightG == null ? null : BigDecimal.valueOf(weightG).divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP));
        ctx.put("hasWeight", weightG != null && weightG > 0);
        ctx.put("heightCm", r.get("height_cm"));
        ctx.put("status", toInt(r.get("status")));
        ctx.put("motherInpNo", str(r.get("mother_inp_no")));
        ctx.put("motherBedNo", str(r.get("mother_bed_no")));
        return ctx;
    }

    /**
     * 手麻P4c 新生儿剂量换算提示(诚实边界): 仅按体重做纯 mg/kg 或 ml/kg 单位换算展示,
     * 系统无新生儿剂量字典, 不做任何医疗拦截或安全阀判断。qty 为单次总量, unit 取 mg 或 ml。
     */
    public Map<String, Object> doseHint(Long babyInpVisitId, BigDecimal qty, String unit) {
        if (babyInpVisitId == null) {
            throw new BizException(400, "新生儿住院就诊ID不能为空");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("qty", qty);
        String u = unit == null ? "" : unit.trim().toLowerCase();
        out.put("unit", u);
        boolean convertible = "mg".equals(u) || "ml".equals(u);
        out.put("convertible", convertible);
        out.put("perKgUnit", convertible ? ("mg".equals(u) ? "mg/kg" : "ml/kg") : null);
        HisNewborn nb = requireNewbornByBabyVisit(babyInpVisitId);
        Integer weightG = nb == null ? null : nb.getWeightG();
        boolean hasWeight = weightG != null && weightG > 0;
        out.put("hasWeight", hasWeight);
        out.put("weightG", weightG);
        if (!hasWeight) {
            out.put("perKg", null);
            out.put("message", "未记录新生儿体重, 无法换算(请先补录体重)");
            return out;
        }
        BigDecimal weightKg = BigDecimal.valueOf(weightG).divide(BigDecimal.valueOf(1000), 3, RoundingMode.HALF_UP);
        out.put("weightKg", weightKg);
        if (!convertible || qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            out.put("perKg", null);
            out.put("message", convertible ? "单次总量需为正数" : "仅支持 mg 或 ml 单位换算");
            return out;
        }
        BigDecimal perKg = qty.divide(weightKg, 2, RoundingMode.HALF_UP);
        out.put("perKg", perKg);
        out.put("message", null);
        return out;
    }

    /** 按新生儿住院就诊ID取建档(含机构隔离); 不存在返回 null。 */
    private HisNewborn requireNewbornByBabyVisit(Long babyInpVisitId) {
        HisNewborn nb = newbornMapper.selectOne(new LambdaQueryWrapper<HisNewborn>()
                .eq(HisNewborn::getBabyInpVisitId, babyInpVisitId)
                .last("LIMIT 1"));
        if (nb == null) {
            return null;
        }
        Long scope = guard.scopeOrgId(nb.getOrgId());
        if (scope == null || !scope.equals(nb.getOrgId())) {
            throw new BizException(403, "无权访问其他机构的新生儿数据");
        }
        return nb;
    }

    private static String sexName(Integer sex) {
        if (sex == null) {
            return null;
        }
        return sex == 2 ? "女" : (sex == 1 ? "男" : null);
    }

    /** 日龄可读文本: <1天按小时, 其余按天。 */
    private static String ageDaysText(LocalDateTime birthTime) {
        if (birthTime == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(birthTime.toLocalDate(), LocalDate.now());
        if (days <= 0) {
            long hours = ChronoUnit.HOURS.between(birthTime, LocalDateTime.now());
            return Math.max(hours, 0) + " 小时";
        }
        return days + " 天";
    }

    private static LocalDateTime toDateTime(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDateTime) {
            return (LocalDateTime) v;
        }
        if (v instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) v).toLocalDateTime();
        }
        return null;
    }

    /** 补录新生儿信息(姓名/Apgar/体重/身长/分娩方式/备注): 仅未出院(status<>3)可改。 */
    @Transactional(rollbackFor = Exception.class)
    public HisNewborn update(Long id, NewbornDTO dto) {
        HisNewborn nb = requireNewborn(id);
        if (nb.getStatus() != null && nb.getStatus() == 3) {
            throw new BizException("已出院的新生儿档案不可修改");
        }
        if (dto == null) {
            throw new BizException(400, "补录内容不能为空");
        }
        LambdaUpdateWrapper<HisNewborn> u = new LambdaUpdateWrapper<HisNewborn>()
                .eq(HisNewborn::getId, id)
                .set(HisNewborn::getUpdateTime, LocalDateTime.now());
        if (StringUtils.hasText(dto.getBabyName())) {
            u.set(HisNewborn::getBabyName, dto.getBabyName().trim());
        }
        if (dto.getBabySex() != null) {
            u.set(HisNewborn::getBabySex, dto.getBabySex());
        }
        if (dto.getBirthTime() != null) {
            u.set(HisNewborn::getBirthTime, dto.getBirthTime());
        }
        if (dto.getApgar1() != null) {
            u.set(HisNewborn::getApgar1, dto.getApgar1());
        }
        if (dto.getApgar5() != null) {
            u.set(HisNewborn::getApgar5, dto.getApgar5());
        }
        if (dto.getApgar10() != null) {
            u.set(HisNewborn::getApgar10, dto.getApgar10());
        }
        if (dto.getWeightG() != null) {
            u.set(HisNewborn::getWeightG, dto.getWeightG());
        }
        if (dto.getHeightCm() != null) {
            u.set(HisNewborn::getHeightCm, dto.getHeightCm());
        }
        if (dto.getBirthType() != null) {
            u.set(HisNewborn::getBirthType, dto.getBirthType());
        }
        if (dto.getRemark() != null) {
            u.set(HisNewborn::getRemark, trimOrNull(dto.getRemark()));
        }
        newbornMapper.update(null, u);
        return newbornMapper.selectById(id);
    }

    /** 转科: status 1在绑->2已转科(可改新生儿住院就诊科室)。 */
    @Transactional(rollbackFor = Exception.class)
    public HisNewborn transfer(Long id, Long deptId) {
        HisNewborn nb = requireNewborn(id);
        if (nb.getStatus() != null && nb.getStatus() == 3) {
            throw new BizException("已出院的新生儿不可转科");
        }
        if (deptId != null && nb.getBabyInpVisitId() != null) {
            visitMapper.update(null, new LambdaUpdateWrapper<HisInpVisit>()
                    .eq(HisInpVisit::getId, nb.getBabyInpVisitId())
                    .set(HisInpVisit::getDeptId, deptId)
                    .set(HisInpVisit::getUpdateTime, LocalDateTime.now()));
        }
        newbornMapper.update(null, new LambdaUpdateWrapper<HisNewborn>()
                .eq(HisNewborn::getId, id)
                .set(HisNewborn::getStatus, 2)
                .set(HisNewborn::getUpdateTime, LocalDateTime.now()));
        log.info("新生儿转科: newbornId={}, deptId={}", id, deptId);
        return newbornMapper.selectById(id);
    }

    /** 出院: status->3, 同步新生儿住院就诊置已出院(visit_status=4)。 */
    @Transactional(rollbackFor = Exception.class)
    public HisNewborn discharge(Long id) {
        HisNewborn nb = requireNewborn(id);
        if (nb.getBabyInpVisitId() != null) {
            visitMapper.update(null, new LambdaUpdateWrapper<HisInpVisit>()
                    .eq(HisInpVisit::getId, nb.getBabyInpVisitId())
                    .set(HisInpVisit::getVisitStatus, 4)
                    .set(HisInpVisit::getDischargeDate, LocalDateTime.now())
                    .set(HisInpVisit::getUpdateTime, LocalDateTime.now()));
        }
        newbornMapper.update(null, new LambdaUpdateWrapper<HisNewborn>()
                .eq(HisNewborn::getId, id)
                .set(HisNewborn::getStatus, 3)
                .set(HisNewborn::getUpdateTime, LocalDateTime.now()));
        log.info("新生儿出院: newbornId={}", id);
        return newbornMapper.selectById(id);
    }

    /* ==================== 校验 / 工具 ==================== */

    /** 分娩手术建档引导(P2d): 手术 module_type=3 且尚无关联新生儿 -> needRegister=true(不自动建)。 */
    public Map<String, Object> registerGuide(Long surgeryId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT s.id, s.module_type, s.inp_visit_id, s.apply_id, s.surgery_name,"
                        + " IFNULL(s.org_id, 0) org_id FROM his_surgery s"
                        + " WHERE s.id = ? AND s.deleted = 0 AND s.tenant_id = ?",
                surgeryId, tenantId());
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("surgeryId", surgeryId);
        if (rows.isEmpty()) {
            result.put("needRegister", false);
            result.put("hasNewborn", false);
            result.put("moduleType", null);
            return result;
        }
        Map<String, Object> s = rows.get(0);
        Long scope = guard.scopeOrgId(toLong(s.get("org_id")));
        Integer moduleType = toInt(s.get("module_type"));
        long babyCnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_newborn WHERE deleted = 0 AND tenant_id = ? AND surgery_id = ?",
                Long.class, tenantId(), surgeryId);
        boolean hasNewborn = babyCnt > 0;
        result.put("moduleType", moduleType);
        result.put("motherInpVisitId", toLong(s.get("inp_visit_id")));
        result.put("hasNewborn", hasNewborn);
        result.put("needRegister", Integer.valueOf(3).equals(moduleType) && !hasNewborn && scope != null);
        return result;
    }

    /** 母亲住院就诊存在性 + 机构归属 + 患者/联系信息(建档数据源)。 */
    private Map<String, Object> requireMotherVisit(Long motherInpVisitId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT v.id, v.org_id, v.dept_id, v.ward_id, v.patient_id, v.contact_phone,"
                        + " p.name mother_name"
                        + " FROM his_inp_visit v"
                        + " LEFT JOIN his_patient p ON p.id = v.patient_id AND p.deleted = 0"
                        + " WHERE v.id = ? AND v.deleted = 0 AND v.tenant_id = ?",
                motherInpVisitId, tenantId());
        if (rows.isEmpty()) {
            throw new BizException(400, "母亲住院就诊记录不存在");
        }
        Map<String, Object> v = rows.get(0);
        Long orgId = toLong(v.get("org_id"));
        Long scope = guard.scopeOrgId(orgId);
        if (scope == null || !scope.equals(orgId)) {
            throw new BizException(403, "无权访问其他机构的住院就诊数据");
        }
        return v;
    }

    /** 新生儿档案存在性 + 机构归属。 */
    private HisNewborn requireNewborn(Long id) {
        if (id == null) {
            throw new BizException(400, "新生儿档案ID不能为空");
        }
        HisNewborn nb = newbornMapper.selectOne(new LambdaQueryWrapper<HisNewborn>()
                .eq(HisNewborn::getId, id));
        if (nb == null) {
            throw new BizException(404, "新生儿档案不存在");
        }
        Long scope = guard.scopeOrgId(nb.getOrgId());
        if (scope == null || !scope.equals(nb.getOrgId())) {
            throw new BizException(403, "无权访问其他机构的新生儿档案");
        }
        return nb;
    }

    /** 新生儿住院号: INP+yyyyMMdd+当日同机构4位流水(探测去重)。 */
    private String generateBabyInpNo(Long orgId) {
        String day = LocalDate.now().format(INP_NO_DATE);
        String prefix = "INP" + day;
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_inp_visit WHERE inp_no LIKE ? AND tenant_id = ?",
                Long.class, prefix + "%", tenantId());
        long seq = (cnt == null ? 0L : cnt) + 1;
        for (int i = 0; i < 200; i++, seq++) {
            String no = prefix + String.format("%04d", seq);
            Long exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM his_inp_visit WHERE inp_no = ? AND tenant_id = ?",
                    Long.class, no, tenantId());
            if (exists == null || exists == 0) {
                return no;
            }
        }
        throw new BizException("新生儿住院号生成失败, 请重试");
    }

    private static String trimOrNull(String v) {
        return StringUtils.hasText(v) ? v.trim() : null;
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static Long toLong(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : null;
    }

    private static Integer toInt(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
