package com.yb.hi.entity.dict;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 【1305】医疗服务项目目录
 */
@Data
@TableName("med_service_catalog")
public class MedServiceCatalog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String medListCodg;    // 第1列 医疗目录编码
    private String prcunt;         // 第2列 计价单位
    private String prcuntName;     // 第3列 计价单位名称
    private String itemExplain;    // 第4列 诊疗项目说明
    private String itemExcluded;   // 第5列 诊疗除外内容
    private String itemConnotation;// 第6列 诊疗项目内涵
    private String valiFlag;       // 第7列 有效标志
    private String memo;           // 第8列 备注
    private String itemCat;        // 第9列 服务项目类别
    private String itemName;       // 第10列 医疗服务项目名称
    private String itemExplain2;   // 第11列 项目说明
    private String rid;            // 第14列 唯一记录号
    private String ver;            // 第15列 版本号
    private String verName;        // 第16列 版本名称
    private String rawData;        // 原始数据行
}
