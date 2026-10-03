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
 * 病历签名规则链(病历P2): 按病历类型(record_type 1-15)配置签名环节链, stage 取
 * author(作者)/resident(住院医师)/attending(主治医师)/director(主任医师), stage_order 升序为
 * 签署顺序, required 标记链上必需环节; title_code_min/max 限职称档位(CV08.30.005, 空=不限),
 * 供签署服务校验环节顺序与签署人资质。
 * 表由 DictSchemaMigration 启动期幂等建出; 15 类签名链种子见 DemoDataInitializer.seedSignatureRules。
 * 说明: tenant_id 由 MyBatis-Plus 租户插件自动注入/过滤, 实体不显式映射, 避免插入重复列;
 * 本表无 create_by/update_by 审计列, 故不继承 BaseEntity, 审计时间由元对象处理器自动填充。
 */
@Data
@TableName("his_emr_signature_rule")
public class HisEmrSignatureRule implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 病历类型: 1入院记录 2首次病程 3日常病程 4查房记录 5术前小结 6手术记录 7术后病程 8出院小结 9死亡记录 10病案首页 11交接班 12转科 13知情同意 14讨论 15会诊 */
    private Integer recordType;
    /** 签名环节: author/resident/attending/director */
    private String stage;
    /** 签名顺序(升序) */
    private Integer stageOrder;
    /** 是否必需: 1是 0否 */
    private Integer required;
    /** 最低职称档(CV08.30.005, 空=不限) */
    private String titleCodeMin;
    /** 最高职称档(CV08.30.005, 空=不限) */
    private String titleCodeMax;
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

