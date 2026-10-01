package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 发热病人登记(体温达阈值自动触发)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_fever_register")
public class HisFeverRegister extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 就诊ID(his_visit.id) */
    private Long visitId;
    /** 患者ID */
    private Long patientId;
    /** 患者姓名 */
    private String patientName;
    /** 体温(℃) */
    private BigDecimal temperature;
    /** 流行病学接触史 */
    private String exposureHistory;
    /** 处理去向 */
    private String disposition;
    /** 登记时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime registerTime;
}
