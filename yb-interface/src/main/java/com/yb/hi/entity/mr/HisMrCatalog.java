package com.yb.hi.entity.mr;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 病案编目主表(病案统计科侧): 与临床端 his_case_front_page 解耦的"编目态副本"。
 * 编目员在其上按国标修订诊断/手术并对照医保版, 经质控审核→确认锁定。
 * tenant_id 由租户插件注入, 实体不映射。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_mr_catalog")
public class HisMrCatalog extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 就诊ID(his_inp_visit.id) */
    private Long visitId;
    /** 患者ID */
    private Long patientId;
    /** 机构ID(sys_org.id) */
    private Long orgId;
    /** 病案号 */
    private String catalogNo;
    /** 临床首页ID(his_case_front_page.id) */
    private Long sourceCasePageId;
    /** 入院时间 */
    private LocalDateTime admissionDate;
    /** 出院时间 */
    private LocalDateTime dischargeDate;
    /** 住院天数 */
    private Integer losDays;
    /** 入院科室ID */
    private Long admissionDeptId;
    /** 出院科室ID */
    private Long dischargeDeptId;
    /** 出院主要诊断编码(编目修订后) */
    private String mainDiagCode;
    /** 出院主要诊断名称 */
    private String mainDiagName;
    /** 中医标志:0西医 1中医 */
    private Integer isTcm;
    /** 编目状态:1待编目 2编目中 3已编目 */
    private Integer catalogStatus;
    /** 审核状态:1未审核 2已审核 3已确认 */
    private Integer auditStatus;
    /** 锁定状态:0未锁 1已锁 */
    private Integer lockStatus;
    /** 责任编目员(his_staff.id) */
    private Long catalogerId;
    /** 责任编目员姓名 */
    private String catalogerName;
    /** 病案首页质量评分 */
    private Integer qualityScore;
    /** 首页快照JSON(基础/费用字段) */
    private String summary;
}
