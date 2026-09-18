package com.yb.hi.dto;

import com.alibaba.fastjson2.PropertyNamingStrategy;
import com.alibaba.fastjson2.annotation.JSONType;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 费用明细(节点: feedetail)
 * 用于 2204门诊费用明细上传 / 2301住院费用明细上传
 */
@Data
@JSONType(naming = PropertyNamingStrategy.SnakeCase)
public class FeeDetailReq {

    /** 费用明细流水号(单次就诊内唯一) */
    private String feedetlSn;
    /** 原费用流水号(退单时传入被退单的流水号) */
    private String initFeedetlSn;
    /** 就诊ID */
    private String mdtrtId;
    /** 医嘱号(住院) */
    private String drordNo;
    /** 人员编号 */
    private String psnNo;
    /** 医疗类别(住院) */
    private String medType;
    /** 收费批次号(门诊) */
    private String chrgBchno;
    /** 病种编码 */
    private String diseCodg;
    /** 处方号 */
    private String rxno;
    /** 外购处方标志 */
    private String rxCircFlag;
    /** 费用发生时间 yyyy-MM-dd HH:mm:ss */
    private String feeOcurTime;
    /** 医疗目录编码 */
    private String medListCodg;
    /** 医药机构目录编码 */
    private String medinsListCodg;
    /** 明细项目费用总额 */
    private BigDecimal detItemFeeSumamt;
    /** 数量(退单为负数) */
    private BigDecimal cnt;
    /** 单价 */
    private BigDecimal pric;
    /** 单次剂量描述 */
    private String sinDosDscr;
    /** 使用频次描述 */
    private String usedFrquDscr;
    /** 周期天数 */
    private BigDecimal prdDays;
    /** 用药途径描述 */
    private String medcWayDscr;
    /** 开单科室编码 */
    private String bilgDeptCodg;
    /** 开单科室名称 */
    private String bilgDeptName;
    /** 开单医生编码 */
    private String bilgDrCodg;
    /** 开单医师姓名 */
    private String bilgDrName;
    /** 受单科室编码 */
    private String acordDeptCodg;
    /** 受单科室名称 */
    private String acordDeptName;
    /** 受单医生编码 */
    private String ordersDrCode;
    /** 受单医生姓名 */
    private String ordersDrName;
    /** 医院审批标志 */
    private String hospApprFlag;
    /** 中药使用方式 */
    private String tcmdrugUsedWay;
    /** 外检标志 */
    private String etipFlag;
    /** 外检医院编码 */
    private String etipHospCode;
    /** 出院带药标志 */
    private String dscgTkdrugFlag;
    /** 生育费用标志 */
    private String matnFeeFlag;
    /** 备注 */
    private String memo;
    /** 组套编号 */
    private String combNo;
    /** 字段扩展 */
    private String expContent;
}
