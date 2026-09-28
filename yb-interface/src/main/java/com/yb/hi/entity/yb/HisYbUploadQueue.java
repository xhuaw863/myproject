package com.yb.hi.entity.yb;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 3301/3302 目录对照上传队列(M4, 事件源: his_yb_map_log)。
 * MAP→3301 上传(new_code); CLEAR→3302 撤销(old_code); CHANGE→先 3302 撤销(old_code) 后 3301 上传(new_code)。
 * 状态: 0待传 1已传 2失败; 每批 ≤100 条(规范 3301 重点说明 2)。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_yb_upload_queue")
public class HisYbUploadQueue extends BaseEntity {

    /** 动作: 首次对照(3301 上传) */
    public static final String ACTION_MAP = "MAP";
    /** 动作: 对照变更(先 3302 后 3301) */
    public static final String ACTION_CHANGE = "CHANGE";
    /** 动作: 清除对照(3302 撤销) */
    public static final String ACTION_CLEAR = "CLEAR";

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_UPLOADED = 1;
    public static final int STATUS_FAILED = 2;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 目录类型: charge/drug/cons */
    private String catalogType;
    /** 院内条目ID */
    private Long catalogId;
    /** 院内编码(=fixmedins_hilist_id) */
    private String itemCode;
    /** 院内名称(=fixmedins_hilist_name) */
    private String itemName;
    /** 目录类别(3301/3302 必填, 取值与平台确认后按目录类型配置) */
    private String listType;
    /** 变更前医保码(CHANGE/CLEAR 撤销用) */
    private String oldCode;
    /** 变更后医保码(MAP/CHANGE 上传用) */
    private String newCode;
    /** 动作: MAP/CHANGE/CLEAR */
    private String action;
    /** 状态: 0待传 1已传 2失败 */
    private Integer status;
    /** 上传批次号(成功批次, 撤/传各一批) */
    private String batchNo;
    /** 上传时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime uploadTime;
    /** 失败原因 */
    private String lastErr;
}
