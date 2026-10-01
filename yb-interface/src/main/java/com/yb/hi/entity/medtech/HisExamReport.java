package com.yb.hi.entity.medtech;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 检查/检验报告(报告-审核双签)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_exam_report")
public class HisExamReport extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 报告单号(BG+日期+序号) */
    private String reportNo;
    /** 医嘱单ID(his_order.id) */
    private Long orderId;
    /** 患者ID */
    private Long patientId;
    /** 报告类型: exam检查(影像)/lab检验(化验) */
    private String reportType;
    /** 所见(检查所见/检验结果汇总) */
    private String findings;
    /** 结论(检查结论/检验诊断) */
    private String conclusion;
    /** 关键图像(JSON: 图像URL/描述数组) */
    private String keyImages;
    /** 报告医师ID(his_staff.id) */
    private Long reportDoctorId;
    /** 报告时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reportTime;
    /** 审核医师ID(his_staff.id) */
    private Long reviewDoctorId;
    /** 审核时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reviewTime;
    /** 状态: 0草稿 1已报告 2已审核 3已作废 */
    private Integer status;
    /** 危急值标志: 1有 0无 */
    private Integer criticalFlag;
    /** 撤回人ID(his_staff.id, 作废时记录) */
    private Long revokeBy;
    /** 撤回时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime revokeTime;
    /** 撤回原因 */
    private String revokeReason;
    /** DICOM StudyInstanceUID(外部PACS影像挂接键, T2阶段5-1) */
    private String pacsStudyUid;
    /** PACS服务标识/来源(区分多PACS实例, T2阶段5-1) */
    private String pacsServer;
}
