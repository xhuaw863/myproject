package com.yb.hi.entity.inpatient;

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
 * 住院就诊主表(状态机: 1待入院→2在院→3出院办理中→4已出院/5已取消)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_inp_visit")
public class HisInpVisit extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 住院号 */
    private String inpNo;
    /** 患者ID(his_patient.id) */
    private Long patientId;
    /** 病区ID(his_ward.id) */
    private Long wardId;
    /** 床位ID(his_bed.id) */
    private Long bedId;
    /** 住院科室ID(his_dept.id) */
    private Long deptId;
    /** 主治医生ID(his_staff.id) */
    private Long doctorId;
    /** 责任护士ID(his_staff.id) */
    private Long nurseId;
    /** 入院日期 */
    private LocalDateTime admitDate;
    /** 出院日期 */
    private LocalDateTime dischargeDate;
    /** 状态: 1待入院 2在院 3出院办理中 4已出院 5已取消 */
    private Integer visitStatus;
    /** 入院诊断 */
    private String admitDiag;
    /** 总费用 */
    private BigDecimal totalCost;
    /** 预交金余额 */
    private BigDecimal depositBalance;
    /** 医疗类别(医保) */
    private String medType;
    /** 医保人员编号 */
    private String psnNo;
    /** 险种类型 */
    private String insutype;
    /** 医保就诊ID */
    private String mdtrtId;
    /* ---------- 模型增强扩展列(DictSchemaMigration 幂等补列) ---------- */
    /** 联系人姓名 */
    private String contactName;
    /** 联系人电话 */
    private String contactPhone;
    /** 联系人关系 */
    private String contactRelation;
    /** 担保人姓名 */
    private String guarantorName;
    /** 担保人电话 */
    private String guarantorPhone;
    /** 担保人身份证号 */
    private String guarantorIdNo;
    /** 血型 */
    private String bloodType;
    /** 入院来源: 1门诊 2急诊 3转诊 4其他 */
    private Integer admitSource;
    /** 预计出院日期 */
    private LocalDate expectedDischargeDate;
    /** 预交金预警线 */
    private BigDecimal depositWarningAmount;
    /** 是否隔离: 1是 0否 */
    private Integer isQuarantine;
    /** 护理等级: 1特级 2一级 3二级 4三级 */
    private Integer nursingLevel;
    /** 饮食类型 */
    private String dietType;
    /** 病情等级: 1危 2重 3一般 */
    private Integer conditionLevel;
    /** DRG分组编码 */
    private String drgGroupCode;
    /** 来源住院证ID(持证入院溯源, his_admission_cert.id) */
    private Long admissionCertId;
    /* ---------- 预入院(T41 幂等补列) ---------- */
    /** 预入院登记时间 */
    private LocalDateTime preAdmitTime;
    /** 预入院检查项(JSON字符串) */
    private String preCheckItems;
}
