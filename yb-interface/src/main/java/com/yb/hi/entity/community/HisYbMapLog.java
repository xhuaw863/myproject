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
 * 医保对照变更留痕(三目录统一: charge/drug/cons)。
 * 每次新增/变更/清除对照写一行; 结合目录行上的 prev_yb_code + yb_map_eff_time,
 * 可回答"某时间点该条目生效的医保码"(该时间之前用 old_code, 之后用 new_code)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_yb_map_log")
public class HisYbMapLog extends BaseEntity {

    /** 变更类型: 首次对照 */
    public static final String TYPE_MAP = "MAP";
    /** 变更类型: 对照变更(原码 -> 新码) */
    public static final String TYPE_CHANGE = "CHANGE";
    /** 变更类型: 清除对照 */
    public static final String TYPE_CLEAR = "CLEAR";
    /** 变更类型: 对照生效时间调整 */
    public static final String TYPE_EFF = "EFF";

    /** 对照方式: 人工确认 */
    public static final String SRC_MANUAL = "manual";
    /** 对照方式: 批量自动 */
    public static final String SRC_AUTO = "auto";

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
    /** 变更前医保码(空=首次对照) */
    private String oldCode;
    /** 变更后医保码(空=清除对照) */
    private String newCode;
    /** 变更类型: MAP/CHANGE/CLEAR */
    private String changeType;
    /** 匹配置信度(自动对照) */
    private Double score;
    /** 对照方式: manual/auto */
    private String src;
    /** 操作人(登录账号) */
    private String operator;
    /** 操作人姓名 */
    private String operatorName;
    /** 操作人归属机构 */
    private Long orgId;
    /** 变更发生时间(即新对照生效时间) */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime changeTime;
    /** 备注 */
    private String memo;
}
