package com.yb.hi.entity.dict;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 【1307】疾病与诊断目录
 */
@Data
@TableName("disease_catalog")
public class DiseaseCatalog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String diseCode;       // 第1列 西医疾病诊断ID
    private String chapter;        // 第2列 章
    private String chapterName;    // 第4列 章名称
    private String catCode;        // 第7列 类目代码
    private String catName;        // 第8列 类目名称
    private String subcatCode;     // 第9列 亚目代码
    private String subcatName;     // 第10列 亚目名称
    private String diagCode;       // 第11列 诊断代码
    private String diagName;       // 第12列 诊断名称
    private String useFlag;        // 第13列 使用标记
    private String natStdDiagCode; // 第14列 国标版诊断代码
    private String natStdDiagName; // 第15列 国标版诊断名称
    private String clinDiagCode;   // 第16列 临床版诊断代码
    private String clinDiagName;   // 第17列 临床版诊断名称
    private String valiFlag;       // 第19列 有效标志
    private String rid;            // 第20列 唯一记录号
    private String ver;            // 第23列 版本号
    private String verName;        // 第24列 版本名称
    private String rawData;        // 原始数据行
}
