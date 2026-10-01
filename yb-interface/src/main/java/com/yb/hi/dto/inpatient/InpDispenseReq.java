package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 住院发药请求(P4): 按医嘱逐行发药, 支持缺药替换、出院带药标记、双签核对。
 * 一次提交可为一个病人(集中发药)或多个医嘱批量发药; 每行可独立指定替换药品与替换范围。
 */
@Data
public class InpDispenseReq {

    /** 发药药房ID(his_pharmacy_def.id, 空则全院FIFO扣减) */
    private Long pharmacyId;
    /** 发药窗口ID(可选, 出院带药取药窗口) */
    private Long windowId;
    /** 核对人(双签第二人) */
    private String checkBy;
    /** 是否出院带药(1 时发药后生成取药待发记录) */
    private Boolean dischargePick;
    /** 备注 */
    private String remark;
    /** 发药明细(按医嘱) */
    private List<Item> items;

    @Data
    public static class Item {
        /** 医嘱ID(his_inp_order.id) */
        private Long orderId;
        /** 替换后实发药品目录ID(缺药替换; 空或等于原药则不替换) */
        private Long replaceDrugCatalogId;
        /** 替换范围:1仅本次 2本次及后续全部(留痕) */
        private Integer replaceScope;
        /** 替换原因(缺药/禁用等) */
        private String replaceReason;
        /** 手工指定应发量(最小单位, 空则取医嘱 quantity) */
        private BigDecimal shouldQty;
    }
}
