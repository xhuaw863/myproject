package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 门诊代办登记(家属/监护人代患者问诊留痕)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_outp_agent")
public class HisOutpAgent extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 门诊就诊ID */
    private Long visitId;
    /** 患者ID */
    private Long patientId;
    /** 患者姓名 */
    private String patientName;
    /** 代办人姓名 */
    private String agentName;
    /** 代办人身份证号 */
    private String agentIdCard;
    /** 代办人联系电话 */
    private String agentPhone;
    /** 与患者关系 */
    private String relation;
    /** 代办事由 */
    private String reason;
    /** 状态: 1-有效 0-作废 */
    private Integer status;
    /** 机构ID */
    private Long orgId;
}
