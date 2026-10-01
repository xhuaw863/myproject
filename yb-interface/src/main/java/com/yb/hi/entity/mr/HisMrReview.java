package com.yb.hi.entity.mr;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 病案审核确认与锁定: 质控人员二级审核确认→锁定, 解锁需录原因。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_mr_review")
public class HisMrReview extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 编目主表ID(his_mr_catalog.id) */
    private Long catalogId;
    /** 就诊ID */
    private Long visitId;
    /** 审核人(sys_user.id) */
    private Long reviewerId;
    /** 审核人姓名 */
    private String reviewerName;
    /** 审核意见 */
    private String auditOpinion;
    /** 审核确认时间 */
    private LocalDateTime confirmTime;
    /** 锁定状态:0未锁 1已锁 */
    private Integer lockStatus;
    /** 锁定时间 */
    private LocalDateTime lockTime;
    /** 解锁原因 */
    private String unlockReason;
}
