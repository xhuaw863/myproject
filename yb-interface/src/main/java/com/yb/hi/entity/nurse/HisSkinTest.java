package com.yb.hi.entity.nurse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 皮试记录(观察窗与结果判定, 阳性须通知医生并确认)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_skin_test")
public class HisSkinTest extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 执行记录ID(his_nurse_exec.id) */
    private Long execId;
    /** 皮试药品名称 */
    private String drugName;
    /** 药品目录ID(his_drug_catalog.id) */
    private Long drugId;
    /** 皮试剂量(如0.1mL) */
    private String testDose;
    /** 观察开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime observeStart;
    /** 观察结束时间(开始+20分钟) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime observeEnd;
    /** 皮试结果: 0观察中 1阴性 2阳性 3未做 */
    private Integer result;
    /** 结果描述(局部反应等) */
    private String resultDesc;
    /** 通知医生时间(阳性时必录) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime notifyDoctorTime;
    /** 医生确认时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime doctorConfirmTime;
}
