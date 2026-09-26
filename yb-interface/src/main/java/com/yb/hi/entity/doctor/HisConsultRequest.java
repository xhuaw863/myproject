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
 * 会诊申请(申请科室发起, 受邀科室接受/完成/拒绝并反馈会诊意见)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_consult_request")
public class HisConsultRequest extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 就诊ID */
    private Long visitId;
    /** 患者ID */
    private Long patientId;
    /** 患者姓名 */
    private String patientName;
    /** 申请科室ID */
    private Long applyDeptId;
    /** 申请科室名称 */
    private String applyDeptName;
    /** 申请医师ID */
    private Long applyDrId;
    /** 申请医师姓名 */
    private String applyDrName;
    /** 受邀会诊科室ID */
    private Long consultDeptId;
    /** 受邀会诊科室名称 */
    private String consultDeptName;
    /** 会诊目的 */
    private String consultPurpose;
    /** 病情摘要 */
    private String conditionSummary;
    /** 紧急程度: 1-普通 2-急 3-紧急 */
    private Integer urgency;
    /** 期望会诊时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expectedTime;
    /** 状态: 1-已申请 2-已接受 3-已完成 4-已拒绝 */
    private Integer status;
    /** 会诊意见(受邀科室反馈) */
    private String consultOpinion;
    /** 机构ID(申请科室归属机构) */
    private Long orgId;
}
