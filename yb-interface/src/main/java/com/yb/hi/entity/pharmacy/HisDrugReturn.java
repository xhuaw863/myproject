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
 * 退药记录(已发药处方退回, 审核通过后经入库单回补库存)
 * 唯一键: tenant_id + return_no
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_drug_return")
public class HisDrugReturn extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 退药单号(TY+yyyyMMdd+4位序号) */
    private String returnNo;
    /** 发药记录ID */
    private Long dispenseId;
    /** 就诊ID */
    private Long visitId;
    /** 患者ID */
    private Long patientId;
    /** 患者姓名 */
    private String patientName;
    /** 退药原因 */
    private String reason;
    /** 状态: 0待审核 1已退药 2已驳回 */
    private Integer status;
    /** 退药金额 */
    private BigDecimal returnAmount;
    /** 审批人 */
    private String approveBy;
    /** 审批时间 */
    private LocalDateTime approveTime;
}
