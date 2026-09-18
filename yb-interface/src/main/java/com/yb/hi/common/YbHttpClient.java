package com.yb.hi.common;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yb.hi.config.TenantYbConfigResolver;
import com.yb.hi.config.YbConfig;
import com.yb.hi.config.YbRuntimeConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * 医保接口HTTP客户端
 * 负责组装报文、签名、发送请求、解析响应
 */
@Slf4j
@Component
public class YbHttpClient {

    private final YbConfig ybConfig;
    private final MockYbServer mockYbServer;
    private final TenantYbConfigResolver configResolver;
    private OkHttpClient httpClient;

    public YbHttpClient(YbConfig ybConfig, MockYbServer mockYbServer, TenantYbConfigResolver configResolver) {
        this.ybConfig = ybConfig;
        this.mockYbServer = mockYbServer;
        this.configResolver = configResolver;
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
     * 调用医保接口(指定参保地)
     */
    public YbResponse call(String infno, Object inputData, String insuplcAdmdvs) {
        YbRuntimeConfig cfg = configResolver.resolve();
        String requestJson = buildRequest(infno, inputData, insuplcAdmdvs, cfg);
        log.info("【医保接口请求】infno={}, msgid={}", infno, JSON.parseObject(requestJson).getString("msgid"));
        log.debug("请求报文: {}", requestJson);

        // 模拟模式: 不调用真实平台, 由本地模拟服务生成响应
        if (cfg.isMockEnabled()) {
            YbResponse mockResp = mockYbServer.handle(infno, requestJson);
            mockResp.setRequestJson(requestJson);
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
                    return fail;
                }
                String responseBody = response.body() != null ? response.body().string() : "";
                log.debug("响应报文: {}", responseBody);
                YbResponse resp = parseResponse(responseBody);
                resp.setRequestJson(requestJson);
                return resp;
            }
        } catch (IOException e) {
            log.error("调用医保接口异常, infno={}", infno, e);
            YbResponse fail = YbResponse.fail("网络异常:" + e.getMessage());
            fail.setRequestJson(requestJson);
            return fail;
        }
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
        if (insuplcAdmdvs != null && !insuplcAdmdvs.isEmpty()) {
            msg.put("insuplc_admdvs", insuplcAdmdvs);
        }
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
