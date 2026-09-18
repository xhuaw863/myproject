package com.yb.hi.entity.dict;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 【1301】西药中成药目录
 */
@Data
@TableName("drug_catalog")
public class DrugCatalog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String medListCodg;   // 第1列 医疗目录编码
    private String drugProdname;  // 第2列 药品商品名
    private String gennameCodg;   // 第3列 通用名编号
    private String drugGenname;   // 第4列 药品通用名
    private String chemname;      // 第5列 化学名称
    private String alis;          // 第6列 别名
    private String engName;       // 第7列 英文名称
    private String dosform;       // 第10列 药品剂型
    private String dosformName;   // 第11列 药品剂型名称
    private String drugType;      // 第12列 药品类别
    private String drugTypeName;  // 第13列 药品类别名称
    private String drugSpec;      // 第14列 药品规格
    private String minUseunt;     // 第39列 最小使用单位
    private String minSalunt;     // 第40列 最小销售单位
    private String minUnt;        // 第41列 最小计量单位
    private String minPrcunt;     // 第49列 最小计价单位
    private String wubi;          // 第50列 五笔助记码
    private String pinyin;        // 第51列 拼音助记码
    private String prodEntpName;  // 第54列 生产企业名称
    private String valiFlag;      // 第79列 有效标志
    private String rid;           // 第80列 唯一记录号
    private String ver;           // 第83列 版本号
    private String verName;       // 第84列 版本名称
    private String rawData;       // 原始数据行
}
