package com.yb.hi.entity.pharmacy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 发药记录(处方收费后药房调配/发药/核对双签, 与 his_prescription.dispense_status 联动)
 * 唯一键: tenant_id + dispense_no
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_dispense")
public class HisDispense extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 发药单号(FY+yyyyMMdd+4位序号) */
    private String dispenseNo;
    /** 就诊ID */
    private Long visitId;
    /** 处方ID */
    private Long prescriptionId;
    /** 患者ID */
    private Long patientId;
    /** 患者姓名 */
    private String patientName;
    /** 医生姓名 */
    private String doctorName;
    /** 科室名称 */
    private String deptName;
    /** 状态: 0待发药 1已调配 2已发药 3已退药 */
    private Integer status;
    /** 发药人 */
    private String dispenseBy;
    /** 发药时间 */
    private LocalDateTime dispenseTime;
    /** 核对人 */
    private String checkBy;
    /** 核对时间 */
    private LocalDateTime checkTime;
    /** 总金额 */
    private BigDecimal totalAmount;
    /** 备注 */
    private String remark;
}
