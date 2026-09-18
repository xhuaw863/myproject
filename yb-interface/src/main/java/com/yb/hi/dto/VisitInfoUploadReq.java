package com.yb.hi.dto;

import lombok.Data;

import java.util.List;

/**
 * 【2203】门诊就诊信息上传 请求体
 * 包含就诊信息(单行)与诊断信息(多行)
 */
@Data
public class VisitInfoUploadReq {

    /** 就诊信息(节点 mdtrtinfo) */
    private MdtrtInfoReq mdtrtinfo;

    /** 诊断信息(节点 diseinfo) */
    private List<DiseInfoReq> diseinfo;
}
