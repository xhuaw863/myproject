package com.yb.hi.entity.ris;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 检查科室配置(科室类型/自动分配设备/默认报告模板/Worklist 开关/双阅比例/急诊标识色)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_exam_dept_config")
public class HisExamDeptConfig extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 科室ID(his_dept.id) */
    private Long deptId;
    /** 科室类型: RADIOLOGY/ULTRASOUND/ENDOSCOPY */
    private String deptType;
    /** 是否自动分配设备 */
    private Integer autoAssignDevice;
    /** 默认报告模板 */
    private Long defaultReportTemplateId;
    /** 是否启用DICOM Worklist */
    private Integer worklistEnabled;
    /** 双阅比例(0-100%) */
    private Integer doubleReadRate;
    /** 急诊标识颜色 */
    private String urgentColor;
}
