package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 病历批注与修订线程(his_emr_annotation): target_type=template/record, 锚点存 ProseMirror 定位 JSON。
 * 修订留痕本体在文档内以 emrTrack mark 表达并随模板保存, 本表存批注文本、回复线程与修订元数据。
 * 表由 DictSchemaMigration 启动期幂等建出; tenant_id 由租户插件注入。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_emr_annotation")
public class HisEmrAnnotation extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 目标类型:template/record */
    private String targetType;
    /** 目标ID(模板id或病历/就诊id) */
    private Long targetId;
    /** 类型:comment/insert/delete/format */
    private String annoType;
    /** ProseMirror 定位JSON({sectionKey,fieldKey?,from,to}) */
    private String anchor;
    /** 批注/修订内容 */
    private String content;
    /** 状态:open/resolved */
    private String status;
    /** 父批注ID(回复线程) */
    private Long parentId;
    /** 作者ID(sys_user.id) */
    private Long authorId;
    /** 作者姓名 */
    private String authorName;
}
