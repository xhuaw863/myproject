package com.yb.hi.dto.pharmacy;

import lombok.Data;

import java.util.List;

/**
 * 执行发药请求
 */
@Data
public class DispenseReq {

    /** 处方ID */
    private Long prescriptionId;
    /** 机构ID(空则取当前登录用户机构) */
    private Long orgId;
    /** 药房ID(his_pharmacy_def.id, 空则不发药房归属) */
    private Long pharmacyId;
    /** 核对人(双签, 空则未核对) */
    private String checkBy;
    /** 处方特殊标志(P1 智能分窗定向): DECOCT代煎/EXPRESS快递/NARCOTIC精麻/TOXIC毒性, 可空 */
    private List<String> specialTypes;
    /** P3 发药扫描的追溯码清单(商品码/监管码已在扫描校验时归一为物理追溯码), 需追溯时必填 */
    private List<String> traceCodes;
    /** 二期价差容差: 超阈时经主管放行方可发药(仅当 pharmacy.price_diff_action=override 生效) */
    private Boolean priceDiffOverride;
    /** 超阈放行人(主管实名, 留痕用) */
    private String overrideBy;
    /** 超阈放行理由(留痕用) */
    private String overrideReason;
    /** 备注 */
    private String remark;
}
