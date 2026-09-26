package com.yb.hi.entity.outpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 挂号记录
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_registration")
public class HisRegistration extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 挂号单号 */
    private String regNo;
    /** 患者ID */
    private Long patientId;
    /** 院内患者号 */
    private String patientNo;
    /** 患者姓名(冗余) */
    private String patientName;
    /** 医保人员编号 */
    private String psnNo;
    /** 险种类型 */
    private String insutype;
    /** 就诊凭证类型 */
    private String mdtrtCertType;
    /** 就诊凭证编号 */
    private String mdtrtCertNo;
    /** 科室ID */
    private Long deptId;
    /** 科室编码 */
    private String deptCode;
    /** 科室名称 */
    private String deptName;
    /** 科别(医保) */
    private String caty;
    /** 医师ID */
    private Long staffId;
    /** 医师医保编码 */
    private String atddrNo;
    /** 医师姓名 */
    private String drName;
    /** 排班ID */
    private Long scheduleId;
    /** 出诊日期 */
    private LocalDate workDate;
    /** 时段: am/pm/night */
    private String timeType;
    /** 号别编码 */
    private String regLevelCode;
    /** 号别名称 */
    private String regLevelName;
    /** 挂号费 */
    private BigDecimal regFee;
    /** 医疗类别: 11-普通门诊 */
    private String medType;
    /** 院内就诊流水号(门诊号) */
    private String iptOtpNo;
    /** 医保就诊ID(2201回填) */
    private String mdtrtId;
    /** 挂号时间 */
    private LocalDateTime regTime;
    /** 状态: 1-已挂号 2-已退号 3-已就诊 */
    private Integer status;
    /** 退号时间 */
    private LocalDateTime cancelTime;
    /** 退号原因 */
    private String cancelReason;
    /** 挂号员 */
    private String operator;
    /** 费别编码 */
    private String feeType;
    /** 减免类型编码(none=无减免, age70free=70岁老人免挂号费等) */
    private String discountType;
    /** 减免原因说明 */
    private String discountReason;
    /** 减免金额 */
    private BigDecimal discountAmount;
    /** 实收金额(挂号费-减免金额) */
    private BigDecimal actualFee;
    /** 支付方式(free=免费) */
    private String payMethod;
    /** 混合支付明细JSON */
    private String payDetail;
    /** 候诊序号(科室简码+4位流水号, 如 NK-0015) */
    private String queueNo;
    /** 同日同科室挂号提示(瞬态, 不落库; 挂号返回值携带) */
    @TableField(exist = false)
    private Boolean sameDeptWarning;
    /** 同日同科室提示文案(瞬态, 不落库; 挂号返回值携带) */
    @TableField(exist = false)
    private String sameDeptInfo;
    /** 换号来源挂号单号(瞬态, 不落库; 换号返回值携带) */
    @TableField(exist = false)
    private String changeFromRegNo;
}
