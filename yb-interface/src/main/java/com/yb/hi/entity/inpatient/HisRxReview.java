package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 住院处方点评单(T2 阶段3): 从医嘱发起点评(快照冻结要素, status=1待点评) → 点评提交(结论/问题类型/评分/意见留痕, status=2已点评)。
 * 机构归属跟随就诊机构(org_id), 由 DictSchemaMigration 幂等建表 his_rx_review。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_rx_review")
public class HisRxReview extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 患者ID(his_patient.id) */
    private Long patientId;
    /** 被点评医嘱ID(his_inp_order.id) */
    private Long orderId;
    /** 被点评开嘱医生ID(his_staff.id) */
    private Long doctorId;
    /** 机构ID(发起时取就诊归属机构) */
    private Long orgId;
    /** 医嘱要素快照JSON(发起点评时冻结) */
    private String rxItemSnapshot;
    /** 点评医师/药师ID(his_staff.id, 点评留痕) */
    private Long reviewStaffId;
    /** 点评时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime reviewTime;
    /** 点评结论: 1合理 2不规范 3不合理 */
    private Integer result;
    /** 问题类型编码(适应证/选药/剂量/用法/相互作用/重复给药/禁忌/其他) */
    private String problemType;
    /** 点评评分(0-100) */
    private Integer score;
    /** 点评意见 */
    private String comment;
    /** 点评状态: 1待点评 2已点评 */
    private Integer status;

    /* ================= 规则引擎预打分留痕(T2 阶段5-2, 发起点评时自动写入, 供人工参考) ================= */
    /** 引擎命中明细JSON */
    private String autoFindings;
    /** 引擎建议结论: 1合理 2不规范 3不合理 */
    private Integer autoResult;
    /** 引擎建议评分(0-100) */
    private Integer autoScore;
    /** 引擎建议问题类型编码 */
    private String autoProblemType;
    /** 是否已自动预打分: 1是 0否 */
    private Integer autoEvaluated;
}
