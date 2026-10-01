package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 诊断证明(诊断证明/病假条/转诊证明)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_medical_cert")
public class HisMedicalCert extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 就诊ID */
    private Long visitId;
    /** 患者ID */
    private Long patientId;
    /** 患者姓名 */
    private String patientName;
    /** 证明类型: 1-诊断证明 2-病假条 3-转诊证明 */
    private Integer certType;
    /** 诊断 */
    private String diagnosis;
    /** 证明内容 */
    private String certContent;
    /** 建议病假天数 */
    private Integer sickLeaveDays;
    /** 备注 */
    private String remark;
    /** 开具医师ID */
    private Long issueDrId;
    /** 开具医师姓名 */
    private String issueDrName;
    /** 开具时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime issueTime;
    /** 机构ID(开具科室归属机构) */
    private Long orgId;
    /** 审核状态: 0无须审核 1待审 2通过 3驳回 */
    private Integer auditStatus;
    /** 审核人ID */
    private Long auditorId;
    /** 审核人姓名 */
    private String auditorName;
    /** 审核时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime auditTime;
    /** 审核意见/驳回原因 */
    private String auditRemark;
}
