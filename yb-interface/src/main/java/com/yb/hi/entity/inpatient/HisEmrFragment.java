package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 病历片段: 可复用文档片段(全院/科室/个人三级作用域), document 为 Tiptap ProseMirror JSON 明文。
 * 书写时以 emrFragment 节点按 fragmentId 引用, 打印/导出时由 EmrDocumentService 批量展开为完整内容。
 * 表由 DictSchemaMigration 启动期幂等建出。
 * 说明: tenant_id 由 MyBatis-Plus 租户插件自动注入/过滤, 实体不显式映射, 避免插入重复列。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_emr_fragment")
public class HisEmrFragment extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 片段编码(租户内唯一) */
    private String code;
    /** 片段名称 */
    private String title;
    /** 作用域层级: 0全院 1科室 2个人 */
    private Integer scopeLevel;
    /** 归属科室ID(his_dept.id, scopeLevel=1 时使用) */
    private Long deptId;
    /** 归属职工ID(his_staff.id, scopeLevel=2 时使用) */
    private Long staffId;
    /** Tiptap ProseMirror JSON 内容 */
    private String document;
    /** 版本号(每次更新自增) */
    private Integer version;
    /** 状态: 1启用 0停用 */
    private Integer status;
}
