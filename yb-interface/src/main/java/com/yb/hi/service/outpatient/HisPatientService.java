package com.yb.hi.service.outpatient;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.common.DateUtil;
import com.yb.hi.entity.basedata.AreaCode;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.entity.outpatient.HisPatientChangeLog;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.outpatient.HisPatientChangeLogMapper;
import com.yb.hi.mapper.outpatient.HisPatientMapper;
import com.yb.hi.service.StdDictQueryService;
import com.yb.hi.service.basedata.AreaCodeService;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * 患者档案服务。
 * 医共体统一患者主索引: 同一自然人全医共体(租户)一份档案,
 * 建档前按 id_card(或 psn_no)查重命中即复用; org_id 仅作建档机构归属标注。
 */
@Service
public class HisPatientService extends ServiceImpl<HisPatientMapper, HisPatient> {

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private final StdDictQueryService stdDict;
    private final AreaCodeService areaService;
    private final HisPatientChangeLogMapper changeLogMapper;

    /** 修改记录时间格式 */
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 修改记录跟踪字段: {编码属性(逗号分隔支持多级地址), 名称属性(逗号分隔, 可空), 中文标签}。
     * 字典字段优先展示名称(三件套 *_name), 无名称时回退编码。
     */
    private static final String[][] TRACKED_FIELDS = {
            {"name", "", "姓名"},
            {"gender", "genderName", "性别"},
            {"birthDate", "", "出生时间"},
            {"age", "", "年龄"},
            {"idCard", "", "身份证号"},
            {"phone", "", "联系电话"},
            {"certType", "certTypeName", "身份证件类别"},
            {"nation", "nationName", "民族"},
            {"nationality", "nationalityName", "国籍"},
            {"maritalStatus", "maritalStatusName", "婚姻状况"},
            {"eduLevel", "eduLevelName", "文化程度"},
            {"occupation", "occupationName", "职业类别"},
            {"occupationOther", "", "职业其他"},
            {"presentProv,presentCity,presentCounty,presentTown",
                    "presentProvName,presentCityName,presentCountyName,presentTownName", "现住址(区划)"},
            {"presentDetail", "", "现住址详细"},
            {"birthProv,birthCity,birthCounty,birthTown",
                    "birthProvName,birthCityName,birthCountyName,birthTownName", "出生地(区划)"},
            {"birthDetail", "", "出生地详细"},
            {"mailProv,mailCity,mailCounty,mailTown,address",
                    "mailProvName,mailCityName,mailCountyName,mailTownName,address", "通讯地址"},
            {"householdProv,householdCity,householdCounty,householdTown,householdAddr",
                    "householdProvName,householdCityName,householdCountyName,householdTownName,householdAddr", "户籍地址"},
            {"employer", "", "工作单位"},
            {"employerPhone", "", "单位电话"},
            {"empProv,empCity,empCounty,empTown,employerAddr",
                    "empProvName,empCityName,empCountyName,empTownName,employerAddr", "单位地址"},
            {"insutype", "insutypeName", "险种类型"},
            {"mdtrtCertType", "mdtrtCertTypeName", "就诊凭证类型"},
            {"mdtrtCertNo", "", "就诊凭证编号"},
            {"psnNo", "", "医保人员编号"},
            {"insuplcAdmdvs", "insuplcAdmdvsName", "参保地区划"},
            {"contactRelation", "contactRelationName", "与患者关系"},
            {"contactName", "", "联系人姓名"},
            {"contactPhone", "", "联系人电话"},
            {"contactIdCard", "", "联系人证件号"},
            {"contactProv,contactCity,contactCounty,contactTown,contactAddr",
                    "contactProvName,contactCityName,contactCountyName,contactTownName,contactAddr", "联系人地址"},
            {"status", "", "状态"},
            {"memo", "", "备注"},
    };

    public HisPatientService(StdDictQueryService stdDict, AreaCodeService areaService,
                             HisPatientChangeLogMapper changeLogMapper) {
        this.stdDict = stdDict;
        this.areaService = areaService;
        this.changeLogMapper = changeLogMapper;
    }

