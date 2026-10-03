package com.yb.hi.entity.emr;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 病历常用语(病历P2): 全局/科室/个人三级作用域(scope 0/1/2)常用语库, 按分类(category)分栏
 * 供书写区快速插入, usage_count 供热度排序; scope=1 用 dept_code 归属, scope=2 用 creator_id 归属。
 * 表由 DictSchemaMigration 启动期幂等建出; 全局种子(14条)见 DemoDataInitializer.seedEmrPhrases。
 * 说明: tenant_id 由 MyBatis-Plus 租户插件自动注入/过滤, 实体不显式映射, 避免插入重复列;
 * 本表无 create_by/update_by 审计列, 故不继承 BaseEntity, 审计时间由元对象处理器自动填充。
 */
@Data
@TableName("his_emr_phrase")
public class HisEmrPhrase implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 分类: chief_complaint/present_illness/past_history/physical_exam/diagnosis/treatment/nursing */
    private String category;
    /** 常用语内容 */
    private String content;
    /** 作用域: 0全局 1科室 2个人 */
    private Integer scope;
    /** 科室编码(scope=1时使用) */
    private String deptCode;
    /** 创建人ID(his_staff.id, scope=2个人常用语) */
    private Long creatorId;
    /** 使用次数(热度排序) */
    private Integer usageCount;
    /** 是否启用: 1启用 0停用 */
    private Integer enabled;
    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    /** 逻辑删除: 0正常 1删除 */
    @TableLogic
    private Integer deleted;
}

