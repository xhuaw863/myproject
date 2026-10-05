package com.yb.hi.dto.ris;

import lombok.Data;

/**
 * 医保检查结果互认查询条件(查患者近期同类检查记录, 供互认提示/避免重复检查)
 */
@Data
public class RisYbMutualQueryDTO {

    /** 患者ID(与 psnNo 二选一) */
    private Long patientId;
    /** 医保人员编号(医保3605互认查询键) */
    private String psnNo;
    /** 拟查医保检查项目代码(空=不限项目) */
    private String examItemCode;
    /** 检查部位(模糊过滤) */
    private String bodyPart;
    /** 查询起始日期(yyyy-MM-dd) */
    private String dateFrom;
    /** 查询截止日期(yyyy-MM-dd) */
    private String dateTo;
    /** 页码(从1开始) */
    private Integer page;
    /** 每页行数(默认20) */
    private Integer size;
}
