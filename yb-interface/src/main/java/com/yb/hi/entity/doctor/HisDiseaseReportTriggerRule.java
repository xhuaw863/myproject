package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 疾病报卡触发规则(全局共享, 无 tenant_id, MybatisPlusConfig.IGNORE_TABLES 豁免):
 * 把原 HisDiseaseReportService.matchCat 硬编码的"诊断码→报卡大类"判定升格为数据,
 * ADMIN 维护界面(public-health/report-trigger-rule)增删改启停, 保存诊断触发判定时查表生效。
 * 种子由 DiseaseReportTriggerRuleSeeder 从 DiseaseReportDict 法定码表幂等导入。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_disease_report_trigger_rule")
public class HisDiseaseReportTriggerRule extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 报卡大类: 1传染病 2精障 3肿瘤 4高血压 5糖尿病 9其他 */
    private Integer reportCategory;
    /** 匹配方式: prefix=诊断码前缀 / exact=诊断码精确 / class=诊断类别(diagClass)等值 */
    private String matchType;
    /** 匹配模式(ICD 码前缀如 A01/C/F06.8, 精确码, 或诊断类别值如 tumor) */
    private String codePattern;
    /** 判定优先级(小者先判, 首个命中即定大类; 种子: 肿瘤10 < 传染病20 < 精障30 < 慢病40) */
    private Integer priority;
    /** 启用: 1参与触发 0停用 */
    private Integer enabled;
    /** 备注(病种名/法规依据) */
    private String remark;
}
