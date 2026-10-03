package com.yb.hi.dto.inpatient;

import lombok.Data;

/**
 * 护理转运交接单创建请求(落 his_nursing_transfer, 创建后 status=0 草稿, 交接/确认走独立端点)
 */
@Data
public class NursingTransferDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 患者ID(his_patient.id, 缺省从就诊主表回填) */
    private Long patientId;
    /** 类型:dept_transfer/surgery/hemodialysis/intervention/endoscopy */
    private String transferType;
    /** 交接核查JSON(缺省按类型套用默认核查模板) */
    private String checklist;
    /** 转出科室 */
    private Long fromDeptId;
    /** 转入科室 */
    private Long toDeptId;
    /** 备注 */
    private String note;
}
