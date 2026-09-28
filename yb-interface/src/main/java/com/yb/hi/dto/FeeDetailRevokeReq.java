package com.yb.hi.dto;

import com.alibaba.fastjson2.PropertyNamingStrategy;
import com.alibaba.fastjson2.annotation.JSONType;
import lombok.Data;

/**
 * 【2205】门诊费用明细信息撤销(节点: data, 单行)
 * 规范表108: mdtrt_id / chrg_bchno / psn_no / exp_content;
 * chrg_bchno 传入"0000"删除该就诊所有未结算明细(全撤, 幂等);
 * 已参与结算的明细不能撤销(规范重点说明2), 须先 2208 撤销结算。
 */
@Data
@JSONType(naming = PropertyNamingStrategy.SnakeCase)
public class FeeDetailRevokeReq {

    /** 就诊ID */
    private String mdtrtId;
    /** 收费批次号("0000"=删除所有未结算明细) */
    private String chrgBchno;
    /** 人员编号 */
    private String psnNo;
    /** 字段扩展 */
    private String expContent;
}
