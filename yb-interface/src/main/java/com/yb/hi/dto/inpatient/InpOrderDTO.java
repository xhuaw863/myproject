package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 住院医嘱开立请求
 */
@Data
public class InpOrderDTO {

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 医嘱类型: 1长期 2临时 */
    private Integer orderType;
    /** 医嘱分类: 1药品 2检查 3检验 4治疗 5护理 6膳食 7其他 */
    private Integer orderCategory;
    /** 医嘱内容 */
    private String orderContent;
    /** 收费项目ID */
    private Long chargeItemId;
    /** 药品ID(药品目录) */
    private Long drugId;
    /** 规格 */
    private String spec;
    /** 剂量 */
    private String dosage;
    /** 剂量单位 */
    private String dosageUnit;
    /** 用法编码 */
    private String usageCode;
    /** 频次编码 */
    private String freqCode;
    /** 数量 */
    private BigDecimal quantity;
    /** 单价 */
    private BigDecimal unitPrice;
    /** 成组医嘱号 */
    private String groupNo;
    /* ---------- 手麻P1扩展字段 ---------- */
    /** 手术ID(his_surgery.id, 术中/术后医嘱) */
    private Long surgeryId;
    /** 手术申请单ID(his_surgery_apply.id, 申请阶段术前医嘱) */
    private Long surgeryApplyId;
    /** 手术医嘱阶段: 1术前 2术中 3术后 */
    private Integer orderPhase;
    /** 代开目标医生ID(his_staff.id; 权限按其口径校验, 非代开时为空) */
    private Long proxyDoctorId;
    /** 代开原因 */
    private String proxyReason;
}
