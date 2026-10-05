package com.yb.hi.entity.community;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.yb.hi.framework.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 医共体门诊班次字典(L1, 牵头机构统一维护): 排班/号源的时段(time_type)由本字典受控。
 * 存量标准码 am/pm/night 建表时按租户种子预置(起止时间为院内惯例默认值, 可修改),
 * 医共体可按需自定义新班次(如 noon-中午门诊); 停用/删除班次后不允许再排新班,
 * 历史排班行仍可按存量 code 兜底翻译。起止时间存 'HH:mm' 字符串, 仅供展示与分诊参考,
 * 不参与号源扣减等资金链路。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("his_shift_dict")
public class HisShiftDict extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 班次编码(租户内唯一, 存量兼容 am/pm/night; 建议小写字母) */
    private String code;
    /** 班次名称(如 上午/下午/晚间/中午门诊) */
    private String name;
    /** 开始时间 HH:mm(可空=未定义精确时间) */
    private String startTime;
    /** 结束时间 HH:mm(可空; 允许小于开始时间表示跨天班次, 保存时仅提示不拦截) */
    private String endTime;
    /** 排序号(周视图列/号源按钮顺序) */
    private Integer sortNo;
    /** 状态:1启用 0停用 */
    private Integer status;
    /** 备注 */
    private String memo;

    /** 拼音简码(名称首字母, 保存时自动生成只读) */
    private String pyCode;

    /** 自定义简码(维护页可编辑, 选填) */
    private String abbrCode;
}
