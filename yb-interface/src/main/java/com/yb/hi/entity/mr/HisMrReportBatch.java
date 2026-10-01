package com.yb.hi.entity.mr;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 病案上报批次(P2 上报三段闭环): 卫统4表 / HQMS绩效 / 医保结算清单的 审核→转换→上报 载体。
 * status 严格前进不可跳档: 1待审核 2已审核待转换 3已转换待上报 4已上报 5失败。明细见 his_mr_report_item。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_mr_report_batch")
public class HisMrReportBatch extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID(sys_org.id) */
    private Long orgId;
    /** 上报类型:wt卫统4表 hqms HQMS绩效 med_list医保结算清单 */
    private String reportType;
    /** 上报批次号 */
    private String batchNo;
    /** 统计起 */
    private LocalDate periodFrom;
    /** 统计止 */
    private LocalDate periodTo;
    /** 闭环状态:1待审核 2已审核待转换 3已转换待上报 4已上报 5失败 */
    private Integer status;
    /** 病案总数 */
    private Integer totalCount;
    /** 审核通过数 */
    private Integer okCount;
    /** 审核拦截数 */
    private Integer errCount;
    /** 审核人姓名 */
    private String reviewerName;
    /** 审核时间 */
    private LocalDateTime reviewTime;
    /** 转换人姓名 */
    private String converterName;
    /** 转换时间 */
    private LocalDateTime convertTime;
    /** 上报人姓名 */
    private String submitterName;
    /** 上报时间 */
    private LocalDateTime submitTime;
    /** 上报文件/数据集引用 */
    private String fileRef;
    /** 失败/拦截原因 */
    private String failReason;
}
