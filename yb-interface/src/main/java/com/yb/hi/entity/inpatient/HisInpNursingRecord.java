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
 * 住院护理记录(体温单/评估/计划/措施/总结五类, content 为 JSON)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_inp_nursing_record")
public class HisInpNursingRecord extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 记录类型: 1体温单 2护理评估 3护理计划 4护理措施 5护理总结 */
    private Integer recordType;
    /** 内容(JSON) */
    private String content;
    /** 记录时间 */
    private LocalDateTime recordTime;
    /** 护士ID(his_staff.id) */
    private Long nurseId;
    /* ---------- 模型增强扩展列(DictSchemaMigration 幂等补列) ---------- */
    /** 量表编码(his_nursing_scale_def.scale_code) */
    private String scaleCode;
    /** 量表评分 */
    private BigDecimal scaleScore;
    /** 量表明细JSON */
    private String scaleDetail;
    /** 护理计划模板ID(his_nursing_plan_template.id) */
    private Long planTemplateId;
    /* ---------- 富文本双轨扩展列(P4a-5, DictSchemaMigration 幂等补列) ---------- */
    /** 护理文书模板ID(his_nursing_template.id, P4a-5 富文本轨) */
    private Long templateId;
    /** 结构化字段扁平JSON(fieldKey→值, P4a-5 富文本双轨派生; 配合 content 密文轨) */
    private String structureData;
}
