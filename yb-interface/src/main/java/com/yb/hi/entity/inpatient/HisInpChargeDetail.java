package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 住院费用明细(逐日记账, 医嘱驱动+固定费床位, 结算前汇总)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_inp_charge_detail")
public class HisInpChargeDetail extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 收费项目ID */
    private Long chargeItemId;
    /** 项目名称 */
    private String itemName;
    /** 项目编码 */
    private String itemCode;
    /** 数量 */
    private BigDecimal quantity;
    /** 单价 */
    private BigDecimal unitPrice;
    /** 金额 */
    private BigDecimal amount;
    /** 记账日期 */
    private LocalDate chargeDate;
    /** 关联医嘱ID(his_inp_order.id) */
    private Long orderId;
    /** 费用类别: 1西药 2中药 3检查 4检验 5治疗 6护理 7材料 8床位 9其他 */
    private Integer feeType;
    /** 状态: 1正常 2退费 */
    private Integer status;
    /** 操作员ID(his_staff.id) */
    private Long operatorId;
    /** 手术ID(his_surgery.id, 手术记费时回写) */
    private Long surgeryId;
    /* ---------- 模型增强扩展列(DictSchemaMigration 幂等补列) ---------- */
    /** 日限额 */
    private BigDecimal dailyLimit;
    /** 总限额 */
    private BigDecimal totalLimit;
    /** 审核状态: 1待审 2通过 3拒绝 */
    private Integer approvalStatus;
    /** 超标原因 */
    private String limitOverrideReason;
}
