package com.yb.hi.entity.mr;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 病案修改留痕: 编目/分配/审核/确认/锁定各环节的字段级变更追溯。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_mr_change_log")
public class HisMrChangeLog extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 编目主表ID(his_mr_catalog.id) */
    private Long catalogId;
    /** 就诊ID */
    private Long visitId;
    /** 操作类型:catalog编目 assign分配 audit审核 confirm确认 lock锁定 unlock解锁 */
    private String opType;
    /** 变更字段键 */
    private String fieldKey;
    /** 变更前值 */
    private String oldVal;
    /** 变更后值 */
    private String newVal;
    /** 操作人 */
    private String opUser;
    /** 操作时间 */
    private LocalDateTime opTime;
}
