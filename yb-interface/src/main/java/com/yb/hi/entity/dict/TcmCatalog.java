package com.yb.hi.entity.dict;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 【1302】中药饮片目录
 */
@Data
@TableName("tcm_catalog")
public class TcmCatalog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String medListCodg;     // 第1列 医疗目录编码
    private String drugName;        // 第2列 单味药名称
    private String scmpFlag;        // 第3列 单复方标志
    private String qualLv;          // 第4列 质量等级
    private String mediPart;        // 第6列 药用部位
    private String safeDose;        // 第7列 安全计量
    private String convUsage;       // 第8列 常规用法
    private String natureFlavor;    // 第9列 性味
    private String meridianTropism; // 第10列 归经
    private String variety;         // 第11列 品种
    private String valiFlag;        // 第14列 有效标志
    private String rid;             // 第15列 唯一记录号
    private String ver;             // 第18列 版本号
    private String verName;         // 第19列 版本名称
    private String rawData;         // 原始数据行
}
