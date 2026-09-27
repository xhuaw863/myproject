package com.yb.hi.entity.nurse;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 输液记录(座位/滴速/穿刺/拔针/巡回)
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_infusion_record")
public class HisInfusionRecord extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 执行记录ID(his_nurse_exec.id) */
    private Long execId;
    /** 输液座位号 */
    private String seatNo;
    /** 溶液(液体名称与容量) */
    private String solution;
    /** 滴速(滴/分) */
    private Integer dripRate;
    /** 穿刺时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime punctureTime;
    /** 穿刺部位(左手背等) */
    private String punctureSite;
    /** 穿刺护士ID(his_staff.id) */
    private Long punctureNurseId;
    /** 拔针时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime removeTime;
    /** 拔针护士ID(his_staff.id) */
    private Long removeNurseId;
    /** 巡回记录(JSON数组: 巡回时间+滴速+情况) */
    private String patrolRecords;
}
