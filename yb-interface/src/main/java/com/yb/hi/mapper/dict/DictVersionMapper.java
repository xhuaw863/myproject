package com.yb.hi.mapper.dict;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yb.hi.entity.dict.DictVersion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 字典版本管理 Mapper
 */
public interface DictVersionMapper extends BaseMapper<DictVersion> {

    /**
     * 获取指定字典类型的本地最大版本号
     */
    @Select("SELECT max_ver FROM dict_version WHERE dict_type = #{dictType}")
    String getMaxVersion(@Param("dictType") String dictType);

    /**
     * 更新指定字典类型的本地最大版本号及下载时间
     */
    @Update("UPDATE dict_version SET max_ver = #{newVer}, last_dld_time = NOW(), updt_time = NOW() " +
            "WHERE dict_type = #{dictType}")
    int updateVersion(@Param("dictType") String dictType, @Param("newVer") String newVer);
}
