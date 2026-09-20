package com.yb.hi.service.basedata;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.mapper.basedata.HisStaffMapper;
import com.yb.hi.service.StdDictQueryService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 职工服务
 */
@Service
public class HisStaffService extends ServiceImpl<HisStaffMapper, HisStaff> {

    private final StdDictQueryService stdDict;

    public HisStaffService(StdDictQueryService stdDict) {
        this.stdDict = stdDict;
    }

    /**
     * 字典字段回填: 性别取医保字典 gend; 职称取卫生健康标准 CV08.30.005 专业技术职务类别;
     * 职工类别无国标字典, 为本地受控枚举(来源标识 local:staff_type)。
     */
    public void enrichDict(HisStaff s) {
        if (s == null) {
            return;
        }
        if (StringUtils.hasText(s.getGender())) {
            s.setGenderName(stdDict.nameOf("cv_code", "gend", s.getGender()));
            s.setGenderSrc("cv_code:gend");
        }
        if (StringUtils.hasText(s.getStaffType())) {
            s.setStaffTypeName(s.getStaffType());
            s.setStaffTypeSrc("local:staff_type");
        }
        if (StringUtils.hasText(s.getTitleCode())) {
            s.setTitleName(stdDict.nameOf("wst364", "CV08.30.005", s.getTitleCode()));
            s.setTitleSrc("wst364:CV08.30.005");
        }
        if (StringUtils.hasText(s.getPracCate())) {
            s.setPracCateName(stdDict.nameOf("whvalue", "CT98.00.024", s.getPracCate()));
            s.setPracCateSrc("whvalue:CT98.00.024");
        }
        /* 手术级别权限(医保字典 cv_code:oprn_lv_code): 与处方权独立, 仅回填三件套 */
        if (StringUtils.hasText(s.getSurgeryLevel())) {
            s.setSurgeryLevelName(stdDict.nameOf("cv_code", "oprn_lv_code", s.getSurgeryLevel()));
            s.setSurgeryLevelSrc("cv_code:oprn_lv_code");
        } else {
            s.setSurgeryLevelName(null);
            s.setSurgeryLevelSrc(null);
        }
        enrichRxRight(s);
    }

    /**
     * 医师处方权限: 抗菌级别三件套回填(字典 hbvalue:HBCV08.50.029) + 一致性守护。
     * 守护规则(体现药事法规):
     *  - 具备任一专项权(麻醉/精一/精二)或抗菌级别时, 自动置总处方权=1(有专项权必然有处方权);
     *  - 总处方权=0 时, 清空全部专项权与抗菌级别(无处方资格不得开任何药);
     *  - 四个权限位 null 归一为 0, 保证前端/库一致。
     */
    private void enrichRxRight(HisStaff s) {
        int rx = s.getRxRight() == null ? 0 : s.getRxRight();
        int nar = s.getNarcoticRight() == null ? 0 : s.getNarcoticRight();
        int p1 = s.getPsych1Right() == null ? 0 : s.getPsych1Right();
        int p2 = s.getPsych2Right() == null ? 0 : s.getPsych2Right();
        boolean hasAbx = StringUtils.hasText(s.getAntibioticLevel());
        if (rx == 0 && (nar == 1 || p1 == 1 || p2 == 1 || hasAbx)) {
            rx = 1; // 有专项权 → 自动具备总处方权
        }
        if (rx == 0) {
            nar = 0;
            p1 = 0;
            p2 = 0;
            hasAbx = false;
            s.setAntibioticLevel(null);
        }
        s.setRxRight(rx);
        s.setNarcoticRight(nar);
        s.setPsych1Right(p1);
        s.setPsych2Right(p2);
        if (hasAbx) {
            s.setAntibioticLevelName(stdDict.nameOf("hbvalue", "HBCV08.50.029", s.getAntibioticLevel()));
            s.setAntibioticLevelSrc("hbvalue:HBCV08.50.029");
        } else {
            s.setAntibioticLevelName(null);
            s.setAntibioticLevelSrc(null);
        }
    }

    /** 新增职工(回填字典名称与来源标识) */
    public void saveStaff(HisStaff s) {
        enrichDict(s);
        save(s);
    }

    /** 修改职工(回填字典名称与来源标识) */
    public void updateStaff(HisStaff s) {
        enrichDict(s);
        updateById(s);
    }

    /** 按科室/类别过滤职工 */
    public List<HisStaff> listByFilter(Long deptId, String staffType, String keyword) {
        return listByFilter(null, deptId, staffType, keyword);
    }

    public List<HisStaff> listByFilter(Long orgId, Long deptId, String staffType, String keyword) {
        return lambdaQuery()
                .eq(orgId != null, HisStaff::getOrgId, orgId)
                .eq(deptId != null, HisStaff::getDeptId, deptId)
                .eq(StringUtils.hasText(staffType), HisStaff::getStaffType, staffType)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(HisStaff::getStaffName, keyword)
                        .or().like(HisStaff::getStaffNo, keyword))
                .orderByAsc(HisStaff::getSortNo)
                .orderByAsc(HisStaff::getId)
                .list();
    }
}
