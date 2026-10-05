package com.yb.hi.dto.ris;

import lombok.Data;

/**
 * RIS 统计查询条件(工作量/阳性率/时效/设备利用等报表共用)
 */
@Data
public class RisStatQueryDTO {

    /** 页码(从1开始, 明细模式用) */
    private Integer page;
    /** 每页行数(默认20, 明细模式用) */
    private Integer size;
    /** 统计起始日期(yyyy-MM-dd, 按检查/报告日期) */
    private String dateFrom;
    /** 统计截止日期(yyyy-MM-dd) */
    private String dateTo;
    /** 机构ID(空=当前机构) */
    private Long orgId;
    /** 执行科室ID */
    private Long deptId;
    /** 设备ID */
    private Long deviceId;
    /** 模态: CT/MR/DR/US/ES */
    private String modality;
    /** 科室类型: RADIOLOGY/ULTRASOUND/ENDOSCOPY */
    private String deptType;
    /** 报告医师ID */
    private Long reportDoctorId;
    /** 状态过滤(报告/申请状态, 语义随统计类型) */
    private Integer status;
    /** 分组维度: day按天/dept按科室/modality按模态/doctor按医师/device按设备 */
    private String groupBy;
}
