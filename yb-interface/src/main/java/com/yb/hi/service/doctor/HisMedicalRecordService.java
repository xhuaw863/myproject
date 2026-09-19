package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.entity.doctor.HisMedicalRecord;
import com.yb.hi.mapper.doctor.HisMedicalRecordMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 门诊病历服务(SOAP): 按就诊唯一维护
 */
@Service
public class HisMedicalRecordService extends ServiceImpl<HisMedicalRecordMapper, HisMedicalRecord> {

    /** 查询某次就诊的病历 */
    public HisMedicalRecord getByVisit(Long visitId) {
        return lambdaQuery().eq(HisMedicalRecord::getVisitId, visitId).one();
    }

    /** 保存/更新病历(按就诊唯一, 存在则更新) */
    public void saveRecord(HisMedicalRecord record) {
        HisMedicalRecord exist = getByVisit(record.getVisitId());
        if (exist != null) {
            record.setId(exist.getId());
        }
        record.setRecordTime(LocalDateTime.now());
        saveOrUpdate(record);
    }
}
