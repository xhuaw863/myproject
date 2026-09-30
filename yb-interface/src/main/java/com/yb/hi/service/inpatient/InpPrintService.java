package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yb.hi.dto.inpatient.PrintTemplateDTO;
import com.yb.hi.entity.inpatient.HisInpChargeDetail;
import com.yb.hi.entity.inpatient.HisInpMedicalRecord;
import com.yb.hi.entity.inpatient.HisInpNursingRecord;
import com.yb.hi.entity.inpatient.HisInpOrder;
import com.yb.hi.entity.inpatient.HisInpSettle;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisPrintTemplate;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.inpatient.HisInpChargeDetailMapper;
import com.yb.hi.mapper.inpatient.HisInpMedicalRecordMapper;
import com.yb.hi.mapper.inpatient.HisInpNursingRecordMapper;
import com.yb.hi.mapper.inpatient.HisInpOrderMapper;
import com.yb.hi.mapper.inpatient.HisInpSettleMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.mapper.inpatient.HisPrintTemplateMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 住院打印服务: 打印模板管理(启动幂等种入8个内置HTML模板) + 日清单/结算单/医嘱单/护理记录/病历/腕带六类HTML渲染。
 * 模板语法(简单占位符, {@link #render(String, Map)} 实现):
 * - 变量: ${key} 替换为数据键值(未命中替换为空串);
 * - 循环: #items ... /items 块内按列表逐项渲染, 项内占位符优先取行数据(指令键以字母开头);
 * - 条件: ?key ... /key 仅当 key 非空(数值0/空串/空集合视为空)时保留块内容, 否则整块移除;
 * - 模板书写约束: CSS 仅用类选择器、色值用数字开头(#333/#666)或确认无同名闭合指令的写法。
 * 说明: JdbcTemplate 手写 SQL 不走 MyBatis-Plus 租户插件, 必须显式带 tenant_id AND deleted=0;
 * 模板种子按租户逐个补种(JdbcTemplate 显式写 tenant_id 与 org_id=0 机构通用), 表未建时跳过下次启动补种。
 */
@Slf4j
@Service
public class InpPrintService {

    /** 费用类别名称(与 InpChargeService 同口径): 1西药 2中药 3检查 4检验 5治疗 6护理 7材料 8床位 9其他 */
    private static final Map<Integer, String> FEE_TYPE_NAMES = new LinkedHashMap<>();

    /** 医嘱状态名称: 1新开 2已审核 3执行中 4已完成 5已停止 6已作废 */
    private static final Map<Integer, String> ORDER_STATUS_NAMES = new LinkedHashMap<>();

    /** 护理记录类型名称: 1体温单 2护理评估 3护理计划 4护理措施 5护理总结 */
    private static final Map<Integer, String> NURSING_TYPE_NAMES = new LinkedHashMap<>();

    /** 病历类型名称(九类文书) */
    private static final Map<Integer, String> MED_RECORD_TYPES = new LinkedHashMap<>();

    /** 完整时间(结算/打印时间) */
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 分钟级时间(医嘱/护理记录) */
    private static final DateTimeFormatter DT_SHORT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 模板循环块指令: #key ... /key */
    private static final Pattern LOOP_PATTERN = Pattern.compile("#([a-zA-Z][a-zA-Z0-9_]*)([\\s\\S]*?)/\\1");

    /** 模板条件块指令: ?key ... /key */
    private static final Pattern COND_PATTERN = Pattern.compile("\\?([a-zA-Z][a-zA-Z0-9_]*)([\\s\\S]*?)/\\1");

    /** 模板变量指令: ${key} */
    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{([a-zA-Z][a-zA-Z0-9_]*)\\}");

    static {
        FEE_TYPE_NAMES.put(1, "西药");
        FEE_TYPE_NAMES.put(2, "中药");
        FEE_TYPE_NAMES.put(3, "检查");
        FEE_TYPE_NAMES.put(4, "检验");
        FEE_TYPE_NAMES.put(5, "治疗");
        FEE_TYPE_NAMES.put(6, "护理");
        FEE_TYPE_NAMES.put(7, "材料");
        FEE_TYPE_NAMES.put(8, "床位");
        FEE_TYPE_NAMES.put(9, "其他");

        ORDER_STATUS_NAMES.put(1, "新开");
        ORDER_STATUS_NAMES.put(2, "已审核");
        ORDER_STATUS_NAMES.put(3, "执行中");
        ORDER_STATUS_NAMES.put(4, "已完成");
        ORDER_STATUS_NAMES.put(5, "已停止");
        ORDER_STATUS_NAMES.put(6, "已作废");

        NURSING_TYPE_NAMES.put(1, "体温单");
        NURSING_TYPE_NAMES.put(2, "护理评估");
        NURSING_TYPE_NAMES.put(3, "护理计划");
        NURSING_TYPE_NAMES.put(4, "护理措施");
        NURSING_TYPE_NAMES.put(5, "护理总结");

        MED_RECORD_TYPES.put(1, "入院记录");
        MED_RECORD_TYPES.put(2, "首次病程");
        MED_RECORD_TYPES.put(3, "日常病程");
        MED_RECORD_TYPES.put(4, "查房记录");
        MED_RECORD_TYPES.put(5, "术前小结");
        MED_RECORD_TYPES.put(6, "手术记录");
        MED_RECORD_TYPES.put(7, "术后病程");
        MED_RECORD_TYPES.put(8, "出院小结");
        MED_RECORD_TYPES.put(9, "死亡记录");
    }

    private final HisPrintTemplateMapper templateMapper;
    private final HisInpVisitMapper visitMapper;
    private final HisInpChargeDetailMapper chargeMapper;
    private final HisInpSettleMapper settleMapper;
    private final HisInpOrderMapper orderMapper;
    private final HisInpNursingRecordMapper nursingMapper;
    private final HisInpMedicalRecordMapper medicalRecordMapper;
    private final OrgAccessGuard guard;
    private final JdbcTemplate jdbcTemplate;

    public InpPrintService(HisPrintTemplateMapper templateMapper, HisInpVisitMapper visitMapper,
                           HisInpChargeDetailMapper chargeMapper, HisInpSettleMapper settleMapper,
                           HisInpOrderMapper orderMapper, HisInpNursingRecordMapper nursingMapper,
                           HisInpMedicalRecordMapper medicalRecordMapper, OrgAccessGuard guard,
                           JdbcTemplate jdbcTemplate) {
        this.templateMapper = templateMapper;
        this.visitMapper = visitMapper;
        this.chargeMapper = chargeMapper;
        this.settleMapper = settleMapper;
        this.orderMapper = orderMapper;
        this.nursingMapper = nursingMapper;
        this.medicalRecordMapper = medicalRecordMapper;
        this.guard = guard;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ==================== 内置打印模板(HTML+内联CSS, 占位符语法见类注释) ==================== */

    /** 模板公共CSS: A4纸张基线/表格/签名区, 含 @media print 优化 */
    private static final String TPL_CSS =
            "* { margin:0; padding:0; box-sizing:border-box; }\n"
            + "body { font-family:'SimSun','Microsoft YaHei',serif; color:#000; font-size:12pt; background:#fff; }\n"
            + ".doc { width:186mm; margin:0 auto; padding:8mm 6mm; }\n"
            + ".doc-title { text-align:center; font-size:17pt; font-weight:bold; letter-spacing:2px;"
            + " font-family:'SimHei','Microsoft YaHei',sans-serif; }\n"
            + ".doc-sub { text-align:center; font-size:10.5pt; color:#333; margin:3px 0 8px; }\n"
            + "table { width:100%; border-collapse:collapse; margin-top:6px; }\n"
            + "th, td { border:1px solid #000; padding:3px 5px; font-size:10.5pt; text-align:center;"
            + " word-break:break-all; }\n"
            + "th { background:#eee; font-weight:bold; }\n"
            + "td.lt { text-align:left; }\n"
            + ".info { margin-top:8px; font-size:10.5pt; line-height:2; }\n"
            + ".info span { margin-right:22px; white-space:nowrap; }\n"
            + ".total { text-align:right; font-size:11.5pt; margin-top:8px; font-weight:bold; }\n"
            + ".sign { margin-top:18px; text-align:right; font-size:10.5pt; line-height:2.2; }\n"
            + ".memo { margin-top:8px; font-size:9.5pt; color:#666; line-height:1.8; }\n"
            + "@media print { body { background:none; } .doc { width:auto; padding:0; }"
            + " th { -webkit-print-color-adjust:exact; print-color-adjust:exact; } }\n";

    /** 1. PRINT_DAILY_BILL 住院每日费用清单(A4 纵向) */
    private static final String TPL_DAILY_BILL =
            "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n"
            + "<title>住院每日费用清单</title>\n<style>\n" + TPL_CSS + "</style>\n</head>\n<body>\n"
            + "<div class=\"doc\">\n"
            + "  <div class=\"doc-title\">住院患者每日费用清单</div>\n"
            + "  <div class=\"doc-sub\">${hospitalName}</div>\n"
            + "  <div class=\"info\">\n"
            + "    <span>姓名：${patientName}</span><span>性别：${gender}</span><span>年龄：${age}岁</span>\n"
            + "    <span>住院号：${inpNo}</span><span>科室：${deptName}</span><span>床号：${bedNo}</span>\n"
            + "    <span>入院日期：${admitDate}</span><span>清单日期：${billDate}</span>\n"
            + "  </div>\n"
            + "  <table>\n"
            + "    <thead><tr><th style=\"width:7%\">序号</th><th style=\"width:14%\">项目编码</th>"
            + "<th>项目名称</th><th style=\"width:10%\">费用类别</th><th style=\"width:9%\">数量</th>"
            + "<th style=\"width:11%\">单价(元)</th><th style=\"width:11%\">金额(元)</th></tr></thead>\n"
            + "    <tbody>\n"
            + "#items\n"
            + "      <tr><td>${index}</td><td>${itemCode}</td><td class=\"lt\">${itemName}</td>"
            + "<td>${feeTypeName}</td><td>${quantity}</td><td>${unitPrice}</td><td>${amount}</td></tr>\n"
            + "/items\n"
            + "    </tbody>\n"
            + "  </table>\n"
            + "  <div class=\"total\">当日合计：${totalAmount} 元（共 ${itemCount} 项）</div>\n"
            + "  <div class=\"info\"><span>在院累计费用：${cumulativeAmount} 元</span>"
            + "<span>预交金余额：${depositBalance} 元</span></div>\n"
            + "  <div class=\"sign\">患者/家属签字：______________　　打印时间：${printTime}</div>\n"
            + "  <div class=\"memo\">注：本清单为每日费用公示单据，最终金额以出院结算单为准。</div>\n"
            + "</div>\n</body>\n</html>";

    /** 2. PRINT_SETTLE 住院结算单(A4 纵向) */
    private static final String TPL_SETTLE =
            "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n"
            + "<title>住院费用结算单</title>\n<style>\n" + TPL_CSS + "</style>\n</head>\n<body>\n"
            + "<div class=\"doc\">\n"
            + "  <div class=\"doc-title\">住院费用结算单</div>\n"
            + "  <div class=\"doc-sub\">${hospitalName}</div>\n"
            + "  <div class=\"info\">\n"
            + "    <span>姓名：${patientName}</span><span>性别：${gender}</span><span>年龄：${age}岁</span>\n"
            + "    <span>住院号：${inpNo}</span><span>科室：${deptName}</span><span>床号：${bedNo}</span>\n"
            + "    <span>入院日期：${admitDate}</span><span>出院日期：${dischargeDate}</span>"
            + "<span>住院天数：${daysOfStay}天</span>\n"
            + "  </div>\n"
            + "  <table>\n"
            + "    <thead><tr><th>费用分类汇总</th><th style=\"width:28%\">金额(元)</th></tr></thead>\n"
            + "    <tbody>\n"
            + "#cats\n"
            + "      <tr><td class=\"lt\">${name}</td><td>${amount}</td></tr>\n"
            + "/cats\n"
            + "      <tr><td class=\"lt\"><b>费用总额</b></td><td><b>${totalAmount}</b></td></tr>\n"
            + "    </tbody>\n"
            + "  </table>\n"
            + "  <table>\n"
            + "    <thead><tr><th colspan=\"2\">结算信息</th></tr></thead>\n"
            + "    <tbody>\n"
            + "?ybFlag\n"
            + "      <tr><td class=\"lt\">医保基金支付</td><td>${fundPay} 元</td></tr>\n"
            + "      <tr><td class=\"lt\">个人账户支付</td><td>${acctPay} 元</td></tr>\n"
            + "      <tr><td class=\"lt\">个人负担金额</td><td>${selfPay} 元</td></tr>\n"
            + "/ybFlag\n"
            + "      <tr><td class=\"lt\">现金支付</td><td>${cashPay} 元</td></tr>\n"
            + "      <tr><td class=\"lt\">预交金抵扣</td><td>${depositDeduct} 元</td></tr>\n"
            + "?refundAmount\n"
            + "      <tr><td class=\"lt\">退还预交金</td><td>${refundAmount} 元</td></tr>\n"
            + "/refundAmount\n"
            + "    </tbody>\n"
            + "  </table>\n"
            + "  <div class=\"sign\">\n"
            + "    结算单号：${settleNo}　　结算时间：${settleTime}<br/>\n"
            + "    收费员：${operatorName}　　患者/家属签字：______________\n"
            + "  </div>\n"
            + "  <div class=\"memo\">注：本结算单金额以出院结算为准，医保支付明细以医保平台结算回执为准。</div>\n"
            + "</div>\n</body>\n</html>";

    /** 3. PRINT_ORDER_LONG 长期医嘱单(A4 纵向) */
    private static final String TPL_ORDER_LONG =
            "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n"
            + "<title>长期医嘱单</title>\n<style>\n" + TPL_CSS
            + "td.sm { font-size:9pt; color:#666; }\n</style>\n</head>\n<body>\n"
            + "<div class=\"doc\">\n"
            + "  <div class=\"doc-title\">长期医嘱单</div>\n"
            + "  <div class=\"doc-sub\">${hospitalName}</div>\n"
            + "  <div class=\"info\">\n"
            + "    <span>姓名：${patientName}</span><span>性别：${gender}</span><span>年龄：${age}岁</span>\n"
            + "    <span>住院号：${inpNo}</span><span>科室：${deptName}</span><span>床号：${bedNo}</span>\n"
            + "  </div>\n"
            + "  <table>\n"
            + "    <thead><tr><th style=\"width:13%\">开嘱时间</th><th>医嘱内容</th>"
            + "<th style=\"width:8%\">频次</th><th style=\"width:9%\">用法</th><th style=\"width:9%\">开嘱医生</th>"
            + "<th style=\"width:9%\">执行护士</th><th style=\"width:13%\">停止时间</th></tr></thead>\n"
            + "    <tbody>\n"
            + "#orders\n"
            + "      <tr><td>${startDate}</td><td class=\"lt\">${content}\n"
            + "?spec<br/><span class=\"sm\">${spec}　${dosage}</span>\n/spec\n"
            + "      </td><td>${freq}</td><td>${usage}</td><td>${doctor}</td><td>${nurse}</td>"
            + "<td>${stopTime}</td></tr>\n"
            + "/orders\n"
            + "    </tbody>\n"
            + "  </table>\n"
            + "  <div class=\"sign\">医师签名：______________　　护士签名：______________</div>\n"
            + "  <div class=\"memo\">注：长期医嘱自开嘱时间起执行，停止时间空白表示尚未停止。</div>\n"
            + "</div>\n</body>\n</html>";

    /** 4. PRINT_ORDER_TEMP 临时医嘱单(A4 纵向) */
    private static final String TPL_ORDER_TEMP =
            "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n"
            + "<title>临时医嘱单</title>\n<style>\n" + TPL_CSS
            + "td.sm { font-size:9pt; color:#666; }\n</style>\n</head>\n<body>\n"
            + "<div class=\"doc\">\n"
            + "  <div class=\"doc-title\">临时医嘱单</div>\n"
            + "  <div class=\"doc-sub\">${hospitalName}</div>\n"
            + "  <div class=\"info\">\n"
            + "    <span>姓名：${patientName}</span><span>性别：${gender}</span><span>年龄：${age}岁</span>\n"
            + "    <span>住院号：${inpNo}</span><span>科室：${deptName}</span><span>床号：${bedNo}</span>\n"
            + "  </div>\n"
            + "  <table>\n"
            + "    <thead><tr><th style=\"width:15%\">开嘱时间</th><th>医嘱内容</th>"
            + "<th style=\"width:8%\">频次</th><th style=\"width:9%\">用法</th><th style=\"width:10%\">开嘱医生</th>"
            + "<th style=\"width:9%\">状态</th></tr></thead>\n"
            + "    <tbody>\n"
            + "#orders\n"
            + "      <tr><td>${startDate}</td><td class=\"lt\">${content}\n"
            + "?spec<br/><span class=\"sm\">${spec}　${dosage}</span>\n/spec\n"
            + "      </td><td>${freq}</td><td>${usage}</td><td>${doctor}</td><td>${statusText}</td></tr>\n"
            + "/orders\n"
            + "    </tbody>\n"
            + "  </table>\n"
            + "  <div class=\"sign\">医师签名：______________　　护士签名：______________</div>\n"
            + "  <div class=\"memo\">注：临时医嘱开立即执行，执行情况以护士站执行记录为准。</div>\n"
            + "</div>\n</body>\n</html>";

    /** 5. PRINT_NURSING 护理记录单(A4 纵向) */
    private static final String TPL_NURSING =
            "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n"
            + "<title>护理记录单</title>\n<style>\n" + TPL_CSS + "</style>\n</head>\n<body>\n"
            + "<div class=\"doc\">\n"
            + "  <div class=\"doc-title\">护理记录单</div>\n"
            + "  <div class=\"doc-sub\">${hospitalName}</div>\n"
            + "  <div class=\"info\">\n"
            + "    <span>姓名：${patientName}</span><span>性别：${gender}</span><span>年龄：${age}岁</span>\n"
            + "    <span>住院号：${inpNo}</span><span>科室：${deptName}</span><span>床号：${bedNo}</span>\n"
            + "    <span>护理等级：${nursingLevel}</span>\n"
            + "  </div>\n"
            + "  <div class=\"doc-sub\">记录区间：${startDate} 至 ${endDate}</div>\n"
            + "  <table>\n"
            + "    <thead><tr><th style=\"width:13%\">日期时间</th><th style=\"width:8%\">体温(℃)</th>"
            + "<th style=\"width:8%\">脉搏(次/分)</th><th style=\"width:8%\">呼吸(次/分)</th>"
            + "<th style=\"width:11%\">血压(mmHg)</th><th>护理措施/病情观察</th>"
            + "<th style=\"width:9%\">签名</th></tr></thead>\n"
            + "    <tbody>\n"
            + "#records\n"
            + "      <tr><td>${time}</td><td>${temperature}</td><td>${pulse}</td><td>${respiration}</td>"
            + "<td>${bloodPressure}</td><td class=\"lt\">${measures}</td><td>${nurseName}</td></tr>\n"
            + "/records\n"
            + "    </tbody>\n"
            + "  </table>\n"
            + "  <div class=\"sign\">护士签名：______________　　打印时间：${printTime}</div>\n"
            + "</div>\n</body>\n</html>";

    /** 6. PRINT_TEMP_CHART 体温单(A4 横向, 7天网格 + 出入量记录区) */
    private static final String TPL_TEMP_CHART =
            "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n"
            + "<title>体温单</title>\n<style>\n"
            + "* { margin:0; padding:0; box-sizing:border-box; }\n"
            + "body { font-family:'SimSun','Microsoft YaHei',serif; color:#000; font-size:11pt; background:#fff; }\n"
            + "@page { size: A4 landscape; margin:8mm; }\n"
            + ".doc { width:277mm; margin:0 auto; }\n"
            + ".doc-title { text-align:center; font-size:16pt; font-weight:bold; letter-spacing:4px;"
            + " font-family:'SimHei','Microsoft YaHei',sans-serif; }\n"
            + ".doc-sub { text-align:center; font-size:10.5pt; color:#333; margin:3px 0 6px; }\n"
            + "table { width:100%; border-collapse:collapse; margin-top:5px; }\n"
            + "th, td { border:1px solid #000; padding:2px 3px; font-size:9.5pt; text-align:center;"
            + " height:7.2mm; word-break:break-all; }\n"
            + "th { background:#eee; font-weight:bold; }\n"
            + ".grid-note { font-size:9pt; color:#666; line-height:1.7; margin-top:4px; }\n"
            + "@media print { body { background:none; } th { -webkit-print-color-adjust:exact;"
            + " print-color-adjust:exact; } }\n"
            + "</style>\n</head>\n<body>\n"
            + "<div class=\"doc\">\n"
            + "  <div class=\"doc-title\">体　温　单</div>\n"
            + "  <div class=\"doc-sub\">${hospitalName}　姓名：${patientName}　性别：${gender}　年龄：${age}岁"
            + "　住院号：${inpNo}　科室：${deptName}　床号：${bedNo}</div>\n"
            + "  <table>\n"
            + "    <thead>\n"
            + "      <tr><th rowspan=\"2\" style=\"width:9%\">项目</th><th colspan=\"7\">日期</th></tr>\n"
            + "      <tr><th>第1天</th><th>第2天</th><th>第3天</th><th>第4天</th><th>第5天</th>"
            + "<th>第6天</th><th>第7天</th></tr>\n"
            + "    </thead>\n"
            + "    <tbody>\n"
            + "      <tr><th>体温(℃)</th><td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr>\n"
            + "      <tr><th>脉搏(次/分)</th><td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr>\n"
            + "      <tr><th>呼吸(次/分)</th><td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr>\n"
            + "      <tr><th>血压(mmHg)</th><td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr>\n"
            + "      <tr><th>体重(kg)</th><td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr>\n"
            + "      <tr><th>大便(次)</th><td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr>\n"
            + "      <tr><th>入量(ml)</th><td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr>\n"
            + "      <tr><th>出量(ml)</th><td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr>\n"
            + "    </tbody>\n"
            + "  </table>\n"
            + "  <div class=\"grid-note\">体温/脉搏曲线绘制区：按日6个时点(2-6-10-14-18-22时)绘制，"
            + "体温蓝线、脉搏红线；曲线由前端打印组件在网格上层绘制后输出。</div>\n"
            + "  <table>\n"
            + "    <thead><tr><th style=\"width:12%\">日期时间</th><th>入量项目及量(ml)</th>"
            + "<th style=\"width:22%\">出量项目及量(ml)</th><th style=\"width:22%\">备注</th></tr></thead>\n"
            + "    <tbody><tr><td></td><td></td><td></td><td></td></tr>\n"
            + "      <tr><td></td><td></td><td></td><td></td></tr>\n"
            + "      <tr><td></td><td></td><td></td><td></td></tr></tbody>\n"
            + "  </table>\n"
            + "  <div class=\"grid-note\" style=\"text-align:right;\">护士签名：______________"
            + "　　打印时间：${printTime}</div>\n"
            + "</div>\n</body>\n</html>";

    /** 7. PRINT_EMR 病历打印(A4 纵向, 结构化字段表 + 签名区) */
    private static final String TPL_EMR =
            "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n"
            + "<title>病历打印</title>\n<style>\n" + TPL_CSS
            + "td.val { text-align:left; line-height:1.9; }\n</style>\n</head>\n<body>\n"
            + "<div class=\"doc\">\n"
            + "  <div class=\"doc-title\">${title}</div>\n"
            + "  <div class=\"doc-sub\">${hospitalName}　病历类型：${recordTypeName}　状态：${statusText}</div>\n"
            + "  <div class=\"info\">\n"
            + "    <span>姓名：${patientName}</span><span>性别：${gender}</span><span>年龄：${age}岁</span>\n"
            + "    <span>住院号：${inpNo}</span><span>科室：${deptName}</span><span>床号：${bedNo}</span>\n"
            + "    <span>主治医生：${doctorName}</span><span>记录时间：${recordTime}</span>\n"
            + "  </div>\n"
            + "  <table>\n"
            + "    <thead><tr><th style=\"width:18%\">项目</th><th>内容</th></tr></thead>\n"
            + "    <tbody>\n"
            + "#fields\n"
            + "      <tr><th>${label}</th><td class=\"val\">${value}</td></tr>\n"
            + "/fields\n"
            + "    </tbody>\n"
            + "  </table>\n"
            + "  <div class=\"sign\">\n"
            + "    记录医师：${doctorName}　　打印时间：${printTime}<br/>\n"
            + "?auditTime\n"
            + "    审核医师：${auditDoctorName}　　审核时间：${auditTime}\n"
            + "/auditTime\n"
            + "  </div>\n"
            + "</div>\n</body>\n</html>";

    /** 8. PRINT_WRISTBAND 腕带(自定义 25mm×200mm 横向, 条码占位 + 过敏警示) */
    private static final String TPL_WRISTBAND =
            "<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n"
            + "<title>患者腕带</title>\n<style>\n"
            + "* { margin:0; padding:0; box-sizing:border-box; }\n"
            + "body { font-family:'SimHei','Microsoft YaHei',sans-serif; color:#000; background:#fff; }\n"
            + "@page { size: 200mm 25mm; margin:0; }\n"
            + ".band { width:200mm; height:25mm; border:0.3mm solid #000; display:flex;"
            + " align-items:center; overflow:hidden; }\n"
            + ".band .warn { background:#000; color:#fff; font-weight:bold; font-size:9pt;"
            + " padding:1mm 2mm; height:100%; display:flex; align-items:center; }\n"
            + ".band .col { flex:1; padding:0 3mm; font-size:9.5pt; line-height:1.6; }\n"
            + ".band .col strong { font-size:13pt; letter-spacing:1px; display:block; }\n"
            + ".band .barcode { flex:0 0 46mm; height:10mm; margin:0 3mm;"
            + " background:repeating-linear-gradient(90deg,#000 0 1.2mm,#fff 1.2mm 2.4mm); }\n"
            + "@media print { body { background:none; } }\n"
            + "</style>\n</head>\n<body>\n"
            + "<div class=\"band\">\n"
            + "?allergyFlag\n"
            + "  <div class=\"warn\">药物过敏<br/>${allergyText}</div>\n"
            + "/allergyFlag\n"
            + "  <div class=\"col\">\n"
            + "    <strong>${patientName}</strong>\n"
            + "    住院号：${inpNo}　床号：${bedNo}\n"
            + "    ${deptName}\n"
            + "?bloodType\n"
            + "　血型：${bloodType}\n/bloodType\n"
            + "  </div>\n"
            + "  <div class=\"barcode\"></div>\n"
            + "</div>\n</body>\n</html>";

    /** 内置模板种子清单(顺序即插入顺序) */
    private static final List<SeedTemplate> SEED_TEMPLATES = buildSeeds();

    /** 模板种子定义(编码/名称/类型/纸张/方向/HTML内容) */
    private static class SeedTemplate {
        final String code;
        final String name;
        final int type;
        final String paper;
        final String orientation;
        final String content;

        SeedTemplate(String code, String name, int type, String paper, String orientation, String content) {
            this.code = code;
            this.name = name;
            this.type = type;
            this.paper = paper;
            this.orientation = orientation;
            this.content = content;
        }
    }

    private static List<SeedTemplate> buildSeeds() {
        List<SeedTemplate> list = new ArrayList<>();
        list.add(new SeedTemplate("PRINT_DAILY_BILL", "住院每日费用清单", 1, "A4", "portrait", TPL_DAILY_BILL));
        list.add(new SeedTemplate("PRINT_SETTLE", "住院结算单", 2, "A4", "portrait", TPL_SETTLE));
        list.add(new SeedTemplate("PRINT_ORDER_LONG", "长期医嘱单", 3, "A4", "portrait", TPL_ORDER_LONG));
        list.add(new SeedTemplate("PRINT_ORDER_TEMP", "临时医嘱单", 3, "A4", "portrait", TPL_ORDER_TEMP));
        list.add(new SeedTemplate("PRINT_NURSING", "护理记录单", 4, "A4", "portrait", TPL_NURSING));
        list.add(new SeedTemplate("PRINT_TEMP_CHART", "体温单", 5, "A4", "landscape", TPL_TEMP_CHART));
        list.add(new SeedTemplate("PRINT_EMR", "病历打印", 6, "A4", "portrait", TPL_EMR));
        list.add(new SeedTemplate("PRINT_WRISTBAND", "患者腕带", 7, "custom(25mm x 200mm)", "landscape",
                TPL_WRISTBAND));
        return list;
    }

    /* ==================== 模板种子 ==================== */

    /**
     * 启动幂等种入内置模板: 按租户逐个检查(sys_tenant 全量), 该租户无任何模板则插入8个
     * (org_id=0 表机构通用)。表未建(全新库首启)时整体跳过, 下次启动补种。
     */
    @PostConstruct
    public void seedTemplates() {
        try {
            List<Long> tenantIds = jdbcTemplate.queryForList(
                    "SELECT id FROM sys_tenant WHERE deleted = 0", Long.class);
            int seededTenants = 0;
            for (Long tid : tenantIds) {
                Integer cnt = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM his_print_template WHERE tenant_id = ?", Integer.class, tid);
                if (cnt != null && cnt > 0) {
                    continue;
                }
                for (SeedTemplate t : SEED_TEMPLATES) {
                    jdbcTemplate.update(
                            "INSERT INTO his_print_template (tenant_id, org_id, template_code, template_name,"
                                    + " template_type, paper_size, orientation, template_content, version, status,"
                                    + " create_time) VALUES (?, 0, ?, ?, ?, ?, ?, ?, 1, 1, NOW())",
                            tid, t.code, t.name, t.type, t.paper, t.orientation, t.content);
                }
                seededTenants++;
            }
            if (seededTenants > 0) {
                log.info("内置打印模板种子完成: {} 个租户 × {} 个模板", seededTenants, SEED_TEMPLATES.size());
            }
        } catch (Exception e) {
            log.warn("打印模板种子跳过(可能表未建, 下次启动补种): {}", e.getMessage());
        }
    }

    /* ==================== 模板管理 ==================== */

    /** 模板列表(按类型可选筛选)。 */
    public R<List<HisPrintTemplate>> listTemplates(Integer type) {
        return R.ok(templateMapper.selectList(new LambdaQueryWrapper<HisPrintTemplate>()
                .eq(type != null, HisPrintTemplate::getTemplateType, type)
                .orderByAsc(HisPrintTemplate::getTemplateType)
                .orderByAsc(HisPrintTemplate::getTemplateCode)));
    }

    /** 按编码获取模板。 */
    public R<HisPrintTemplate> getTemplate(String code) {
        return R.ok(requireTemplate(code));
    }

    /** 保存模板(编码已存在则更新并版本号+1, 否则新增)。 */
    public R<HisPrintTemplate> saveTemplate(PrintTemplateDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getTemplateCode())) {
            throw new BizException(400, "模板编码不能为空");
        }
        if (!StringUtils.hasText(dto.getTemplateName())) {
            throw new BizException(400, "模板名称不能为空");
        }
        HisPrintTemplate exist = templateMapper.selectOne(new LambdaQueryWrapper<HisPrintTemplate>()
                .eq(HisPrintTemplate::getTemplateCode, dto.getTemplateCode().trim())
                .last("LIMIT 1"));
        if (exist == null) {
            HisPrintTemplate t = new HisPrintTemplate();
            t.setOrgId(guard.currentOrgId());
            t.setTemplateCode(dto.getTemplateCode().trim());
            t.setTemplateName(dto.getTemplateName().trim());
            t.setTemplateType(dto.getTemplateType());
            t.setPaperSize(StringUtils.hasText(dto.getPaperSize()) ? dto.getPaperSize() : "A4");
            t.setOrientation(StringUtils.hasText(dto.getOrientation()) ? dto.getOrientation() : "portrait");
            t.setTemplateContent(dto.getTemplateContent());
            t.setHeaderHtml(dto.getHeaderHtml());
            t.setFooterHtml(dto.getFooterHtml());
            t.setCssStyle(dto.getCssStyle());
            t.setVersion(1);
            t.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
            templateMapper.insert(t);
            log.info("新增打印模板: code={}, name={}, type={}", t.getTemplateCode(), t.getTemplateName(),
                    t.getTemplateType());
            return R.ok(t);
        }
        HisPrintTemplate upd = new HisPrintTemplate();
        upd.setId(exist.getId());
        upd.setTemplateName(dto.getTemplateName().trim());
        if (dto.getTemplateType() != null) {
            upd.setTemplateType(dto.getTemplateType());
        }
        if (StringUtils.hasText(dto.getPaperSize())) {
            upd.setPaperSize(dto.getPaperSize());
        }
        if (StringUtils.hasText(dto.getOrientation())) {
            upd.setOrientation(dto.getOrientation());
        }
        if (dto.getTemplateContent() != null) {
            upd.setTemplateContent(dto.getTemplateContent());
        }
        if (dto.getHeaderHtml() != null) {
            upd.setHeaderHtml(dto.getHeaderHtml());
        }
        if (dto.getFooterHtml() != null) {
            upd.setFooterHtml(dto.getFooterHtml());
        }
        if (dto.getCssStyle() != null) {
            upd.setCssStyle(dto.getCssStyle());
        }
        if (dto.getStatus() != null) {
            upd.setStatus(dto.getStatus());
        }
        upd.setVersion((exist.getVersion() == null ? 1 : exist.getVersion()) + 1);
        templateMapper.updateById(upd);
        log.info("更新打印模板: code={}, version={}", exist.getTemplateCode(), upd.getVersion());
        return R.ok(templateMapper.selectById(exist.getId()));
    }

    /* ==================== 日清单渲染 ==================== */

    /** 渲染住院每日费用清单HTML(当日明细 + 累计 + 预交金余额)。 */
    public R<String> renderDailyBill(Long visitId, LocalDate date) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        if (date == null) {
            throw new BizException(400, "清单日期不能为空");
        }
        HisInpVisit visit = requireVisit(visitId);
        HisPrintTemplate tpl = requireTemplate("PRINT_DAILY_BILL");
        Map<String, Object> data = baseData(visit);
        List<HisInpChargeDetail> details = chargeMapper.selectList(new LambdaQueryWrapper<HisInpChargeDetail>()
                .eq(HisInpChargeDetail::getInpVisitId, visitId)
                .eq(HisInpChargeDetail::getChargeDate, date)
                .eq(HisInpChargeDetail::getStatus, 1)
                .orderByAsc(HisInpChargeDetail::getId));
        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        int index = 1;
        for (HisInpChargeDetail d : details) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("index", index++);
            it.put("itemName", nvls(d.getItemName()));
            it.put("itemCode", nvls(d.getItemCode()));
            it.put("feeTypeName", FEE_TYPE_NAMES.getOrDefault(
                    d.getFeeType() == null ? 0 : d.getFeeType(), "其他"));
            it.put("quantity", d.getQuantity() == null ? "" : d.getQuantity().toPlainString());
            it.put("unitPrice", money(d.getUnitPrice()));
            it.put("amount", money(d.getAmount()));
            items.add(it);
            totalAmount = totalAmount.add(nvl(d.getAmount()));
        }
        BigDecimal cumulative = jdbcTemplate.queryForObject(
                "SELECT IFNULL(SUM(amount), 0) FROM his_inp_charge_detail"
                        + " WHERE inp_visit_id = ? AND charge_date <= ? AND status = 1 AND deleted = 0"
                        + " AND tenant_id = ?",
                BigDecimal.class, visitId, date, tenantId());
        data.put("billDate", date.toString());
        data.put("items", items);
        data.put("itemCount", items.size());
        data.put("totalAmount", money(totalAmount));
        data.put("cumulativeAmount", money(cumulative));
        data.put("depositBalance", money(visit.getDepositBalance()));
        return R.ok(render(tpl.getTemplateContent(), data));
    }

    /* ==================== 结算单渲染 ==================== */

    /** 渲染住院结算单HTML(费用分类汇总 + 医保支付明细 + 个人自付)。 */
    public R<String> renderSettlement(Long settleId) {
        if (settleId == null) {
            throw new BizException(400, "结算ID不能为空");
        }
        HisInpSettle settle = settleMapper.selectById(settleId);
        if (settle == null) {
            throw new BizException(404, "住院结算记录不存在");
        }
        HisInpVisit visit = requireVisit(settle.getInpVisitId());
        HisPrintTemplate tpl = requireTemplate("PRINT_SETTLE");
        Map<String, Object> data = baseData(visit);
        // 费用分类汇总(该就诊全部正常明细按类别)
        List<Map<String, Object>> cats = new ArrayList<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(
                "SELECT fee_type, IFNULL(SUM(amount), 0) total_amount FROM his_inp_charge_detail"
                        + " WHERE inp_visit_id = ? AND status = 1 AND deleted = 0 AND tenant_id = ?"
                        + " GROUP BY fee_type ORDER BY fee_type",
                settle.getInpVisitId(), tenantId())) {
            Map<String, Object> c = new LinkedHashMap<>();
            int feeType = row.get("fee_type") == null ? 0 : ((Number) row.get("fee_type")).intValue();
            c.put("name", FEE_TYPE_NAMES.getOrDefault(feeType, "其他"));
            c.put("amount", money(row.get("total_amount")));
            cats.add(c);
        }
        data.put("cats", cats);
        data.put("daysOfStay", visit.getAdmitDate() != null && visit.getDischargeDate() != null
                ? String.valueOf(Math.max(ChronoUnit.DAYS.between(visit.getAdmitDate(),
                        visit.getDischargeDate()), 0L)) : "");
        data.put("totalAmount", money(settle.getTotalAmount()));
        data.put("fundPay", money(settle.getFundPay()));
        data.put("acctPay", money(settle.getAcctPay()));
        data.put("selfPay", money(settle.getSelfPay()));
        data.put("cashPay", money(settle.getCashPay()));
        data.put("depositDeduct", money(settle.getDepositDeduct()));
        data.put("refundAmount", money(settle.getRefundAmount()));
        data.put("ybFlag", StringUtils.hasText(visit.getPsnNo()) && StringUtils.hasText(visit.getMdtrtId())
                ? 1 : 0);
        data.put("settleNo", nvls(settle.getSettleNo()));
        data.put("settleTime", settle.getSettleTime() == null ? "" : settle.getSettleTime().format(DT));
        data.put("operatorName", staffName(settle.getOperatorId()));
        return R.ok(render(tpl.getTemplateContent(), data));
    }

    /* ==================== 医嘱单渲染 ==================== */

    /** 渲染医嘱单HTML(orderType: 1长期 2临时)。 */
    public R<String> renderOrders(Long visitId, Integer orderType) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        if (orderType == null || (orderType != 1 && orderType != 2)) {
            throw new BizException(400, "医嘱类型必须为1长期/2临时");
        }
        HisInpVisit visit = requireVisit(visitId);
        HisPrintTemplate tpl = requireTemplate(orderType == 1 ? "PRINT_ORDER_LONG" : "PRINT_ORDER_TEMP");
        Map<String, Object> data = baseData(visit);
        List<HisInpOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<HisInpOrder>()
                .eq(HisInpOrder::getInpVisitId, visitId)
                .eq(HisInpOrder::getOrderType, orderType)
                .orderByAsc(HisInpOrder::getStartTime)
                .orderByAsc(HisInpOrder::getId));
        Set<Long> staffIds = new HashSet<>();
        for (HisInpOrder o : orders) {
            if (o.getDoctorId() != null) {
                staffIds.add(o.getDoctorId());
            }
            if (o.getAuditNurseId() != null) {
                staffIds.add(o.getAuditNurseId());
            }
            if (o.getStopDoctorId() != null) {
                staffIds.add(o.getStopDoctorId());
            }
            if (o.getStopNurseId() != null) {
                staffIds.add(o.getStopNurseId());
            }
        }
        Map<Long, String> staffNames = staffNames(staffIds);
        List<Map<String, Object>> items = new ArrayList<>();
        for (HisInpOrder o : orders) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("startDate", o.getStartTime() == null ? "" : o.getStartTime().format(DT_SHORT));
            it.put("content", nvls(o.getOrderContent()));
            it.put("spec", nvls(o.getSpec()));
            it.put("dosage", (nvls(o.getDosage()) + " " + nvls(o.getDosageUnit())).trim());
            it.put("freq", nvls(o.getFreqCode()));
            it.put("usage", nvls(o.getUsageCode()));
            it.put("doctor", staffNames.getOrDefault(o.getDoctorId(), ""));
            it.put("nurse", staffNames.getOrDefault(o.getAuditNurseId(), ""));
            it.put("stopTime", o.getStopTime() == null ? "" : o.getStopTime().format(DT_SHORT));
            it.put("statusText", ORDER_STATUS_NAMES.getOrDefault(o.getOrderStatus(), ""));
            items.add(it);
        }
        data.put("orders", items);
        return R.ok(render(tpl.getTemplateContent(), data));
    }

    /* ==================== 护理记录单渲染 ==================== */

    /** 渲染护理记录单HTML(区间缺省最近7天, 体征取自体温单记录JSON, 其余类型按内容文本)。 */
    public R<String> renderNursingRecord(Long visitId, LocalDate startDate, LocalDate endDate) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        LocalDate start = startDate != null ? startDate : LocalDate.now().minusDays(6);
        LocalDate end = endDate != null ? endDate : LocalDate.now();
        if (end.isBefore(start)) {
            throw new BizException(400, "结束日期不能早于开始日期");
        }
        HisInpVisit visit = requireVisit(visitId);
        HisPrintTemplate tpl = requireTemplate("PRINT_NURSING");
        Map<String, Object> data = baseData(visit);
        List<HisInpNursingRecord> records = nursingMapper.selectList(
                new LambdaQueryWrapper<HisInpNursingRecord>()
                        .eq(HisInpNursingRecord::getInpVisitId, visitId)
                        .ge(HisInpNursingRecord::getRecordTime, start.atStartOfDay())
                        .le(HisInpNursingRecord::getRecordTime, end.atTime(LocalTime.MAX))
                        .orderByAsc(HisInpNursingRecord::getRecordTime));
        Set<Long> nurseIds = new HashSet<>();
        for (HisInpNursingRecord r : records) {
            if (r.getNurseId() != null) {
                nurseIds.add(r.getNurseId());
            }
        }
        Map<Long, String> staffNames = staffNames(nurseIds);
        List<Map<String, Object>> items = new ArrayList<>();
        for (HisInpNursingRecord r : records) {
            items.add(nursingRow(r, staffNames));
        }
        data.put("startDate", start.toString());
        data.put("endDate", end.toString());
        data.put("nursingLevel", nursingLevelText(visit.getNursingLevel()));
        data.put("records", items);
        return R.ok(render(tpl.getTemplateContent(), data));
    }

    /* ==================== 病历渲染 ==================== */

    /** 渲染病历HTML(structureData 结构化字段优先, 缺失回落 content JSON/原文)。 */
    public R<String> renderEmr(Long recordId) {
        if (recordId == null) {
            throw new BizException(400, "病历ID不能为空");
        }
        HisInpMedicalRecord record = medicalRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BizException(404, "病历记录不存在");
        }
        HisInpVisit visit = requireVisit(record.getInpVisitId());
        HisPrintTemplate tpl = requireTemplate("PRINT_EMR");
        Map<String, Object> data = baseData(visit);
        data.put("title", nvls(record.getTitle()));
        data.put("recordTypeName", MED_RECORD_TYPES.getOrDefault(record.getRecordType(), "病历文书"));
        data.put("statusText", record.getStatus() == null ? ""
                : record.getStatus() == 1 ? "草稿"
                : record.getStatus() == 2 ? "已提交" : "已审核");
        data.put("recordTime", record.getRecordTime() == null ? "" : record.getRecordTime().format(DT));
        data.put("doctorName", staffName(record.getDoctorId()));
        data.put("auditDoctorName", staffName(record.getAuditDoctorId()));
        data.put("auditTime", record.getAuditTime() == null ? "" : record.getAuditTime().format(DT));
        data.put("fields", parseEmrFields(record));
        return R.ok(render(tpl.getTemplateContent(), data));
    }

    /* ==================== 腕带渲染 ==================== */

    /** 渲染患者腕带HTML(姓名/住院号/科室/床号/血型/过敏警示 + Code128B条码增强)。 */
    public R<String> renderWristband(Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        HisInpVisit visit = requireVisit(visitId);
        HisPrintTemplate tpl = requireTemplate("PRINT_WRISTBAND");
        Map<String, Object> data = baseData(visit);
        List<String> allergens = jdbcTemplate.queryForList(
                "SELECT allergen_name FROM his_inp_allergy WHERE inp_visit_id = ? AND status = 1"
                        + " AND deleted = 0 AND tenant_id = ?",
                String.class, visitId, tenantId());
        data.put("allergyFlag", allergens.isEmpty() ? 0 : 1);
        data.put("allergyText", String.join("、", allergens));
        String html = render(tpl.getTemplateContent(), data);

        /* T48 P3 腕带条码增强: 不改模板常量(TPL_WRISTBAND), 对渲染结果做后处理 ——
         * 将装饰性渐变条码占位替换为 Code128B 竖线序列 + 大号等宽住院号(打印不执行 JS, 纯 HTML/CSS 实现)。 */
        String barcodePlaceholder = "<div class=\"barcode\"></div>";
        String inpNo = nvls(data.get("inpNo")).trim();
        if (html.contains(barcodePlaceholder) && !inpNo.isEmpty()) {
            /* Code128B 模式表(索引 0-102 数据/起始, 103-105 START A/B/C, 106 STOP; 每项数字交替表示条/空模块宽) */
            final String[] pats = {
                    "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212",
                    "221213", "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221",
                    "223211", "221132", "221231", "213212", "223112", "312131", "311222", "321122", "321221",
                    "312212", "322112", "322211", "212123", "212321", "232121", "111323", "131123", "131321",
                    "112313", "132113", "132311", "211313", "231113", "231311", "112133", "112331", "132131",
                    "113123", "113321", "133121", "313121", "211331", "231131", "213113", "213311", "213131",
                    "311123", "311321", "331121", "312113", "312311", "332111", "314111", "221411", "431111",
                    "111224", "111422", "121124", "121421", "141122", "141221", "112214", "112412", "122114",
                    "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111", "111242",
                    "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
                    "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311",
                    "113141", "114131", "311141", "411131",
                    "211412", "211214", "211232",
                    "2331112"
            };
            /* 值序列: START B(104) + (ASCII-32)×n + 校验((104+Σvi×i)%103) + STOP(106) */
            List<Integer> vals = new ArrayList<>();
            vals.add(104);
            int checksum = 104;
            boolean encodable = true;
            for (int i = 0; i < inpNo.length(); i++) {
                int c = inpNo.charAt(i);
                if (c < 32 || c > 126) {
                    encodable = false;
                    break;
                }
                int v = c - 32;
                vals.add(v);
                checksum += v * (i + 1);
            }
            StringBuilder bars = new StringBuilder();
            if (encodable) {
                vals.add(checksum % 103);
                vals.add(106);
                int modules = 0;
                for (int v : vals) {
                    for (int j = 0; j < pats[v].length(); j++) {
                        modules += pats[v].charAt(j) - '0';
                    }
                }
                /* 模块宽自适应: 有效宽 44mm, 0.01mm 整数精度(规避 Locale 相关浮点格式化), 上限 0.5mm */
                int moduleHm = Math.min(500, (int) Math.round(4400.0 / Math.max(modules, 1)));
                for (int v : vals) {
                    String p = pats[v];
                    for (int j = 0; j < p.length(); j++) {
                        int w = moduleHm * (p.charAt(j) - '0');
                        String wmm = (w / 100) + "." + (w % 100 < 10 ? "0" : "") + (w % 100);
                        bars.append(j % 2 == 0
                                ? "<i style=\"display:block;flex:none;height:6mm;width:" + wmm + "mm;background:#000\"></i>"
                                : "<i style=\"display:block;flex:none;height:6mm;width:" + wmm + "mm\"></i>");
                    }
                }
            } else {
                /* 兑底: 住院号含 Code B 不可编码字符(如中文)时, 按字符码值奇偶生成装饰性竖线交替条 */
                for (int i = 0; i < inpNo.length(); i++) {
                    bars.append("<i style=\"display:block;flex:none;height:6mm;width:0.5mm;background:#000\"></i>");
                    if (inpNo.charAt(i) % 2 == 0) {
                        bars.append("<i style=\"display:block;flex:none;height:6mm;width:0.5mm;background:#000\"></i>")
                                .append("<i style=\"display:block;flex:none;height:6mm;width:0.5mm\"></i>");
                    } else {
                        bars.append("<i style=\"display:block;flex:none;height:6mm;width:1mm\"></i>");
                    }
                }
            }
            String safeNo = inpNo.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            String barcodeHtml = "<div class=\"barcode\" style=\"background:#fff;display:flex;"
                    + "flex-direction:column;align-items:center;justify-content:center;\">"
                    + "<div style=\"display:flex;align-items:flex-end;\">" + bars + "</div>"
                    + "<div style=\"font-family:'Courier New',Consolas,monospace;font-size:7.5pt;"
                    + "font-weight:bold;letter-spacing:0.3mm;color:#000;white-space:nowrap;margin-top:0.5mm;\">"
                    + safeNo + "</div></div>";
            html = html.replace(barcodePlaceholder, barcodeHtml);
        }
        return R.ok(html);
    }

    /* ==================== 模板渲染引擎 ==================== */

    /**
     * 简单模板渲染: 先展开循环块(#key.../key, 列表逐项递归渲染, 项字段覆盖外层同名键),
     * 再展开条件块(?key.../key, 键非空保留), 最后替换 ${key} 变量(未命中替换为空串)。
     */
    private String render(String template, Map<String, Object> data) {
        if (template == null) {
            return "";
        }
        String out = expandLoops(template, data);
        out = expandConditionals(out, data);
        return substitute(out, data);
    }

    /** 循环块: 数据键为 List 时逐项渲染块内容, 否则原样保留(兼容 CSS 色值等非指令文本)。 */
    private String expandLoops(String tpl, Map<String, Object> data) {
        Matcher m = LOOP_PATTERN.matcher(tpl);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            Object v = data.get(key);
            if (!(v instanceof List)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            StringBuilder rep = new StringBuilder();
            for (Object item : (List<?>) v) {
                Map<String, Object> row = new LinkedHashMap<>(data);
                if (item instanceof Map) {
                    row.putAll((Map<? extends String, ?>) item);
                } else {
                    row.put("value", item);
                }
                row.remove(key);
                rep.append(render(m.group(2), row));
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 条件块: 键非空保留块内容(占位符后续替换), 键为空/数值0/空集合时整块移除。 */
    private String expandConditionals(String tpl, Map<String, Object> data) {
        Matcher m = COND_PATTERN.matcher(tpl);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1);
            Object v = data.get(key);
            if (isNonEmpty(v)) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(2)));
            } else {
                m.appendReplacement(sb, "");
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 变量替换: ${key} -> stringify(data.get(key))。 */
    private String substitute(String tpl, Map<String, Object> data) {
        Matcher m = VAR_PATTERN.matcher(tpl);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(stringify(data.get(m.group(1)))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 非空判定: null/空串/空集合/数值0 视为空。 */
    private static boolean isNonEmpty(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof String) {
            return StringUtils.hasText((String) v);
        }
        if (v instanceof Collection) {
            return !((Collection<?>) v).isEmpty();
        }
        if (v instanceof Map) {
            return !((Map<?, ?>) v).isEmpty();
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue() != 0d;
        }
        return true;
    }

    /** 值转字符串: 时间按标准格式, BigDecimal 走 toPlainString。 */
    private static String stringify(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof LocalDateTime) {
            return ((LocalDateTime) v).format(DT);
        }
        if (v instanceof LocalDate) {
            return v.toString();
        }
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).toPlainString();
        }
        return String.valueOf(v);
    }

    /* ==================== 内部工具 ==================== */

    /** 模板必读校验: 存在 + 内容非空。 */
    private HisPrintTemplate requireTemplate(String code) {
        HisPrintTemplate tpl = templateMapper.selectOne(new LambdaQueryWrapper<HisPrintTemplate>()
                .eq(HisPrintTemplate::getTemplateCode, code)
                .last("LIMIT 1"));
        if (tpl == null) {
            throw new BizException(404, "打印模板不存在: " + code + ", 请重启应用完成模板种子初始化");
        }
        if (!StringUtils.hasText(tpl.getTemplateContent())) {
            throw new BizException("打印模板内容为空: " + code);
        }
        return tpl;
    }

    /** 就诊存在性校验。 */
    private HisInpVisit requireVisit(Long visitId) {
        HisInpVisit visit = visitMapper.selectById(visitId);
        if (visit == null) {
            throw new BizException(404, "住院就诊记录不存在");
        }
        return visit;
    }

    /** 打印公共数据头: 医院/患者/就诊/打印时间。 */
    private Map<String, Object> baseData(HisInpVisit visit) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hospitalName", orgName(visit.getOrgId()));
        data.put("patientName", "");
        data.put("gender", "");
        data.put("age", "");
        if (visit.getPatientId() != null) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT name, IFNULL(gender_name, gender) gender, age FROM his_patient"
                            + " WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                    visit.getPatientId(), tenantId());
            if (!rows.isEmpty()) {
                Map<String, Object> p = rows.get(0);
                data.put("patientName", nvls(p.get("name")));
                String g = nvls(p.get("gender"));
                data.put("gender", "1".equals(g) ? "男" : "2".equals(g) ? "女" : g);
                data.put("age", p.get("age") == null ? "" : String.valueOf(p.get("age")));
            }
        }
        data.put("inpNo", nvls(visit.getInpNo()));
        data.put("deptName", deptName(visit.getDeptId()));
        data.put("bedNo", bedNo(visit.getBedId()));
        data.put("doctorName", staffName(visit.getDoctorId()));
        data.put("admitDate", visit.getAdmitDate() == null ? "" : visit.getAdmitDate().format(DT));
        data.put("dischargeDate", visit.getDischargeDate() == null ? "" : visit.getDischargeDate().format(DT));
        data.put("bloodType", nvls(visit.getBloodType()));
        data.put("printTime", LocalDateTime.now().format(DT));
        return data;
    }

    /** 护理记录行: 体温单记录解析生命体征, 其余类型把 content JSON 展开为措施文本。 */
    private Map<String, Object> nursingRow(HisInpNursingRecord r, Map<Long, String> staffNames) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", r.getRecordTime() == null ? "" : r.getRecordTime().format(DT_SHORT));
        String temperature = "";
        String pulse = "";
        String respiration = "";
        String bloodPressure = "";
        String measures = "";
        JSONObject json = null;
        if (StringUtils.hasText(r.getContent())) {
            try {
                json = JSON.parseObject(r.getContent());
            } catch (Exception e) {
                json = null;
            }
        }
        if (json != null) {
            temperature = nvls(json.get("temperature"));
            pulse = nvls(json.get("pulse"));
            respiration = nvls(json.get("respiration"));
            Object sys = json.get("systolicBp");
            Object dia = json.get("diastolicBp");
            if (sys != null || dia != null) {
                bloodPressure = nvls(sys) + "/" + nvls(dia);
            } else {
                bloodPressure = nvls(json.get("blood_pressure"));
            }
            StringBuilder ms = new StringBuilder();
            for (Map.Entry<String, Object> e : json.entrySet()) {
                String k = e.getKey();
                if ("time".equals(k) || "temperature".equals(k) || "pulse".equals(k)
                        || "respiration".equals(k) || "systolicBp".equals(k) || "diastolicBp".equals(k)
                        || "blood_pressure".equals(k)) {
                    continue;
                }
                String val = e.getValue() == null ? "" : String.valueOf(e.getValue());
                if (StringUtils.hasText(val)) {
                    if (ms.length() > 0) {
                        ms.append("；");
                    }
                    ms.append(k).append("：").append(val);
                }
            }
            measures = ms.toString();
        } else if (StringUtils.hasText(r.getContent())) {
            measures = r.getContent();
        }
        if (!StringUtils.hasText(measures) && r.getRecordType() != null && r.getRecordType() != 1) {
            measures = NURSING_TYPE_NAMES.getOrDefault(r.getRecordType(), "");
        }
        row.put("temperature", temperature);
        row.put("pulse", pulse);
        row.put("respiration", respiration);
        row.put("bloodPressure", bloodPressure);
        row.put("measures", measures);
        row.put("nurseName", staffNames.getOrDefault(r.getNurseId(), ""));
        return row;
    }

    /** 病历结构化字段解析: structureData 优先, 缺失回落 content; 对象按键值展开, 数组按 label/value 展开。 */
    private List<Map<String, Object>> parseEmrFields(HisInpMedicalRecord record) {
        List<Map<String, Object>> fields = new ArrayList<>();
        String source = StringUtils.hasText(record.getStructureData()) ? record.getStructureData()
                : record.getContent();
        if (!StringUtils.hasText(source)) {
            return fields;
        }
        try {
            Object parsed = JSON.parse(source);
            if (parsed instanceof JSONObject) {
                for (Map.Entry<String, Object> e : ((JSONObject) parsed).entrySet()) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("label", e.getKey());
                    f.put("value", e.getValue() == null ? "" : String.valueOf(e.getValue()));
                    fields.add(f);
                }
            } else if (parsed instanceof JSONArray) {
                for (Object o : (JSONArray) parsed) {
                    if (!(o instanceof JSONObject)) {
                        continue;
                    }
                    JSONObject jo = (JSONObject) o;
                    String label = jo.getString("label");
                    if (!StringUtils.hasText(label)) {
                        label = jo.getString("name");
                    }
                    if (!StringUtils.hasText(label)) {
                        label = jo.getString("key");
                    }
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("label", StringUtils.hasText(label) ? label : "项目");
                    f.put("value", jo.get("value") == null ? "" : String.valueOf(jo.get("value")));
                    fields.add(f);
                }
            }
        } catch (Exception e) {
            log.warn("病历结构化数据非法 JSON, 按原文展示: recordId={}", record.getId());
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("label", "内容");
            f.put("value", source);
            fields.add(f);
        }
        return fields;
    }

    /** 机构名称(sys_org, 查不到返回空串)。 */
    private String orgName(Long orgId) {
        if (orgId == null) {
            return "";
        }
        List<String> names = jdbcTemplate.queryForList(
                "SELECT org_name FROM sys_org WHERE id = ? AND deleted = 0", String.class, orgId);
        return names.isEmpty() ? "" : names.get(0);
    }

    /** 科室名称(his_dept, 查不到返回空串)。 */
    private String deptName(Long deptId) {
        if (deptId == null) {
            return "";
        }
        List<String> names = jdbcTemplate.queryForList(
                "SELECT dept_name FROM his_dept WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                String.class, deptId, tenantId());
        return names.isEmpty() ? "" : names.get(0);
    }

    /** 床位号(his_bed, 查不到返回空串)。 */
    private String bedNo(Long bedId) {
        if (bedId == null) {
            return "";
        }
        List<String> nos = jdbcTemplate.queryForList(
                "SELECT bed_no FROM his_bed WHERE id = ? AND deleted = 0 AND tenant_id = ?",
                String.class, bedId, tenantId());
        return nos.isEmpty() ? "" : nos.get(0);
    }

    /** 职工姓名(his_staff, 查不到返回空串)。 */
    private String staffName(Long staffId) {
        if (staffId == null) {
            return "";
        }
        return staffNames(java.util.Collections.singletonList(staffId))
                .getOrDefault(staffId, "");
    }

    /** 职工ID -> 姓名批量映射(列表场景消除逐行查询)。 */
    private Map<Long, String> staffNames(Collection<Long> staffIds) {
        Map<Long, String> names = new HashMap<>();
        if (staffIds == null || staffIds.isEmpty()) {
            return names;
        }
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        StringBuilder sql = new StringBuilder(
                "SELECT id, staff_name FROM his_staff WHERE deleted = 0 AND tenant_id = ? AND id IN (");
        boolean first = true;
        for (Long id : staffIds) {
            if (!first) {
                sql.append(",");
            }
            sql.append("?");
            args.add(id);
            first = false;
        }
        sql.append(")");
        for (Map<String, Object> row : jdbcTemplate.queryForList(sql.toString(), args.toArray())) {
            names.put(((Number) row.get("id")).longValue(), nvls(row.get("staff_name")));
        }
        return names;
    }

    /** 护理等级文案: 1特级 2一级 3二级 4三级。 */
    private static String nursingLevelText(Integer level) {
        if (level == null) {
            return "";
        }
        switch (level) {
            case 1: return "特级护理";
            case 2: return "一级护理";
            case 3: return "二级护理";
            case 4: return "三级护理";
            default: return "";
        }
    }

    /** 金额格式化(2位小数)。 */
    private static BigDecimal money(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(2) : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(Object v) {
        if (v == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        return v instanceof BigDecimal ? ((BigDecimal) v).setScale(2, RoundingMode.HALF_UP)
                : new BigDecimal(v.toString()).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String nvls(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** 当前租户ID(null 回落 0, 与 MyBatis-Plus 租户插件默认一致)。 */
    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
