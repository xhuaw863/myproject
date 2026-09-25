package com.yb.hi.entity.basedata;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 周排班模板(按星期+时段固化科室/医师/号别/号源, 一键生成周期排班)
 * 冲突约束: 同一租户内 医师 + 星期几 + 时段 唯一。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_schedule_template")
public class HisScheduleTemplate extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 模板名称 */
    private String templateName;
    /** 科室ID */
    private Long deptId;
    /** 医师ID */
    private Long staffId;
    /** 医师姓名(冗余, 服务端回填) */
    private String staffName;
    /** 科室名称(冗余, 服务端回填) */
    private String deptName;
    /** 星期几: 1周一~7周日 */
    private Integer weekday;
    /** 时段: am-上午 pm-下午 night-晚间 */
    private String timeType;
    /** 号别编码(挂号号别值域 reg_level) */
    private String regLevelCode;
    /** 号别名称 */
    private String regLevelName;
    /** 挂号费 */
    private BigDecimal regFee;
    /** 号源数 */
    private Integer totalNum;
    /** 诊室 */
    private String room;
    /** 状态: 1-启用 0-停用 */
    private Integer status;
}
