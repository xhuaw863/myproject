package com.yb.hi.dto.warehouse;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 追溯码发药绑定请求: 将一批在库追溯码绑定到发药记录/患者/就诊并置为已发药(1); 可选校验"码数=应发最小包装数"软提示 */
@Data
public class TraceBindReq {

    /** 待绑定的追溯码清单 */
    private List<String> traceCodes;
    /** 发药记录ID */
    private Long dispenseId;
    /** 患者ID */
    private Long patientId;
    /** 就诊ID */
    private Long visitId;
    /** 应发最小包装数(可选: 与扫描码数比对, 不符仅返回软提示不拦截) */
    private BigDecimal requiredPackQty;
}
