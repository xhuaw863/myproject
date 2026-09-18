package com.yb.hi.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.yb.hi.config.TenantYbConfigResolver;
import com.yb.hi.config.YbRuntimeConfig;
import com.yb.hi.entity.SetlRecord;
import com.yb.hi.entity.dict.DictVersion;
import com.yb.hi.mapper.SetlRecordMapper;
import com.yb.hi.mapper.dict.DictVersionMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询与系统信息接口(界面验证用)
 */
@RestController
@RequestMapping("/api")
public class QueryController {

    private final TenantYbConfigResolver configResolver;
    private final SetlRecordMapper setlRecordMapper;
    private final DictVersionMapper dictVersionMapper;

    public QueryController(TenantYbConfigResolver configResolver, SetlRecordMapper setlRecordMapper, DictVersionMapper dictVersionMapper) {
        this.configResolver = configResolver;
        this.setlRecordMapper = setlRecordMapper;
        this.dictVersionMapper = dictVersionMapper;
    }

    /** 系统信息(界面顶部状态栏, 按当前租户解析) */
    @GetMapping("/system/info")
    public Map<String, Object> info() {
        YbRuntimeConfig c = configResolver.resolve();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("mockEnabled", c.isMockEnabled());
        info.put("apiUrl", c.getApiUrl());
        info.put("fixmedinsCode", c.getFixmedinsCode());
        info.put("fixmedinsName", c.getFixmedinsName());
        return info;
    }

    /** 结算记录(最近20条) */
    @GetMapping("/query/setl-records")
    public List<SetlRecord> setlRecords() {
        QueryWrapper<SetlRecord> qw = new QueryWrapper<>();
        qw.orderByDesc("id").last("LIMIT 20");
        return setlRecordMapper.selectList(qw);
    }

    /** 字典版本状态 */
    @GetMapping("/query/dict-versions")
    public List<DictVersion> dictVersions() {
        return dictVersionMapper.selectList(null);
    }
}
