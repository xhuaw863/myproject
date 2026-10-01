package com.yb.hi.service.emr;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.inpatient.HisEmrElement;
import com.yb.hi.framework.common.R;
import com.yb.hi.mapper.inpatient.HisEmrElementMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 病历数据元检索/透视服务(Phase C): 基于 his_emr_element 提供字段级检索与单份病历的病案首页/上报数据集透视。
 *
 * - {@link #searchElements}: 扁平要素分页检索(按 scope/患者/就诊/记录/科室/医生/字段键/术语/值/时间维度过滤),
 *   保留租户插件与逻辑删除口径, 前端按病历分组展示并导出。
 * - {@link #pivot}: 单份病历(scope+visitId/recordId)的全部要素 fieldKey→显示值 透视, 附病历归属元信息。
 */
@Slf4j
@Service
public class EmrSearchService {

    private final HisEmrElementMapper elementMapper;
    private final OrgAccessGuard guard;

    public EmrSearchService(HisEmrElementMapper elementMapper, OrgAccessGuard guard) {
        this.elementMapper = elementMapper;
        this.guard = guard;
    }

    /**
     * 要素扁平分页检索: 空条件即全量(受机构 scope 与租户约束), 按机构→字段键→创建时间倒序。
     * valueText 走 LIKE 模糊; valueNumMin/Max 数值区间; valueDate 起止(日粒度, 含当天)。
     */
    public R<IPage<HisEmrElement>> searchElements(Integer scope, Long patientId, Long visitId, Long recordId,
                                                  Long deptId, Long doctorId, Long orgId, String fieldKey,
                                                  String termCode, String valueText, BigDecimal valueNumMin,
                                                  BigDecimal valueNumMax, LocalDate valueDateStart,
                                                  LocalDate valueDateEnd, long page, long size) {
        Long scopeOrg = guard.scopeOrgId(orgId);
        Page<HisEmrElement> p = new Page<>(page <= 0 ? 1 : page, size <= 0 ? 20 : Math.min(size, 500));
        IPage<HisEmrElement> result = elementMapper.selectPage(p, Wrappers.<HisEmrElement>lambdaQuery()
                .eq(scope != null, HisEmrElement::getScope, scope)
                .eq(scopeOrg != null, HisEmrElement::getOrgId, scopeOrg)
                .eq(patientId != null, HisEmrElement::getPatientId, patientId)
                .eq(visitId != null, HisEmrElement::getVisitId, visitId)
                .eq(recordId != null, HisEmrElement::getRecordId, recordId)
                .eq(deptId != null, HisEmrElement::getDeptId, deptId)
                .eq(doctorId != null, HisEmrElement::getDoctorId, doctorId)
                .eq(StringUtils.hasText(fieldKey), HisEmrElement::getFieldKey, trim(fieldKey))
                .eq(StringUtils.hasText(termCode), HisEmrElement::getTermCode, trim(termCode))
                .like(StringUtils.hasText(valueText), HisEmrElement::getValueText, trim(valueText))
                .ge(valueNumMin != null, HisEmrElement::getValueNum, valueNumMin)
                .le(valueNumMax != null, HisEmrElement::getValueNum, valueNumMax)
                .ge(valueDateStart != null, HisEmrElement::getValueDate,
                        valueDateStart == null ? null : valueDateStart.atStartOfDay())
                .lt(valueDateEnd != null, HisEmrElement::getValueDate,
                        valueDateEnd == null ? null : valueDateEnd.plusDays(1).atStartOfDay())
                .orderByDesc(HisEmrElement::getCreateTime)
                .orderByDesc(HisEmrElement::getId));
        return R.ok(result);
    }

    /**
     * 单份病历要素透视: scope=2 以 visitId, scope=1 以 recordId 定位; 返回 {meta, fields:{fieldKey:值}}。
     * 多值字段(同 fieldKey 多行)按 sort_no 以逗号拼接显示值; 有 term_code 时值取 value_text。
     */
    public R<Map<String, Object>> pivot(Integer scope, Long visitId, Long recordId) {
        boolean byVisit = recordId == null;
        List<HisEmrElement> els = elementMapper.selectList(Wrappers.<HisEmrElement>lambdaQuery()
                .eq(scope != null, HisEmrElement::getScope, scope)
                .eq(byVisit, HisEmrElement::getVisitId, visitId)
                .eq(!byVisit, HisEmrElement::getRecordId, recordId)
                .orderByAsc(HisEmrElement::getFieldKey)
                .orderByAsc(HisEmrElement::getSortNo)
                .orderByAsc(HisEmrElement::getId));
        Map<String, Object> meta = new LinkedHashMap<>();
        Map<String, StringBuilder> acc = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        LocalDateTime maxTime = null;
        for (HisEmrElement e : els) {
            if (meta.isEmpty() && e != null) {
                meta.put("scope", e.getScope());
                meta.put("orgId", e.getOrgId());
                meta.put("visitId", e.getVisitId());
                meta.put("recordId", e.getRecordId());
                meta.put("patientId", e.getPatientId());
                meta.put("deptId", e.getDeptId());
                meta.put("doctorId", e.getDoctorId());
                meta.put("recordType", e.getRecordType());
                meta.put("templateId", e.getTemplateId());
            }
            String display = StringUtils.hasText(e.getValueText()) ? e.getValueText()
                    : (e.getValueNum() != null ? e.getValueNum().stripTrailingZeros().toPlainString()
                    : (e.getValueDate() != null ? e.getValueDate().toString() : e.getTermCode()));
            if (!StringUtils.hasText(display)) {
                continue;
            }
            acc.computeIfAbsent(e.getFieldKey(), k -> new StringBuilder())
                    .append(acc.get(e.getFieldKey()).length() > 0 ? ", " : "").append(display);
            if (StringUtils.hasText(e.getFieldLabel())) {
                labels.put(e.getFieldKey(), e.getFieldLabel());
            }
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<String, StringBuilder> en : acc.entrySet()) {
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("label", labels.getOrDefault(en.getKey(), en.getKey()));
            cell.put("value", en.getValue().toString());
            fields.put(en.getKey(), cell);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("meta", meta);
        result.put("elementCount", els.size());
        result.put("fields", fields);
        result.put("pivotTime", maxTime == null ? null : maxTime.toString());
        return R.ok(result);
    }

    /** 病历维度去重列表(轻量): 检索命中要素按 (scope,visit/record) 归并, 供上报/病案首页批量透视选择 */
    public R<List<Map<String, Object>>> distinctVisits(Integer scope, Long orgId, LocalDate since) {
        Long scopeOrg = guard.scopeOrgId(orgId);
        List<HisEmrElement> els = elementMapper.selectList(Wrappers.<HisEmrElement>lambdaQuery()
                .select(HisEmrElement::getScope, HisEmrElement::getVisitId, HisEmrElement::getRecordId,
                        HisEmrElement::getPatientId, HisEmrElement::getDeptId, HisEmrElement::getDoctorId,
                        HisEmrElement::getOrgId)
                .eq(scope != null, HisEmrElement::getScope, scope)
                .eq(scopeOrg != null, HisEmrElement::getOrgId, scopeOrg)
                .ge(since != null, HisEmrElement::getCreateTime, since == null ? null : since.atStartOfDay())
                .orderByDesc(HisEmrElement::getCreateTime));
        Map<String, Map<String, Object>> uniq = new LinkedHashMap<>();
        for (HisEmrElement e : els) {
            String k = e.getScope() + "#" + (e.getRecordId() != null ? e.getRecordId() : e.getVisitId());
            if (uniq.containsKey(k)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scope", e.getScope());
            row.put("visitId", e.getVisitId());
            row.put("recordId", e.getRecordId());
            row.put("patientId", e.getPatientId());
            row.put("deptId", e.getDeptId());
            row.put("doctorId", e.getDoctorId());
            row.put("orgId", e.getOrgId());
            uniq.put(k, row);
        }
        return R.ok(new ArrayList<>(uniq.values()));
    }

    private String trim(String s) {
        return s == null ? null : s.trim();
    }
}
