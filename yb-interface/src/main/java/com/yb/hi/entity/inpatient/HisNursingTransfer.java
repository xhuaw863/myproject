package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 护理转运交接单(P4c): 转科/手术/血透/介入/内镜转运, checklist 交接核查JSON, 交出/接收双签名
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_nursing_transfer")
public class HisNursingTransfer extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 患者ID(his_patient.id) */
    private Long patientId;
    /** 类型:dept_transfer/surgery/hemodialysis/intervention/endoscopy */
    private String transferType;
    /** 交接核查JSON */
    private String checklist;
    /** 交出人ID */
    private Long senderId;
    /** 交出人 */
    private String senderName;
    /** 接收人ID */
    private Long receiverId;
    /** 接收人 */
    private String receiverName;
    /** 交接时间 */
    private LocalDateTime handoverTime;
    /** 转出科室 */
    private Long fromDeptId;
    /** 转入科室 */
    private Long toDeptId;
    /** 0草稿 1已交接 2已确认 */
    private Integer status;
    /** 备注 */
    private String note;
}
