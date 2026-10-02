package com.yb.hi.common;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 模拟医保平台(开发/界面验证用)
 * 当 yb.mock-enabled=true 时, YbHttpClient 不调用真实平台, 由本类按接口规范生成模拟响应,
 * 以便在无真实医保平台环境下验证报文组装、解析、入库的完整链路。
 * 批次4 M1: 维护模拟结算流水(setl_id -> setlinfo), 支撑 2208 撤单/2601 冲正/补偿任务核对。
 */
@Slf4j
@Component
public class MockYbServer {

    private static final AtomicInteger SEQ = new AtomicInteger(100);

    /** 字典文件缓存: file_qury_no -> zip字节 */
    private final Map<String, byte[]> dictFiles = new ConcurrentHashMap<>();

    /** 文件流缓存(9101 上传/3202 差异结果): file_qury_no -> 字节 */
    private final Map<String, byte[]> mockFiles = new ConcurrentHashMap<>();

    /** 模拟结算流水: setl_id -> setlinfo(2207 落库, 2208 按 setl_id 取原单, 2601 冲正移除) */
    private final Map<String, JSONObject> mockSetls = new ConcurrentHashMap<>();

    /** 已撤销结算ID集合(2208 受理后登记, 补偿任务据此判断撤销是否实际生效) */
    private final Set<String> mockCancelledSetls = ConcurrentHashMap.newKeySet();

    /**
     * 字典元数据: infno -> [列数, 版本号列索引, 唯一记录号列索引, 名称列索引, 有效标志列索引, 编码前缀]
     */
    private static final Map<String, int[]> DICT_META = new ConcurrentHashMap<>();
    private static final Map<String, String> DICT_NAME = new ConcurrentHashMap<>();

    static {
        DICT_META.put("1301", new int[]{97, 82, 79, 1, 78});
        DICT_META.put("1302", new int[]{33, 17, 14, 1, 13});
        DICT_META.put("1303", new int[]{85, 70, 67, 1, 64});
        DICT_META.put("1305", new int[]{20, 14, 13, 9, 6});
        DICT_META.put("1306", new int[]{71, 69, 68, 1, 67});
        DICT_META.put("1307", new int[]{24, 22, 19, 11, 18});
        DICT_NAME.put("1301", "西药中成药");
        DICT_NAME.put("1302", "中药饮片");
        DICT_NAME.put("1303", "医疗机构制剂");
        DICT_NAME.put("1305", "医疗服务项目");
        DICT_NAME.put("1306", "医用耗材");
        DICT_NAME.put("1307", "疾病与诊断");
    }

