package com.yb.hi.entity.ris;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * RIS报告模板(所见/结论/印象/技术描述 + 全院/科室/个人三级作用域)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_ris_report_template")
public class HisRisReportTemplate extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 模板编码 */
    private String templateCode;
    /** 模板名称 */
    private String templateName;
    /** 适用模态: CT/MR/DR/US/ES/ALL */
    private String modality;
    /** 适用部位(空=通用) */
    private String bodyPart;
    /** 适用科室类型: RADIOLOGY/ULTRASOUND/ENDOSCOPY */
    private String deptType;
    /** 级别: 1全院/2科室/3个人 */
    private Integer templateLevel;
    /** 科室级别时的科室ID */
    private Long ownerDeptId;
    /** 个人级别时的医生ID */
    private Long ownerStaffId;
    /** 所见模板(结构化JSON) */
    private String findingsTemplate;
    /** 结论模板(结构化JSON) */
    private String conclusionTemplate;
    /** 印象模板 */
    private String impressionTemplate;
    /** 检查技术描述模板 */
    private String techniqueTemplate;
    /** 是否为正常模板 */
    private Integer normalFlag;
    /** 排序 */
    private Integer sortOrder;
    /** 使用次数 */
    private Integer useCount;
    /** 状态: 1启用/0停用 */
    private Integer status;
}
