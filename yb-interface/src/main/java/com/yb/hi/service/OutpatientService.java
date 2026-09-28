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
     * 字段集对齐文档【2201A】(表99, 含 med_type 医疗类别); infno 仍传 "2201"
     * (国家医保平台门诊挂号交易号即 2201, 2201A 仅为文档对扩展字段集的标注, 非单独交易号)
     */
    public YbResponse register(OutpatientRegisterReq req) {
        return register(req, null);
    }

    /** 2201(带患者参保地区划, 规范表3: 输入含psn_no时insuplc_admdvs必填) */
    public YbResponse register(OutpatientRegisterReq req, String insuplcAdmdvs) {
        log.info("门诊挂号: psnNo={}, iptOtpNo={}", req.getPsnNo(), req.getIptOtpNo());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", req);
        return ybHttpClient.call("2201", input, insuplcAdmdvs);
    }

    /**
     * 【2202】门诊挂号撤销
     * 输入节点: data(psn_no, mdtrt_id, ipt_otp_no) ; 输出: 无
     */
    public YbResponse cancelRegister(OutpatientRegisterCancelReq req) {
        return cancelRegister(req, null);
    }

    /** 2202(带患者参保地区划) */
    public YbResponse cancelRegister(OutpatientRegisterCancelReq req, String insuplcAdmdvs) {
        log.info("门诊挂号撤销: psnNo={}, mdtrtId={}, iptOtpNo={}", req.getPsnNo(), req.getMdtrtId(), req.getIptOtpNo());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", req);
        return ybHttpClient.call("2202", input, insuplcAdmdvs);
    }

    /**
     * 【2203】门诊就诊信息上传
     * 输入节点: mdtrtinfo(单行) + diseinfo(多行)
     */
    public YbResponse uploadVisitInfo(MdtrtInfoReq mdtrtInfo, List<DiseInfoReq> diseInfo) {
        return uploadVisitInfo(mdtrtInfo, diseInfo, null);
    }

    /** 2203(带患者参保地区划) */
    public YbResponse uploadVisitInfo(MdtrtInfoReq mdtrtInfo, List<DiseInfoReq> diseInfo, String insuplcAdmdvs) {
        log.info("门诊就诊信息上传: mdtrtId={}", mdtrtInfo.getMdtrtId());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("mdtrtinfo", mdtrtInfo);
        input.put("diseinfo", diseInfo);
        return ybHttpClient.call("2203", input, insuplcAdmdvs);
    }

    /**
     * 【2204】门诊费用明细信息上传
     * 输入节点: feedetail(多行) ; 输出节点: result(多行)
     */
    public YbResponse uploadFeeDetail(List<FeeDetailReq> feeDetails) {
        return uploadFeeDetail(feeDetails, null);
    }

    /** 2204(带患者参保地区划) */
    public YbResponse uploadFeeDetail(List<FeeDetailReq> feeDetails, String insuplcAdmdvs) {
        log.info("门诊费用明细上传: 明细{}条", feeDetails == null ? 0 : feeDetails.size());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("feedetail", feeDetails);
        return ybHttpClient.call("2204", input, insuplcAdmdvs);
    }

    /**
     * 【2205】门诊费用明细信息撤销
     * 输入节点: data(单行) ; 输出: 无。
     * chrg_bchno="0000" 全撤未结算明细(部分退费全撤重结 A8 使用); 已参与结算的明细不能撤销, 须先 2208。
     */
    public YbResponse revokeFeeDetail(FeeDetailRevokeReq req, String insuplcAdmdvs) {
        log.info("门诊费用明细撤销: mdtrtId={}, chrgBchno={}", req.getMdtrtId(), req.getChrgBchno());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", req);
        return ybHttpClient.call("2205", input, insuplcAdmdvs);
    }

    /**
     * 【2206】门诊预结算
     * 输入节点: data ; 输出节点: setlinfo(单行) + setldetail(多行)
     * 预结算结果不留存本地记录
     */
    public YbResponse preSettlement(SettlementReq req) {
        return preSettlement(req, null);
    }

    /** 2206(带患者参保地区划) */
    public YbResponse preSettlement(SettlementReq req, String insuplcAdmdvs) {
        log.info("门诊预结算: mdtrtId={}, medfeeSumamt={}", req.getMdtrtId(), req.getMedfeeSumamt());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", req);
        YbResponse resp = ybHttpClient.call("2206", input, insuplcAdmdvs);
        setlResultHandler.parse(resp);
        return resp;
    }

    /**
     * 【2207】门诊结算
     * 输入节点: data ; 输出节点: setlinfo + setldetail
     * 结算成功后留存本地结算记录
     */
    public YbResponse settlement(SettlementReq req) {
        return settlement(req, null);
    }

    /** 2207(带患者参保地区划) */
    public YbResponse settlement(SettlementReq req, String insuplcAdmdvs) {
        log.info("门诊结算: mdtrtId={}, medfeeSumamt={}", req.getMdtrtId(), req.getMedfeeSumamt());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", req);
        YbResponse resp = ybHttpClient.call("2207", input, insuplcAdmdvs);
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
        return cancelSettlement(req, null);
    }

    /** 2208(带患者参保地区划) */
    public YbResponse cancelSettlement(SetlCancelReq req, String insuplcAdmdvs) {
        log.info("门诊结算撤销: setlId={}, mdtrtId={}", req.getSetlId(), req.getMdtrtId());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", req);
        YbResponse resp = ybHttpClient.call("2208", input, insuplcAdmdvs);
        if (resp.isSuccess()) {
            setlResultHandler.parseAndSave(resp, "outpatient", "2208", "0");
        }
        return resp;
    }

    /**
     * 【3201】医药机构费用结算对总账(规范表196/197)
     * 输入节点: data(单行, ReconTotalReq 严格按表196字段); 输出节点: stmtinfo。
     */
    public YbResponse reconcileTotal(ReconTotalReq req) {
        log.info("对总账3201: insutype={}, stmt={}~{}", req.getInsutype(), req.getStmtBegndate(), req.getStmtEnddate());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", req);
        return ybHttpClient.call("3201", input, null);
    }

    /**
     * 【3202】医药机构费用结算对明细账(规范表198/199)
     * 输入节点: data(单行, ReconDetailReq 严格按表198字段, refd_setl_flag 3位);
     * 输出节点: fileinfo(file_qury_no 下载差异明细文件, 内容见表201)。
     */
    public YbResponse reconcileDetail(ReconDetailReq req) {
        log.info("对明细账3202: file_qury_no={}, stmt={}~{}", req.getFileQuryNo(), req.getStmtBegndate(), req.getStmtEnddate());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("data", req);
        return ybHttpClient.call("3202", input, null);
    }

    /**
     * 【9101】文件上传(规范表415/416)
     * 输入节点: fsUploadIn(in 字节数组/filename/fixmedins_code); 输出节点: 无节点(根下直接 file_qury_no 等)。
     */
    public YbResponse fileUpload(FileUploadReq req) {
        log.info("文件上传9101: filename={}", req.getFilename());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("fsUploadIn", req);
        return ybHttpClient.call("9101", input, null);
    }
}
