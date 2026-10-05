package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 病历模板版本快照: 每次保存/发布/回滚对模板 document/fields/print_config/locked_sections 落一行,
 * 支撑模板版本树、逐版本对比与回滚(区别于病历实例版本快照 his_emr_version)。
 * 表由 DictSchemaMigration 启动期幂等建出。
 * 说明: tenant_id 由 MyBatis-Plus 租户插件自动注入/过滤, 实体不显式映射。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_emr_template_version")
public class HisEmrTemplateVersion extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 模板ID(his_emr_template.id) */
    private Long templateId;
    /** 版本号(与模板 version 对应) */
    private Integer versionNo;
    /** Tiptap 文档快照 */
    private String document;
    /** 字段定义JSON快照 */
    private String fields;
    /** 打印配置JSON快照 */
    private String printConfig;
    /** 锁定章节JSON快照 */
    private String lockedSections;
    /** 变更说明 */
    private String changeSummary;
    /** 操作人ID(sys_user.id) */
    private Long operatorId;
    /** 操作人姓名 */
    private String operatorName;
    /** 操作:save/publish/rollback */
    private String operateType;
}
