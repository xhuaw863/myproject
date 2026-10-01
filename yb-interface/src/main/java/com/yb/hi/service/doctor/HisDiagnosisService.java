package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.doctor.HisDiagnosis;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.doctor.HisDiagnosisMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 诊断服务: 按就诊维护诊断列表(替换式), 供2203上传
 */
@Service
public class HisDiagnosisService extends ServiceImpl<HisDiagnosisMapper, HisDiagnosis> {

    private final HisDiagFreqService diagFreqService;

    public HisDiagnosisService(HisDiagFreqService diagFreqService) {
        this.diagFreqService = diagFreqService;
    }

    /** 查询某次就诊的诊断列表(按排序号) */
    public List<HisDiagnosis> listByVisit(Long visitId) {
        return lambdaQuery()
                .eq(HisDiagnosis::getVisitId, visitId)
                .orderByAsc(HisDiagnosis::getDiagSrtNo)
                .list();
    }

    /**
     * 保存诊断(替换式): 先删旧诊断再插入新诊断, 自动补全排序号/主诊断标识/诊断时间
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveDiagnoses(Long visitId, String diagDept, String drNo, String drName,
                              List<HisDiagnosis> diagnoses) {
        saveDiagnoses(visitId, diagDept, drNo, drName, diagnoses, null, null);
    }

    /**
     * 保存诊断(替换式)重载: 额外传入本次就诊的医师/科室 ID, 供诊断高频沉淀按就诊实际开单医生归集
     * (不依赖登录用户, 避免管理员代操作时频次归属错位)。staffId/deptId 为空时回退登录上下文。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveDiagnoses(Long visitId, String diagDept, String drNo, String drName,
                              List<HisDiagnosis> diagnoses, Long visitStaffId, Long visitDeptId) {
        lambdaUpdate().eq(HisDiagnosis::getVisitId, visitId).remove();
        if (CollectionUtils.isEmpty(diagnoses)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int srt = 1;
        boolean hasMain = false;
        for (HisDiagnosis d : diagnoses) {
            d.setId(null);
            d.setVisitId(visitId);
            d.setDiagType(d.getDiagType() == null ? "1" : d.getDiagType());
            d.setDiagSrtNo(d.getDiagSrtNo() == null ? srt : d.getDiagSrtNo());
            if ("1".equals(d.getMaindiagFlag())) {
                hasMain = true;
            }
            d.setDiagDept(diagDept);
            d.setDiseDorNo(drNo);
            d.setDiseDorName(drName);
            d.setDiagTime(now);
            d.setValiFlag("1");
            srt++;
        }
        // 若无主诊断, 默认第一条为主诊断
        if (!hasMain) {
            diagnoses.get(0).setMaindiagFlag("1");
        }
        saveBatch(diagnoses);
        // OP-B 诊断高频沉淀: 优先按本次就诊实际医师/科室归集, 缺失时回退登录上下文(失败不阻断接诊保存)
        try {
            Long staffId = visitStaffId;
            Long deptId = visitDeptId;
            if (staffId == null || deptId == null) {
                LoginUser user = UserContext.get();
                if (user != null) {
                    if (staffId == null) { staffId = user.getStaffId(); }
                    if (deptId == null) { deptId = user.getDeptId(); }
                }
            }
            diagFreqService.recordUsage(diagnoses, staffId, deptId);
        } catch (Exception ignore) {
            // 频次沉淀为辅助能力, 异常不阻断接诊保存
        }
    }
}
