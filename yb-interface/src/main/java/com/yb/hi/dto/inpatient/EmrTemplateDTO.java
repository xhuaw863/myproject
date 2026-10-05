package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 病历结构化模板请求(fields 列表序列化为 his_emr_template.fields JSON 落库)
 */
@Data
public class EmrTemplateDTO {

    /** 模板编码 */
    private String templateCode;
    /** 模板名称 */
    private String templateName;
    /** 记录类型(对应his_inp_medical_record.record_type的9种类型) */
    private Integer recordType;
    /** 模板类别: 1入院记录 2首次病程 3日常病程 4上级查房 5手术记录 6术后病程 7出院小结 8死亡记录 9病危通知 */
    private Integer templateCategory;
    /** 字段定义列表 */
    private List<EmrFieldDefDTO> fields;
    /** 设计器直传完整字段定义(含 dictRef/subFields/section 等新属性, 非空则原样序列化落库, 优先于 fields) */
    private List<Map<String, Object>> rawFields;
    /** 布局定义JSON(分节/栅格, 设计器产出) */
    private String layout;
    /** 父模板ID(三级继承: 科室挂全院, 个人挂科室/全院; 新建时防成环校验) */
    private Long parentTemplateId;
    /** 模板层级: 0全院 1科室 2个人(新建时缺省按 ownerScope 推导) */
    private Integer scopeLevel;
    /** 母板锁定章节key列表JSON字符串数组(空白串=清空锁定) */
    private String lockedSections;
    /** Tiptap ProseMirror JSON文档(type=doc) */
    private String document;
    /** 打印格式脚本(历史兼容) */
    private String printScript;
    /** 结构化打印配置JSON(A4纸张、方向、页边距、页眉页脚和页码) */
    private String printConfig;
    /** 关联数据集ID(his_emr_dataset.id) */
    private Long datasetId;
    /** 适用范围:1住院 2门诊 */
    private Integer scope;
    /** 模板归属层级:personal(个人)/dept(科室)/global(全院), 仅新建时生效 */
    private String ownerScope;
    /** 科室ID(his_dept.id, 0=全院) */
    private Long deptId;
    /** 版本号 */
    private Integer version;
    /** 状态: 1启用 0停用 */
    private Integer status;
    /** 发布态: 0草稿 1待审 2已驳回 3已发布(仅展示/审批使用, 普通保存不改此字段) */
    private Integer publishStatus;
    /** 本次保存的变更说明(写入版本快照, 可空) */
    private String changeSummary;
}
