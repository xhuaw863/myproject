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
 * 病历操作审计日志(病历P2): 病历级动作留痕 CREATE/UPDATE/VIEW/PRINT/SIGN/DELETE/SUBMIT/AUDIT,
 * detail 承载变更摘要JSON; 按病历维(record_id+scope)与操作人维(operator_id+create_time)双索引支撑追溯。
 * 表由 DictSchemaMigration 启动期幂等建出。
 * 说明: tenant_id 由 MyBatis-Plus 租户插件自动注入/过滤, 实体不显式映射, 避免插入重复列;
 * 本表无 create_by/update_by 审计列, 故不继承 BaseEntity, create_time 由元对象处理器自动填充。
 */
@Data
@TableName("his_emr_audit_log")
public class HisEmrAuditLog implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 关联病历ID(his_inp_medical_record.id 或门诊病历ID) */
    private Long recordId;
    /** 适用范围: 1住院 2门诊 */
    private Integer scope;
    /** 动作: CREATE/UPDATE/VIEW/PRINT/SIGN/DELETE/SUBMIT/AUDIT */
    private String action;
    /** 操作人ID(his_staff.id) */
    private Long operatorId;
    /** 操作人姓名(冗余留痕) */
    private String operatorName;
    /** 变更摘要JSON */
    private String detail;
    /** 操作来源IP */
    private String ipAddress;
    /** 操作时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    /** 逻辑删除: 0正常 1删除 */
    @TableLogic
    private Integer deleted;
}

