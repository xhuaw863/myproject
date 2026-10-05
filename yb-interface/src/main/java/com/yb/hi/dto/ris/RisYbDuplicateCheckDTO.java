package com.yb.hi.dto.ris;

import lombok.Data;

import java.time.LocalDate;

/**
 * 重复检查校验请求(开单前查患者近期是否已做同类检查, 供开单拦截/提示)
 */
@Data
public class RisYbDuplicateCheckDTO {

    /** 患者ID(与 psnNo 二选一) */
    private Long patientId;
    /** 医保人员编号 */
    private String psnNo;
    /** 拟开医保检查项目代码 */
    private String examItemCode;
    /** 检查类型: XRAY/CT/MRI/US/DSA/ENDO */
    private String examType;
    /** 检查部位(同部位才算重复) */
    private String bodyPart;
    /** 拟检查日期(缺省当天, 校验窗口以此回溯) */
    private LocalDate planExamDate;
    /** 回溯天数(缺省7天) */
    private Integer checkDays;
    /** 排除的申请单ID(编辑改单时不与自身比对) */
    private Long excludeRequestId;
}
