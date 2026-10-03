package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 病历结构化模板(九类文书字段定义 JSON, dept_id=0 表全院通用)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_emr_template")
public class HisEmrTemplate extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 模板编码 */
    private String templateCode;
    /** 模板名称 */
    private String templateName;
    /** 记录类型(对应his_inp_medical_record.record_type的9种类型) */
    private Integer recordType;
    /** 模板类别: 1入院记录 2首次病程 3日常病程 4上级查房 5手术记录 6术后病程 7出院小结 8死亡记录 9病危通知 */
    private Integer templateCategory;
    /** 字段定义JSON */
    private String fields;
    /** 适用范围:1住院 2门诊 */
    private Integer scope;
    /** 布局定义JSON(分节/栅格, 设计器产出) */
    private String layout;
    /** 父模板ID(三级继承: 科室模板挂全院母板, 个人模板挂科室/全院母板; null=根模板) */
    private Long parentTemplateId;
    /** 模板层级: 0全院 1科室 2个人(与 staffId/deptId 归属语义一致) */
    private Integer scopeLevel;
    /** 母板锁定的章节key列表JSON(父模板下发, 子模板须随母板同步) */
    private String lockedSections;
    /** Tiptap ProseMirror JSON文档(结构化书写主载荷) */
    private String document;
    /** 打印格式脚本 */
    private String printScript;
    /** 关联数据集ID(his_emr_dataset.id) */
    private Long datasetId;
    /** 个人模板归属职工ID(his_staff.id, null=科室/全院) */
    private Long staffId;
    /** 科室ID(his_dept.id, 0=全院) */
    private Long deptId;
    /** 版本号 */
    private Integer version;
    /** 状态: 1启用 0停用 */
    private Integer status;
}
