package com.yb.hi.entity.dict;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 【1306】医用耗材目录
 */
@Data
@TableName("consumable_catalog")
public class ConsumableCatalog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String medListCodg;  // 第1列 医疗目录编码
    private String consName;     // 第2列 耗材名称
    private String udi;          // 第3列 医疗器械唯一标识码
    private String gennameCode;  // 第4列 医保通用名代码
    private String genname;      // 第5列 医保通用名
    private String prodModel;    // 第6列 产品型号
    private String specCode;     // 第7列 规格代码
    private String spec;         // 第8列 规格
    private String consCat;      // 第9列 耗材分类
    private String specModel;    // 第10列 规格型号
    private String minUseunt;    // 第18列 最小使用单位
    private String minSalunt;    // 第36列 最小销售单位
    private String hiValueFlag;  // 第37列 高值耗材标志
    private String valiFlag;     // 第68列 有效标志
    private String rid;          // 第69列 唯一记录号
    private String ver;          // 第70列 版本号
    private String verName;      // 第71列 版本名称
    private String rawData;      // 原始数据行
}
