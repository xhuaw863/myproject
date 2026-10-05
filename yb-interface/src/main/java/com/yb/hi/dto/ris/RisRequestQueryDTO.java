package com.yb.hi.dto.ris;

import lombok.Data;

/**
 * 检查申请单查询条件(分页列表/工作台检索)
 */
@Data
public class RisRequestQueryDTO {

    /** 页码(从1开始) */
    private Integer page;
    /** 每页行数(默认20) */
    private Integer size;
    /** 关键字(申请单号/患者姓名/检查项目模糊) */
    private String keyword;
    /** 来源: 1门诊/2住院/3急诊/4体检 */
    private Integer sourceType;
    /** 检查类型: XRAY/CT/MRI/US/DSA/ENDO */
    private String examType;
    /** 状态: 0待预约/1已预约/2已登记/3检查中/4已完成/5已报告/6已审核/7已取消 */
    private Integer status;
    /** 是否急诊 */
    private Integer isUrgent;
    /** 患者ID */
    private Long patientId;
    /** 申请科室ID */
    private Long applyDeptId;
    /** 执行科室ID */
    private Long targetDeptId;
    /** 设备ID */
    private Long deviceId;
    /** 缴费标志: 0未缴费/1已缴费 */
    private Integer paidFlag;
    /** 医保上报状态: 0未上报/1已上报/2失败 */
    private Integer ybUploadStatus;
    /** 申请日期起(yyyy-MM-dd) */
    private String dateFrom;
    /** 申请日期止(yyyy-MM-dd) */
    private String dateTo;
}