    /**
     * 处理模拟交易请求
     */
    public YbResponse handle(String infno, String requestJson) {
        JSONObject msg = JSON.parseObject(requestJson);
        String msgid = msg.getString("msgid");
        JSONObject input = msg.getJSONObject("input");

        JSONObject output = new JSONObject();
        switch (infno) {
            case "1301": case "1302": case "1303":
            case "1305": case "1306": case "1307":
                output = mockDictDownload(infno);
                break;
            case "2201":
                output = mockRegister(input);
                break;
            case "2202":
                // 挂号撤销: 无输出节点, infcode=0 即成功
                break;
            case "2204": case "2301":
                output = mockFeeDetail(input);
                break;
            case "2206": case "2303": case "2304":
                // 预结算/上传结算: 不落模拟流水
                output = mockSettlement(input, msgid, false);
                break;
            case "2207": {
                // 结算: 落模拟流水(medins_setl_id=msgid), 供 2208/2601/补偿核对;
                // 批次5 M2 通道A: drug_trac_info 节点(表90)必填字段缺失即拒绝, 模拟平台追溯码拦截
                String tracErr = validateDrugTrac(input == null ? null : input.getJSONArray("drug_trac_info"));
                if (tracErr != null) {
                    log.warn("【模拟医保平台】2207 追溯码节点校验拒绝: {}", tracErr);
                    return failResponse(msgid, tracErr);
                }
                JSONArray tracNodes = input == null ? null : input.getJSONArray("drug_trac_info");
                if (tracNodes != null && !tracNodes.isEmpty()) {
                    log.info("【模拟医保平台】2207 受理追溯码节点 {} 条", tracNodes.size());
                }
                output = mockSettlement(input, msgid, true);
                break;
            }
            case "2208": case "2305":
                // 撤销: 按 setl_id 返回原结算 setlinfo
                output = mockCancelSettlement(input);
                break;
            case "2601":
                // 冲正: 规范输出无节点, infcode=0 即成功; 模拟端同步移除原结算流水
                mockReverse(input);
                break;
            case "3201": case "3202":
                // 对账(M3): 平台侧按模拟结算流水核对, 不平生成表201差异明细文件
                output = mockReconcile(infno, input);
                break;
            case "9101":
                output = mockFileUpload(input);
                break;
            case "2401":
                output = mockAdmission(input);
                break;
            case "3301":
                // 目录对照上传(M4): 受理入库 mock 对照表, 规范输出无节点, infcode=0 即成功
                mockCatalogUpload(input);
                break;
            case "3302":
                // 目录对照撤销(M4): 移除 mock 对照, 规范输出无节点, infcode=0 即成功
                mockCatalogRevoke(input);
                break;
            case "3505": case "3505A": {
                // 进销存商品销售(批次5 M2b 通道B): 两级判定——传输 infcode=0 + 业务 retRslt(表209)。
                // 必填违例(07号文§十二.2 冻结清单)时 retRslt=0 模拟平台业务拒绝
                JSONArray rows = "3505".equals(infno) ? singleRowToList(input)
                        : (input == null ? null : input.getJSONArray("selinfoDetail"));
                String selErr = validateSelInfo(rows);
                JSONObject result = new JSONObject();
                if (selErr != null) {
                    log.warn("【模拟医保平台】{} 销售明细业务拒绝: {}", infno, selErr);
                    result.put("retRslt", "0");
                    result.put("msgRslt", selErr);
                } else {
                    int codes = 0;
                    for (int i = 0; i < rows.size(); i++) {
                        JSONArray tn = rows.getJSONObject(i).getJSONArray("drugtracinfo");
                        if (tn != null) {
                            codes += tn.size();
                        }
                    }
                    result.put("retRslt", "1");
                    result.put("msgRslt", "受理成功");
                    log.info("【模拟医保平台】{} 受理销售明细 {} 行/追溯码 {} 条", infno, rows.size(), codes);
                }
                output.put("result", result);
                break;
            }
            default:
                // 2203/2402 等无输出交易
                break;
        }

        JSONObject resp = new JSONObject();
        resp.put("infcode", "0");
        resp.put("inf_refmsgid", msgid);
        resp.put("refmsg_time", DateUtil.currentDateTime());
        resp.put("respond_time", DateUtil.currentDateTime());
        resp.put("err_msg", "");
        resp.put("output", output.toJSONString());

        // 与真实模式 parseResponse 同口径逐字段映射(parseObject 不做 snake_case->camelCase, 会丢 inf_refmsgid/err_msg)
        YbResponse ybResp = new YbResponse();
        ybResp.setInfcode(resp.getString("infcode"));
        ybResp.setInfRefmsgid(resp.getString("inf_refmsgid"));
        ybResp.setRefmsgTime(resp.getString("refmsg_time"));
        ybResp.setRespondTime(resp.getString("respond_time"));
        ybResp.setErrMsg(resp.getString("err_msg"));
        ybResp.setOutput(resp.getString("output"));
        ybResp.setRawJson(resp.toJSONString());
        log.info("【模拟医保平台】infno={} 已生成模拟响应", infno);
        return ybResp;
    }

    /** 平台拒绝响应(模拟): infcode 非0 + 错误信息, 其余报文头与成功同口径 */
    private YbResponse failResponse(String msgid, String err) {
        JSONObject resp = new JSONObject();
        resp.put("infcode", "1");
        resp.put("inf_refmsgid", msgid);
        resp.put("refmsg_time", DateUtil.currentDateTime());
        resp.put("respond_time", DateUtil.currentDateTime());
        resp.put("err_msg", err);
        resp.put("output", "");
        YbResponse ybResp = new YbResponse();
        ybResp.setInfcode(resp.getString("infcode"));
        ybResp.setInfRefmsgid(resp.getString("inf_refmsgid"));
        ybResp.setRefmsgTime(resp.getString("refmsg_time"));
        ybResp.setRespondTime(resp.getString("respond_time"));
        ybResp.setErrMsg(resp.getString("err_msg"));
        ybResp.setOutput("");
        ybResp.setRawJson(resp.toJSONString());
        return ybResp;
    }

