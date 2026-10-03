package com.yb.hi.dto;

import com.alibaba.fastjson2.PropertyNamingStrategy;
import com.alibaba.fastjson2.annotation.JSONType;
import lombok.Data;

/**
 * 【2302】住院费用明细撤销(节点: data, 多行)
 * 规范表126: feedetl_sn / mdtrt_id / psn_no / exp_content;
 * feedetl_sn 传入"0000"时删除该就诊全部未结算明细(全撤, 规范语义幂等);
 * 已参与结算的明细不能撤销, 须先 2305 撤销结算。与 2205(门诊, chrg_bchno)字段口径不同, 故独立 DTO。
 */
@Data
@JSONType(naming = PropertyNamingStrategy.SnakeCase)
public class InpFeeDetailRevokeReq {

    /** 费用明细流水号("0000"=删除全部未结算明细) */
    private String feedetlSn;
    /** 就诊ID */
    private String mdtrtId;
    /** 人员编号 */
    private String psnNo;
    /** 字段扩展 */
    private String expContent;
}
