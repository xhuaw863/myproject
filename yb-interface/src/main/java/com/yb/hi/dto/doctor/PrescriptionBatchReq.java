package com.yb.hi.dto.doctor;

import lombok.Data;

import java.util.List;

/**
 * 批量开处方请求(拆方原子提交, C7): 一次请求携带全部处方批次, 服务端单事务开立,
 * 任一批失败整体回滚 —— 避免前端分批串行提交时中途失败重试导致已成功批次重复开立。
 */
@Data
public class PrescriptionBatchReq {

    /** 就诊ID */
    private Long visitId;
    /** 发药药房ID(各批共用): 医生手选; 空则按科室默认药房回落 */
    private Long pharmacyId;
    /** 处方批次(拆方后每组一批: rxType + 明细), 至少一批且每批明细非空 */
    private List<PrescriptionReq> batches;
}
