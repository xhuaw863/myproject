package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.doctor.HisDiagnosis;
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
    }
}
