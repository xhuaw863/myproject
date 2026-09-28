package com.yb.hi.dto;

import com.alibaba.fastjson2.PropertyNamingStrategy;
import com.alibaba.fastjson2.annotation.JSONType;
import lombok.Data;

/**
 * 【3302】目录对照撤销(节点: data, 多行; 输出无)
 * 规范表208(严格按字段字义, 不增不减, 全部必填):
 * fixmedins_code(30,Y)/fixmedins_hilist_id(30,Y)/list_type(30,Y)/med_list_codg(50,Y)
 * 重点说明: 入参不能为空; 可能有多条入参, 可选择删除已审核的和未审核的对照关系(单条)。
 */
@Data
@JSONType(naming = PropertyNamingStrategy.SnakeCase)
public class CatalogRevokeReq {

    /** 定点医药机构编号(30位, 撤销必须明确机构) */
    private String fixmedinsCode;
    /** 定点医药机构目录编号(院内编码, 30位) */
    private String fixmedinsHilistId;
    /** 目录类别(30位, 取值与平台确认) */
    private String listType;
    /** 医疗目录编码(被撤销的医保码, 50位) */
    private String medListCodg;
}
