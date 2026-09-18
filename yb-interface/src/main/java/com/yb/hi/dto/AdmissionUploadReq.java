package com.yb.hi.dto;

import lombok.Data;

import java.util.List;

/**
 * 【2401】入院办理 请求体
 * 包含就诊信息(单行)与入院诊断信息(多行)
 */
@Data
public class AdmissionUploadReq {

    /** 就诊信息(节点 mdtrtinfo) */
    private AdmissionReq mdtrtinfo;

    /** 入院诊断信息(节点 diseinfo) */
    private List<DiseInfoReq> diseinfo;
}
