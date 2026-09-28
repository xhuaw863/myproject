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
            case "2207":
                // 结算: 落模拟流水(medins_setl_id=msgid), 供 2208/2601/补偿核对
                output = mockSettlement(input, msgid, true);
                break;
            case "2208": case "2305":
                // 撤销: 按 setl_id 返回原结算 setlinfo
                output = mockCancelSettlement(input);
                break;
            case "2601":
                // 冲正: 规范输出无节点, infcode=0 即成功; 模拟端同步移除原结算流水
                mockReverse(input);
                break;
            case "3201": case "3202":
                // 对账(M3 落地): 骨架先按成功空集返回, 字段布局待 M3 按规范表196-199 实现
                output = mockReconcile(infno);
                break;
            case "9101":
                output = mockFileUpload();
                break;
            case "2401":
                output = mockAdmission(input);
                break;
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

        YbResponse ybResp = JSON.parseObject(resp.toJSONString(), YbResponse.class);
        ybResp.setRawJson(resp.toJSONString());
        log.info("【模拟医保平台】infno={} 已生成模拟响应", infno);
        return ybResp;
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

    /** 对总账/对明细账(3201/3202): M3 落地, 骨架按成功空集返回 */
    private JSONObject mockReconcile(String infno) {
        JSONObject output = new JSONObject();
        output.put(infno.equals("3201") ? "data" : "setldetail", new JSONArray());
        return output;
    }

    /** 文件上传(9101): 返回文件查询号 */
    private JSONObject mockFileUpload() {
        JSONObject output = new JSONObject();
        output.put("file_qury_no", "FQ" + SEQ.incrementAndGet());
        output.put("filename", "upload_" + DateUtil.currentTimeCompact() + ".txt");
        output.put("dld_endtime", DateUtil.currentDate());
        return output;
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
