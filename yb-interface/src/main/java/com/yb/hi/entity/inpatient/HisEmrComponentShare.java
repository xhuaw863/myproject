package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 病历组件与模板市场登记(his_emr_component_share): 把片段/图示/整模板/数据元集合登记为可共享组件。
 * 上架不改母件, 仅登记一条可被检索与克隆的目录行; cloneToMine 按 comp_type 生成独立副本。
 * 表由 DictSchemaMigration 启动期幂等建出; tenant_id 由租户插件注入。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_emr_component_share")
public class HisEmrComponentShare extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 组件类型:fragment/drawing/template/elementSet */
    private String compType;
    /** 引用物ID(母件ID) */
    private Long refId;
    /** 组件标题(冗余便于列表) */
    private String title;
    /** 分类 */
    private String category;
    /** 来源作用域:0全院 1科室 2个人 */
    private Integer scopeLevel;
    /** 归属科室ID */
    private Long deptId;
    /** 共享范围:0全院 1医共体跨机构 */
    private Integer shareScope;
    /** 简介 */
    private String summary;
    /** 克隆/下载次数 */
    private Integer downloadCount;
    /** 来源机构ID */
    private Long sourceOrgId;
    /** 状态:1上架 0下架 */
    private Integer status;
}
