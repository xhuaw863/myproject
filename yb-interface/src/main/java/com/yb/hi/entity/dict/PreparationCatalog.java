package com.yb.hi.entity.dict;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 【1303】医疗机构制剂目录
 */
@Data
@TableName("preparation_catalog")
public class PreparationCatalog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String medListCodg;   // 第1列 医疗目录编码
    private String drugProdname;  // 第2列 药品商品名
    private String alis;          // 第3列 别名
    private String dosform;       // 第5列 剂型
    private String dosformName;   // 第6列 剂型名称
    private String ing;           // 第8列 成分
    private String efccAtd;       // 第9列 功能主治
    private String drugSpec;      // 第11列 药品规格
    private String drugType;      // 第19列 药品类别
    private String drugTypeName;  // 第20列 药品类别名称
    private String prodEntpName;  // 第41列 生产企业名称
    private String valiFlag;      // 第65列 有效标志
    private String rid;           // 第68列 唯一记录号
    private String ver;           // 第71列 版本号
    private String verName;       // 第72列 版本名称
    private String rawData;       // 原始数据行
}
