package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 住院知情同意书(手术/麻醉/输血/特殊检查/特殊治疗/自费/病危七类, 患者-医师-见证人三签闭环)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_inp_informed_consent")
public class HisInpInformedConsent extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 同意书类型: 1手术 2麻醉 3输血 4特殊检查 5特殊治疗 6自费 7病危 */
    private Integer consentType;
    /** 同意书标题 */
    private String title;
    /** 打印模板ID(his_print_template.id) */
    private Long templateId;
    /** 同意书内容JSON */
    private String content;
    /** 患者/家属签字时间 */
    private LocalDateTime patientSignTime;
    /** 医师签字时间 */
    private LocalDateTime doctorSignTime;
    /** 见证人签字时间 */
    private LocalDateTime witnessSignTime;
    /** 谈话医师ID(his_staff.id) */
    private Long doctorId;
    /** 状态: 1待签 2已签 3已撤销 */
    private Integer status;
}
