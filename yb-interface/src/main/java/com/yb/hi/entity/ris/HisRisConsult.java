package com.yb.hi.entity.ris;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * RIS远程会诊/双阅记录(会诊意见/同意与否闭环, 双阅/远程会诊/科内讨论三类)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_ris_consult")
public class HisRisConsult extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 会诊号 */
    private String consultNo;
    /** 原报告ID */
    private Long reportId;
    /** 发起医生 */
    private Long requestDoctorId;
    /** 会诊原因 */
    private String requestReason;
    /** 会诊类型: 1双阅/2远程会诊/3科内讨论 */
    private Integer consultType;
    /** 会诊医生 */
    private Long consultDoctorId;
    /** 会诊机构(远程) */
    private Long consultOrgId;
    /** 会诊意见 */
    private String consultOpinion;
    /** 会诊时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime consultTime;
    /** 同意标志: 0不同意/1同意 */
    private Integer agreeFlag;
    /** 状态: 0待会诊/1已完成/2已取消 */
    private Integer status;
}
