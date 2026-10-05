package com.yb.hi.dto.doctor;

import com.yb.hi.entity.doctor.HisDiagnosis;
import lombok.Data;

import java.util.List;

/**
 * 接诊中显式保存诊断请求(OP-B 报卡前移): 诊断面板"保存诊断"按钮提交,
 * 替换式落 his_diagnosis 后同响应返回报卡触发清单(免二次往返)。
 */
@Data
public class DiagnosisSaveReq {

    /** 就诊ID(his_visit.id), 仅接诊中(visitStatus=2)可保存 */
    private Long visitId;

    /** 本次诊断全量清单(替换式: 先删后插, 与完成接诊同一口径) */
    private List<HisDiagnosis> diagnoses;
}
