package com.yb.hi.entity.ris;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 医保影像云索引(结算明细×StudyUID 上传状态, 供医保影像云索引上报)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_ris_cloud_index")
public class HisRisCloudIndex extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 报告ID */
    private Long reportId;
    /** 申请单ID */
    private Long requestId;
    /** 医保结算ID */
    private String settleId;
    /** 费用明细流水号 */
    private String chargeDetailSn;
    /** 医保影像检查项目编码 */
    private String ybExamCode;
    /** DICOM Study UID */
    private String studyUid;
    /** 上传状态: 0待上传/1已上传/2上传失败 */
    private Integer uploadStatus;
    /** 上传时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime uploadTime;
    /** 上传响应 */
    private String uploadResponse;
    /** 云端索引ID */
    private String cloudIndexId;
}
