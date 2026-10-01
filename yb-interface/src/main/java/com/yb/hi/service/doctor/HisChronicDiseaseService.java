package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.doctor.HisChronicDisease;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.doctor.HisChronicDiseaseMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 患者门诊慢特病备案服务(OP-C): 按患者维护门特/门慢病种备案与有效期, 供开方自动拆方与处方笺备注引用。
 */
@Service
public class HisChronicDiseaseService {

    private final HisChronicDiseaseMapper chronicMapper;

    public HisChronicDiseaseService(HisChronicDiseaseMapper chronicMapper) {
        this.chronicMapper = chronicMapper;
    }

    /** 患者有效备案病种列表(status=1, 按有效期止升序) */
    public List<HisChronicDisease> listByPatient(Long patientId) {
        return chronicMapper.selectList(Wrappers.<HisChronicDisease>lambdaQuery()
                .eq(HisChronicDisease::getPatientId, patientId)
                .eq(HisChronicDisease::getStatus, 1)
                .orderByAsc(HisChronicDisease::getValidTo));
    }

    /** 新增/更新备案: 新建时补机构与状态默认(租户由 MP 插件注入) */
    public HisChronicDisease save(HisChronicDisease row) {
        if (row.getId() == null) {
            if (row.getStatus() == null) {
                row.setStatus(1);
            }
            if (row.getSource() == null) {
                row.setSource("manual");
            }
            LoginUser user = UserContext.get();
            if (row.getOrgId() == null && user != null) {
                row.setOrgId(user.getOrgId());
            }
            chronicMapper.insert(row);
        } else {
            chronicMapper.updateById(row);
        }
        return row;
    }
}
