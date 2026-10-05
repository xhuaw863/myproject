package com.yb.hi.entity.doctor;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 疾病报卡漏报留痕: 自动弹出触发时医生选"暂不报卡"落一行, 记录应报未报, 供集中审核页漏报监控统计。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_disease_report_skip")
public class HisDiseaseReportSkip extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 就诊ID(his_visit.id) */
    private Long visitId;
    /** 患者ID */
    private Long patientId;
    /** 触发诊断代码 */
    private String diagCode;
    /** 触发诊断名称 */
    private String diagName;
    /** 应报卡大类:1传染病 2精障 3肿瘤 4高血压 5糖尿病 9其他 */
    private Integer reportCategory;
    /** 暂不报卡原因 */
    private String skipReason;
    /** 操作人(医生)姓名 */
    private String skipBy;
    /** 暂不报卡时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime skipTime;
}
