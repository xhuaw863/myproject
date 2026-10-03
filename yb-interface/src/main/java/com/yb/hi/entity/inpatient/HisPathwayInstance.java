package com.yb.hi.entity.inpatient;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 患者临床路径实例(住院就诊×模板入径, 进行中/完成/退出/暂停状态机, 退出留原因)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_pathway_instance")
public class HisPathwayInstance extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 住院就诊ID(his_inp_visit.id) */
    private Long inpVisitId;
    /** 模板ID(his_pathway_template.id) */
    private Long templateId;
    /** 启动日期 */
    private LocalDateTime startDate;
    /** 当前天数 */
    private Integer currentDay;
    /** 结束日期 */
    private LocalDateTime endDate;
    /** 状态: 1进行中 2已完成 3已退出 4暂停 */
    private Integer status;
    /** 退出原因(自由文本备注) */
    private String exitReason;
    /** 退出原因分类码(cv_code:pathway_exit_reason), 供质控柏拉图构成分析 */
    private String exitType;
    /** 退出原因分类名称(字典回填) */
    private String exitTypeName;
    /** 主治医生ID(his_staff.id) */
    private Long doctorId;
}
