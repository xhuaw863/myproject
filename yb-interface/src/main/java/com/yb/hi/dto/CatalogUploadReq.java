package com.yb.hi.dto;

import com.alibaba.fastjson2.PropertyNamingStrategy;
import com.alibaba.fastjson2.annotation.JSONType;
import lombok.Data;

/**
 * 【3301】目录对照上传(节点: data, 多行; 输出无)
 * 规范表206(严格按字段字义, 不增不减; 必填: 前4项, 其余可选按规范约定空串""):
 * fixmedins_hilist_id(30,Y)/fixmedins_hilist_name(100,Y)/list_type(30,Y)/med_list_codg(50,Y)
 * /begndate/enddate(日期型 yyyy-MM-dd)/aprvno(30)/dosform(200)/exct_cont(2000)/item_cont(2000)
 * /prcunt(100)/spec(200)/pacspec(100)/memo(500)
 * 重点说明: 交易输入为多行数据; 每次不能超过 100 条。
 * 3301A(表207)为本交易加 scdz/prdr_name 两列(药品场景产地/生产厂家), 待平台确认适用性后实施。
 */
@Data
@JSONType(naming = PropertyNamingStrategy.SnakeCase)
public class CatalogUploadReq {

    /** 定点医药机构目录编号(院内编码, 30位) */
    private String fixmedinsHilistId;
    /** 定点医药机构目录名称(院内名称, 100位) */
    private String fixmedinsHilistName;
    /** 目录类别(30位, 取值与平台确认: yb.list-type-* 配置) */
    private String listType;
    /** 医疗目录编码(对照的医保码, 50位) */
    private String medListCodg;
    /** 开始日期(可选, yyyy-MM-dd) */
    private String begndate;
    /** 结束日期(可选, yyyy-MM-dd) */
    private String enddate;
    /** 批准文号(30位, 可选) */
    private String aprvno;
    /** 剂型(200位, 可选) */
    private String dosform;
    /** 除外内容(2000位, 可选) */
    private String exctCont;
    /** 项目内涵(2000位, 可选) */
    private String itemCont;
    /** 计价单位(100位, 可选) */
    private String prcunt;
    /** 规格(200位, 可选) */
    private String spec;
    /** 包装规格(100位, 可选) */
    private String pacspec;
    /** 备注(500位, 可选) */
    private String memo;
}
