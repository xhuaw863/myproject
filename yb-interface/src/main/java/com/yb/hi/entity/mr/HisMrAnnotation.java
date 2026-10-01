package com.yb.hi.entity.mr;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 病案批注与反馈(P1): 编目员与责任编码员围绕某份病案的沟通线程(parent_id 回复串联)。
 * 不回写临床首页, 仅在编目侧记录批注/反馈/处理状态; target_field 可定位到首页某字段/诊断/手术行。
 * ann_type: feedback反馈 ask询问 reply答复 other其他。resolved: 0未处理 1已处理。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_mr_annotation")
public class HisMrAnnotation extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 就诊ID(his_inp_visit.id) */
    private Long visitId;
    /** 编目主表ID(his_mr_catalog.id) */
    private Long catalogId;
    /** 机构ID(sys_org.id) */
    private Long orgId;
    /** 父批注ID(回复线程, NULL=顶层) */
    private Long parentId;
    /** 批注人(his_staff.id) */
    private Long fromStaffId;
    /** 批注人姓名 */
    private String fromStaffName;
    /** 接收人(his_staff.id, 可空) */
    private Long toStaffId;
    /** 接收人姓名 */
    private String toStaffName;
    /** 类型:feedback反馈 ask询问 reply答复 other其他 */
    private String annType;
    /** 关联/定位字段键(如 catalog.mainDiagCode / diag.I10 / oper.xxx) */
    private String targetField;
    /** 批注内容 */
    private String content;
    /** 是否已处理:0未处理 1已处理 */
    private Integer resolved;
}
