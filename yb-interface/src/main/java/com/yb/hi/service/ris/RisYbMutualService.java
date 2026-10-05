package com.yb.hi.service.ris;

import com.yb.hi.common.YbHttpClient;
import com.yb.hi.common.YbResponse;
import com.yb.hi.config.TenantYbConfigResolver;
import com.yb.hi.framework.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RIS 检查检验结果互认 54xx 接口服务
 * - 5401 项目互认查询: 按人员+检查项目查医保平台已互认的检查报告列表
 * - 5402 报告明细查询: 按 5401 命中的报告号拉取报告三段明细
 *   (checkReportDetails 检查报告 / inspectionReportInformation 检验报告 / inspectionDetails 检验明细)
 *
 * 报文边界: 54xx 报文字段为 snake_case(返回字段示例 psn_no/rpotc_no/rpt_date);
 * fixmedins_code 缺省回落当前机构/租户三级解析配置({@link TenantYbConfigResolver})。
 */
@Slf4j
@Service
public class RisYbMutualService {

    private final YbHttpClient ybHttpClient;
    private final TenantYbConfigResolver configResolver;

    public RisYbMutualService(YbHttpClient ybHttpClient, TenantYbConfigResolver configResolver) {
        this.ybHttpClient = ybHttpClient;
        this.configResolver = configResolver;
    }

    /**
     * 【5401】项目互认查询
     * 输入节点: data(psn_no + exam_item_code/exam_item_name, snake_case);
     * 输出节点: result(多行): psn_no/rpotc_no/rpt_date/rpotc_type_code/exam_rpotc_name/exam_rslt_poit_flag/exam_ccls 等。
     *
     * @return 互认报告记录列表(平台无记录返回空列表)
     */
    public List<Map<String, Object>> queryMutualRecognition(String psnNo, String examItemCode, String examItemName) {
        log.info("5401项目互认查询: psnNo={}, examItemCode={}", psnNo, examItemCode);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("psn_no", psnNo);
        data.put("exam_item_code", examItemCode);
        data.put("exam_item_name", examItemName);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", data);
        YbResponse resp = ybHttpClient.call("5401", input);
        if (!resp.isSuccess()) {
            throw new BizException(500, "5401项目互认查询失败: " + resp.getErrMsg());
        }
        List<Map<String, Object>> result = toListOfMaps(resp.getOutputArray("result"));
        log.info("5401互认查询命中: {} 条", result.size());
        return result;
    }

    /**
     * 【5402】报告明细查询
     * 输入节点: data(psn_no + rpotc_no(5401 命中的报告号) + fixmedins_code, snake_case);
     * 输出节点: checkReportDetails + inspectionReportInformation + inspectionDetails 三组。
     *
     * @return {checkReportDetails: List, inspectionReportInformation: List, inspectionDetails: List}
     */
    public Map<String, Object> queryReportDetail(String psnNo, String rpotcNo, String fixmedinsCode) {
        String effFixmedins = firstNonEmpty(fixmedinsCode, configResolver.resolve().getFixmedinsCode());
        log.info("5402报告明细查询: psnNo={}, rpotcNo={}, fixmedinsCode={}", psnNo, rpotcNo, effFixmedins);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("psn_no", psnNo);
        data.put("rpotc_no", rpotcNo);
        data.put("fixmedins_code", effFixmedins);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", data);
        YbResponse resp = ybHttpClient.call("5402", input);
        if (!resp.isSuccess()) {
            throw new BizException(500, "5402报告明细查询失败: " + resp.getErrMsg());
        }
        com.alibaba.fastjson2.JSONObject output = resp.getOutputObject();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkReportDetails", toListOfMaps(output.getJSONArray("checkReportDetails")));
        result.put("inspectionReportInformation", toListOfMaps(output.getJSONArray("inspectionReportInformation")));
        result.put("inspectionDetails", toListOfMaps(output.getJSONArray("inspectionDetails")));
        return result;
    }

    // ---------- 内部实现 ----------

    /** JSONArray -> List<Map>(空安全, 平台无输出节点时返回空列表) */
    private static List<Map<String, Object>> toListOfMaps(com.alibaba.fastjson2.JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            com.alibaba.fastjson2.JSONObject o = arr.getJSONObject(i);
            if (o != null) {
                list.add(new LinkedHashMap<>(o));
            }
        }
        return list;
    }

    private static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : b;
    }
}
