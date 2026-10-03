package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.util.List;

/**
 * 住院入院登记请求
 */
@Data
public class InpAdmitDTO {

    /** 患者ID(his_patient.id) */
    private Long patientId;
    /** 住院科室ID(his_dept.id) */
    private Long deptId;
    /** 病区ID(his_ward.id) */
    private Long wardId;
    /** 床位ID(his_bed.id) */
    private Long bedId;
    /** 主治医生ID(his_staff.id) */
    private Long doctorId;
    /** 入院诊断 */
    private String admitDiag;
    /** 医疗类别(医保) */
    private String medType;
    /** 医保人员编号 */
    private String psnNo;
    /** 险种类型 */
    private String insutype;
    /** 费别编码(his_fee_type_dict.code, 住院场景) */
    private String feeType;

    /* ---------- 入院登记扩展: 联系人/担保人/过敏史(模型增强配套) ---------- */
    /** 联系人姓名 */
    private String contactName;
    /** 联系人电话 */
    private String contactPhone;
    /** 联系人与患者关系 */
    private String contactRelation;
    /** 担保人姓名 */
    private String guarantorName;
    /** 担保人电话 */
    private String guarantorPhone;
    /** 担保人证件号 */
    private String guarantorIdNo;
    /** 入院登记时录入的过敏史(逐条落库至 his_inp_allergy) */
    private List<AllergyDTO> allergies;
    /** 预入院检查项(JSON字符串; 仅预入院登记 pre-admit 使用) */
    private String preCheckItems;
    /** 住院证ID(his_admission_cert.id; 持证入院时传, 登记成功后证置"已入院"并互写溯源; pre-admit 不消费证) */
    private Long certId;
}
