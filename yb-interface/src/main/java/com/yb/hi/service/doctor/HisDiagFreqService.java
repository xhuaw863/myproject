package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.doctor.HisDiagFreq;
import com.yb.hi.entity.doctor.HisDiagnosis;
import com.yb.hi.entity.doctor.HisVisit;
import com.yb.hi.mapper.doctor.HisDiagFreqMapper;
import com.yb.hi.mapper.doctor.HisDiagnosisMapper;
import com.yb.hi.mapper.doctor.HisVisitMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 诊断高频沉淀与诊断助手服务(OP-B):
 * - recordUsage: 保存诊断时按"个人(staffId)"与"科室(deptId)"两个维度累计频次;
 * - assistant: 聚合三类候选(患者历史诊断 / 本科室高频 / 个人常用)供诊断检索框空输入时上拉展示。
 */
@Service
public class HisDiagFreqService {

    private final HisDiagFreqMapper freqMapper;
    private final HisDiagnosisMapper diagnosisMapper;
    private final HisVisitMapper visitMapper;

    public HisDiagFreqService(HisDiagFreqMapper freqMapper, HisDiagnosisMapper diagnosisMapper,
                              HisVisitMapper visitMapper) {
        this.freqMapper = freqMapper;
        this.diagnosisMapper = diagnosisMapper;
        this.visitMapper = visitMapper;
    }

    /** 记录诊断使用频次: 每诊断落两条(个人维度 staff_id=本次医师 / 科室维度 dept_id=本次科室), 存在则累加。 */
    public void recordUsage(List<HisDiagnosis> diagnoses, Long staffId, Long deptId) {
        if (CollectionUtils.isEmpty(diagnoses)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (HisDiagnosis d : diagnoses) {
            if (d.getDiagCode() == null) {
                continue;
            }
            if (staffId != null) {
                upsert(staffId, null, d, now);
            }
            if (deptId != null) {
                upsert(null, deptId, d, now);
            }
        }
    }

    private void upsert(Long staffId, Long deptId, HisDiagnosis d, LocalDateTime now) {
        HisDiagFreq existing = freqMapper.selectOne(Wrappers.<HisDiagFreq>lambdaQuery()
                .eq(HisDiagFreq::getDiagCode, d.getDiagCode())
                .eq(staffId != null, HisDiagFreq::getStaffId, staffId)
                .isNull(staffId == null, HisDiagFreq::getStaffId)
                .eq(deptId != null, HisDiagFreq::getDeptId, deptId)
                .isNull(deptId == null, HisDiagFreq::getDeptId)
                .last("LIMIT 1"));
        if (existing == null) {
            HisDiagFreq f = new HisDiagFreq();
            f.setStaffId(staffId);
            f.setDeptId(deptId);
            f.setDiagCode(d.getDiagCode());
            f.setDiagName(d.getDiagName());
            f.setDiagClass(d.getDiagClass());
            f.setUseCount(1);
            f.setLastTime(now);
            freqMapper.insert(f);
        } else {
            existing.setUseCount((existing.getUseCount() == null ? 0 : existing.getUseCount()) + 1);
            existing.setLastTime(now);
            existing.setDiagName(d.getDiagName());
            existing.setDiagClass(d.getDiagClass());
            freqMapper.updateById(existing);
        }
    }

    /**
     * 诊断助手聚合: 返回 {history:[], deptFrequent:[], personalFrequent:[]}, 每项含 code/name/class。
     */
    public Map<String, Object> assistant(Long patientId, Long deptId, Long staffId, int limit) {
        int size = Math.max(1, Math.min(limit, 50));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("history", historyOfPatient(patientId, size));
        result.put("deptFrequent", freqList(null, deptId, size));
        result.put("personalFrequent", freqList(staffId, null, size));
        return result;
    }

    /** 本科室高频 / 个人常用: 按 use_count 降序取候选(去重诊断码)。 */
    private List<Map<String, Object>> freqList(Long staffId, Long deptId, int size) {
        List<HisDiagFreq> rows = freqMapper.selectList(Wrappers.<HisDiagFreq>lambdaQuery()
                .eq(staffId != null, HisDiagFreq::getStaffId, staffId)
                .isNull(staffId == null, HisDiagFreq::getStaffId)
                .eq(deptId != null, HisDiagFreq::getDeptId, deptId)
                .isNull(deptId == null, HisDiagFreq::getDeptId)
                .orderByDesc(HisDiagFreq::getUseCount)
                .orderByDesc(HisDiagFreq::getLastTime)
                .last("LIMIT " + size));
        List<Map<String, Object>> list = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (HisDiagFreq f : rows) {
            if (f.getDiagCode() == null || !seen.add(f.getDiagCode())) {
                continue;
            }
            list.add(item(f.getDiagCode(), f.getDiagName(), f.getDiagClass(), f.getUseCount()));
        }
        return list;
    }

    /** 患者历史诊断: 该患者历次就诊的 his_diagnosis 去重(排除本次就诊), 按诊断时间倒序。 */
    private List<Map<String, Object>> historyOfPatient(Long patientId, int size) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (patientId == null) {
            return list;
        }
        List<HisVisit> visits = visitMapper.selectList(Wrappers.<HisVisit>lambdaQuery()
                .eq(HisVisit::getPatientId, patientId)
                .select(HisVisit::getId)
                .orderByDesc(HisVisit::getId)
                .last("LIMIT 100"));
        if (CollectionUtils.isEmpty(visits)) {
            return list;
        }
        List<Long> visitIds = new ArrayList<>();
        for (HisVisit v : visits) {
            visitIds.add(v.getId());
        }
        List<HisDiagnosis> ds = diagnosisMapper.selectList(Wrappers.<HisDiagnosis>lambdaQuery()
                .in(HisDiagnosis::getVisitId, visitIds)
                .orderByDesc(HisDiagnosis::getDiagTime)
                .last("LIMIT " + (size * 4)));
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (HisDiagnosis d : ds) {
            if (d.getDiagCode() == null || !seen.add(d.getDiagCode())) {
                continue;
            }
            list.add(item(d.getDiagCode(), d.getDiagName(), d.getDiagClass(), null));
            if (list.size() >= size) {
                break;
            }
        }
        return list;
    }

    private Map<String, Object> item(String code, String name, String clazz, Integer count) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("clazz", clazz);
        m.put("count", count);
        return m;
    }
}
