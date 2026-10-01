package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.doctor.HisOrderFreq;
import com.yb.hi.mapper.doctor.HisOrderFreqMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 医嘱/处方高频沉淀与助手服务(OP-C, 类比 HisDiagFreqService):
 * - recordUsage: 开立时按个人(staffId)/科室(deptId)两维度累计项目频次(C2 接线开立流程);
 * - assistant: 聚合本科室高频 / 个人常用两类候选, 供医嘱/处方检索框空输入时侧栏助手展示。
 */
@Service
public class HisOrderFreqService {

    private final HisOrderFreqMapper freqMapper;

    public HisOrderFreqService(HisOrderFreqMapper freqMapper) {
        this.freqMapper = freqMapper;
    }

    /** 记录开立频次: 每个(code,kind)落两条(个人维度/科室维度), 存在则累加。 */
    public void recordUsage(List<FreqKey> keys, Long staffId, Long deptId) {
        if (CollectionUtils.isEmpty(keys)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (FreqKey k : keys) {
            if (k.code == null) {
                continue;
            }
            if (staffId != null) {
                upsert(staffId, null, k, now);
            }
            if (deptId != null) {
                upsert(null, deptId, k, now);
            }
        }
    }

    private void upsert(Long staffId, Long deptId, FreqKey k, LocalDateTime now) {
        HisOrderFreq existing = freqMapper.selectOne(Wrappers.<HisOrderFreq>lambdaQuery()
                .eq(HisOrderFreq::getItemCode, k.code)
                .eq(HisOrderFreq::getItemKind, k.kind)
                .eq(staffId != null, HisOrderFreq::getStaffId, staffId)
                .isNull(staffId == null, HisOrderFreq::getStaffId)
                .eq(deptId != null, HisOrderFreq::getDeptId, deptId)
                .isNull(deptId == null, HisOrderFreq::getDeptId)
                .last("LIMIT 1"));
        if (existing == null) {
            HisOrderFreq f = new HisOrderFreq();
            f.setStaffId(staffId);
            f.setDeptId(deptId);
            f.setItemKind(k.kind);
            f.setItemCode(k.code);
            f.setItemName(k.name);
            f.setUseCount(1);
            f.setLastTime(now);
            freqMapper.insert(f);
        } else {
            existing.setUseCount((existing.getUseCount() == null ? 0 : existing.getUseCount()) + 1);
            existing.setLastTime(now);
            existing.setItemName(k.name);
            freqMapper.updateById(existing);
        }
    }

    /** 助手聚合: {deptFrequent:[], personalFrequent:[]}, 每项含 code/name/kind/count。 */
    public Map<String, Object> assistant(Long deptId, Long staffId, int limit) {
        int size = Math.max(1, Math.min(limit, 50));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deptFrequent", freqList(null, deptId, size));
        result.put("personalFrequent", freqList(staffId, null, size));
        return result;
    }

    private List<Map<String, Object>> freqList(Long staffId, Long deptId, int size) {
        List<HisOrderFreq> rows = freqMapper.selectList(Wrappers.<HisOrderFreq>lambdaQuery()
                .eq(staffId != null, HisOrderFreq::getStaffId, staffId)
                .isNull(staffId == null, HisOrderFreq::getStaffId)
                .eq(deptId != null, HisOrderFreq::getDeptId, deptId)
                .isNull(deptId == null, HisOrderFreq::getDeptId)
                .orderByDesc(HisOrderFreq::getUseCount)
                .orderByDesc(HisOrderFreq::getLastTime)
                .last("LIMIT " + size));
        List<Map<String, Object>> list = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (HisOrderFreq f : rows) {
            String uk = f.getItemKind() + "|" + f.getItemCode();
            if (f.getItemCode() == null || !seen.add(uk)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", f.getItemCode());
            m.put("name", f.getItemName());
            m.put("kind", f.getItemKind());
            m.put("count", f.getUseCount());
            list.add(m);
        }
        return list;
    }

    /** 频次累计键(code/kind/name)。 */
    public static class FreqKey {
        public final String code;
        public final String name;
        public final String kind;

        public FreqKey(String code, String name, String kind) {
            this.code = code;
            this.name = name;
            this.kind = kind;
        }
    }
}
