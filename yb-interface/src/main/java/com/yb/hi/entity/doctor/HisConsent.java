package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 门诊知情同意书(特殊检查/特殊治疗/输血/自费/病危五类, 医师谈话+患者(家属)签字闭环)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_consent")
public class HisConsent extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 门诊就诊ID */
    private Long visitId;
    /** 患者ID */
    private Long patientId;
    /** 患者姓名 */
    private String patientName;
    /** 同意书类型: 1-特殊检查 2-特殊治疗 3-输血 4-自费 5-病危 */
    private Integer consentType;
    /** 同意书标题 */
    private String title;
    /** 同意书内容(告知事项正文) */
    private String content;
    /** 谈话医师ID */
    private Long doctorId;
    /** 谈话医师姓名 */
    private String doctorName;
    /** 患者/家属签署姓名 */
    private String patientSignName;
    /** 签署人与患者关系(本人/配偶/父母子女等) */
    private String relation;
    /** 见证人姓名 */
    private String witnessName;
    /** 患者签署时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime signTime;
    /** 医师签署时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime doctorSignTime;
    /** 状态: 1-待签 2-已签 3-已撤销 */
    private Integer status;
    /** 机构ID */
    private Long orgId;
}
