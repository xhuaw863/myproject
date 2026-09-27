package com.yb.hi.service.basedata;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.basedata.HisDept;
import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.mapper.basedata.HisStaffMapper;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.platform.service.SysOrgService;
import com.yb.hi.framework.util.PinyinUtil;
import com.yb.hi.service.StdDictQueryService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职工服务
 */
@Service
public class HisStaffService extends ServiceImpl<HisStaffMapper, HisStaff> {

    private final StdDictQueryService stdDict;
    private final HisDeptService deptService;
    private final SysOrgService orgService;

    public HisStaffService(StdDictQueryService stdDict, HisDeptService deptService, SysOrgService orgService) {
        this.stdDict = stdDict;
        this.deptService = deptService;
        this.orgService = orgService;
    }

    /**
     * 字典字段回填: 性别取医保字典 gend; 职称取卫生健康标准 CV08.30.005 专业技术职务类别;
     * 职工类别无国标字典, 为本地受控枚举(来源标识 local:staff_type)。
     */
    public void enrichDict(HisStaff s) {
        if (s == null) {
            return;
        }
        // 拼音简码随姓名自动重算(只读); 自定义码 abbr_code 由维护页透传不覆盖
        s.setPyCode(PinyinUtil.initials(s.getStaffName()));
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
        return listByFilter(null, false, deptId, staffType, keyword, true, null);
    }

    /**
     * 按机构/科室/类别过滤职工。
     * withSubOrgs=true 时选上级机构级联含其下级机构(县→乡→村);
     * withChildren=true 时选上级科室级联含其下级子树科室(大类→科室→窗口/诊室); 否则均仅精确匹配选中项。
     * status 非空时按在职状态(1在职/0停用)精确过滤。
     */
    public List<HisStaff> listByFilter(Long orgId, boolean withSubOrgs, Long deptId, String staffType, String keyword, boolean withChildren, Integer status) {
        List<Long> orgIds = (orgId == null || !withSubOrgs) ? null : orgService.subtreeIds(orgId);
        List<Long> deptIds = (deptId == null || !withChildren) ? null : deptService.subtreeIds(deptId);
        return lambdaQuery()
                .in(orgIds != null, HisStaff::getOrgId, orgIds)
                .eq(orgId != null && orgIds == null, HisStaff::getOrgId, orgId)
                .in(deptIds != null, HisStaff::getDeptId, deptIds)
                .eq(deptId != null && deptIds == null, HisStaff::getDeptId, deptId)
                .eq(StringUtils.hasText(staffType), HisStaff::getStaffType, staffType)
                .eq(status != null, HisStaff::getStatus, status)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(HisStaff::getStaffName, keyword)
                        .or().like(HisStaff::getStaffNo, keyword)
                        .or().like(HisStaff::getPyCode, keyword)
                        .or().like(HisStaff::getAbbrCode, keyword))
                .orderByAsc(HisStaff::getSortNo)
                .orderByAsc(HisStaff::getId)
                .list();
    }

    /** 导出职工列表(head/rows/total): 与列表同一筛选(含级联开关), 一次性导出全部匹配行 */
    public Map<String, Object> exportRows(Long orgId, boolean withSubOrgs, Long deptId, String staffType, String keyword, boolean withChildren, Integer status) {
        List<HisStaff> list = listByFilter(orgId, withSubOrgs, deptId, staffType, keyword, withChildren, status);
        Map<Long, String> deptNames = new LinkedHashMap<>();
        for (HisDept d : deptService.listAll()) {
            deptNames.put(d.getId(), d.getDeptName());
        }
        Map<Long, String> orgNames = new LinkedHashMap<>();
        for (SysOrg o : orgService.listAll()) {
            orgNames.put(o.getId(), o.getOrgName());
        }
        List<List<String>> head = new ArrayList<>();
        for (String h : new String[]{"工号", "姓名", "类别", "性别", "职称", "科室", "归属机构", "主治医师编码", "国家医保业务编码", "执业类别",
                "身份证号", "出生日期", "联系电话", "可挂号", "挂号费", "处方权", "麻醉", "精一", "精二",
                "抗菌级别", "手术级别", "状态", "排序号", "备注"}) {
            head.add(Collections.singletonList(h));
        }
        List<List<Object>> rows = new ArrayList<>();
        for (HisStaff s : list) {
            rows.add(Arrays.asList(
                    nz(s.getStaffNo()), nz(s.getStaffName()), nz(s.getStaffType()), genderText(s), nz(s.getTitleName()),
                    s.getDeptId() == null ? "" : nz(deptNames.get(s.getDeptId())),
                    s.getOrgId() == null ? "" : nz(orgNames.get(s.getOrgId())),
                    nz(s.getAtddrNo()), nz(s.getMedInsurCode()), nz(s.getPracCateName()),
                    nz(s.getIdCard()), s.getBirthDate() == null ? "" : s.getBirthDate().toString(), nz(s.getPhone()),
                    flagText(s.getCanRegister()), s.getRegFee(),
                    flagText(s.getRxRight()), flagText(s.getNarcoticRight()), flagText(s.getPsych1Right()), flagText(s.getPsych2Right()),
                    nz(s.getAntibioticLevelName()), nz(s.getSurgeryLevelName()),
                    s.getStatus() != null && s.getStatus() == 1 ? "在职" : "停用",
                    s.getSortNo() == null ? "" : s.getSortNo(), nz(s.getMemo())));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("head", head);
        out.put("rows", rows);
        out.put("total", rows.size());
        return out;
    }

    /** 性别文本: 优先用回填名称, 无则按医保字典 gend 码翻译(1男/2女) */
    private static String genderText(HisStaff s) {
        if (StringUtils.hasText(s.getGenderName())) {
            return s.getGenderName();
        }
        if ("1".equals(s.getGender())) {
            return "男";
        }
        if ("2".equals(s.getGender())) {
            return "女";
        }
        return nz(s.getGender());
    }

    /** 0/1 开关文本: 1=是 0=否 null=空 */
    private static String flagText(Integer v) {
        return v == null ? "" : (v == 1 ? "是" : "否");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
