package com.yb.hi.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yb.hi.common.DateUtil;
import com.yb.hi.common.YbResponse;
import com.yb.hi.dto.SetlInfoResult;
import com.yb.hi.entity.SetlRecord;
import com.yb.hi.mapper.SetlRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 结算结果处理器
 * 负责解析 setlinfo 输出节点并留存本地结算记录
 */
@Slf4j
@Component
public class SetlResultHandler {

    private final SetlRecordMapper setlRecordMapper;

    public SetlResultHandler(SetlRecordMapper setlRecordMapper) {
        this.setlRecordMapper = setlRecordMapper;
    }

    /**
     * 解析结算信息输出节点(setlinfo)
     */
    public SetlInfoResult parse(YbResponse resp) {
        if (resp == null || !resp.isSuccess()) {
            return null;
        }
        JSONObject setlinfo = resp.getOutputNode("setlinfo");
        if (setlinfo == null) {
            return null;
        }
        return JSON.parseObject(setlinfo.toJSONString(), SetlInfoResult.class);
    }

    /**
     * 解析并留存结算记录
     *
     * @param resp    医保响应
     * @param bizType 业务类型 outpatient/inpatient
     * @param infno   交易编号
     * @param status  状态 1-已结算 0-已撤销
     */
    public SetlInfoResult parseAndSave(YbResponse resp, String bizType, String infno, String status) {
        SetlInfoResult result = parse(resp);
        if (result == null) {
            log.warn("结算响应无 setlinfo 节点, infno={}", infno);
            return null;
        }
        try {
            JSONObject setlinfo = resp.getOutputNode("setlinfo");
            SetlRecord rec = new SetlRecord();
            rec.setSetlId(result.getSetlId());
            rec.setMdtrtId(result.getMdtrtId());
            rec.setPsnNo(result.getPsnNo());
            rec.setPsnName(result.getPsnName());
            rec.setInsutype(result.getInsutype());
            rec.setMedType(result.getMedType());
            rec.setBizType(bizType);
            rec.setInfno(infno);
            rec.setSetlTime(result.getSetlTime());
            rec.setMedfeeSumamt(result.getMedfeeSumamt());
            rec.setFundPaySumamt(result.getFundPaySumamt());
            rec.setPsnPartAmt(result.getPsnPartAmt());
            rec.setAcctPay(result.getAcctPay());
            rec.setPsnCashPay(result.getPsnCashPay());
            rec.setStatus(status);
            rec.setSetlinfoJson(setlinfo == null ? null : setlinfo.toJSONString());
            rec.setCrteTime(DateUtil.currentDateTime());
            setlRecordMapper.insert(rec);
            log.info("结算记录已留存: infno={}, setlId={}, status={}", infno, result.getSetlId(), status);
        } catch (Exception e) {
            log.error("留存结算记录失败, infno={}", infno, e);
        }
        return result;
    }
}
