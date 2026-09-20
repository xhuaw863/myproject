package com.yb.hi.service.outpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.common.DateUtil;
import com.yb.hi.entity.basedata.AreaCode;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.outpatient.HisPatientMapper;
import com.yb.hi.service.StdDictQueryService;
import com.yb.hi.service.basedata.AreaCodeService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.atomic.AtomicInteger;

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

    public HisPatientService(StdDictQueryService stdDict, AreaCodeService areaService) {
        this.stdDict = stdDict;
        this.areaService = areaService;
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
        /* ---------- B 现住址五级级联: 按 area_code_2021 回填各级名称 ---------- */
        if (StringUtils.hasText(p.getPresentProv())) {
            p.setPresentProvName(areaName(p.getPresentProv()));
        }
        if (StringUtils.hasText(p.getPresentCity())) {
            p.setPresentCityName(areaName(p.getPresentCity()));
        }
        if (StringUtils.hasText(p.getPresentCounty())) {
            p.setPresentCountyName(areaName(p.getPresentCounty()));
        }
        if (StringUtils.hasText(p.getPresentTown())) {
            p.setPresentTownName(areaName(p.getPresentTown()));
        }
        if (StringUtils.hasText(p.getPresentProv()) || StringUtils.hasText(p.getPresentCity())
                || StringUtils.hasText(p.getPresentCounty()) || StringUtils.hasText(p.getPresentTown())) {
            p.setPresentSrc("area_code_2021");
        }
        /* ---------- C 联系人与患者关系 ---------- */
        if (StringUtils.hasText(p.getContactRelation())) {
            p.setContactRelationName(stdDict.nameOf("hbvalue", "GB/T 4761-2008", p.getContactRelation()));
            p.setContactRelationSrc("hbvalue:GB/T 4761-2008");
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
        return p;
    }

    /** 修改患者档案(同样回填字典名称与来源标识) */
    public void updatePatient(HisPatient p) {
        enrichDict(p);
        updateById(p);
    }
}
