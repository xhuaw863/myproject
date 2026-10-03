package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 护理文书模板(与医生站 his_emr_template 解耦: 一般/危重/手术护理记录等,
 * document 承载 Tiptap ProseMirror JSON, fields 承载字段定义JSON, 支持打印纸张/方向与三级作用域)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_nursing_template")
public class HisNursingTemplate extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 模板编码 */
    private String code;
    /** 模板名称 */
    private String name;
    /** 文书类型:nursing_record/assessment/transfer/consent/nursing_plan */
    private String recordType;
    /** 适用病区JSON数组(空=全院) */
    private String wardScope;
    /** 纸张大小(默认A4) */
    private String paperSize;
    /** 打印方向:portrait/landscape */
    private String orientation;
    /** 字段定义JSON */
    private String fields;
    /** Tiptap JSON文档 */
    private String document;
    /** 打印格式脚本 */
    private String printScript;
    /** 范围:0全院 1病区 2个人 */
    private Integer scopeLevel;
}
