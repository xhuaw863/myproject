package com.yb.hi.entity.emr;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 病历质控节点日志(病历P5a): 病历生命周期节点级留痕(创建/提交/签名/归档/质控/整改/申诉),
 * 记录操作前后评分变化(score_before/score_after)与操作人。表由 DictSchemaMigration 启动期幂等建出。
 * 说明: 本表无 update_by/update_time/deleted 列, 故不继承 BaseEntity, 仅 create_time 由元对象处理器填充;
 * tenant_id 由 MyBatis-Plus 租户插件自动注入/过滤, 实体不显式映射, 避免插入重复列。
 */
@Data
@TableName("his_emr_qc_node")
public class HisEmrQcNode implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 病历记录ID(his_inp_medical_record.id) */
    private Long recordId;
    /** 就诊ID(住院his_inp_visit.id/门诊his_visit.id) */
    private Long visitId;
    /** 节点类型: 创建/提交/签名/归档/质控/整改/申诉 */
    private String nodeType;
    /** 节点描述 */
    private String nodeDesc;
    /** 操作前评分 */
    private BigDecimal scoreBefore;
    /** 操作后评分 */
    private BigDecimal scoreAfter;
    /** 操作人ID(his_staff.id) */
    private Long operatorId;
    /** 操作人姓名 */
    private String operatorName;
    /** 操作时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
