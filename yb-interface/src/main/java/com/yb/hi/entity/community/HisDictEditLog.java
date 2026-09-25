package com.yb.hi.entity.community;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 医共体统一字典字段级修改留痕(三目录统一: charge/drug/cons)。
 * 编辑保存时逐字段 diff, 每个变化字段写一行(修改前/后为展示值);
 * 医保码字段变化不在此表(走 his_yb_map_log 对照留痕, 三目录对照工作台可见), 避免一处变更两处重复。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_dict_edit_log")
public class HisDictEditLog extends BaseEntity {

    /** 来源: 统一字典编辑保存 */
    public static final String SRC_EDIT = "编辑";
    /** 来源: 统一字典新增 */
    public static final String SRC_CREATE = "新增";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 目录类型: charge/drug/cons */
    private String catalogType;
    /** 院内条目ID */
    private Long catalogId;
    /** 院内编码(冗余, 便于检索展示) */
    private String itemCode;
    /** 院内名称(冗余) */
    private String itemName;
    /** 变更字段属性名 */
    private String fieldName;
    /** 变更字段中文名 */
    private String fieldLabel;
    /** 修改前值(展示值) */
    private String oldValue;
    /** 修改后值(展示值) */
    private String newValue;
    /** 来源: 编辑/新增 */
    private String source;
    /** 操作人(登录账号) */
    private String operator;
    /** 操作人姓名 */
    private String operatorName;
    /** 操作人归属机构 */
    private Long orgId;
    /** 变更发生时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime changeTime;
}
