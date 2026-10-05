package com.yb.hi.dto.ris;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 医保影像云索引上报请求(结算明细×StudyUID 索引上传, 落 his_ris_cloud_index)
 */
@Data
public class RisYbCloudDTO {

    /** 报告ID(his_exam_report.id) */
    private Long reportId;
    /** 申请单ID(his_exam_request.id) */
    private Long requestId;
    /** 医保结算ID(结算单号) */
    private String settleId;
    /** 费用明细流水号(收费明细行) */
    private String chargeDetailSn;
    /** 医保影像检查项目编码 */
    private String ybExamCode;
    /** DICOM Study UID */
    private String studyUid;
    /** 上传状态: 0待上传/1已上传/2上传失败 */
    private Integer uploadStatus;
    /** 上传时间 */
    private LocalDateTime uploadTime;
    /** 上传响应(平台回执) */
    private String uploadResponse;
    /** 云端索引ID(平台返回) */
    private String cloudIndexId;
}
