package com.yb.hi.entity.dict;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 字典版本管理
 */
@Data
@TableName("dict_version")
public class DictVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 字典类型标识 */
    private String dictType;

    /** 字典名称 */
    private String dictName;

    /** 对应交易编号 */
    private String infno;

    /** 本地最大版本号 */
    private String maxVer;

    /** 最近下载时间 */
    private String lastDldTime;

    /** 更新时间 */
    private String updtTime;
}
