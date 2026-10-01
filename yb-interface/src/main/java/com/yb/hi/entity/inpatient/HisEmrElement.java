package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 病历数据元(要素): 结构化 structure 按模板 fields 抽取出的字段级记录,
 * 支撑按字段检索/统计/病案首页透视/上报数据集, 以及逻辑性/规范性质控。
 *
 * 值分列存储: 标量文本→value_text; number/体征→value_num(+value_unit); date/datetime→value_date;
 * select/dict 命中→term_code(+dict_source), 同时回填 value_text(显示名)。
 * table/array 字段以 sort_no 保留元素序, 一个 field_key 可对应多行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_emr_element")
public class HisEmrElement extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 适用范围: 1住院 2门诊 */
    private Integer scope;
    /** 病历记录ID(住院 his_inp_medical_record.id; 门诊可空) */
    private Long recordId;
    /** 就诊ID(住院 his_inp_visit.id / 门诊 his_visit.id) */
    private Long visitId;
    /** 患者ID */
    private Long patientId;
    /** 科室ID(his_dept.id) */
    private Long deptId;
    /** 医生/书写人ID(his_staff.id) */
    private Long doctorId;
    /** 记录类型(对应 record_type/template_category) */
    private Integer recordType;
    /** 来源模板ID(his_emr_template.id) */
    private Long templateId;
    /** 字段键(structure 中的 fieldKey) */
    private String fieldKey;
    /** 字段名称(冗余便于展示/导出) */
    private String fieldLabel;
    /** 术语/值域编码(select/dict 命中时) */
    private String termCode;
    /** 字典来源标识 */
    private String dictSource;
    /** 文本值 */
    private String valueText;
    /** 数值值 */
    private BigDecimal valueNum;
    /** 日期/时间值 */
    private LocalDateTime valueDate;
    /** 单位(数值/体征) */
    private String valueUnit;
    /** 同字段多值序号(table/array 元素序) */
    private Integer sortNo;
}
