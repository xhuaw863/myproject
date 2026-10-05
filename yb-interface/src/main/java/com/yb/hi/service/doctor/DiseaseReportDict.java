package com.yb.hi.service.doctor;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 疾病报告卡值域字典(P1 慢性病: 严重精神障碍 / 恶性肿瘤)。
 * 值域来源均为国家权威标准枚举(见各域 src 注释), 后端单一真源、经 /disease-report/dicts 下发前端,
 * 保证"字典值优先录入 + 可溯源"。肿瘤 ICD-O-3 部位/形态学为内置常用子集(src 标注 内置子集),
 * 完整码表待国家肿瘤登记 ICD-O-3 授权导入后替换, 不阻塞报卡落库。
 */
@Component
public class DiseaseReportDict {

    /** 全量值域(键即前端 dataKey) */
    public Map<String, Object> dicts() {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("reportCategory", reportCategory());
        all.put("smiDisease", smiDisease());
        all.put("riskLevel", riskLevel());
        all.put("household", household());
        all.put("consent", consent());
        all.put("guardianRelation", guardianRelation());
        all.put("tumorTopo", tumorTopo());
        all.put("tumorMorph", tumorMorph());
        all.put("behavior", behavior());
        all.put("laterality", laterality());
        all.put("differentiation", differentiation());
        all.put("dxBasis", dxBasis());
        /* ---------- 法定传染病报告卡(2024 国标核心版): 兼作自动弹出触发匹配源 ---------- */
        all.put("infectiousDisease", infectiousDisease());
        all.put("infectiousCaseType", infectiousCaseType());
        all.put("infectiousClass", infectiousClass());
        return all;
    }

    private static Map<String, Object> item(String code, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        return m;
    }

