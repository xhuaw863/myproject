package com.yb.hi.entity.outpatient;

import com.baomidou.mybatisplus.annotation.IdType;
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
}
