package com.yb.hi.dto.basedata;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 周排班模板-批量创建请求: 为多个医师 × 多个时段槽位生成模板(已存在的组合跳过)。
 */
@Data
public class BatchTemplateReq {

    /** 科室ID(为空时按各医师归属科室回退) */
    private Long deptId;
    /** 科室名称(冗余落库) */
    private String deptName;
    /** 医师ID列表 */
    private List<Long> staffIds;
    /** 时段槽位列表(星期 + 时段 + 号别 + 号源) */
    private List<SlotReq> slots;

    /** 单个时段槽位 */
    @Data
    public static class SlotReq {
        /** 星期几: 1周一~7周日 */
        private Integer weekday;
        /** 时段: am-上午 pm-下午 night-晚间 */
        private String timeType;
        /** 号别编码 */
        private String regLevelCode;
        /** 号别名称 */
        private String regLevelName;
        /** 挂号费 */
        private BigDecimal regFee;
        /** 号源数 */
        private Integer totalNum;
        /** 诊室 */
        private String room;
    }
}
