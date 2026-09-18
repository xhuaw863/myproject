package com.yb.hi.dto;

import lombok.Data;

import java.util.List;

/**
 * 【2402】出院办理 请求体
 * 包含出院信息(单行)与出院诊断信息(多行)
 */
@Data
public class DischargeUploadReq {

    /** 出院信息(节点 dscginfo) */
    private DischargeReq dscginfo;

    /** 出院诊断信息(节点 diseinfo) */
    private List<DiseInfoReq> diseinfo;
}
