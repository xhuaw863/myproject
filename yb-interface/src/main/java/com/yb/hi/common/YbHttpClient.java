package com.yb.hi.common;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.yb.hi.config.TenantYbConfigResolver;
import com.yb.hi.config.YbConfig;
import com.yb.hi.config.YbRuntimeConfig;
import com.yb.hi.entity.yb.HisYbTxnLog;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.yb.HisYbTxnLogMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 医保接口HTTP客户端
 * 负责组装报文、签名、发送请求、解析响应。
 * 批次4 M1: 每次出站交易统一落 his_yb_txn_log(PENDING -> SUCCESS/FAIL/UNKNOWN),
 * 交易结果三分(SUCCESS/FAIL/UNKNOWN)是补偿引擎与 2601 冲正(omsgid 取原交易 msgid)的事实基础。
 */
@Slf4j
@Component
public class YbHttpClient {

    private final YbConfig ybConfig;
    private final MockYbServer mockYbServer;
    private final TenantYbConfigResolver configResolver;
    private final HisYbTxnLogMapper txnLogMapper;
    private OkHttpClient httpClient;

    public YbHttpClient(YbConfig ybConfig, MockYbServer mockYbServer, TenantYbConfigResolver configResolver,
                        HisYbTxnLogMapper txnLogMapper) {
        this.ybConfig = ybConfig;
        this.mockYbServer = mockYbServer;
        this.configResolver = configResolver;
        this.txnLogMapper = txnLogMapper;
    }

