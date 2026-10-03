package com.yb.hi.entity.emr;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 病历质控整改通知单(病历P5b): 质控缺陷打包成单下发到责任科室/医疗组, status 走
 * 0下发→1已读→2整改中→3已整改→4已复核→5已关闭 闭环(6申诉中并落 appeal_* 三列),
 * 复核结论回写 review_* 四项; defect_ids 存缺陷ID JSON 数组。表由 DictSchemaMigration 启动期幂等建出。
 * 说明: tenant_id 由 MyBatis-Plus 租户插件自动注入/过滤, 实体不显式映射, 避免插入重复列。
 */
@Data
@TableName("his_emr_qc_notice")
public class HisEmrQcNotice implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 通知单编号 */
    private String noticeNo;
    /** 就诊ID(住院his_inp_visit.id/门诊his_visit.id) */
    private Long visitId;
    /** 病历记录ID(his_inp_medical_record.id) */
    private Long recordId;
    /** 责任科室ID(his_dept.id) */
    private Long deptId;
    /** 责任科室名称(冗余) */
    private String deptName;
    /** 医疗组(责任单元) */
    private String medicalGroup;
    /** 关联缺陷ID列表JSON数组(his_emr_qc_defect.id) */
    private String defectIds;
    /** 累计扣分 */
    private BigDecimal totalDeduct;
    /** 整改前等级(甲/乙/丙) */
    private String gradeBefore;
    /** 要求整改截止日期 */
    private LocalDate requireRectifyDate;
    /** 状态: 0下发 1已读 2整改中 3已整改 4已复核 5已关闭 6申诉中 */
    private Integer status;
    /** 下发人ID(his_staff.id) */
    private Long issuerId;
    /** 下发人姓名 */
    private String issuerName;
    /** 下发时间 */
    private LocalDateTime issueTime;
    /** 整改说明 */
    private String rectifyNote;
    /** 整改完成时间 */
    private LocalDateTime rectifyTime;
    /** 复核人ID(his_staff.id) */
    private Long reviewerId;
    /** 复核人姓名 */
    private String reviewerName;
    /** 复核时间 */
    private LocalDateTime reviewTime;
    /** 复核结果 */
    private String reviewResult;
    /** 申诉理由 */
    private String appealReason;
    /** 申诉时间 */
    private LocalDateTime appealTime;
    /** 申诉处理结果 */
    private String appealResult;
    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    /** 逻辑删除: 0正常 1删除 */
    @TableLogic
    private Integer deleted;
}

