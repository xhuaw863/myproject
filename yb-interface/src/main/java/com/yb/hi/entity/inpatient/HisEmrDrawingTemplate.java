package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 医学图示模板: SVG 人体/部位图示(标注底图), 按类别维护(body_front/body_back/head/oral/hand/foot/wound/custom),
 * 书写时插入 emrDrawing 画布作为背景供医生标注位置, svgTemplate 为完整 SVG 源串。
 * 表由 DictSchemaMigration 启动期幂等建出。
 * 说明: tenant_id 由 MyBatis-Plus 租户插件自动注入/过滤, 实体不显式映射, 避免插入重复列。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_emr_drawing_template")
public class HisEmrDrawingTemplate extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 模板编码(租户内唯一) */
    private String code;
    /** 模板名称 */
    private String title;
    /** 类别: body_front/body_back/head/oral/hand/foot/wound/custom */
    private String category;
    /** SVG 模板内容(标注底图) */
    private String svgTemplate;
    /** 说明 */
    private String description;
    /** 状态: 1启用 0停用 */
    private Integer status;
}
