package com.yb.hi.service.doctor;

import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.mapper.basedata.HisStaffMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 医师处方权限只读校验服务(OP-C, 需求2.2.2.3.14.3):
 * 汇总 his_staff 已建模的处方/精麻/抗菌分级/手术权限与有效期, 供开方前只读校验(不阻断改写, 由前端按类型拦截)。
 * 权限授予与留痕在员工档案维护, 本服务只读聚合, 避免与药事/员工管理写路径耦合。
 */
@Service
public class HisPrescribeAuthService {

    private final HisStaffMapper staffMapper;

    public HisPrescribeAuthService(HisStaffMapper staffMapper) {
        this.staffMapper = staffMapper;
    }

    /**
     * 医师处方权限聚合。type 可选: rx/narcotic/psych1/psych2/abx/surgery, 命中时附 typeAllowed。
     * 有效期(rxValidUntil)早于今日视为过期, 专项权限随过期一并失效。
     */
    public Map<String, Object> getAuth(Long staffId, String type) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("staffId", staffId);
        if (staffId == null) {
            result.put("found", false);
            return result;
        }
        HisStaff staff = staffMapper.selectById(staffId);
        if (staff == null) {
            result.put("found", false);
            return result;
        }
        LocalDate until = staff.getRxValidUntil();
        boolean expired = until != null && until.isBefore(LocalDate.now());
        result.put("found", true);
        result.put("staffName", staff.getStaffName());
        result.put("rxRight", nz(staff.getRxRight()));
        result.put("narcoticRight", nz(staff.getNarcoticRight()));
        result.put("psych1Right", nz(staff.getPsych1Right()));
        result.put("psych2Right", nz(staff.getPsych2Right()));
        result.put("antibioticLevel", staff.getAntibioticLevel());
        result.put("antibioticLevelName", staff.getAntibioticLevelName());
        result.put("surgeryLevel", staff.getSurgeryLevel());
        result.put("surgeryLevelName", staff.getSurgeryLevelName());
        result.put("rxValidUntil", until);
        result.put("expired", expired);
        if (type != null && !type.isEmpty()) {
            result.put("typeAllowed", checkType(staff, type, expired));
        }
        return result;
    }

    private boolean checkType(HisStaff staff, String type, boolean expired) {
        if (expired) {
            return false;
        }
        switch (type) {
            case "rx":
                return nz(staff.getRxRight()) == 1;
            case "narcotic":
                return nz(staff.getNarcoticRight()) == 1;
            case "psych1":
                return nz(staff.getPsych1Right()) == 1;
            case "psych2":
                return nz(staff.getPsych2Right()) == 1;
            case "abx":
                return staff.getAntibioticLevel() != null && !staff.getAntibioticLevel().isEmpty();
            case "surgery":
                return staff.getSurgeryLevel() != null && !staff.getSurgeryLevel().isEmpty();
            default:
                return false;
        }
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