    /**
     * 字典字段回填: 业务只存编码, 服务端按编码查字典回填名称与来源标识(医保字典优先)。
     * 性别 gend / 险种 insutype / 就诊凭证 mdtrt_cert_type 均取自医保字典 std_cv_code; 参保地区划取自 area_code_2021。
     */
    public void enrichDict(HisPatient p) {
        if (p == null) {
            return;
        }
        if (StringUtils.hasText(p.getGender())) {
            p.setGenderName(stdDict.nameOf("cv_code", "gend", p.getGender()));
            p.setGenderSrc("cv_code:gend");
        }
        if (StringUtils.hasText(p.getInsutype())) {
            p.setInsutypeName(stdDict.nameOf("cv_code", "insutype", p.getInsutype()));
            p.setInsutypeSrc("cv_code:insutype");
        }
        if (StringUtils.hasText(p.getMdtrtCertType())) {
            p.setMdtrtCertTypeName(stdDict.nameOf("cv_code", "mdtrt_cert_type", p.getMdtrtCertType()));
            p.setMdtrtCertTypeSrc("cv_code:mdtrt_cert_type");
        }
        if (StringUtils.hasText(p.getInsuplcAdmdvs())) {
            p.setInsuplcAdmdvsName(areaName(p.getInsuplcAdmdvs()));
            p.setInsuplcAdmdvsSrc("area_code_2021");
        }
        /* ---------- A 身份人口学(医保字典优先, 其余取湖北采集规范值域 hbvalue) ---------- */
        if (StringUtils.hasText(p.getCertType())) {
            p.setCertTypeName(stdDict.nameOf("cv_code", "psn_cert_type", p.getCertType()));
            p.setCertTypeSrc("cv_code:psn_cert_type");
        }
        if (StringUtils.hasText(p.getNation())) {
            p.setNationName(stdDict.nameOf("cv_code", "naty", p.getNation()));
            p.setNationSrc("cv_code:naty");
        }
        if (StringUtils.hasText(p.getNationality())) {
            p.setNationalityName(stdDict.nameOf("hbvalue", "GB/T 2659.1-2022", p.getNationality()));
            p.setNationalitySrc("hbvalue:GB/T 2659.1-2022");
        }
        if (StringUtils.hasText(p.getMaritalStatus())) {
            p.setMaritalStatusName(stdDict.nameOf("hbvalue", "GB/T 2261.2-2003", p.getMaritalStatus()));
            p.setMaritalStatusSrc("hbvalue:GB/T 2261.2-2003");
        }
        if (StringUtils.hasText(p.getEduLevel())) {
            p.setEduLevelName(stdDict.nameOf("hbvalue", "GB/T 4658-2006", p.getEduLevel()));
            p.setEduLevelSrc("hbvalue:GB/T 4658-2006");
        }
        if (StringUtils.hasText(p.getOccupation())) {
            p.setOccupationName(stdDict.nameOf("hbvalue", "CV02.01.202", p.getOccupation()));
            p.setOccupationSrc("hbvalue:CV02.01.202");
        }
        /* ---------- B/B2 现住址+出生地/户籍/通讯/单位/联系人 地址四级级联: 按 area_code_2021 回填各级名称 ---------- */
        fillArea(p, p.getPresentProv(), HisPatient::setPresentProvName, p.getPresentCity(), HisPatient::setPresentCityName,
                p.getPresentCounty(), HisPatient::setPresentCountyName, p.getPresentTown(), HisPatient::setPresentTownName, HisPatient::setPresentSrc);
        fillArea(p, p.getBirthProv(), HisPatient::setBirthProvName, p.getBirthCity(), HisPatient::setBirthCityName,
                p.getBirthCounty(), HisPatient::setBirthCountyName, p.getBirthTown(), HisPatient::setBirthTownName, HisPatient::setBirthSrc);
        fillArea(p, p.getHouseholdProv(), HisPatient::setHouseholdProvName, p.getHouseholdCity(), HisPatient::setHouseholdCityName,
                p.getHouseholdCounty(), HisPatient::setHouseholdCountyName, p.getHouseholdTown(), HisPatient::setHouseholdTownName, HisPatient::setHouseholdSrc);
        fillArea(p, p.getMailProv(), HisPatient::setMailProvName, p.getMailCity(), HisPatient::setMailCityName,
                p.getMailCounty(), HisPatient::setMailCountyName, p.getMailTown(), HisPatient::setMailTownName, HisPatient::setMailSrc);
        fillArea(p, p.getEmpProv(), HisPatient::setEmpProvName, p.getEmpCity(), HisPatient::setEmpCityName,
                p.getEmpCounty(), HisPatient::setEmpCountyName, p.getEmpTown(), HisPatient::setEmpTownName, HisPatient::setEmpSrc);
        fillArea(p, p.getContactProv(), HisPatient::setContactProvName, p.getContactCity(), HisPatient::setContactCityName,
                p.getContactCounty(), HisPatient::setContactCountyName, p.getContactTown(), HisPatient::setContactTownName, HisPatient::setContactSrc);
        /* ---------- C 联系人与患者关系 ---------- */
        if (StringUtils.hasText(p.getContactRelation())) {
            p.setContactRelationName(stdDict.nameOf("hbvalue", "GB/T 4761-2008", p.getContactRelation()));
            p.setContactRelationSrc("hbvalue:GB/T 4761-2008");
        }
    }

