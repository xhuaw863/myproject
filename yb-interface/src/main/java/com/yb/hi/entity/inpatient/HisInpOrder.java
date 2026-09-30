package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 住院医嘱(长期/临时两类, 开嘱→审核→执行→完成/停止/作废状态机, 成组医嘱号聚合)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_inp_order")
public class HisInpOrder extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 医嘱类型: 1长期 2临时 */
    private Integer orderType;
    /** 医嘱分类: 1药品 2检查 3检验 4治疗 5护理 6膳食 7其他 */
    private Integer orderCategory;
    /** 医嘱内容 */
    private String orderContent;
    /** 收费项目ID */
    private Long chargeItemId;
    /** 药品ID(药品目录) */
    private Long drugId;
    /** 规格 */
    private String spec;
    /** 剂量 */
    private String dosage;
    /** 剂量单位 */
    private String dosageUnit;
    /** 用法编码 */
    private String usageCode;
    /** 频次编码 */
    private String freqCode;
    /** 开始时间 */
    private LocalDateTime startTime;
    /** 停止时间 */
    private LocalDateTime stopTime;
    /** 状态: 1新开 2已审核 3执行中 4已完成 5已停止 6已作废 */
    private Integer orderStatus;
    /** 开嘱医生ID(his_staff.id) */
    private Long doctorId;
    /** 审核护士ID(his_staff.id) */
    private Long auditNurseId;
    /** 审核时间 */
    private LocalDateTime auditTime;
    /** 停嘱医生ID(his_staff.id) */
    private Long stopDoctorId;
    /** 停嘱护士确认ID(his_staff.id) */
    private Long stopNurseId;
    /** 成组医嘱号 */
    private String groupNo;
    /** 数量 */
    private BigDecimal quantity;
    /** 单价 */
    private BigDecimal unitPrice;
    /** 临床路径实例ID(his_pathway_instance.id, 路径驱动开嘱时回写) */
    private Long pathwayInstanceId;
    /* ---------- 模型增强扩展列(DictSchemaMigration 幂等补列) ---------- */
    /** 来源医嘱模板ID(his_order_template.id) */
    private Long orderTemplateId;
    /** 来源医嘱套餐ID(his_order_template.id, 套餐型) */
    private Long orderSetId;
    /** 高警示药品: 1是 0否 */
    private Integer highAlertFlag;
    /** 需双人核对: 1是 0否 */
    private Integer doubleCheckFlag;
    /** 合理用药审查结果JSON */
    private String rationalCheckResult;
    /* ---------- 药师审核扩展列(DictSchemaMigration 幂等补列, T35) ---------- */
    /** 药审状态: 0无需 1待审 2通过 3驳回(药品类医嘱开立即待审, 护士审核前强制药审通过) */
    private Integer pharmAuditStatus;
    /** 审核药师ID(his_staff.id) */
    private Long pharmAuditId;
    /** 药审时间 */
    private LocalDateTime pharmAuditTime;
    /** 药审驳回原因 */
    private String pharmRejectReason;
    /* ---------- 医嘱续开扩展列(DictSchemaMigration 幂等补列, T41/T42) ---------- */
    /** 续开来源医嘱ID(his_inp_order.id, 续开复制开立时回写, 溯源原长期医嘱) */
    private Long sourceOrderId;

    /* ---------- 医保预审(开立回执瞬态回填, 不落库) ---------- */
    /** 医保预警提示(如"该项目无医保编码，将全额自费"; 非阻断, 不落库) */
    @TableField(exist = false)
    private String insuranceWarning;
    /** 医保甲乙丙分类名称(甲类/乙类/丙类; 对照目录同步维护, 不落库) */
    @TableField(exist = false)
    private String insuranceCategory;
}