    /** 报卡大类(his_disease_report.report_category): 传染病本次为独立轨道, 此处仅列 P1 慢病 + 兜底 */
    private List<Map<String, Object>> reportCategory() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(item("2", "严重精神障碍"));
        l.add(item("3", "恶性肿瘤"));
        l.add(item("4", "高血压"));
        l.add(item("5", "糖尿病"));
        l.add(item("9", "其他"));
        return l;
    }

    /** 严重精神障碍发病报告卡 6 病(国家严重精神障碍发病报告管理办法/基本公卫规范第三版, ICD-10 F 码) */
    private List<Map<String, Object>> smiDisease() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(withIcd("F20", "精神分裂症", "F20"));
        l.add(withIcd("F25", "分裂情感性障碍", "F25"));
        l.add(withIcd("F24", "偏执性精神病", "F24"));
        l.add(withIcd("F31", "双相（情感）障碍", "F31"));
        l.add(withIcd("F06.8", "癫痫所致精神障碍", "G40/F06.8"));
        l.add(withIcd("F70", "精神发育迟滞伴发精神障碍", "F70-F79"));
        return l;
    }

    private static Map<String, Object> withIcd(String code, String name, String icd) {
        Map<String, Object> m = item(code, name);
        m.put("icd", icd);
        return m;
    }

    /** 危险分级(国家严重精神障碍 6 级评估) */
    private List<Map<String, Object>> riskLevel() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(item("0", "0级 无符合以下1-5级行为"));
        l.add(item("1", "1级 曾口头威胁/喊叫,无打砸行为"));
        l.add(item("2", "2级 打砸行为,局限家庭,可被劝止"));
        l.add(item("3", "3级 打砸行为,不管场合,劝止无效"));
        l.add(item("4", "4级 短暂的意识不清/极度行为紊乱,无危险性"));
        l.add(item("5", "5级 有持续行为紊乱/威胁他人或自伤"));
        return l;
    }

    /** 户别 */
    private List<Map<String, Object>> household() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(item("1", "城镇"));
        l.add(item("2", "农村"));
        return l;
    }

    /** 知情同意 */
    private List<Map<String, Object>> consent() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(item("1", "同意"));
        l.add(item("0", "不同意"));
        return l;
    }

    /** 监护人关系 */
    private List<Map<String, Object>> guardianRelation() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(item("1", "配偶"));
        l.add(item("2", "父母"));
        l.add(item("3", "子女"));
        l.add(item("9", "其他"));
        return l;
    }

    /** 肿瘤 ICD-O-3 解剖学部位(C 码)——内置常用子集, 完整码表待国家授权导入 */
    private List<Map<String, Object>> tumorTopo() {
        String[][] d = {
            {"C000", "唇"}, {"C02", "舌"}, {"C11", "鼻咽"}, {"C15", "食管"}, {"C16", "胃"},
            {"C18", "结肠"}, {"C19", "直肠"}, {"C220", "肝"}, {"C23", "胆囊"}, {"C25", "胰腺"},
            {"C32", "喉"}, {"C34", "支气管和肺"}, {"C40", "骨和关节"}, {"C44", "皮肤"},
            {"C50", "乳房"}, {"C53", "子宫颈"}, {"C54", "子宫体"}, {"C56", "卵巢"},
            {"C57", "其他女性生殖器官"}, {"C61", "前列腺"}, {"C64", "肾"}, {"C67", "膀胱"},
            {"C70", "脑膜"}, {"C71", "脑"}, {"C73", "甲状腺"}, {"C77", "淋巴结"}, {"C80", "继发和未部位恶性肿瘤"}
        };
        List<Map<String, Object>> l = new ArrayList<>();
        for (String[] x : d) { l.add(item(x[0], x[1])); }
        return l;
    }

    /** 肿瘤 ICD-O-3 形态学(M 码)——内置常用子集, 行为位取第 3 位 */
    private List<Map<String, Object>> tumorMorph() {
        String[][] d = {
            {"8010", "癌细胞瘤(非特指)"}, {"8011", "鳞状细胞癌"}, {"8052", "乳头状鳞状细胞癌"},
            {"8070", "腺癌(非特指)"}, {"8140", "腺癌"}, {"8246", "神经内分泌肿瘤"},
            {"8310", "透明细胞癌"}, {"8480", "黏液表皮样癌"}, {"8500", "乳腺浸润性导管癌"},
            {"8574", "睾丸癌"}, {"8720", "恶性黑素瘤"}, {"8900", "肉瘤(非特指)"},
            {"9590", "淋巴瘤(非特指)"}, {"9840", "白血病"}, {"9945", "霍奇金淋巴瘤"}
        };
        List<Map<String, Object>> l = new ArrayList<>();
        for (String[] x : d) {
            Map<String, Object> m = item(x[0], x[1]);
            m.put("behavior", "3");
            l.add(m);
        }
        return l;
    }

    /** 行为分类(ICD-O-3 第 3 位) */
    private List<Map<String, Object>> behavior() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(item("0", "良性"));
        l.add(item("1", "动态未定性"));
        l.add(item("2", "原位癌(非侵袭性)"));
        l.add(item("3", "恶性,原发"));
        l.add(item("6", "恶性,转移"));
        l.add(item("9", "恶性,独立原发(双侧)"));
        return l;
    }

    /** 侧别 */
    private List<Map<String, Object>> laterality() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(item("1", "左"));
        l.add(item("2", "右"));
        l.add(item("3", "双侧"));
        l.add(item("9", "不适用/不详"));
        return l;
    }

    /** 分化程度 */
    private List<Map<String, Object>> differentiation() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(item("1", "高分化(G1)"));
        l.add(item("2", "中分化(G2)"));
        l.add(item("3", "低分化(G3)"));
        l.add(item("4", "未分化(G4)"));
        l.add(item("9", "未知(GX)"));
        return l;
    }

    /** 肿瘤诊断依据(全国肿瘤登记病例报告卡口径) */
    private List<Map<String, Object>> dxBasis() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(item("01", "临床诊断"));
        l.add(item("02", "影像学诊断"));
        l.add(item("03", "实验室诊断"));
        l.add(item("04", "病理诊断"));
        l.add(item("05", "手术/尸检诊断"));
        l.add(item("09", "不详"));
        return l;
    }

    private static Map<String, Object> withCls(String code, String name, String cls) {
        Map<String, Object> m = item(code, name);
        m.put("cls", cls);
        return m;
    }

    /** 法定传染病目录(2024版 甲乙丙3类核心集): code=ICD-10 代表码(触发按前缀/精确匹配), cls=甲/乙/丙 */
    private List<Map<String, Object>> infectiousDisease() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(withCls("A20", "鼠疫", "甲"));
        l.add(withCls("A00", "霍乱", "甲"));
        l.add(withCls("B99", "新型冠状病毒感染", "乙"));
        l.add(withCls("B24", "艾滋病", "乙"));
        l.add(withCls("B15", "甲型肝炎", "乙"));
        l.add(withCls("B16", "乙型肝炎", "乙"));
        l.add(withCls("B17", "丙型肝炎", "乙"));
        l.add(withCls("A80", "脊髓灰质炎", "乙"));
        l.add(withCls("J09", "人感染高致病性禽流感", "乙"));
        l.add(withCls("B05", "麻疹", "乙"));
        l.add(withCls("A90", "登革热", "乙"));
        l.add(withCls("A22", "炭疽", "乙"));
        l.add(withCls("A03", "细菌性和阿米巴性痢疾", "乙"));
        l.add(withCls("A15", "肺结核", "乙"));
        l.add(withCls("A01", "伤寒和副伤寒", "乙"));
        l.add(withCls("A39", "流行性脑脊髓膜炎", "乙"));
        l.add(withCls("A37", "百日咳", "乙"));
        l.add(withCls("A36", "白喉", "乙"));
        l.add(withCls("A33", "新生儿破伤风", "乙"));
        l.add(withCls("A35", "破伤风", "乙"));
        l.add(withCls("A38", "猩红热", "乙"));
        l.add(withCls("A23", "布鲁氏菌病", "乙"));
        l.add(withCls("A54", "淋病", "乙"));
        l.add(withCls("A50", "梅毒", "乙"));
        l.add(withCls("A27", "钩端螺旋体病", "乙"));
        l.add(withCls("B65", "血吸虫病", "乙"));
        l.add(withCls("B50", "疟疾", "乙"));
        l.add(withCls("A91", "流行性出血热", "乙"));
        l.add(withCls("B26", "流行性腮腺炎", "丙"));
        l.add(withCls("B06", "风疹", "丙"));
        l.add(withCls("A08", "手足口病", "丙"));
        l.add(withCls("J11", "流行性感冒", "丙"));
        l.add(withCls("A30", "麻风病", "丙"));
        l.add(withCls("B55", "黑热病", "丙"));
        l.add(withCls("B67", "包虫病", "丙"));
        l.add(withCls("B74", "丝虫病", "丙"));
        l.add(withCls("H10", "急性出血性结膜炎", "丙"));
        l.add(withCls("A04", "感染性腹泻病", "丙"));
        l.add(withCls("B33", "其他传染病", "丙"));
        return l;
    }

    /** 病例分类(传染病报告卡2024口径) */
    private List<Map<String, Object>> infectiousCaseType() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(item("1", "疑似病例"));
        l.add(item("2", "确诊病例"));
        l.add(item("3", "临床诊断病例"));
        l.add(item("4", "病原携带者"));
        l.add(item("5", "阳性检测"));
        return l;
    }

    /** 传染病甲乙丙分类(列表筛选用) */
    private List<Map<String, Object>> infectiousClass() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(item("甲", "甲类"));
        l.add(item("乙", "乙类"));
        l.add(item("丙", "丙类"));
        return l;
    }
}