    /** 地址四级级联统一回填: 按编码查 area_code_2021 填各级名称, 任一级有值则置来源标识。 */
    private void fillArea(HisPatient p, String prov, BiConsumer<HisPatient, String> setProvName,
                          String city, BiConsumer<HisPatient, String> setCityName,
                          String county, BiConsumer<HisPatient, String> setCountyName,
                          String town, BiConsumer<HisPatient, String> setTownName,
                          BiConsumer<HisPatient, String> setSrc) {
        if (StringUtils.hasText(prov)) {
            setProvName.accept(p, areaName(prov));
        }
        if (StringUtils.hasText(city)) {
            setCityName.accept(p, areaName(city));
        }
        if (StringUtils.hasText(county)) {
            setCountyName.accept(p, areaName(county));
        }
        if (StringUtils.hasText(town)) {
            setTownName.accept(p, areaName(town));
        }
        if (StringUtils.hasText(prov) || StringUtils.hasText(city) || StringUtils.hasText(county) || StringUtils.hasText(town)) {
            setSrc.accept(p, "area_code_2021");
        }
    }

    private String areaName(String code) {
        try {
            String s = code.trim();
            AreaCode a = areaService.getById(Long.parseLong(s));
            if (a == null && s.length() < 12) {
                // 兼容医保6位行政区划: area_code_2021 为12位, 按位数补足12位再查
                StringBuilder sb = new StringBuilder(s);
                while (sb.length() < 12) {
                    sb.append('0');
                }
                a = areaService.getById(Long.parseLong(sb.toString()));
            }
            return a == null ? null : a.getName();
        } catch (Exception e) {
            return null;
        }
    }

