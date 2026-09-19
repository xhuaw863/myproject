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
    /** 处方明细 */
    private List<HisPrescriptionItem> items;
}
