package com.yb.hi.entity.basedata;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 行政区划(国家统计局2021版, 5级: 省/市/县/镇/村)。
 * 全局共享的国家标准参考表(无 tenant_id / 无审计字段), 已在 MybatisPlusConfig.IGNORE_TABLES 中排除租户过滤。
 * 主键 code 为业务区划代码(12位数字), 非自增, 故用 IdType.INPUT。
 */
@Data
@TableName("area_code_2021")
public class AreaCode implements Serializable {

    /** 区划代码(如 110101001001) */
    @TableId(type = IdType.INPUT)
    private Long code;

    /** 名称 */
    private String name;

    /** 级别 1-5: 省/市/县/镇/村 (level 为非保留关键字, 加反引号更稳妥) */
    @TableField("`level`")
    private Integer level;

    /** 父级区划代码(顶级为 0) */
    private Long pcode;

    /** 拼音简码(区划名称首字母, 回填生成只读) */
    private String pyCode;
}
