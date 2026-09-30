package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 病历宏变量定义(患者/就诊/诊断/医嘱/检验/体征六源取值, 书写时自动替换占位符)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_emr_macro")
public class HisEmrMacro extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 宏变量编码 */
    private String macroCode;
    /** 宏变量名称 */
    private String macroName;
    /** 数据来源: 1患者 2就诊 3诊断 4医嘱 5检验 6体征 */
    private Integer dataSource;
    /** 来源字段 */
    private String sourceField;
    /** 格式化模式 */
    private String formatPattern;
    /** 说明 */
    private String description;
}
