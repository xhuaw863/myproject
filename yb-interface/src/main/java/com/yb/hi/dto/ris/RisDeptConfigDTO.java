package com.yb.hi.dto.ris;

import lombok.Data;

/**
 * 检查科室配置保存请求(落 his_exam_dept_config)
 */
@Data
public class RisDeptConfigDTO {

    /** 配置ID(编辑时传) */
    private Long id;
    /** 科室ID(his_dept.id) */
    private Long deptId;
    /** 科室类型: RADIOLOGY/ULTRASOUND/ENDOSCOPY */
    private String deptType;
    /** 是否自动分配设备 */
    private Integer autoAssignDevice;
    /** 默认报告模板ID(his_ris_report_template.id) */
    private Long defaultReportTemplateId;
    /** 是否启用DICOM Worklist */
    private Integer worklistEnabled;
    /** 双阅比例(0-100%) */
    private Integer doubleReadRate;
    /** 急诊标识颜色 */
    private String urgentColor;
}
