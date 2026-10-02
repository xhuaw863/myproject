package com.yb.hi.entity.warehouse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 医保药品追溯码(药库/药房合规): 最小包装唯一码, 入库采集→在库→发药绑定患者/处方→退货/报废/调拨更新状态→2404 报送(批次5 M1: 状态机接入, M2 接真实交易链)。
 * 唯一键: tenant_id + trace_code(重复扫码拦截)。状态: 0在库 1已发药 2已退货 3已报废/调拨在途;
 * 报送 upload_status: 0未报送 1报送中 2失败待补 9已报送(与 his_upload_status 状态机口径对齐)。
 * location_id 复用 his_warehouse_def.id(药库或药房库存位), 与两级库存一致。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_drug_trace_code")
public class HisDrugTraceCode extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 机构ID */
    private Long orgId;
    /** 所属库位ID(药库/药房库存位 his_warehouse_def.id) */
    private Long locationId;
    /** 药品目录ID */
    private Long drugCatalogId;
    /** 药品编码(快照) */
    private String drugCode;
    /** 批次号(快照) */
    private String batchNo;
    /** 医保药品追溯码(租户内唯一) */
    private String traceCode;
    /** 状态: 0在库 1已发药 2已退货 3已报废/调拨在途 9已上报 */
    private Integer status;
    /** 最小包装数量 */
    private BigDecimal minPackQty;
    /** 关联单据类型(in/dispense/return/transfer) */
    private String refBillType;
    /** 关联单据ID */
    private Long refBillId;
    /** 发药绑定患者ID */
    private Long patientId;
    /** 发药绑定就诊ID */
    private Long visitId;
    /** 发药记录ID */
    private Long dispenseId;
    /** 报送状态: 0未报送 1报送中 2失败待补 9已报送(批次5 M1归一) */
    private Integer uploadStatus;
    /** 报送时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime uploadTime;
    /** 报送回执(批次号) */
    private String uploadReceipt;
    /** 2404报送发送方报文ID(UNKNOWN复核/重发凭据, 批次5 M1) */
    private String uploadMsgid;
    /** 2404报送批次/平台回执报文ID(批次5 M1) */
    private String uploadBatchNo;
}
