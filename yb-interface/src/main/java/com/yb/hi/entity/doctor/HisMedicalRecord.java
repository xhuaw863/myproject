package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 门诊病历(SOAP结构)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_medical_record")
public class HisMedicalRecord extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 就诊ID */
    private Long visitId;
    /** S-主观资料(主诉/现病史) */
    private String subjective;
    /** O-客观资料(查体) */
    private String objective;
    /** A-评估(诊断) */
    private String assessment;
    /** P-计划(处理) */
    private String plan;
    /** 书写医师 */
    private String drName;
    /** 医师签名 */
    private String drSign;
    /** 记录时间 */
    private LocalDateTime recordTime;
}
