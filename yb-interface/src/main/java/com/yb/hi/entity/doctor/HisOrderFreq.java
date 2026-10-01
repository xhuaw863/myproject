package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 医嘱/处方高频使用沉淀(医嘱处方助手数据源, 类比 his_diag_freq):
 * 按医师(个人常用)/科室(本科室高频)两个维度累计项目使用频次, item_kind 区分药品处方项与医嘱项。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_order_freq")
public class HisOrderFreq extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 医师ID(his_staff.id, 个人常用维度) */
    private Long staffId;
    /** 科室ID(his_dept.id, 科室高频维度) */
    private Long deptId;
    /** 项目类型: rx药品处方项 / order诊疗医嘱项 */
    private String itemKind;
    /** 项目代码(药品 med_list_codg / 诊疗 item_code) */
    private String itemCode;
    /** 项目名称 */
    private String itemName;
    /** 累计使用次数 */
    private Integer useCount;
    /** 最近使用时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastTime;
}
