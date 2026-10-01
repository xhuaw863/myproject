package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 诊断→医嘱/处方模板关联映射: 选定诊断后按 diagCode+deptId 命中, 提示调入对应组套模板
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_diag_template_link")
public class HisDiagTemplateLink extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 诊断代码 */
    private String diagCode;
    /** 诊断名称 */
    private String diagName;
    /** 模板类型: rx_set处方组套/order_set医嘱组套 */
    private String templateType;
    /** 模板ID(his_medical_template.id) */
    private Long templateId;
    /** 适用科室ID(空=通用) */
    private Long deptId;
}
