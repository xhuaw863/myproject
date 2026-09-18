package com.yb.hi.service;

import com.yb.hi.common.YbHttpClient;
import com.yb.hi.common.YbResponse;
import com.yb.hi.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 住院业务服务
 * 实现接口: 2401入院办理, 2301住院费用明细上传, 2303住院预结算,
 *          2304住院结算, 2305住院结算撤销, 2402出院办理
 */
@Slf4j
@Service
public class InpatientService {

    private final YbHttpClient ybHttpClient;
    private final SetlResultHandler setlResultHandler;

    public InpatientService(YbHttpClient ybHttpClient, SetlResultHandler setlResultHandler) {
        this.ybHttpClient = ybHttpClient;
        this.setlResultHandler = setlResultHandler;
    }

    /**
     * 【2401】入院办理
     * 输入节点: mdtrtinfo(单行) + diseinfo(多行) ; 输出节点: result(mdtrt_id)
     */
    public YbResponse admission(AdmissionReq mdtrtInfo, List<DiseInfoReq> diseInfo) {
        log.info("入院办理: psnNo={}, iptNo={}", mdtrtInfo.getPsnNo(), mdtrtInfo.getIptNo());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("mdtrtinfo", mdtrtInfo);
        input.put("diseinfo", diseInfo);
        // 住院需在报文级传入参保地医保区划
        return ybHttpClient.call("2401", input, mdtrtInfo.getInsuplcAdmdvs());
    }

    /**
     * 【2301】住院费用明细上传
     * 输入节点: feedetail(多行) ; 输出节点: result(多行)
     */
    public YbResponse uploadFeeDetail(List<FeeDetailReq> feeDetails) {
        log.info("住院费用明细上传: 明细{}条", feeDetails == null ? 0 : feeDetails.size());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("feedetail", feeDetails);
        return ybHttpClient.call("2301", input);
    }

    /**
     * 【2303】住院预结算
     * 输入节点: data ; 输出节点: setlinfo + setldetail
     */
    public YbResponse preSettlement(SettlementReq req) {
        log.info("住院预结算: mdtrtId={}, medfeeSumamt={}", req.getMdtrtId(), req.getMedfeeSumamt());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", req);
        YbResponse resp = ybHttpClient.call("2303", input);
        setlResultHandler.parse(resp);
        return resp;
    }

    /**
     * 【2304】住院结算
     * 输入节点: data ; 输出节点: setlinfo + setldetail
     */
    public YbResponse settlement(SettlementReq req) {
        log.info("住院结算: mdtrtId={}, medfeeSumamt={}", req.getMdtrtId(), req.getMedfeeSumamt());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", req);
        YbResponse resp = ybHttpClient.call("2304", input);
        if (resp.isSuccess()) {
            setlResultHandler.parseAndSave(resp, "inpatient", "2304", "1");
        }
        return resp;
    }

    /**
     * 【2305】住院结算撤销
     * 输入节点: data ; 输出节点: setlinfo + setldetail
     */
    public YbResponse cancelSettlement(SetlCancelReq req) {
        log.info("住院结算撤销: setlId={}, mdtrtId={}", req.getSetlId(), req.getMdtrtId());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", req);
        YbResponse resp = ybHttpClient.call("2305", input);
        if (resp.isSuccess()) {
            setlResultHandler.parseAndSave(resp, "inpatient", "2305", "0");
        }
        return resp;
    }

    /**
     * 【2402】出院办理
     * 输入节点: dscginfo(单行) + diseinfo(多行) ; 无输出
     */
    public YbResponse discharge(DischargeReq dscgInfo, List<DiseInfoReq> diseInfo) {
        log.info("出院办理: mdtrtId={}, endtime={}", dscgInfo.getMdtrtId(), dscgInfo.getEndtime());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("dscginfo", dscgInfo);
        input.put("diseinfo", diseInfo);
        return ybHttpClient.call("2402", input);
    }
}