    /** 2207 drug_trac_info 节点校验(表90 冻结字段): feedetl_sn/drug_trac_codg/trdn_flag/min_prcunt_type 均必填; 合规则返 null */
    private String validateDrugTrac(JSONArray nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }
        for (int i = 0; i < nodes.size(); i++) {
            JSONObject n = nodes.getJSONObject(i);
            if (n == null || isEmptyStr(n.getString("feedetl_sn")) || isEmptyStr(n.getString("drug_trac_codg"))
                    || isEmptyStr(n.getString("trdn_flag")) || isEmptyStr(n.getString("min_prcunt_type"))) {
                return "drug_trac_info第" + (i + 1) + "行必填字段缺失(feedetl_sn/drug_trac_codg/trdn_flag/min_prcunt_type)";
            }
        }
        return null;
    }

    private static boolean isEmptyStr(String v) {
        return v == null || v.trim().isEmpty();
    }

    /** 3505 单行 selinfo 包装为数组供统一校验(3505A 直接取 selinfoDetail) */
    private static JSONArray singleRowToList(JSONObject input) {
        JSONObject sel = input == null ? null : input.getJSONObject("selinfo");
        if (sel == null) {
            return null;
        }
        JSONArray arr = new JSONArray();
        arr.add(sel);
        return arr;
    }

    /**
     * 3505/3505A 销售明细校验(07号文§十二.2 表206/208 逐字冻结必填面): 16 必填 + 医保结算(mdtrt_setl_type=1)时 setl_id 必填
     * + drugtracinfo 节点内 drug_trac_codg 非空; 合规则返 null, 否则返首个违例描述
     */
    private String validateSelInfo(JSONArray rows) {
        if (rows == null || rows.isEmpty()) {
            return "销售明细节点(selinfo/selinfoDetail)缺失";
        }
        String[] req = {"fixmedins_hilist_id", "fixmedins_hilist_name", "fixmedins_bchno", "prsc_dr_name",
                "hi_feesetl_type", "mdtrt_sn", "psn_cert_type", "manu_lotnum", "manu_date", "rx_flag", "trdn_flag",
                "rtal_docno", "sel_retn_cnt", "sel_retn_time", "sel_retn_opter_name", "mdtrt_setl_type"};
        for (int i = 0; i < rows.size(); i++) {
            JSONObject r = rows.getJSONObject(i);
            if (r == null) {
                return "销售明细第" + (i + 1) + "行为空";
            }
            for (String k : req) {
                if (isEmptyStr(r.getString(k))) {
                    return "销售明细第" + (i + 1) + "行必填缺失: " + k;
                }
            }
            if ("1".equals(r.getString("mdtrt_setl_type")) && isEmptyStr(r.getString("setl_id"))) {
                return "销售明细第" + (i + 1) + "行医保结算时 setl_id 必填";
            }
            JSONArray nodes = r.getJSONArray("drugtracinfo");
            if (nodes != null) {
                for (int j = 0; j < nodes.size(); j++) {
                    JSONObject n = nodes.getJSONObject(j);
                    if (n == null || isEmptyStr(n.getString("drug_trac_codg"))) {
                        return "销售明细第" + (i + 1) + "行 drugtracinfo 第" + (j + 1) + "条缺 drug_trac_codg";
                    }
                }
            }
        }
        return null;
    }

    /**
     * 获取模拟字典文件(ZIP)
     */
    public byte[] downloadFile(String fileQueryNo) {
        byte[] zip = dictFiles.get(fileQueryNo);
        if (zip == null) {
            log.warn("模拟字典文件不存在: {}", fileQueryNo);
            return new byte[0];
        }
        return zip;
    }

    // ==================== 各交易模拟 ====================

    /** 字典下载: 生成文件查询号并预生成ZIP文件 */
    private JSONObject mockDictDownload(String infno) {
        String fileQueryNo = "MOCKF" + SEQ.incrementAndGet();
        String filename = infno + "_" + DateUtil.currentTimeCompact() + ".zip";
        byte[] zip = buildDictZip(infno);
        dictFiles.put(fileQueryNo, zip);

        JSONObject output = new JSONObject();
        output.put("file_qury_no", fileQueryNo);
        output.put("filename", filename);
        output.put("dld_endtime", DateUtil.currentDate());
        output.put("data_cnt", 3);
        return output;
    }

    /** 门诊挂号/入院: 返回就诊ID */
    private JSONObject mockRegister(JSONObject input) {
        JSONObject data = input.getJSONObject("data");
        JSONObject out = new JSONObject();
        out.put("mdtrt_id", "M" + SEQ.incrementAndGet());
        out.put("psn_no", data.getString("psn_no"));
        out.put("ipt_otp_no", data.getString("ipt_otp_no"));
        JSONObject output = new JSONObject();
        output.put("data", out);
        return output;
    }

    private JSONObject mockAdmission(JSONObject input) {
        JSONObject mdtrtinfo = input.getJSONObject("mdtrtinfo");
        JSONObject out = new JSONObject();
        out.put("mdtrt_id", "M" + SEQ.incrementAndGet());
        out.put("psn_no", mdtrtinfo == null ? null : mdtrtinfo.getString("psn_no"));
        JSONObject output = new JSONObject();
        output.put("result", out);
        return output;
    }

    /** 费用明细上传: 按输入明细逐条返回分割结果 */
    private JSONObject mockFeeDetail(JSONObject input) {
        JSONArray details = input.getJSONArray("feedetail");
        JSONArray result = new JSONArray();
        if (details != null) {
            for (int i = 0; i < details.size(); i++) {
                JSONObject d = details.getJSONObject(i);
                JSONObject r = new JSONObject();
                r.put("feedetl_sn", d.getString("feedetl_sn"));
                r.put("det_item_fee_sumamt", d.get("det_item_fee_sumamt"));
                r.put("cnt", d.get("cnt"));
                r.put("pric", d.get("pric"));
                r.put("pric_uplmt_amt", d.get("pric"));
                r.put("selfpay_prop", "0.10");
                r.put("fulamt_ownpay_amt", "0.00");
                r.put("overlmt_amt", "0.00");
                r.put("preselfpay_amt", "0.00");
                r.put("inscp_scp_amt", d.get("det_item_fee_sumamt"));
                r.put("chrgitm_lv", "1");
                r.put("med_chrgitm_type", "01");
                r.put("bas_medn_flag", "0");
                r.put("hi_nego_drug_flag", "0");
                r.put("chld_medc_flag", "0");
                r.put("list_sp_item_flag", "0");
                r.put("lmt_used_flag", "0");
                r.put("drt_reim_flag", "1");
                result.add(r);
            }
        }
        JSONObject output = new JSONObject();
        output.put("result", result);
        return output;
    }

    /** 结算/预结算: 按70%统筹比例模拟基金支付; store=true(2207)时落模拟流水 */
    private JSONObject mockSettlement(JSONObject input, String msgid, boolean store) {
        JSONObject data = input.getJSONObject("data");
        BigDecimal medfee = data.getBigDecimal("medfee_sumamt");
        if (medfee == null) {
            medfee = BigDecimal.ZERO;
        }
        BigDecimal fundPay = medfee.multiply(new BigDecimal("0.70")).setScale(2, RoundingMode.HALF_UP);
        BigDecimal psnPart = medfee.subtract(fundPay).setScale(2, RoundingMode.HALF_UP);

        JSONObject setlinfo = new JSONObject();
        setlinfo.put("mdtrt_id", data.getString("mdtrt_id"));
        setlinfo.put("setl_id", "S" + SEQ.incrementAndGet());
        setlinfo.put("psn_no", data.getString("psn_no"));
        setlinfo.put("psn_name", "模拟测试员");
        setlinfo.put("psn_cert_type", "01");
        setlinfo.put("certno", data.getString("mdtrt_cert_no"));
        setlinfo.put("gend", "1");
        setlinfo.put("age", "35.0");
        setlinfo.put("insutype", data.getString("insutype"));
        setlinfo.put("psn_type", "1");
        setlinfo.put("cvlserv_flag", "0");
        setlinfo.put("setl_time", DateUtil.currentDateTime());
        setlinfo.put("mdtrt_cert_type", data.getString("mdtrt_cert_type"));
        setlinfo.put("med_type", data.getString("med_type"));
        setlinfo.put("medfee_sumamt", medfee.toPlainString());
        setlinfo.put("fulamt_ownpay_amt", "0.00");
        setlinfo.put("overlmt_selfpay", "0.00");
        setlinfo.put("preselfpay_amt", "0.00");
        setlinfo.put("inscp_scp_amt", medfee.toPlainString());
        setlinfo.put("act_pay_dedc", "0.00");
        setlinfo.put("hifp_pay", fundPay.toPlainString());
        setlinfo.put("pool_prop_selfpay", "0.7000");
        setlinfo.put("cvlserv_pay", "0.00");
        setlinfo.put("hifes_pay", "0.00");
        setlinfo.put("hifmi_pay", "0.00");
        setlinfo.put("hifob_pay", "0.00");
        setlinfo.put("maf_pay", "0.00");
        setlinfo.put("oth_pay", "0.00");
        setlinfo.put("fund_pay_sumamt", fundPay.toPlainString());
        setlinfo.put("psn_part_amt", psnPart.toPlainString());
        setlinfo.put("acct_pay", "0.00");
        setlinfo.put("psn_cash_pay", psnPart.toPlainString());
        setlinfo.put("hosp_part_amt", "0.00");
        setlinfo.put("balc", "1234.56");
        setlinfo.put("acct_mulaid_pay", "0.00");
        setlinfo.put("medins_setl_id", msgid);
        setlinfo.put("clr_optins", "420100");
        setlinfo.put("clr_way", "1");
        setlinfo.put("clr_type", "1");
        setlinfo.put("hifdm_pay", "0.00");

        JSONArray setldetail = new JSONArray();
        JSONObject detail = new JSONObject();
        detail.put("fund_pay_type", "310101");
        detail.put("inscp_scp_amt", medfee.toPlainString());
        detail.put("crt_payb_lmt_amt", medfee.toPlainString());
        detail.put("fund_payamt", fundPay.toPlainString());
        detail.put("fund_pay_type_name", "基本医疗保险统筹基金支出");
        detail.put("setl_proc_info", "模拟结算过程: 统筹支付70%");
        setldetail.add(detail);

        JSONObject output = new JSONObject();
        output.put("setlinfo", setlinfo);
        output.put("setldetail", setldetail);
        if (store) {
            mockSetls.put(setlinfo.getString("setl_id"), setlinfo);
        }
        return output;
    }

    /** 结算撤销(2208): 按 setl_id 返回原结算 setlinfo(规范: 输出为被撤销结算单信息) */
    private JSONObject mockCancelSettlement(JSONObject input) {
        JSONObject data = input.getJSONObject("data");
        String setlId = data == null ? null : data.getString("setl_id");
        JSONObject setlinfo = setlId == null ? null : mockSetls.get(setlId);
        if (setlinfo == null) {
            // 未找到原结算流水(如手工构造报文): 按输入回显最小 setlinfo
            setlinfo = new JSONObject();
            setlinfo.put("setl_id", setlId);
            setlinfo.put("mdtrt_id", data == null ? null : data.getString("mdtrt_id"));
            setlinfo.put("psn_no", data == null ? null : data.getString("psn_no"));
            setlinfo.put("medfee_sumamt", "0.00");
            setlinfo.put("fund_pay_sumamt", "0.00");
            setlinfo.put("psn_part_amt", "0.00");
            setlinfo.put("psn_cash_pay", "0.00");
        }
        JSONObject output = new JSONObject();
        output.put("setlinfo", setlinfo);
        output.put("setldetail", new JSONArray());
        // 撤销受理登记(2208 UNKNOWN 时补偿任务据此判断撤销是否实际生效)
        if (setlId != null) {
            mockCancelledSetls.add(setlId);
        }
        return output;
    }

    /** 冲正(2601): 按 omsgid(原交易 msgid) 移除模拟结算流水, 使全撤重结(A8)可在 mock 下闭环 */
    private void mockReverse(JSONObject input) {
        JSONObject data = input.getJSONObject("data");
        String omsgid = data == null ? null : data.getString("omsgid");
        if (omsgid == null) {
            return;
        }
        Set<String> removed = new java.util.HashSet<>();
        mockSetls.entrySet().removeIf(e -> {
            if (omsgid.equals(e.getValue().getString("medins_setl_id"))) {
                removed.add(e.getKey());
                return true;
            }
            return false;
        });
        mockCancelledSetls.removeAll(removed);
        log.info("【模拟医保平台】2601 冲正已移除原结算流水, omsgid={}", omsgid);
    }

    /** 结算是否已撤销(2208 受理登记; 补偿任务 RESOLVE_UNKNOWN 用) */
    public boolean isSetlCancelled(String setlId) {
        return setlId != null && mockCancelledSetls.contains(setlId);
    }

    // ==================== 目录对照模拟(M4: 3301/3302) ====================

    /** mock 平台侧目录对照表: key = fixmedins_hilist_id|list_type|med_list_codg */
    private final Map<String, JSONObject> mockCatalog = new ConcurrentHashMap<>();

    /** 3301 受理入库 mock 对照表(表206 多行; 输出无) */
    private void mockCatalogUpload(JSONObject input) {
        JSONArray rows = input.getJSONArray("data");
        if (rows == null) {
            return;
        }
        int n = 0;
        for (int i = 0; i < rows.size(); i++) {
            JSONObject r = rows.getJSONObject(i);
            if (r == null) {
                continue;
            }
            String key = r.getString("fixmedins_hilist_id") + "|" + r.getString("list_type") + "|" + r.getString("med_list_codg");
            mockCatalog.put(key, r);
            n++;
        }
        log.info("【模拟医保平台】3301 对照受理入库 {} 条, 现有对照 {} 条", n, mockCatalog.size());
    }

    /** 3302 移除 mock 对照(表208 多行; 输出无) */
    private void mockCatalogRevoke(JSONObject input) {
        JSONArray rows = input.getJSONArray("data");
        if (rows == null) {
            return;
        }
        int n = 0;
        for (int i = 0; i < rows.size(); i++) {
            JSONObject r = rows.getJSONObject(i);
            if (r == null) {
                continue;
            }
            String key = r.getString("fixmedins_hilist_id") + "|" + r.getString("list_type") + "|" + r.getString("med_list_codg");
            if (mockCatalog.remove(key) != null) {
                n++;
            }
        }
        log.info("【模拟医保平台】3302 对照撤销 {} 条, 现有对照 {} 条", n, mockCatalog.size());
    }

    /** mock 平台侧现存对照条数(联调断言用) */
    public int mockCatalogCount() {
        return mockCatalog.size();
    }

    /** mock 平台侧是否存有该对照(联调断言用) */
    public boolean mockCatalogContains(String hilistId, String listType, String medListCodg) {
        return mockCatalog.containsKey(hilistId + "|" + listType + "|" + medListCodg);
    }

    /**
     * 对总账/对明细账(3201/3202, M3):
     * 3201 按区间内未撤销模拟结算流水汇总, 与院内上报金额比对, 平/不平按表197 stmtinfo 回执
     * (stmt_rslt: 000000平/000103数据不一致, 规范字典对账结果);
     * 3202 解析院内上传明细文件(表200, 按 file_qury_no 定位), 逐条核对模拟流水,
     * 差异按表201生成结果文件(ZIP+TXT, TAB分隔, 空值null)并以 fileinfo 返回查询号。
     */
    private JSONObject mockReconcile(String infno, JSONObject input) {
        JSONObject data = input.getJSONObject("data");
        String beg = data == null ? null : data.getString("stmt_begndate");
        String end = data == null ? null : data.getString("stmt_enddate");
        JSONObject output = new JSONObject();
        if ("3201".equals(infno)) {
            JSONObject stmtinfo = new JSONObject();
            stmtinfo.put("setl_optins", data == null ? null : data.getString("setl_optins"));
            // 平台口径: setl_time 在 [beg, end] 内且未撤销的结算流水
            BigDecimal remoteMedfee = BigDecimal.ZERO;
            BigDecimal remoteFund = BigDecimal.ZERO;
            BigDecimal remoteAcct = BigDecimal.ZERO;
            int remoteCnt = 0;
            for (JSONObject s : mockSetls.values()) {
                String t = s.getString("setl_time");
                if (t != null && inRange(t, beg, end) && !mockCancelledSetls.contains(s.getString("setl_id"))) {
                    remoteMedfee = remoteMedfee.add(toBd(s.getString("medfee_sumamt")));
                    remoteFund = remoteFund.add(toBd(s.getString("fund_pay_sumamt")));
                    remoteAcct = remoteAcct.add(toBd(s.getString("acct_pay")));
                    remoteCnt++;
                }
            }
            BigDecimal medfee = toBd(data == null ? null : data.getString("medfee_sumamt"));
            BigDecimal fund = toBd(data == null ? null : data.getString("fund_pay_sumamt"));
            BigDecimal acct = toBd(data == null ? null : data.getString("acct_pay"));
            int cnt = data == null || data.getString("fixmedins_setl_cnt") == null ? 0
                    : Integer.parseInt(data.getString("fixmedins_setl_cnt"));
            boolean match = remoteCnt == cnt && remoteMedfee.compareTo(medfee) == 0
                    && remoteFund.compareTo(fund) == 0 && remoteAcct.compareTo(acct) == 0;
            stmtinfo.put("stmt_rslt", match ? "000000" : "000103");
            stmtinfo.put("stmt_rslt_dscr", match ? "对总账成功: 数据一致"
                    : "对总账失败: 数据不一致(平台: 笔数" + remoteCnt + "/总额" + remoteMedfee.toPlainString() + ")");
            stmtinfo.put("exp_content", "");
            output.put("stmtinfo", stmtinfo);
            return output;
        }
        // 3202: 解析院内明细文件, 逐条核对, 差异生成表201结果文件
        String fqn = data == null ? null : data.getString("file_qury_no");
        byte[] upload = fqn == null ? null : mockFiles.get(fqn);
        StringBuilder diffTxt = new StringBuilder();
        List<String[]> rows = upload == null ? new java.util.ArrayList<>() : parseDetailTxt(upload);
        for (String[] r : rows) {
            String setlId = r[0];
            String[] diff = null;
            if (setlId == null || setlId.isEmpty()) {
                diff = new String[]{"null", "null", setlId, "null", "000102", r[6],
                        "结算ID为空, 平台无法核对", r[3], r[4], r[5], "null"};
            } else {
                JSONObject s = mockSetls.get(setlId);
                if (s == null) {
                    diff = new String[]{"null", "null", setlId, "null", "000102", r[6],
                            "平台无此结算流水(医药机构多)", r[3], r[4], r[5], "null"};
                } else if (!amountsMatch(s, r)) {
                    diff = new String[]{s.getString("psn_no"), s.getString("mdtrt_id"), setlId,
                            s.getString("medins_setl_id"), "000103", r[6], "结算数据不一致", r[3], r[4], r[5], "null"};
                }
            }
            if (diff != null) {
                for (String cell : diff) {
                    diffTxt.append(cell == null || cell.isEmpty() ? "null" : cell).append('\t');
                }
                diffTxt.setLength(diffTxt.length() - 1);
                diffTxt.append('\n');
            }
        }
        String resultFqn = "RQC" + SEQ.incrementAndGet();
        mockFiles.put(resultFqn, zipTxt(diffTxt.toString(), "recon_result.txt"));
        JSONObject fileinfo = new JSONObject();
        fileinfo.put("file_qury_no", resultFqn);
        fileinfo.put("filename", "3202_result_" + DateUtil.currentTimeCompact() + ".zip");
        fileinfo.put("dld_endtime", DateUtil.currentDate());
        output.put("fileinfo", fileinfo);
        return output;
    }

    /** 结算时间是否落在 [beg, end](yyyy-MM-dd) 区间 */
    private boolean inRange(String setlTime, String beg, String end) {
        if (beg == null || end == null) {
            return true;
        }
        String day = setlTime.length() >= 10 ? setlTime.substring(0, 10) : setlTime;
        return day.compareTo(beg) >= 0 && day.compareTo(end) <= 0;
    }

    /** 3202 明细行与平台流水核对: 退费反向行(负数)取绝对值比对且要求已撤销 */
    private boolean amountsMatch(JSONObject s, String[] r) {
        BigDecimal medfee = toBd(r[3]);
        BigDecimal fund = toBd(r[4]);
        BigDecimal acct = toBd(r[5]);
        boolean negative = medfee.signum() < 0;
        BigDecimal pm = negative ? medfee.negate() : medfee;
        BigDecimal pf = negative ? fund.negate() : fund;
        BigDecimal pa = negative ? acct.negate() : acct;
        boolean match = pm.compareTo(toBd(s.getString("medfee_sumamt"))) == 0
                && pf.compareTo(toBd(s.getString("fund_pay_sumamt"))) == 0
                && pa.compareTo(toBd(s.getString("acct_pay"))) == 0;
        if (negative) {
            // 退费反向行: 平台侧该结算须已撤销, 否则数据不一致
            return match && mockCancelledSetls.contains(s.getString("setl_id"));
        }
        return match;
    }

    private BigDecimal toBd(String v) {
        return v == null || v.isEmpty() || "null".equals(v) ? BigDecimal.ZERO : new BigDecimal(v);
    }

    /** 解析 3202 明细 ZIP(内含TXT, TAB分隔): 每行 8 列(表200) */
    private List<String[]> parseDetailTxt(byte[] zip) {
        List<String[]> rows = new java.util.ArrayList<>();
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = zis.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                String txt = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                for (String line : txt.split("\n")) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    String[] cells = line.split("\t", -1);
                    String[] r = new String[8];
                    for (int i = 0; i < r.length; i++) {
                        String c = i < cells.length ? cells[i] : "null";
                        r[i] = "null".equals(c) || c.isEmpty() ? null : c;
                    }
                    rows.add(r);
                }
            }
        } catch (Exception e) {
            log.warn("【模拟医保平台】3202 明细文件解析失败: {}", e.getMessage());
        }
        return rows;
    }

    /** TXT 内容压缩为 ZIP(9101/3202 文件流格式) */
    private byte[] zipTxt(String txt, String innerName) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bos)) {
                zos.putNextEntry(new ZipEntry(innerName));
                zos.write(txt.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            return bos.toByteArray();
        } catch (Exception e) {
            log.warn("【模拟医保平台】ZIP 生成失败: {}", e.getMessage());
            return new byte[0];
        }
    }

    /** 文件上传(9101): 存储文件流并返回文件查询号(输出节点: 无节点, 根下直挂 file_qury_no 等) */
    private JSONObject mockFileUpload(JSONObject input) {
        JSONObject fs = input == null ? null : input.getJSONObject("fsUploadIn");
        String filename = fs == null ? null : fs.getString("filename");
        byte[] bytes = fs == null ? null : fs.getBytes("in");
        String fqn = "FQ" + SEQ.incrementAndGet();
        if (bytes != null) {
            mockFiles.put(fqn, bytes);
        }
        JSONObject output = new JSONObject();
        output.put("file_qury_no", fqn);
        output.put("filename", filename);
        output.put("fixmedins_code", fs == null ? null : fs.getString("fixmedins_code"));
        output.put("dld_endtime", DateUtil.currentDate());
        log.info("【模拟医保平台】9101 文件上传受理: file_qury_no={}, filename={}, bytes={}",
                fqn, filename, bytes == null ? 0 : bytes.length);
        return output;
    }

    /** 按文件查询号取文件流(3202 差异明细文件下载解析; 9101 回查) */
    public byte[] getMockFile(String fileQuryNo) {
        return fileQuryNo == null ? null : mockFiles.get(fileQuryNo);
    }

    /**
     * 按原交易 msgid(结算单 medins_setl_id) 查找模拟结算流水。
     * 补偿任务 RESOLVE_UNKNOWN 用: 找到=平台侧已受理结算, 未找到=平台侧未受理。
     */
    public JSONObject findSetlByMsgid(String omsgid) {
        if (omsgid == null) {
            return null;
        }
        for (JSONObject setlinfo : mockSetls.values()) {
            if (omsgid.equals(setlinfo.getString("medins_setl_id"))) {
                return setlinfo;
            }
        }
        return null;
    }

    // ==================== 字典ZIP生成 ====================

    /**
     * 生成模拟字典ZIP(内含TXT, TAB分隔, 3条记录)
     */
    private byte[] buildDictZip(String infno) {
        int[] meta = DICT_META.get(infno);
        String name = DICT_NAME.get(infno);
        StringBuilder txt = new StringBuilder();
        String baseVer = DateUtil.currentTimeCompact();
        for (int i = 1; i <= 3; i++) {
            String[] row = new String[meta[0]];
            java.util.Arrays.fill(row, "");
            String code = infno + String.format("%06d", i);
            row[0] = code;                                   // 医疗目录编码
            row[meta[3]] = name + "测试" + i;                 // 名称列
            row[meta[4]] = "1";                              // 有效标志
            row[meta[2]] = "RID" + infno + i;                // 唯一记录号
            row[meta[1]] = baseVer + String.format("%03d", i); // 版本号
            if ("1307".equals(infno)) {
                row[10] = code;                              // 诊断代码
            }
            txt.append(String.join("\t", row)).append("\n");
        }
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry(infno + ".txt"));
            zos.write(txt.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.finish();
            return bos.toByteArray();
        } catch (Exception e) {
            log.error("生成模拟字典ZIP失败, infno={}", infno, e);
            return new byte[0];
        }
    }
}
