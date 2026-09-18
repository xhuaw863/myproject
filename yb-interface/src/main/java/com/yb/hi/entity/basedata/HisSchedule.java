package com.yb.hi.entity.basedata;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 排班号源
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_schedule")
public class HisSchedule extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 科室ID */
    private Long deptId;
    /** 职工(医师)ID */
    private Long staffId;
    /** 出诊日期 */
    private LocalDate workDate;
    /** 时段: am-上午 pm-下午 night-晚间 */
    private String timeType;
    /** 号别编码 */
    private String regLevelCode;
    /** 号别名称 */
    private String regLevelName;
    /** 挂号费 */
    private BigDecimal regFee;
    /** 总号源数 */
    private Integer totalNum;
    /** 剩余号源数 */
    private Integer leftNum;
    /** 状态: 1-开放 0-停诊 */
    private Integer status;
}
