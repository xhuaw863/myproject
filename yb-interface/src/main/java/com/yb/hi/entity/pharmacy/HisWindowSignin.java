package com.yb.hi.entity.pharmacy;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 发药窗口患者签到轻表(签到型窗口发药前定位患者; 独立表避免污染 his_dispense 主表)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_window_signin")
public class HisWindowSignin extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 窗口ID(his_pharmacy_window.id) */
    private Long windowId;
    /** 药房ID(his_pharmacy_def.id) */
    private Long pharmacyId;
    /** 患者ID */
    private Long patientId;
    /** 就诊ID */
    private Long visitId;
    /** 处方ID */
    private Long prescriptionId;
    /** 签到凭证号(条码/刷卡/发票号) */
    private String signinNo;
    /** 签到状态: 1已签到 0已取消 */
    private Integer signinStatus;
    /** 签到操作人 */
    private String signinBy;
    /** 签到时间 */
    private LocalDateTime signinTime;
}
