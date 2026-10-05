package com.yb.hi.dto.ris;

import lombok.Data;

import java.time.LocalDate;

/**
 * 医保 4501 检查信息上传请求(报告审核后上报医保, 落 his_exam_report 医保列 + his_exam_request 上报状态)
 */
@Data
public class RisYbExamUploadDTO {

    /** 关联申请单ID(his_exam_request.id, 服务端拣取患者/就诊上下文) */
    private Long requestId;
    /** 关联报告ID(his_exam_report.id) */
    private Long reportId;
    /** 就医流水号(医保4501.mdtrt_sn) */
    private String mdtrtSn;
    /** 医保就诊ID(医保4501.mdtrt_id) */
    private String mdtrtId;
    /** 医保人员编号(医保4501.psn_no) */
    private String psnNo;
    /** 医保检查项目代码 */
    private String examItemCode;
    /** 医保检查项目名称 */
    private String examItemName;
    /** 院内检查项目代码(医保4501.inhosp_exam_item_code) */
    private String inhospExamItemCode;
    /** 院内检查项目名称(医保4501.inhosp_exam_item_name) */
    private String inhospExamItemName;
    /** 医保检查类别代码 */
    private String examTypeCode;
    /** 医保检查类别名称(WS/T 102-1998) */
    private String examTypeName;
    /** 影像检查类型(医保字典: 1X线/2CT/3MRI/4US/5ECT) */
    private String imgExamType;
    /** 检查日期(医保4501.exam_date) */
    private LocalDate examDate;
    /** 报告日期(医保4501.rpt_date) */
    private LocalDate rptDate;
    /** 报告单类别代码(医保4501.rpotc_type_code) */
    private String rpotcTypeCode;
    /** 检查报告单名称(医保4501.exam_rpotc_name) */
    private String examRpotcName;
    /** 检查结论(医保4501.exam_ccls) */
    private String examCcls;
    /** 检查结果阳性标志(医保4501.exam_rslt_poit_flag) */
    private String examRsltPoitFlag;
    /** 检查结果异常标志(医保4501.exam_rslt_abn) */
    private String examRsltAbn;
    /** 报告医师(医保4502.rpot_doc) */
    private String rpotDoc;
    /** 影像存储路径(医保4501.imageinfo.store_path) */
    private String storePath;
    /** 有效标志(医保4501.vali_flag, 缺省1) */
    private String valiFlag;
}
