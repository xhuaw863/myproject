package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 病历模板发布审批流水: 记录 提交→通过/驳回 的状态迁移与审核意见留痕(his_emr_template_approval)。
 * 一次审批动作落一行; 模板当前发布态由 his_emr_template.publish_status 承载, 本表为过程留痕。
 * 表由 DictSchemaMigration 启动期幂等建出; tenant_id 由租户插件注入。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_emr_template_approval")
public class HisEmrTemplateApproval extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 模板ID */
    private Long templateId;
    /** 变更前发布态 */
    private Integer fromStatus;
    /** 变更后发布态 */
    private Integer toStatus;
    /** 提交人ID */
    private Long submitUserId;
    /** 提交人姓名 */
    private String submitUserName;
    /** 提交时间 */
    private LocalDateTime submitTime;
    /** 审核人ID */
    private Long reviewUserId;
    /** 审核人姓名 */
    private String reviewUserName;
    /** 审核时间 */
    private LocalDateTime reviewTime;
    /** 审核动作:pass/reject */
    private String reviewAction;
    /** 审核意见 */
    private String reviewOpinion;
}
