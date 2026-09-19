package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 检查/检验/治疗单主表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_order")
public class HisOrder extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 就诊ID */
    private Long visitId;
    /** 单据号 */
    private String orderNo;
    /** 患者ID */
    private Long patientId;
    /** 患者姓名 */
    private String patientName;
    /** 开单科室ID */
    private Long deptId;
    /** 开单科室名称 */
    private String deptName;
    /** 医师ID */
    private Long drId;
    /** 医师姓名 */
    private String drName;
    /** 单据类型: 检查/检验/治疗 */
    private String orderType;
    /** 临床诊断 */
    private String diagName;
    /** 单据金额 */
    private BigDecimal totalAmount;
    /** 状态: 1-已开 2-已执行 3-已退 */
    private Integer status;
}
