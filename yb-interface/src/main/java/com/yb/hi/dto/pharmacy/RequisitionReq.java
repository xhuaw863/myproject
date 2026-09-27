package com.yb.hi.dto.pharmacy;

import lombok.Data;

import java.util.List;

/** 创建/提交药品请领单请求(药房→药库) */
@Data
public class RequisitionReq {

    /** 机构ID(空则取当前登录用户机构) */
    private Long orgId;
    /** 请领药房ID */
    private Long pharmacyId;
    /** 发货来源药库ID(空则取药房关联药库 warehouse_id) */
    private Long toWarehouseId;
    /** 是否直接提交待审核(true=待审核, false=草稿) */
    private boolean submit;
    /** 备注 */
    private String remark;
    /** 请领明细 */
    private List<RequisitionItemReq> items;
}
