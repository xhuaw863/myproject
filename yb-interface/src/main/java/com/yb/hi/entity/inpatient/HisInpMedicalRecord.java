package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 住院病历(九类结构化文书, 草稿→已提交→已审核三级审核流)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_inp_medical_record")
public class HisInpMedicalRecord extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 记录类型: 1入院记录 2首次病程 3日常病程 4查房记录 5术前小结 6手术记录 7术后病程 8出院小结 9死亡记录 */
    private Integer recordType;
    /** 标题 */
    private String title;
    /** 内容(JSON) */
    private String content;
    /** 记录时间 */
    private LocalDateTime recordTime;
    /** 记录医生ID(his_staff.id) */
    private Long doctorId;
    /** 审核医生ID(his_staff.id) */
    private Long auditDoctorId;
    /** 审核时间 */
    private LocalDateTime auditTime;
    /** 状态: 1草稿 2已提交 3已审核 */
    private Integer status;
    /* ---------- 模型增强扩展列(DictSchemaMigration 幂等补列) ---------- */
    /** 结构化模板ID(his_emr_template.id) */
    private Long templateId;
    /** 结构化数据JSON */
    private String structureData;
    /** 书写截止时间(时限性质控) */
    private LocalDateTime deadlineTime;
    /** 质控评分 */
    private BigDecimal qualityScore;
    /** 质控明细JSON */
    private String qualityDetail;
    /** 上级医师ID(his_staff.id) */
    private Long attendingDoctorId;
    /** 查房级别: 1住院医师 2主治 3主任 */
    private Integer roundLevel;
    /* ---------- 三级查房签名链(T43, DictSchemaMigration 幂等补列) ---------- */
    /** 主治医师签名ID(his_staff.id, 三级查房签名链第二级) */
    private Long attendingSignId;
    /** 主治医师签名时间 */
    private LocalDateTime attendingSignTime;
    /** 主任医师签名ID(his_staff.id, 三级查房签名链第三级) */
    private Long directorSignId;
    /** 主任医师签名时间 */
    private LocalDateTime directorSignTime;
}
