package com.yb.hi.dto.ris;

import lombok.Data;

import java.util.List;

/**
 * RIS 报告模板保存请求(含结构化数据元定义一并提交, 落 his_ris_report_template + his_ris_report_element)
 */
@Data
public class RisReportTemplateDTO {

    /** 模板ID(编辑时传) */
    private Long id;
    /** 模板编码(空则服务端生成) */
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
    /** 状态: 1启用/0停用 */
    private Integer status;
    /** 结构化数据元定义列表(随模板保存; 空则不动数据元) */
    private List<RisElementDTO> elements;
}
