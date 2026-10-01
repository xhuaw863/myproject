package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 诊前预问诊记录(医生接诊前置了解患者基本病情)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_pre_consult")
public class HisPreConsult extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 就诊ID(his_visit.id) */
    private Long visitId;
    /** 患者ID */
    private Long patientId;
    /** 预问诊内容 JSON(症状/部位/时长/自述) */
    private String contentJson;
    /** 录入人 */
    private String recorder;
    /** 录入人ID */
    private Long recorderId;
}
