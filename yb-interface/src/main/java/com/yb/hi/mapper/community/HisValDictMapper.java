package com.yb.hi.mapper.community;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.community.HisValDict;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 医共体值域字典(业务自由值域统一取数源) Mapper
 */
public interface HisValDictMapper extends BaseMapper<HisValDict> {

    /**
     * 当前租户已存在的值域分组(去重): dict_type + 展示名(取组内任一 type_name) + 行数。
     * 供「值域字典」维护页类别下拉动态加载, 使任何按域/整组导入产生的分组自动出现, 不再依赖前端硬编码白名单。
     * 租户条件由多租户插件自动注入(his_val_dict 未入 IGNORE_TABLES)。
     */
    @Select("SELECT dict_type AS v, MAX(type_name) AS l, COUNT(*) AS cnt FROM his_val_dict"
            + " WHERE deleted = 0 GROUP BY dict_type ORDER BY dict_type")
    List<Map<String, Object>> selectDistinctTypes();
}
