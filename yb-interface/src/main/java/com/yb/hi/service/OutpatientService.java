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
 * 门诊业务服务
 * 实现接口: 2201门诊挂号, 2203门诊就诊信息上传, 2204门诊费用明细上传,
 *          2206门诊预结算, 2207门诊结算, 2208门诊结算撤销
 */
@Slf4j
@Service
public class OutpatientService {

    private final YbHttpClient ybHttpClient;
    private final SetlResultHandler setlResultHandler;

    public OutpatientService(YbHttpClient ybHttpClient, SetlResultHandler setlResultHandler) {
        this.ybHttpClient = ybHttpClient;
        this.setlResultHandler = setlResultHandler;
    }

    /**
     * 【2201】门诊挂号
     * 输入节点: data ; 输出节点: data(mdtrt_id)
     */
    public YbResponse register(OutpatientRegisterReq req) {
        log.info("门诊挂号: psnNo={}, iptOtpNo={}", req.getPsnNo(), req.getIptOtpNo());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", req);
        return ybHttpClient.call("2201", input);
    }

    /**
     * 【2202】门诊挂号撤销
     * 输入节点: data(psn_no, mdtrt_id, ipt_otp_no) ; 输出: 无
     */
    public YbResponse cancelRegister(OutpatientRegisterCancelReq req) {
        log.info("门诊挂号撤销: psnNo={}, mdtrtId={}, iptOtpNo={}", req.getPsnNo(), req.getMdtrtId(), req.getIptOtpNo());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", req);
        return ybHttpClient.call("2202", input);
    }

    /**
     * 【2203】门诊就诊信息上传
     * 输入节点: mdtrtinfo(单行) + diseinfo(多行)
     */
    public YbResponse uploadVisitInfo(MdtrtInfoReq mdtrtInfo, List<DiseInfoReq> diseInfo) {
        log.info("门诊就诊信息上传: mdtrtId={}", mdtrtInfo.getMdtrtId());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("mdtrtinfo", mdtrtInfo);
        input.put("diseinfo", diseInfo);
        return ybHttpClient.call("2203", input);
    }

    /**
     * 【2204】门诊费用明细信息上传
     * 输入节点: feedetail(多行) ; 输出节点: result(多行)
     */
    public YbResponse uploadFeeDetail(List<FeeDetailReq> feeDetails) {
        log.info("门诊费用明细上传: 明细{}条", feeDetails == null ? 0 : feeDetails.size());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("feedetail", feeDetails);
        return ybHttpClient.call("2204", input);
    }

    /**
     * 【2206】门诊预结算
     * 输入节点: data ; 输出节点: setlinfo(单行) + setldetail(多行)
     * 预结算结果不留存本地记录
     */
    public YbResponse preSettlement(SettlementReq req) {
        log.info("门诊预结算: mdtrtId={}, medfeeSumamt={}", req.getMdtrtId(), req.getMedfeeSumamt());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", req);
        YbResponse resp = ybHttpClient.call("2206", input);
        setlResultHandler.parse(resp);
        return resp;
    }

    /**
     * 【2207】门诊结算
     * 输入节点: data ; 输出节点: setlinfo + setldetail
     * 结算成功后留存本地结算记录
     */
    public YbResponse settlement(SettlementReq req) {
        log.info("门诊结算: mdtrtId={}, medfeeSumamt={}", req.getMdtrtId(), req.getMedfeeSumamt());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", req);
        YbResponse resp = ybHttpClient.call("2207", input);
        if (resp.isSuccess()) {
            setlResultHandler.parseAndSave(resp, "outpatient", "2207", "1");
        }
        return resp;
    }

    /**
     * 【2208】门诊结算撤销
     * 输入节点: data(setl_id, mdtrt_id, psn_no) ; 输出节点: setlinfo + setldetail
     */
    public YbResponse cancelSettlement(SetlCancelReq req) {
        log.info("门诊结算撤销: setlId={}, mdtrtId={}", req.getSetlId(), req.getMdtrtId());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", req);
        YbResponse resp = ybHttpClient.call("2208", input);
        if (resp.isSuccess()) {
            setlResultHandler.parseAndSave(resp, "outpatient", "2208", "0");
        }
        return resp;
    }
}
