package com.yb.hi.service.outpatient;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.basedata.AreaCode;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.entity.outpatient.HisPatientInsu;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.outpatient.HisPatientInsuMapper;
import com.yb.hi.service.StdDictQueryService;
import com.yb.hi.service.basedata.AreaCodeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 患者医保参保信息服务(档案子表)。
 * 医保读卡(1101 人员信息获取)返回的参保信息列表完整保存; 一人可多条参保记录(职工/居民、参保/停保)。
 * 读卡同步按"来源=医保读卡(1101)"覆盖旧读卡记录, 手工记录保留; 同步动作写入档案修改记录留痕。
 */
@Service
public class HisPatientInsuService extends ServiceImpl<HisPatientInsuMapper, HisPatientInsu> {

    /** 读卡同步记录来源标识 */
    public static final String SRC_READ_CARD = "医保读卡(1101)";

    private final StdDictQueryService stdDict;
    private final AreaCodeService areaService;
    private final HisPatientService patientService;

    public HisPatientInsuService(StdDictQueryService stdDict, AreaCodeService areaService,
                                 HisPatientService patientService) {
        this.stdDict = stdDict;
        this.areaService = areaService;
        this.patientService = patientService;
    }

    /** 某患者的全部参保记录(在保优先, 起始日期倒序) */
    public List<HisPatientInsu> listByPatient(Long patientId) {
        QueryWrapper<HisPatientInsu> q = new QueryWrapper<>();
        q.eq("patient_id", patientId)
                .orderByDesc("psn_insu_stas = '1'").orderByDesc("psn_insu_date").orderByDesc("id");
        List<HisPatientInsu> list = list(q);
        list.forEach(this::enrich);
        return list;
    }

    /** 字典回填: 险种取医保字典 cv_code:insutype, 参保地区划取 area_code_2021 */
    public void enrich(HisPatientInsu r) {
        if (r == null) {
            return;
        }
        if (StringUtils.hasText(r.getInsutype())) {
            r.setInsutypeName(stdDict.nameOf("cv_code", "insutype", r.getInsutype()));
            r.setInsutypeSrc("cv_code:insutype");
        }
        if (StringUtils.hasText(r.getPsnType())) {
            r.setPsnTypeName(stdDict.nameOf("cv_code", "psn_type", r.getPsnType()));
            r.setPsnTypeSrc("cv_code:psn_type");
        }
        if (StringUtils.hasText(r.getPsnInsuStas())) {
            r.setPsnInsuStasName(stdDict.nameOf("cv_code", "psn_insu_stas", r.getPsnInsuStas()));
            r.setPsnInsuStasSrc("cv_code:psn_insu_stas");
        }
        if (StringUtils.hasText(r.getCvlservFlag())) {
            r.setCvlservFlagName(stdDict.nameOf("cv_code", "cvlserv_flag", r.getCvlservFlag()));
            r.setCvlservFlagSrc("cv_code:cvlserv_flag");
        }
        if (StringUtils.hasText(r.getInsuplcAdmdvs())) {
            r.setInsuplcAdmdvsName(areaName(r.getInsuplcAdmdvs()));
            r.setInsuplcAdmdvsSrc("area_code_2021");
        }
    }

    /**
     * 读卡同步: 模拟 1101 人员信息获取返回完整参保信息列表并覆盖保存(来源=读卡),
     * 重复读卡不产生重复记录; 同步结果写入档案修改记录。返回同步后全部参保记录。
     */
    @Transactional
    public List<HisPatientInsu> syncFromReadCard(Long patientId) {
        HisPatient p = patientService.getById(patientId);
        if (p == null) {
            throw new BizException("患者档案不存在: " + patientId);
        }
        List<HisPatientInsu> remote = mock1101InsuInfo(p);
        remove(new QueryWrapper<HisPatientInsu>().eq("patient_id", patientId).eq("src", SRC_READ_CARD));
        for (HisPatientInsu r : remote) {
            r.setId(null);
            r.setPatientId(patientId);
            r.setSrc(SRC_READ_CARD);
            enrich(r);
            save(r);
        }
        patientService.logInsuSync(p, remote.size());
        return listByPatient(patientId);
    }

    /** 模拟 1101 返回: 一人多条参保记录(当前职工在保 + 历史居民停保) */
    private List<HisPatientInsu> mock1101InsuInfo(HisPatient p) {
        String psnNo = StringUtils.hasText(p.getPsnNo()) ? p.getPsnNo()
                : (StringUtils.hasText(p.getIdCard()) && p.getIdCard().length() >= 8
                ? "PSN" + p.getIdCard().substring(p.getIdCard().length() - 8) : null);
        List<HisPatientInsu> list = new ArrayList<>();
        HisPatientInsu cur = new HisPatientInsu();
        cur.setPsnNo(psnNo);
        cur.setBalc(new BigDecimal("1286.50"));
        cur.setInsutype("310");
        cur.setPsnType("11");
        cur.setPsnInsuStas("1");
        cur.setPsnInsuDate(LocalDate.of(2015, 7, 1));
        cur.setCvlservFlag("0");
        cur.setInsuplcAdmdvs("420921");
        cur.setEmpName("孝昌县人民医院");
        list.add(cur);
        HisPatientInsu his = new HisPatientInsu();
        his.setPsnNo(psnNo);
        his.setBalc(BigDecimal.ZERO);
        his.setInsutype("390");
        his.setPsnType("15");
        his.setPsnInsuStas("2");
        his.setPsnInsuDate(LocalDate.of(2010, 1, 1));
        his.setPausInsuDate(LocalDate.of(2015, 6, 30));
        his.setCvlservFlag("0");
        his.setInsuplcAdmdvs("420921");
        list.add(his);
        return list;
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
}