    /** 分页查询患者(姓名/患者号/身份证/医保编号/电话 模糊检索) */
    public IPage<HisPatient> pageQuery(long page, long size, String keyword) {
        LambdaQueryChainWrapper<HisPatient> q = lambdaQuery();
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(HisPatient::getName, keyword)
                    .or().like(HisPatient::getPatientNo, keyword)
                    .or().like(HisPatient::getIdCard, keyword)
                    .or().like(HisPatient::getPsnNo, keyword)
                    .or().like(HisPatient::getPhone, keyword));
        }
        return q.orderByDesc(HisPatient::getId).page(new Page<>(page, size));
    }

    /** 按身份证号精确查询(读卡/建档查重) */
    public HisPatient getByIdCard(String idCard) {
        if (!StringUtils.hasText(idCard)) {
            return null;
        }
        return lambdaQuery().eq(HisPatient::getIdCard, idCard).last("limit 1").one();
    }

    /** 按医保人员编号精确查询(建档查重) */
    public HisPatient getByPsnNo(String psnNo) {
        if (!StringUtils.hasText(psnNo)) {
            return null;
        }
        return lambdaQuery().eq(HisPatient::getPsnNo, psnNo).last("limit 1").one();
    }

    /** 生成租户内统一患者号(跨机构通用) */
    public String generatePatientNo() {
        int s = SEQ.incrementAndGet() % 1000;
        return "P" + DateUtil.currentTimeCompact() + String.format("%03d", s);
    }

    /**
     * 建档/复用统一主索引: 按 id_card(优先)或 psn_no 在租户内查重,
     * 命中则直接复用已有档案(不重复建卡); 未命中才新建, 并写入当前用户 org_id 作建档机构。
     */
    public HisPatient createPatient(HisPatient p) {
        if (!StringUtils.hasText(p.getName())) {
            throw new BizException(400, "患者姓名不能为空");
        }
        HisPatient exist = getByIdCard(p.getIdCard());
        if (exist == null) {
            exist = getByPsnNo(p.getPsnNo());
        }
        if (exist != null) {
            // 医共体内跨机构共享同一档案: 命中即复用
            return exist;
        }
        if (!StringUtils.hasText(p.getPatientNo())) {
            p.setPatientNo(generatePatientNo());
        }
        if (p.getOrgId() == null) {
            LoginUser lu = UserContext.get();
            if (lu != null) {
                p.setOrgId(lu.getOrgId());
            }
        }
        if (p.getStatus() == null) {
            p.setStatus(1);
        }
        enrichDict(p);
        save(p);
        recordCreate(p);
        return p;
    }

    /** 修改患者档案(回填字典名称与来源标识; 并按字段级 diff 记录修改留痕) */
    public void updatePatient(HisPatient p) {
        HisPatient old = p.getId() == null ? null : getById(p.getId());
        enrichDict(p);
        updateById(p);
        if (old != null) {
            // 以更新后的库内实际值为准比对, 避免 null 字段被忽略更新导致的误报
            recordChanges(old, getById(p.getId()), "手动修改");
        }
    }

    /** 查询某患者的修改记录(时间倒序) */
    public List<HisPatientChangeLog> listChangeLogs(Long patientId) {
        QueryWrapper<HisPatientChangeLog> q = new QueryWrapper<>();
        q.eq("patient_id", patientId).orderByDesc("create_time").orderByDesc("id");
        return changeLogMapper.selectList(q);
    }

    /** 医保读卡同步参保信息留痕: 来源=医保读卡, 字段=医保参保信息(子表完整记录条数) */
    public void logInsuSync(HisPatient p, int count) {
        if (p == null || p.getId() == null) {
            return;
        }
        insertLog(p, genBatchNo(), "insuRecords", "医保参保信息", null,
                "同步 " + count + " 条参保记录(1101 读卡完整返读)", "医保读卡", currentRealName());
    }

    /* ==================== 修改记录(字段级留痕) ==================== */

    /** 建档: 记录初始快照(旧值为空) */
    private void recordCreate(HisPatient p) {
        if (p == null || p.getId() == null) {
            return;
        }
        String batch = genBatchNo();
        BeanWrapper nw = new BeanWrapperImpl(p);
        String byName = currentRealName();
        for (String[] f : TRACKED_FIELDS) {
            String nv = display(nw, f[0], f[1], f[2]);
            if (!StringUtils.hasText(nv)) {
                continue;
            }
            insertLog(p, batch, f[0], f[2], null, nv, "建档", byName);
        }
    }

    /** 修改: 比对旧/新档案, 逐字段记录变更(同一次保存共享批次号) */
    private void recordChanges(HisPatient oldP, HisPatient newP, String source) {
        if (oldP == null || newP == null) {
            return;
        }
        String batch = genBatchNo();
        BeanWrapper ow = new BeanWrapperImpl(oldP);
        BeanWrapper nw = new BeanWrapperImpl(newP);
        String byName = currentRealName();
        for (String[] f : TRACKED_FIELDS) {
            String ov = display(ow, f[0], f[1], f[2]);
            String nv = display(nw, f[0], f[1], f[2]);
            if (Objects.equals(ov, nv)) {
                continue;
            }
            insertLog(newP, batch, f[0], f[2], ov, nv, source, byName);
        }
    }

    private void insertLog(HisPatient p, String batch, String fieldName, String label,
                           String ov, String nv, String source, String byName) {
        HisPatientChangeLog cl = new HisPatientChangeLog();
        cl.setPatientId(p.getId());
        cl.setPatientName(p.getName());
        cl.setBatchNo(batch);
        cl.setFieldName(fieldName.contains(",") ? fieldName.substring(0, fieldName.indexOf(',')) : fieldName);
        cl.setFieldLabel(label);
        cl.setOldValue(trunc(ov));
        cl.setNewValue(trunc(nv));
        cl.setSource(source);
        cl.setChangeByName(byName);
        changeLogMapper.insert(cl);
    }

    /** 取字段展示值: 状态特殊处理; 字典字段优先名称; 多级地址按 / 拼接 */
    private String display(BeanWrapper bw, String codeProps, String nameProps, String label) {
        if ("状态".equals(label)) {
            Object s = bw.getPropertyValue("status");
            if (s == null) {
                return "";
            }
            return Integer.valueOf(1).equals(s) ? "正常" : "停用";
        }
        String names = joinProps(bw, nameProps);
        if (StringUtils.hasText(names)) {
            return names;
        }
        return joinProps(bw, codeProps);
    }

    private String joinProps(BeanWrapper bw, String csv) {
        if (!StringUtils.hasText(csv)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String prop : csv.split(",")) {
            String v = fmt(bw.getPropertyValue(prop.trim()));
            if (StringUtils.hasText(v)) {
                if (sb.length() > 0) {
                    sb.append("/");
                }
                sb.append(v);
            }
        }
        return sb.toString();
    }

    private String fmt(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof LocalDateTime) {
            return ((LocalDateTime) v).format(DTF);
        }
        if (v instanceof LocalDate) {
            return v.toString();
        }
        return String.valueOf(v);
    }

    private String trunc(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    private String currentRealName() {
        LoginUser lu = UserContext.get();
        return lu == null ? null : lu.getRealName();
    }

    private String genBatchNo() {
        return "CL" + DateUtil.currentTimeCompact() + String.format("%04d", SEQ.incrementAndGet() % 10000);
    }
}
