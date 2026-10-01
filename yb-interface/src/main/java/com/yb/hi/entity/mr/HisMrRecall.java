package com.yb.hi.entity.mr;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 病案收回/回收登记(P1 病案室日常作业): 出院病案从临床科室回收至病案室并上架的流转追踪。
 * 与编目主表 his_mr_catalog 通过 visit_id/catalog_id 关联, 不回写临床首页。
 * recall_status: 1待收回 2已收回 3逾期(逾期由规则/手动标记, 供回收率报表统计)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_mr_recall")
public class HisMrRecall extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 就诊ID(his_inp_visit.id) */
    private Long visitId;
    /** 编目主表ID(his_mr_catalog.id) */
    private Long catalogId;
    /** 患者ID */
    private Long patientId;
    /** 机构ID(sys_org.id) */
    private Long orgId;
    /** 病案条码(扫描录入) */
    private String barcode;
    /** 收回状态:1待收回 2已收回 3逾期 */
    private Integer recallStatus;
    /** 应回收日期(出院后按规则推算) */
    private LocalDateTime dueDate;
    /** 实际回收时间 */
    private LocalDateTime recallTime;
    /** 回收人(his_staff.id) */
    private Long recallUserId;
    /** 回收人姓名 */
    private String recallUserName;
    /** 是否已上架:0否 1是 */
    private Integer shelfFlag;
    /** 上架库位/架号 */
    private String shelfLocation;
    /** 备注 */
    private String remark;
}
