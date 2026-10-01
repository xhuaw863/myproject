package com.yb.hi.entity.mr;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 病案质控审核错误项: 批量/单份审核产生的规则命中记录, 供错误归类与定位。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_mr_quality_err")
public class HisMrQualityErr extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 编目主表ID(his_mr_catalog.id) */
    private Long catalogId;
    /** 就诊ID */
    private Long visitId;
    /** 审核批次号 */
    private String batchNo;
    /** 规则类别:强制 非强制 */
    private String ruleCategory;
    /** 规则编码 */
    private String ruleCode;
    /** 定位字段键 */
    private String fieldKey;
    /** 错误描述 */
    private String errorMsg;
    /** 是否已修复:0否 1是 */
    private Integer resolved;
}
