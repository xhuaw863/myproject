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
 * 住院证(门诊医生开具, 患者持证办理入院)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_admission_cert")
public class HisAdmissionCert extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 就诊ID */
    private Long visitId;
    /** 患者ID */
    private Long patientId;
    /** 患者姓名 */
    private String patientName;
    /** 拟收治科室ID */
    private Long admitDeptId;
    /** 拟收治科室名称 */
    private String admitDeptName;
    /** 入院诊断 */
    private String admitDiagnosis;
    /** 病情摘要 */
    private String conditionSummary;
    /** 入院目的 */
    private String admitPurpose;
    /** 紧急程度: 1-普通 2-急 3-危急 */
    private Integer urgency;
    /** 状态: 1-已开具 2-已入院 3-已作废 */
    private Integer status;
    /** 开具医师ID */
    private Long applyDrId;
    /** 开具医师姓名 */
    private String applyDrName;
    /** 开具时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime applyTime;
    /** 机构ID(开具时就诊科室归属机构) */
    private Long orgId;
    /** 消费本证的住院就诊ID(持证入院登记回写, his_inp_visit.id) */
    private Long admittedVisitId;
}
