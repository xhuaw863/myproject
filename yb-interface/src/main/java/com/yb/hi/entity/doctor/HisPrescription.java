package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 处方主表
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_prescription")
public class HisPrescription extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 就诊ID */
    private Long visitId;
    /** 处方号 */
    private String rxNo;
    /** 患者ID */
    private Long patientId;
    /** 患者姓名 */
    private String patientName;
    /** 科室ID */
    private Long deptId;
    /** 科室名称 */
    private String deptName;
    /** 医师ID */
    private Long drId;
    /** 医师姓名 */
    private String drName;
    /** 处方类型: 西药/中药 */
    private String rxType;
    /** 临床诊断 */
    private String diagName;
    /** 处方金额 */
    private BigDecimal totalAmount;
    /** 单据状态: 1-已开 -1-已作废(收费/发药进度看 dispenseStatus 与就诊 chargeStatus) */
    private Integer status;
    /** 发药状态: 0-未发药 1-已发药 2-已退药(由药房发药/退药流程回写) */
    private Integer dispenseStatus;
}
