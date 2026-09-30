package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 麻醉记录(一台手术一条, 术前/术后评估 + 诱导-插管-拔管-苏醒时间轴, 生命体征/术中事件JSON)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_anesthesia")
public class HisAnesthesia extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 手术ID(his_surgery.id) */
    private Long surgeryId;
    /** 麻醉类型: 1全麻 2局麻 3椎管内 4神经阻滞 5复合 6其他 */
    private Integer anesthesiaType;
    /** 具体麻醉方式 */
    private String anesthesiaMethod;
    /** 术前评估JSON */
    private String preAssessment;
    /** 诱导时间 */
    private LocalDateTime inductionTime;
    /** 插管时间 */
    private LocalDateTime intubationTime;
    /** 拔管时间 */
    private LocalDateTime extubationTime;
    /** 苏醒时间 */
    private LocalDateTime recoveryTime;
    /** 生命体征JSON数组 */
    private String vitalSigns;
    /** 术中事件JSON */
    private String anesthesiaEvents;
    /** 术后评估JSON */
    private String postAssessment;
    /** 状态: 1记录中 2已完成 */
    private Integer status;
}
