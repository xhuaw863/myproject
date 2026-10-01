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
 * 诊断高频使用沉淀(诊断助手数据源): 按医师(个人常用)/科室(本科室高频)两个维度累计诊断使用频次
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_diag_freq")
public class HisDiagFreq extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 医师ID(his_staff.id, 个人常用维度) */
    private Long staffId;
    /** 科室ID(his_dept.id, 科室高频维度) */
    private Long deptId;
    /** 诊断代码 */
    private String diagCode;
    /** 诊断名称 */
    private String diagName;
    /** 诊断类别: west/tcm/symp/oper/tumor */
    private String diagClass;
    /** 累计使用次数 */
    private Integer useCount;
    /** 最近使用时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastTime;
}
