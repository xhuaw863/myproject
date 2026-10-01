package com.yb.hi.entity.mr;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 病案上报批次明细(P2): 每条就诊在 审核→转换→上报 三段中的逐项结果快照。
 * check_status: 1通过 2拦截(审核阶段落); converted/reported 标记转换与上报阶段是否已处理。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_mr_report_item")
public class HisMrReportItem extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 批次ID(his_mr_report_batch.id) */
    private Long batchId;
    /** 就诊ID(his_inp_visit.id) */
    private Long visitId;
    /** 编目主表ID(his_mr_catalog.id) */
    private Long catalogId;
    /** 患者姓名 */
    private String patientName;
    /** 住院号 */
    private String inpNo;
    /** 主要诊断编码 */
    private String mainDiagCode;
    /** 审核结果:1通过 2拦截 */
    private Integer checkStatus;
    /** 审核意见/拦截原因 */
    private String checkMsg;
    /** 是否已转换:0否 1是 */
    private Integer converted;
    /** 是否已上报:0否 1是 */
    private Integer reported;
    /** 上报回执/失败原因 */
    private String reportMsg;
}
