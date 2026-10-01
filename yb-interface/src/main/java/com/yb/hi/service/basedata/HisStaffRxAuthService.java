package com.yb.hi.service.basedata;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.entity.basedata.HisStaffRxAuth;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.basedata.HisStaffMapper;
import com.yb.hi.mapper.basedata.HisStaffRxAuthMapper;
import com.yb.hi.service.StdDictQueryService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * 医师处方权限·按级授权明细服务(T2 阶段5-3)。
 * 权限名称按类别回填(抗菌分级走字典 hbvalue:HBCV08.50.029); 机构跟随职工归属机构。
 * 到期解析 {@link #effectiveAbxValidUntil}: 覆盖某分级的有效授权行取最晚有效期; 无明细行返回 null(交由 his_staff.rx_valid_until 回落)。
 */
@Service
public class HisStaffRxAuthService extends ServiceImpl<HisStaffRxAuthMapper, HisStaffRxAuth> {

    /** 抗菌分级字典来源(与 his_staff.antibiotic_level、his_drug_catalog.abx_grade 同码)。 */
    private static final String ABX_KIND = "abx";

    private final StdDictQueryService stdDict;
    private final HisStaffMapper staffMapper;

    public HisStaffRxAuthService(StdDictQueryService stdDict, HisStaffMapper staffMapper) {
        this.stdDict = stdDict;
        this.staffMapper = staffMapper;
    }

    /** 某职工的按级授权明细列表(按类别、编码、状态排序)。 */
    public List<HisStaffRxAuth> listByStaff(Long staffId) {
        if (staffId == null) {
            throw new BizException(400, "职工ID不能为空");
        }
        return lambdaQuery()
                .eq(HisStaffRxAuth::getStaffId, staffId)
                .orderByAsc(HisStaffRxAuth::getAuthKind)
                .orderByAsc(HisStaffRxAuth::getAuthCode)
                .orderByDesc(HisStaffRxAuth::getStatus)
                .orderByDesc(HisStaffRxAuth::getId)
                .list();
    }

    /** 新增/修改授权明细(校验职工存在、必填项、名称回填、机构跟随)。 */
    public HisStaffRxAuth saveAuth(HisStaffRxAuth a) {
        if (a == null || a.getStaffId() == null) {
            throw new BizException(400, "职工ID不能为空");
        }
        if (!StringUtils.hasText(a.getAuthKind())) {
            throw new BizException(400, "权限类别不能为空");
        }
        if (!StringUtils.hasText(a.getAuthCode())) {
            throw new BizException(400, "权限编码不能为空");
        }
        HisStaff staff = staffMapper.selectById(a.getStaffId());
        if (staff == null) {
            throw new BizException(400, "职工不存在");
        }
        enrich(a);
        a.setOrgId(staff.getOrgId());
        if (a.getStatus() == null) {
            a.setStatus(1);
        }
        if (a.getId() == null) {
            save(a);
        } else {
            updateById(a);
        }
        return a;
    }

    /** 删除授权明细(逻辑删除)。 */
    public void removeAuth(Long id) {
        if (id == null) {
            throw new BizException(400, "授权记录ID不能为空");
        }
        removeById(id);
    }

    /**
     * 抗菌分级有效期解析: 在有效(status=1)且覆盖目标分级(auth_code 数值 ≥ grade)的 abx 授权行中, 取最晚 valid_until。
     * 返回 null 表示无按级明细(交由调用方回落 his_staff.rx_valid_until)。grade 非数字或无行亦返回 null。
     */
    public LocalDate effectiveAbxValidUntil(Long staffId, String grade) {
        Integer need = parseGrade(grade);
        if (staffId == null || need == null) {
            return null;
        }
        return listByStaff(staffId).stream()
                .filter(r -> ABX_KIND.equalsIgnoreCase(r.getAuthKind()))
                .filter(r -> r.getStatus() != null && r.getStatus() == 1)
                .filter(r -> {
                    Integer have = parseGrade(r.getAuthCode());
                    return have != null && have >= need;
                })
                .map(HisStaffRxAuth::getValidUntil)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    /** 名称回填: abx 取分级字典, 其余专项取本地名称; 生效/有效期缺省校验。 */
    private void enrich(HisStaffRxAuth a) {
        if (ABX_KIND.equalsIgnoreCase(a.getAuthKind())) {
            String name = stdDict.nameOf("hbvalue", "HBCV08.50.029", a.getAuthCode());
            a.setAuthName(StringUtils.hasText(name) ? name : null);
        } else if (!StringUtils.hasText(a.getAuthName())) {
            a.setAuthName(kindLabel(a.getAuthKind()));
        }
        if (a.getValidFrom() != null && a.getValidUntil() != null && a.getValidFrom().isAfter(a.getValidUntil())) {
            throw new BizException(400, "生效日期不能晚于有效期至");
        }
    }

    private static String kindLabel(String kind) {
        if (kind == null) {
            return null;
        }
        switch (kind.toLowerCase()) {
            case "narcotic": return "麻醉药品";
            case "psych1": return "第一类精神药品";
            case "psych2": return "第二类精神药品";
            case ABX_KIND: return "抗菌分级";
            default: return kind;
        }
    }

    private static Integer parseGrade(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        try {
            return Integer.valueOf(code.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
