package com.yb.hi.dto.doctor;

import com.yb.hi.entity.doctor.HisOrderItem;
import lombok.Data;

import java.util.List;

/**
 * 开检查/检验/治疗单请求: 就诊ID + 单据类型 + 明细列表(金额/主表信息由服务补全)
 */
@Data
public class OrderReq {

    /** 就诊ID */
    private Long visitId;
    /** 单据类型: 检查/检验/治疗 */
    private String orderType;
    /** 单据明细 */
    private List<HisOrderItem> items;
}