    @PostConstruct
    public void init() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(ybConfig.getConnectTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(ybConfig.getReadTimeout(), TimeUnit.MILLISECONDS)
                .writeTimeout(ybConfig.getReadTimeout(), TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * 调用医保接口
     *
     * @param infno 交易编号 如 "1301"
     * @param inputData 交易输入数据(input节点内容)
     * @return 医保平台响应
     */
    public YbResponse call(String infno, Object inputData) {
        return call(infno, inputData, null);
    }

    /**
     * 调用医保接口(指定参保地)。
     * 结果三分: isSuccess()=平台明确成功; !isSuccess()&&!isUnknown()=平台明确拒绝(FAIL);
     * isUnknown()=超时/网络异常, 平台侧是否受理不可知(补偿引擎走 UNKNOWN 决策树)。
     */
    public YbResponse call(String infno, Object inputData, String insuplcAdmdvs) {
        YbRuntimeConfig cfg = configResolver.resolve();
        String requestJson = buildRequest(infno, inputData, insuplcAdmdvs, cfg);
        String msgid = JSON.parseObject(requestJson).getString("msgid");
        log.info("【医保接口请求】infno={}, msgid={}", infno, msgid);
        log.debug("请求报文: {}", requestJson);

        // 交易日志: 出站即落 PENDING(msgid 持久化, 2601 冲正 omsgid 的唯一来源)
        HisYbTxnLog txn = buildTxnLog(infno, msgid, requestJson, cfg);
        if (txn != null) {
            try {
                txnLogMapper.insert(txn);
            } catch (Exception e) {
                log.warn("医保交易日志落库失败(不阻塞交易): infno={}, msgid={}, 原因: {}", infno, msgid, e.getMessage());
            }
        }

        // 模拟模式: 不调用真实平台, 由本地模拟服务生成响应
        // (测试钩子: -Dyb.mock.unknown.<infno>=true 模拟网络超时, 供补偿链路验收)
        if (cfg.isMockEnabled()) {
            YbResponse mockResp;
            if ("true".equalsIgnoreCase(System.getProperty("yb.mock.unknown." + infno))) {
                mockResp = YbResponse.unknown("模拟网络超时(UNKNOWN)");
            } else {
                mockResp = mockYbServer.handle(infno, requestJson);
            }
            mockResp.setRequestJson(requestJson);
            updateTxnLog(txn, mockResp);
            return mockResp;
        }

        try {
            RequestBody body = RequestBody.create(requestJson, MediaType.parse("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(cfg.getApiUrl())
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.error("HTTP请求失败, code={}", response.code());
                    YbResponse fail = YbResponse.fail("HTTP请求失败,状态码:" + response.code());
                    fail.setRequestJson(requestJson);
                    updateTxnLog(txn, fail);
                    return fail;
                }
                String responseBody = response.body() != null ? response.body().string() : "";
                log.debug("响应报文: {}", responseBody);
                YbResponse resp = parseResponse(responseBody);
                resp.setRequestJson(requestJson);
                updateTxnLog(txn, resp);
                return resp;
            }
        } catch (IOException e) {
            // 超时/网络异常: 平台侧状态不可知 -> UNKNOWN(与平台明确拒绝的 FAIL 严格区分)
            log.error("调用医保接口异常, infno={}, msgid={}", infno, msgid, e);
            YbResponse unk = YbResponse.unknown("网络异常:" + e.getMessage());
            unk.setRequestJson(requestJson);
            updateTxnLog(txn, unk);
            return unk;
        }
    }

    /** 出站交易日志(业务键从 input 抽取; 日志落库失败不阻塞交易) */
    private HisYbTxnLog buildTxnLog(String infno, String msgid, String requestJson, YbRuntimeConfig cfg) {
        try {
            HisYbTxnLog txn = new HisYbTxnLog();
            txn.setInfno(infno);
            txn.setMsgid(msgid);
            txn.setStatus(HisYbTxnLog.ST_PENDING);
            txn.setInputJson(requestJson);
            com.yb.hi.framework.tenant.LoginUser lu = UserContext.get();
            txn.setOrgId(lu == null ? null : lu.getOrgId());
            JSONObject msg = JSON.parseObject(requestJson);
            JSONObject input = msg.getJSONObject("input");
            if (input != null) {
                txn.setMdtrtId(extractStr(input, "mdtrt_id"));
                txn.setPsnNo(extractStr(input, "psn_no"));
                txn.setSetlId(extractStr(input, "setl_id"));
                txn.setChrgBchno(extractStr(input, "chrg_bchno"));
            }
            return txn;
        } catch (Exception e) {
            log.warn("组装医保交易日志失败: infno={}, 原因: {}", infno, e.getMessage());
            return null;
        }
    }

    /** 按交易回执更新日志状态(SUCCESS/FAIL/UNKNOWN) */
    private void updateTxnLog(HisYbTxnLog txn, YbResponse resp) {
        if (txn == null || txn.getId() == null) {
            return;
        }
        try {
            if (resp.isUnknown()) {
                txn.setStatus(HisYbTxnLog.ST_UNKNOWN);
            } else if (resp.isSuccess()) {
                txn.setStatus(HisYbTxnLog.ST_SUCCESS);
            } else {
                txn.setStatus(HisYbTxnLog.ST_FAIL);
            }
            txn.setErrMsg(resp.getErrMsg());
            txn.setOutputJson(resp.getRawJson());
            txn.setSetlId(extractSetlId(resp));
            txnLogMapper.updateById(txn);
        } catch (Exception e) {
            log.warn("医保交易日志状态回写失败: infno={}, msgid={}, 原因: {}", txn.getInfno(), txn.getMsgid(), e.getMessage());
        }
    }

    /** 从响应 output 抽取结算ID(2207/2208 的 setlinfo.setl_id), 供补偿任务按 setl_id 定位 */
    private String extractSetlId(YbResponse resp) {
        try {
            JSONObject setlinfo = resp.getOutputNode("setlinfo");
            if (setlinfo != null && setlinfo.getString("setl_id") != null) {
                return setlinfo.getString("setl_id");
            }
        } catch (Exception ignored) {
            // 无 setlinfo 节点即无结算ID
        }
        return null;
    }

    /**
     * 从 input JSON 递归浅抽业务键: 按规范各交易的输入节点形态
     * (data 单行 / data 多行 / feedetail 多行 / mdtrtinfo 单行)取首个出现的目标字段。
     */
    private String extractStr(JSONObject input, String key) {
        for (String node : new String[]{"data", "feedetail", "mdtrtinfo"}) {
            Object v = input.get(node);
            if (v instanceof JSONObject) {
                String s = ((JSONObject) v).getString(key);
                if (s != null) {
                    return s;
                }
            } else if (v instanceof JSONArray) {
                JSONArray arr = (JSONArray) v;
                if (!arr.isEmpty() && arr.get(0) instanceof JSONObject) {
                    String s = arr.getJSONObject(0).getString(key);
                    if (s != null) {
                        return s;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 组装请求报文
     * 按照接口规范第4章定义: infno, msgid, mdtrtarea_admvs, insuplc_admdvs,
     * recer_sys_code, infver, opter_type, opter, opter_name, inf_time,
     * fixmedins_code, fixmedins_name, sign_no, cainfo, input
     */
    private String buildRequest(String infno, Object inputData, String insuplcAdmdvs, YbRuntimeConfig cfg) {
        JSONObject msg = new JSONObject();
        msg.put("infno", infno);
        msg.put("msgid", generateMsgId(cfg));
        msg.put("mdtrtarea_admvs", cfg.getMdtrtareaAdmvs());
        msg.put("recer_sys_code", cfg.getRecerSysCode());
        msg.put("infver", cfg.getInfver());
        msg.put("opter_type", cfg.getOpterType());
        msg.put("opter", cfg.getOpter());
        msg.put("opter_name", cfg.getOpterName());
        msg.put("inf_time", DateUtil.currentDateTime());
        msg.put("fixmedins_code", cfg.getFixmedinsCode());
        msg.put("fixmedins_name", cfg.getFixmedinsName());
        if (cfg.getSignNo() != null && !cfg.getSignNo().isEmpty()) {
            msg.put("sign_no", cfg.getSignNo());
        }
        if (cfg.getEncType() != null && !cfg.getEncType().isEmpty()) {
            msg.put("enc_type", cfg.getEncType());
        }

        // input节点
        String inputStr = inputData instanceof String ? (String) inputData : JSON.toJSONString(inputData);
        msg.put("input", inputStr);

        // 参保地医保区划(规范表3: 交易输入含人员编号psn_no时必填):
        // 调用方显式传入(患者级, 取自his_patient_insu)优先, 其次取租户/机构配置的默认参保地;
        // 都取不到且输入含psn_no时告警(mock模式不校验, 真实平台将拒收)
        String effInsuplc = (insuplcAdmdvs != null && !insuplcAdmdvs.isEmpty())
                ? insuplcAdmdvs : cfg.getInsuplcAdmdvs();
        if (effInsuplc != null && !effInsuplc.isEmpty()) {
            msg.put("insuplc_admdvs", effInsuplc);
        } else if (inputStr.contains("\"psn_no\"") || inputStr.contains("\"psnNo\"")) {
            log.warn("【医保接口】infno={} 交易输入含人员编号但未取得参保地区划(insuplc_admdvs), 真实平台将拒收", infno);
        }

        // 签名: 剔除cainfo和input后，按ASCII升序排列参数，SM2签名
        String cainfo = SignUtil.sign(msg, cfg.getSm2PrivateKey());
        msg.put("cainfo", cainfo);

        return msg.toJSONString();
    }

    /**
     * 解析响应报文
     */
    private YbResponse parseResponse(String responseBody) {
        try {
            JSONObject json = JSON.parseObject(responseBody);
            YbResponse resp = new YbResponse();
            resp.setInfcode(json.getString("infcode"));
            resp.setInfRefmsgid(json.getString("inf_refmsgid"));
            resp.setRefmsgTime(json.getString("refmsg_time"));
            resp.setRespondTime(json.getString("respond_time"));
            resp.setErrMsg(json.getString("err_msg"));
            resp.setOutput(json.getString("output"));
            resp.setRawJson(responseBody);
            return resp;
        } catch (Exception e) {
            log.error("解析响应报文失败", e);
            return YbResponse.fail("解析响应失败:" + e.getMessage());
        }
    }

    /**
     * 生成发送方报文ID: 定点医药机构编号(12)+时间(14)+顺序号(4)
     */
    private String generateMsgId(YbRuntimeConfig cfg) {
        String code = cfg.getFixmedinsCode();
        if (code == null || code.isEmpty()) {
            code = "000000000000";
        }
        if (code.length() > 12) {
            code = code.substring(0, 12);
        } else {
            code = String.format("%-12s", code).replace(' ', '0');
        }
        return code + DateUtil.currentTimeCompact() + SeqGenerator.next();
    }

    /**
     * 下载文件(字典下载接口返回的文件)
     */
    public byte[] downloadFile(String fileQueryNo) throws IOException {
        YbRuntimeConfig cfg = configResolver.resolve();
        if (cfg.isMockEnabled()) {
            return mockYbServer.downloadFile(fileQueryNo);
        }
        String url = cfg.getFileDownloadUrl() + "?file_qury_no=" + fileQueryNo;
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("文件下载失败, code=" + response.code());
            }
            return response.body().bytes();
        }
    }
}
