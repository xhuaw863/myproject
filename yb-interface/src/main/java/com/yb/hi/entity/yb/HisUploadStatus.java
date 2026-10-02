package com.yb.hi.entity.yb;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 医保上传管线状态机(M5, 设计 §6.1/6.2): 2203 等逐单上传状态。
 * status: 0待传 1已传 2失败待补 3已撤销; retry_count/next_retry 指数退避 1/5/15/60min,
 * max 6 次自动重试后转人工; uk_biz(tenant_id,biz_type,biz_id) 一单一状态。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_upload_status")
public class HisUploadStatus extends BaseEntity {

    /** 业务类型: 就诊(2203 就诊信息上传) */
    public static final String BIZ_VISIT = "VISIT";
    /** 业务类型: 追溯码(2404 药品追溯码报送, 一码一状态行 biz_id=his_drug_trace_code.id; 批次5 M1) */
    public static final String BIZ_TRACE = "TRACE";
    /** 业务类型: 追溯码销售退货(3506A, 批次5 M3; 与 TRACE 销售报送行分离, 一码一退货状态行 biz_id=码行id) */
    public static final String BIZ_TRACE_RTN = "TRACE_RTN";
    /** 业务类型: 追溯码销售批次删除冲正(3507A inv_data_type=4, 批次5 M4; 一码一删除状态行 biz_id=码行id) */
    public static final String BIZ_TRACE_DEL = "TRACE_DEL";

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_UPLOADED = 1;
    public static final int STATUS_FAILED = 2;
    public static final int STATUS_REVOKED = 3;

    /** 自动重试上限(达到后转人工, 上报中心手动重传) */
    public static final int MAX_AUTO_RETRY = 6;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务类型: REG/VISIT/RX/FEE/SETL/CANCEL/TRACE/TRACE_RTN */
    private String bizType;
    /** 业务主键(如就诊ID) */
    private Long bizId;
    /** 医保就诊ID(mdtrt_id) */
    private String mdtrtId;
    /** 状态: 0待传 1已传 2失败待补 3已撤销 */
    private Integer status;
    /** 已重试次数 */
    private Integer retryCount;
    /** 下次重试时间(指数退避) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime nextRetry;
    /** 最近失败原因 */
    private String lastErr;
    /** 成功报文ID(平台回执) */
    private String msgid;

    /** 指数退避: 第1次 1min, 第2次 5min, 第3次 15min, 其后 60min */
    public static int backoffMinutes(int retryCount) {
        if (retryCount <= 1) {
            return 1;
        }
        if (retryCount == 2) {
            return 5;
        }
        if (retryCount == 3) {
            return 15;
        }
        return 60;
    }
}
