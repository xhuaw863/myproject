package com.yb.hi.dto.doctor;

import com.yb.hi.entity.doctor.HisPrescriptionItem;
import lombok.Data;

import java.util.List;

/**
 * 开处方请求: 就诊ID + 处方类型 + 明细列表(金额/主表信息由服务补全)
 */
@Data
public class PrescriptionReq {

    /** 就诊ID */
    private Long visitId;
    /** 处方类型: 西药/中药 */
    private String rxType;
    /** 发药药房ID(his_pharmacy_def.id): 医生手选; 空则按科室默认药房回落, 仍空不绑(发药全院FIFO) */
    private Long pharmacyId;
    /** 处方明细 */
    private List<HisPrescriptionItem> items;
}
