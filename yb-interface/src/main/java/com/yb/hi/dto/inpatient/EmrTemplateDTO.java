package com.yb.hi.dto.inpatient;

import lombok.Data;

import java.util.List;

/**
 * 病历结构化模板请求(fields 列表序列化为 his_emr_template.fields JSON 落库)
 */
@Data
public class EmrTemplateDTO {

    /** 模板编码 */
    private String templateCode;
    /** 模板名称 */
    private String templateName;
    /** 记录类型(对应his_inp_medical_record.record_type的9种类型) */
    private Integer recordType;
    /** 模板类别: 1入院记录 2首次病程 3日常病程 4上级查房 5手术记录 6术后病程 7出院小结 8死亡记录 9病危通知 */
    private Integer templateCategory;
    /** 字段定义列表 */
    private List<EmrFieldDefDTO> fields;
    /** 科室ID(his_dept.id, 0=全院) */
    private Long deptId;
    /** 版本号 */
    private Integer version;
    /** 状态: 1启用 0停用 */
    private Integer status;
}
