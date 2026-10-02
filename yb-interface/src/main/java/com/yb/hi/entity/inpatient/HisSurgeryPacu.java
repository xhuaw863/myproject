package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 术毕复苏(PACU)单(手麻P3b): 独立旁路单据, 入复苏(手术status=4)→生命体征登记(vitals_json 时间点数组)→
 * 出复苏(Aldrete>=9放行, <9须填理由)。主状态机不插新枚举值, 以"存在status=1在途复苏单则拒绝complete/cancel"
 * 软门禁衔接 4→5。患者三快照列建单时自 surgery/inp_visit/patient 冗余, 列表页免 JOIN。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_surgery_pacu")
public class HisSurgeryPacu extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 手术ID(his_surgery.id) */
    private Long surgeryId;
    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 患者ID快照(his_patient.id) */
    private Long patientId;
    /** 患者姓名快照 */
    private String patientName;
    /** 住院号快照 */
    private String inpNo;
    /** 入复苏时间 */
    private LocalDateTime admitTime;
    /** 入复苏护士ID(his_staff.id) */
    private Long admitNurseId;
    /** 入复苏备注 */
    private String admitNote;
    /** 入复苏Aldrete评分(0-10) */
    private Integer aldreteAdmit;
    /** 生命体征时间点数组JSON[{t,hp,hr,p,s,tm}] */
    private String vitalsJson;
    /** 状态: 1复苏中 2已出 */
    private Integer status;
    /** 出复苏时间 */
    private LocalDateTime dischargeTime;
    /** 出复苏护士ID(his_staff.id) */
    private Long dischargeNurseId;
    /** 出复苏Aldrete评分(0-10, <9须填理由) */
    private Integer aldreteDischarge;
    /** 出复苏去向: 1回病房 2转ICU 3门诊随访 */
    private Integer dischargeDest;
    /** 出复苏备注(Aldrete<9时为低分理由) */
    private String dischargeNote;
    /** 备注 */
    private String remark;
}
