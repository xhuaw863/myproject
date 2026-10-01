package com.yb.hi.stddict;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 标准字典注册表: 集中定义 27 类标准字典的源文件、sheet、列映射与查询映射。
 * 以湖北省医保编码数据库(2024版)为主, 纳入国家临床版与中医分类等标准。
 * 列索引均基于勘察确认的源文件表头顺序(0基)。
 */
public final class StdDictRegistry {

    private StdDictRegistry() {
    }

    // 源文件目录前缀(相对 std-dict.base-path)
    private static final String HBYB = "药品材料字典/湖北省医保编码数据库（2024年12月2日版）/";
    private static final String ICD_HB = "疾病诊断和手术操作分类与代码/湖北省医保编码/";
    private static final String ICD_NAT = "疾病诊断和手术操作分类与代码/国家疾病手术编码/";
    private static final String TCM = "疾病诊断和手术操作分类与代码/中医诊断/";

    /** 构建全部标准字典定义(有序) */
    public static Map<String, StdDict> build() {
        Map<String, StdDict> map = new LinkedHashMap<>();
        for (StdDict d : new StdDict[]{
                cvCode(),
                drug(), consumable(), medService(), tcm(), preparation(), ivd(), consItemRel(), supplier(),
                icd10(), icd9(), icd10Nat(), icd9Nat(), morphology(), tcmDisease(), tcmSyndrome(), tcmMapping(),
                tcmDiseaseNew(), tcmSyndromeNew(),
                wst364(),
                hbvalue(),
                whvalue(),
                msiNat(), msiHb(), msiFin(), msiCat(),
                mrCostClass(), invoiceClass(), acctClass()}) {
            d.stdType(stdTypeOf(d.getKey()));
            d.srcDoc(srcDocOf(d.getKey()));
            map.put(d.getKey(), d);
        }
        return map;
    }

    /**
     * 按数据来源划分字典标准类型(4类):
     *  - 国家临床标准: 国家临床版 ICD/肿瘤形态学
     *  - 中医标准: 中医疾病/证候分类(老版+新版)与新老对照
     *  - 卫生健康标准: WS/T 364 卫生健康信息数据元值域代码 + 湖北省采集规范 + 武汉市平台值域代码
     *  - 物价标准: 全国医疗服务项目技术规范(2023) + 湖北物价目录(2023) + 财务归集口径规范
     *  - 医保字典(默认): 湖北医保编码库 + 医保接口规范第6章 + 医保版ICD
     */
    private static String stdTypeOf(String key) {
        switch (key) {
            case "msi_nat":
            case "msi_hb":
            case "msi_fin":
            case "msi_cat":
            case "mr_cost_class":
            case "invoice_class":
            case "acct_class":
                return "物价标准";
            case "icd10_nat":
            case "icd9_nat":
            case "morphology":
                return "国家临床标准";
            case "tcm_disease":
            case "tcm_syndrome":
            case "tcm_mapping":
            case "tcm_disease_new":
            case "tcm_syndrome_new":
                return "中医标准";
            case "wst364":
            case "hbvalue":
            case "whvalue":
                return "卫生健康标准";
            default:
                return "医保字典";
        }
    }

    /**
     * 每类字典的来源文档(哪一份规范/数据库的标准)。
     * 多源字典(icd9_nat/morphology)的逐行来源在各自 StdSource 上以 src_doc 常量覆盖。
     */
    private static String srcDocOf(String key) {
        switch (key) {
            case "cv_code":       return "湖北省医保定点医药机构接口规范V1.2.02·第6章 字典表";
            case "drug":          return "湖北省医保药品(西药、中成药)编码数据库·2024-12-02";
            case "consumable":    return "湖北省医用耗材(20位)编码数据库·2024-11-29";
            case "med_service":   return "湖北省医疗服务项目编码数据库·2024-11-29";
            case "tcm":           return "湖北省医保药品(中药饮片)编码数据库·2024-11-28";
            case "preparation":   return "湖北省医保药品(医疗机构制剂)编码数据库·2024-11-25";
            case "ivd":           return "湖北省医保体外诊断试剂编码数据库·2024-11-26";
            case "cons_item_rel": return "湖北省医用耗材与医疗服务项目对应关系·2024-12-02";
            case "supplier":      return "供货商(企业)字典·由医保各目录生产企业去重汇总(湖北医保编码数据库2024)";
            case "icd10":         return "医保ICD10疾病诊断分类与代码 v2.0(湖北省医保编码)";
            case "icd9":          return "医保ICD9手术操作分类与代码 v2.0(湖北省医保编码)";
            case "icd10_nat":     return "疾病分类与代码国家临床版2.0(2022汇总版)";
            case "icd9_nat":      return "手术操作分类代码国家临床版(3.0/2.0)";
            case "morphology":    return "肿瘤形态学编码(国家临床版2.0/医保版)";
            case "tcm_disease":   return "中医疾病分类与代码数据·20191201";
            case "tcm_syndrome":  return "中医证候分类与代码数据·20191201";
            case "tcm_mapping":   return "中医疾病新老对照(修订版)";
            case "tcm_disease_new":  return "中医临床诊疗术语 第1部分：疾病(修订版·GB/T 15657-2021)";
            case "tcm_syndrome_new": return "中医临床诊疗术语 第2部分：证候(修订版·GB/T 15657-2021)";
            case "wst364":        return "WS/T 364—2023 卫生健康信息数据元值域代码";
            case "hbvalue":       return "湖北省健康医疗大数据采集规范--数据元值域代码20240826";
            case "whvalue":       return "武汉市全民健康信息平台数据元值域代码规范20240905_V1(20250326修订)";
            case "msi_nat":       return "全国医疗服务项目技术规范(2023年版)";
            case "msi_hb":        return "湖北省医疗服务价格项目及医保支付目录(2023版)";
            case "msi_fin":        return "医疗服务项目相关财务归集口径规范";
            case "msi_cat":        return "全国医疗服务项目技术规范(2023年版)·项目分类";
            case "mr_cost_class": return "医疗服务项目相关财务归集口径规范·病案首页费用分类";
            case "invoice_class": return "医疗服务项目相关财务归集口径规范·收费票据分类";
            case "acct_class":    return "医疗服务项目相关财务归集口径规范·会计科目分类";
            default:              return "";
        }
    }

    /**
     * 【0】医保字典值域代码(接口规范V1.2.02 第6章 字典表)。
     * 无 xlsx 数据源: 由 tools/extract_cv_dict.py 从接口规范PDF第6章离线抽取为
     * classpath 种子文件 seed/std_cv_code.tsv, 由 StdDictImportService 的种子分支加载。
     */
    private static StdDict cvCode() {
        return new StdDict("cv_code", "std_cv_code", "医保字典值域代码(接口规范第6章)")
                .query("val_code", "val_name", "dict_name", "dict_code",
                        "val_code", "val_name", "dict_code", "dict_name");
    }

    /**
     * 【新】WS/T 364—2023 卫生健康信息数据元值域代码(标准类型=卫生健康标准)。
     * 无 xlsx 数据源: 由 tools/extract_wst364.py 从各分册 PDF 离线抽取为
     * classpath 种子文件 seed/std_wst364_code.tsv(首行表头), 由 StdDictImportService 的种子分支加载。
     */
    private static StdDict wst364() {
        return new StdDict("wst364", "std_wst364_code", "WS/T 364-2023 值域代码")
                .seed("WS/T 364-2023", "WS/T 364-2023 值域代码(第1~17部分)")
                .query("val_code", "val_name", "cv_name", "cv_code",
                        "val_code", "val_name", "cv_name", "cv_code");
    }

    /**
     * 【新】湖北省健康医疗大数据采集规范--数据元值域代码(标准类型=卫生健康标准)。
     * 无 xlsx 数据源: 由 tools/extract_hb_value.py 从 docx 离线抽取为
     * classpath 种子文件 seed/std_hbvalue_code.tsv(首行表头), 由 StdDictImportService 的种子分支加载。
     */
    private static StdDict hbvalue() {
        return new StdDict("hbvalue", "std_hbvalue_code", "湖北采集规范值域代码")
                .seed("20240826", "湖北采集规范值域代码(171表)")
                .query("val_code", "val_name", "dict_name", "dict_code",
                        "val_code", "val_name", "dict_name", "dict_code");
    }

    /**
     * 【新】武汉市全民健康信息平台数据元值域代码规范(标准类型=卫生健康标准)。
     * 无 xlsx 数据源: 由 tools/extract_wh_value.py 从 docx 离线抽取为
     * classpath 种子文件 seed/std_whvalue_code.tsv(首行表头), 由 StdDictImportService 的种子分支加载。
     */
    private static StdDict whvalue() {
        return new StdDict("whvalue", "std_whvalue_code", "武汉平台值域代码")
                .seed("20240905_V1(20250326修订)", "武汉平台值域代码(264表)")
                .query("val_code", "val_name", "dict_name", "dict_code",
                        "val_code", "val_name", "dict_name", "dict_code");
    }

    /**
     * 【新】全国医疗服务项目技术规范(2023年版)(标准类型=物价标准)。
     * 原样全列导入(不合并/不对照): 由 tools/extract_med_service_price.py 离线抽取为
     * classpath 种子文件 seed/std_msi_nat.tsv(首行表头), 由 StdDictImportService 的种子分支加载。
     */
    private static StdDict msiNat() {
        return new StdDict("msi_nat", "std_msi_nat", "全国医疗服务项目技术规范(2023年版)")
                .seed("2023", "全国医疗服务项目技术规范(2023年版)全列")
                .query("item_code", "item_name", "unit", "invoice_class",
                        "item_name_en", "cat_name", "consumable_req", "cat_code");
    }

    /**
     * 【新】医疗服务项目物价分类(2023技术规范·类/章/节三级, 标准类型=物价标准)。
     * 编码沿用 xlsx 原生字母码(类1/章2/节3字母, 区间码取首段), 第四级组并入节,
     * 区间横幅行与附录表(器械和器具等)不纳入; 由 tools/extract_msi_cat_tree.py 离线抽取为
     * classpath 种子文件 seed/std_msi_cat.tsv(同工具为 std_msi_nat 追加 cat_code 列)。
     */
    private static StdDict msiCat() {
        return new StdDict("msi_cat", "std_msi_cat", "医疗服务项目物价分类(2023技术规范)")
                .seed("2023", "全国医疗服务项目技术规范(2023年版)·项目分类(类/章/节)")
                .query("cat_code", "cat_name", "parent_code", "item_count",
                        "cat_code", "cat_name", "parent_code");
    }

    /**
     * 【新】湖北省医疗服务价格项目及医保支付目录(2023版)(标准类型=物价标准)。
     * 基础项+子项原样成行(不合并/不对照): 由 tools/extract_med_service_price.py 离线抽取为
     * classpath 种子文件 seed/std_msi_hb.tsv(首行表头), 由 StdDictImportService 的种子分支加载。
     */
    private static StdDict msiHb() {
        return new StdDict("msi_hb", "std_msi_hb", "湖北医疗服务价格项目及医保支付目录(2023版)")
                .seed("2023", "湖北省医疗服务价格项目及医保支付目录(2023版)全列")
                .query("item_code", "item_name", "unit", "pay_cat",
                        "cat_name", "remark");
    }

    /**
     * 【新】医疗服务项目相关财务归集口径规范(标准类型=物价标准)。
     * PDF 827页线框表逐行原样导入(含层级行/续行, 不做跨版本对照加工):
     * 由 tools/extract_med_service_price.py 离线抽取为 classpath 种子文件
     * seed/std_msi_fin.tsv(首行表头), 由 StdDictImportService 的种子分支加载。
     */
    private static StdDict msiFin() {
        return new StdDict("msi_fin", "std_msi_fin", "医疗服务项目财务归集口径规范")
                .seed("2023", "医疗服务项目相关财务归集口径规范全表行")
                .query("code_2023", "name_2023", "invoice_class", "acct_class",
                        "code_2012", "code_2001", "name_2012", "name_2001");
    }

    /**
     * 【新】病案首页费用分类(标准类型=物价标准)。
     * 由 tools/extract_msi_fin_classes.py 从 std_msi_fin(财务归集口径规范)的 mr_cost_class 列
     * 去重规范化抽取为 classpath 种子文件 seed/std_mr_cost_class.tsv(首行表头), 走种子分支加载。
     * 层级: 大类(综合医疗服务/诊断/治疗/康复/中医) -> 费用分项(源文括号序号1~12)。
     */
    private static StdDict mrCostClass() {
        return new StdDict("mr_cost_class", "std_mr_cost_class", "病案首页费用分类")
                .seed("2023", "医疗服务项目相关财务归集口径规范·病案首页费用分类")
                .query("item_code", "item_name", "cat_name", "raw_value",
                        "cat_no", "cat_name", "item_name", "raw_value");
    }

    /**
     * 【新】收费票据分类(标准类型=物价标准)。
     * 由 tools/extract_msi_fin_classes.py 从 std_msi_fin 的 invoice_class 列去重抽取为
     * classpath 种子文件 seed/std_invoice_class.tsv(首行表头), 走种子分支加载。
     * 源文无编码, class_code 为字典内序号; 与会计科目分类逐行 1:1 对应(acct_class 列)。
     */
    private static StdDict invoiceClass() {
        return new StdDict("invoice_class", "std_invoice_class", "收费票据分类")
                .seed("2023", "医疗服务项目相关财务归集口径规范·收费票据分类")
                .query("class_code", "class_name", "acct_class", "item_count",
                        "class_name", "acct_class");
    }

    /**
     * 【新】会计科目分类(标准类型=物价标准)。
     * 由 tools/extract_msi_fin_classes.py 从 std_msi_fin 的 acct_class 列去重抽取为
     * classpath 种子文件 seed/std_acct_class.tsv(首行表头), 走种子分支加载。
     * 源文无编码, class_code 为字典内序号; 与收费票据分类逐行 1:1 对应(invoice_class 列)。
     */
    private static StdDict acctClass() {
        return new StdDict("acct_class", "std_acct_class", "会计科目分类")
                .seed("2023", "医疗服务项目相关财务归集口径规范·会计科目分类")
                .query("class_code", "class_name", "invoice_class", "item_count",
                        "class_name", "invoice_class");
    }

    /** 【1】西药中成药 */
    private static StdDict drug() {
        StdSource s = new StdSource(HBYB + "湖北医保药品（西药、中成药）编码数据库-20241202（243877）.xlsx", 0)
                .col("seq", 0).col("major_class", 1).col("drug_code", 2).col("reg_name", 3)
                .col("trade_name", 4).col("reg_dosform", 5).col("act_dosform", 6).col("reg_spec", 7)
                .col("act_spec", 8).col("pack_material", 9).col("min_pack_qty", 10).col("min_prep_unit", 11)
                .col("min_pack_unit", 12).col("drug_entp", 13).col("mkt_holder", 14).col("approval_no", 15)
                .col("drug_std_code", 16).col("market_status", 17).col("subpack_entp", 18).col("hi_drug_name", 19)
                .col("chrgitm_lv", 20).col("hi_dosform", 21).col("gen_no", 22).col("memo", 23)
                .col("msd_flag", 24).col("nego_flag", 25).col("nego_start", 26).col("nego_end", 27)
                .col("ltd_self_flag", 28).col("pay_std_prep", 29).col("pay_std_pack", 30).col("data_source", 31)
                .col("chg_field", 32).cst("ver", "20241202");
        return new StdDict("drug", "std_drug", "西药中成药(湖北医保)").add(s)
                .query("drug_code", "reg_name", "act_spec", "drug_entp",
                        "drug_code", "reg_name", "trade_name", "hi_drug_name", "drug_std_code", "approval_no");
    }

    /** 【2】医用耗材(20位) */
    private static StdDict consumable() {
        StdSource s = new StdSource(HBYB + "湖北省医用耗材（20位）编码数据库-20241129（97430条）.xlsx", 0)
                .col("seq", 0).col("cons_code", 1).col("cat1", 2).col("cat2", 3).col("cat3", 4)
                .col("hi_genname", 5).col("material", 6).col("feature", 7).col("cons_entp", 8)
                .col("policy_flag", 9).col("pay_std", 10).col("reg_cert_no", 11).col("cons_type", 12)
                .col("data_source", 13).col("chg_log", 14).cst("ver", "20241129");
        return new StdDict("consumable", "std_consumable", "医用耗材(20位)").add(s)
                .query("cons_code", "hi_genname", "cat3", "cons_entp", "cons_code", "hi_genname");
    }

    /** 【3】医疗服务项目 */
    private static StdDict medService() {
        StdSource s = new StdSource(HBYB + "湖北省医疗服务项目编码数据库-20241129（6459条）.xlsx", 0)
                .col("seq", 0).col("nat_item_code", 1).col("nat_item_name", 2).col("loc_item_code", 3)
                .col("loc_item_name", 4).col("item_connotation", 5).col("item_excluded", 6).col("prc_unit", 7)
                .col("item_explain", 8).col("policy_flag", 9).col("pay_std", 10).col("memo", 11)
                .col("data_source", 12).cst("ver", "20241129");
        return new StdDict("med_service", "std_med_service", "医疗服务项目").add(s)
                .query("nat_item_code", "loc_item_name", "prc_unit", "nat_item_name",
                        "nat_item_code", "loc_item_code", "nat_item_name", "loc_item_name");
    }

    /** 【4】中药饮片 */
    private static StdDict tcm() {
        StdSource s = new StdSource(HBYB + "湖北省医保药品(中药饮片)编码数据库-20241128（10821条）.xlsx", 0)
                .col("seq", 0).col("major_class", 1).col("nat_tcm_code", 2).col("tcm_name", 3)
                .col("material_name", 4).col("proc_method", 5).col("efcc_class", 6).col("material_family", 7)
                .col("material_species", 8).col("medi_part", 9).col("nature_meridian", 10).col("func_indication", 11)
                .col("usage_dosage", 12).col("pay_policy", 13).col("chrgitm_lv", 14).col("data_source", 15)
                .cst("ver", "20241128");
        return new StdDict("tcm", "std_tcm", "中药饮片").add(s)
                .query("nat_tcm_code", "tcm_name", "efcc_class", "material_name", "nat_tcm_code", "tcm_name", "material_name");
    }

    /** 【5】医疗机构制剂 */
    private static StdDict preparation() {
        StdSource s = new StdSource(HBYB + "湖北省医保药品（医疗机构制剂）编码数据库-20241125（1871条）.xlsx", 0)
                .col("seq", 0).col("chg_log", 1).col("policy_flag", 2).col("region", 3).col("prep_code", 4)
                .col("applicant", 5).col("prep_class", 6).col("prep_name", 7).col("dosform", 8).col("spec", 9)
                .col("min_pack_qty", 10).col("min_pack_unit", 11).col("min_prep_unit", 12).col("pack_material", 13)
                .col("entrust_entp", 14).col("entrust_addr", 15).col("approval_no", 16).col("approval_valid_date", 17)
                .col("license_no", 18).col("exec_std", 19).col("indication", 20).col("usage_method", 21)
                .col("child_use", 22).col("elder_use", 23).cst("ver", "20241125");
        return new StdDict("preparation", "std_preparation", "医疗机构制剂").add(s)
                .query("prep_code", "prep_name", "spec", "applicant", "prep_code", "prep_name", "approval_no");
    }

    /** 【6】体外诊断试剂 */
    private static StdDict ivd() {
        StdSource s = new StdSource(HBYB + "湖北省医保体外诊断试剂编码数据库（2233条）-20241126.xlsx", 0)
                .col("seq", 0).col("ivd_code", 1).col("cat1", 2).col("cat2", 3).col("cat3", 4)
                .col("test_class", 5).col("test_index", 6).col("app_mode", 7).col("test_type", 8).col("test_item", 9)
                .col("entp_name", 10).col("result_attr", 11).col("prod_name", 12).col("reg_record_no", 13)
                .col("pack_spec", 14).col("pack_unit", 15).col("volume_ml", 16).col("human_dose", 17)
                .col("other", 18).col("applicable_inst", 19).col("udi", 20).col("data_source", 21)
                .col("chg_log", 22).cst("ver", "20241126");
        return new StdDict("ivd", "std_ivd", "体外诊断试剂").add(s)
                .query("ivd_code", "prod_name", "pack_spec", "test_index", "ivd_code", "prod_name", "test_index");
    }

    /** 【7】耗材与医疗服务项目对应关系 */
    private static StdDict consItemRel() {
        StdSource s = new StdSource(HBYB + "湖北省医用耗材与医疗服务项目对应关系-20241202（250617条）.xlsx", 0)
                .col("seq", 0).col("code", 1).col("diag_item_code", 2).col("item_cat_name", 3)
                .col("cons_variety", 4).col("cons_code20", 5).col("cat1", 6).col("cat2", 7).col("cat3", 8)
                .col("hi_genname", 9).col("material", 10).col("feature", 11).col("cons_entp", 12)
                .col("policy_flag", 13).col("reg_cert_no", 14).col("cons_type", 15).col("data_source", 16)
                .col("memo", 17).cst("ver", "20241202");
        return new StdDict("cons_item_rel", "std_cons_item_rel", "耗材与医疗服务项目对应关系").add(s)
                .query("cons_code20", "cons_variety", "item_cat_name", "diag_item_code",
                        "cons_code20", "diag_item_code", "cons_variety");
    }

    /**
     * 【新·供货商(企业)字典】(标准类型=医保字典)。
     * 无独立 xlsx 源: 由 tools/extract_supplier.py 从 std_drug(drug_entp/mkt_holder)、
     * std_consumable(cons_entp)、std_ivd(entp_name)、std_preparation(applicant) 四类目录的
     * 企业名去重归并为 classpath 种子文件 seed/std_supplier.tsv(首行表头), 走种子分支加载。
     * 供医共体药品/耗材目录的"生产企业/上市许可持有人"以字典编码引用(取代自由汉字文本)。
     * 统一查询映射: code=sup_code, name=sup_name(企业名称), spec=sup_type(企业类型), extra=src_catalog(来源目录)。
     */
    private static StdDict supplier() {
        return new StdDict("supplier", "std_supplier", "供货商(企业)字典")
                .seed("20241202", "医保各目录生产企业去重汇总")
                // 检索列: 企业名称/编码/简称/统一社会信用代码(拼音简码由维护服务自动附加)
                .query("sup_code", "sup_name", "sup_type", "src_catalog",
                        "sup_name", "sup_code", "sup_short_name", "uscc");
    }

    /** 【8】医保ICD10疾病诊断(完整分类与代码, sheet序号1) */
    private static StdDict icd10() {
        StdSource s = new StdSource(ICD_HB + "疾病诊断分类与代码/（西药）诊断分类与代码2021-01-26 (1)/医保ICD10_v2.0_0122.xlsx", 1)
                .col("chapter", 0).col("chapter_code_range", 1).col("chapter_name", 2).col("section_code_range", 3)
                .col("section_name", 4).col("cat_code", 5).col("cat_name", 6).col("subcat_code", 7)
                .col("subcat_name", 8).col("diag_code", 9).col("diag_name", 10)
                .cst("src_sheet", "完整分类与代码").cst("ver", "v2.0");
        return new StdDict("icd10", "std_icd10", "医保ICD10疾病诊断").add(s)
                .query("diag_code", "diag_name", "cat_name", "subcat_name", "diag_code", "diag_name");
    }

    /** 【9】医保ICD9手术操作(完整分类与代码, sheet序号1) */
    private static StdDict icd9() {
        StdSource s = new StdSource(ICD_HB + "手术操作分类与代码2021-01-26/医保ICD9_v2.0_0122.xlsx", 1)
                .col("chapter", 0).col("chapter_name", 1).col("cat_code", 2).col("cat_name", 3)
                .col("subcat_code", 4).col("subcat_name", 5).col("detail_code", 6).col("detail_name", 7)
                .col("oper_code", 8).col("oper_name", 9)
                .cst("src_sheet", "完整分类与代码").cst("ver", "v2.0");
        return new StdDict("icd9", "std_icd9", "医保ICD9手术操作").add(s)
                .query("oper_code", "oper_name", "cat_name", "subcat_name", "oper_code", "oper_name");
    }

    /** 【10】国家临床版2.0疾病分类与代码 */
    private static StdDict icd10Nat() {
        StdSource s = new StdSource(ICD_NAT + "附件1 疾病分类与代码国家临床版2.0（2022汇总版）.xlsx", 0)
                .col("main_code", 0).col("add_code", 1).col("disease_name", 2)
                .cst("src", "国家临床版2.0").cst("ver", "2022汇总版");
        return new StdDict("icd10_nat", "std_icd10_nat", "国家临床版疾病分类与代码").add(s)
                .query("main_code", "disease_name", "add_code", "src_doc", "main_code", "disease_name");
    }

    /** 【11】国家临床版手术操作(3.0 与 ICD-9-CM3 2.0, 双源) */
    private static StdDict icd9Nat() {
        StdSource a = new StdSource(ICD_NAT + "附件2.手术操作分类代码国家临床版3.0（2022汇总版）.xlsx", 0)
                .col("main_code", 0).col("add_code", 1).col("oper_name", 2).col("oper_cat", 3).col("input_option", 4)
                .cst("src", "国家临床版3.0").cst("src_doc", "手术操作分类代码国家临床版3.0（2022汇总版）").cst("ver", "2022汇总版");
        StdSource b = new StdSource(ICD_NAT + "国家临床版2.0手术操作编码(ICD-9-CM3).xlsx", 0)
                .col("main_code", 0).col("oper_name", 1).col("oper_cat", 2).col("input_option", 3)
                .cst("src", "国家临床版2.0").cst("src_doc", "国家临床版2.0手术操作编码(ICD-9-CM3)").cst("ver", "2.0");
        return new StdDict("icd9_nat", "std_icd9_nat", "国家临床版手术操作分类与代码").add(a).add(b)
                .query("main_code", "oper_name", "oper_cat", "src_doc", "main_code", "oper_name");
    }

    /** 【12】肿瘤形态学编码(国家版 + 医保版, 双源) */
    private static StdDict morphology() {
        StdSource a = new StdSource(ICD_NAT + "国家临床版2.0肿瘤形态学编码（M码）.xlsx", 0)
                .col("morph_code", 0).col("morph_name", 1)
                .cst("src", "国家临床版2.0").cst("src_doc", "国家临床版2.0肿瘤形态学编码（M码）").cst("ver", "2.0");
        StdSource b = new StdSource(ICD_HB + "疾病诊断分类与代码/ICD-10医保版数据（肿瘤形态学）20191201/ICD-10医保版数据（肿瘤形态学）20191201.xlsx", 0)
                .col("tumor_type_code", 0).col("tumor_type_name", 1).col("morph_code", 2).col("morph_name", 3)
                .cst("src", "医保版").cst("src_doc", "ICD-10医保版数据(肿瘤形态学)20191201").cst("ver", "20191201");
        return new StdDict("morphology", "std_morphology", "肿瘤形态学编码").add(a).add(b)
                .query("morph_code", "morph_name", "tumor_type_name", "src_doc", "morph_code", "morph_name");
    }

    /** 【13】中医疾病分类与代码 */
    private static StdDict tcmDisease() {
        StdSource s = new StdSource(ICD_HB + "疾病诊断分类与代码/中医疾病分类与代码2019-12-01/中医疾病分类与代码数据20191201.xlsx", 0)
                .col("dept_cat_code", 0).col("dept_cat_name", 1).col("spec_sys_code", 2).col("spec_sys_name", 3)
                .col("dis_class_code", 4).col("dis_class_name", 5).cst("ver", "20191201");
        return new StdDict("tcm_disease", "std_tcm_disease", "中医疾病分类与代码").add(s)
                .query("dis_class_code", "dis_class_name", "spec_sys_name", "dept_cat_name",
                        "dis_class_code", "dis_class_name");
    }

    /** 【14】中医证候分类与代码 */
    private static StdDict tcmSyndrome() {
        StdSource s = new StdSource(ICD_HB + "疾病诊断分类与代码/中医证候分类与代码2019-12-01/中医证候分类与代码数据20191201.xlsx", 0)
                .col("syn_cat_code", 0).col("syn_cat_name", 1).col("syn_attr_code", 2).col("syn_attr_name", 3)
                .col("syn_class_code", 4).col("syn_class_name", 5).cst("ver", "20191201");
        return new StdDict("tcm_syndrome", "std_tcm_syndrome", "中医证候分类与代码").add(s)
                .query("syn_class_code", "syn_class_name", "syn_attr_name", "syn_cat_name",
                        "syn_class_code", "syn_class_name");
    }

    /** 【15】中医疾病/证候/治法新老对照(三源) */
    private static StdDict tcmMapping() {
        String f = TCM + "中医疾病新老对照.xlsx";
        StdSource dis = new StdSource(f, 0)
                .col("seq", 0).col("new_code", 1).col("new_no", 2).col("new_name", 3)
                .col("new_optional", 4).col("old_code", 5).col("old_name", 6)
                .cst("map_type", "疾病").cst("ver", "修订版");
        StdSource syn = new StdSource(f, 1)
                .col("seq", 0).col("new_code", 1).col("new_no", 2).col("new_name", 3)
                .col("new_optional", 4).col("old_code", 5).col("old_name", 6)
                .cst("map_type", "证候").cst("ver", "修订版");
        StdSource method = new StdSource(f, 2)
                .col("seq", 0).col("new_code", 1).col("new_no", 2).col("new_name", 3).col("new_optional", 4)
                .cst("map_type", "治法").cst("ver", "修订版");
        return new StdDict("tcm_mapping", "std_tcm_mapping", "中医新老对照").add(dis).add(syn).add(method)
                .query("new_code", "new_name", "old_name", "map_type",
                        "new_code", "new_name", "old_name", "map_type");
    }

    /** 【16】中医疾病分类与代码(新版/修订版 GB/T 15657-2021 第1部分)。
     *  源为对照表 sheet0 的"修订版"列(新代码A01…+名称), 与老版 tcm_disease(BNF码)并存。 */
    private static StdDict tcmDiseaseNew() {
        StdSource s = new StdSource(TCM + "中医疾病新老对照.xlsx", 0)
                .col("seq", 0).col("dis_code", 1).col("dis_no", 2).col("dis_name", 3).col("optional_name", 4)
                .cst("ver", "修订版");
        return new StdDict("tcm_disease_new", "std_tcm_disease_new", "中医疾病分类与代码(新版)").add(s)
                .query("dis_code", "dis_name", "dis_no", "optional_name",
                        "dis_code", "dis_name", "optional_name");
    }

    /** 【17】中医证候分类与代码(新版/修订版 GB/T 15657-2021 第2部分)。
     *  源为对照表 sheet1 的"修订版"列(新代码B01…+名称), 与老版 tcm_syndrome(ZBF码)并存。 */
    private static StdDict tcmSyndromeNew() {
        StdSource s = new StdSource(TCM + "中医疾病新老对照.xlsx", 1)
                .col("seq", 0).col("syn_code", 1).col("syn_no", 2).col("syn_name", 3).col("optional_name", 4)
                .cst("ver", "修订版");
        return new StdDict("tcm_syndrome_new", "std_tcm_syndrome_new", "中医证候分类与代码(新版)").add(s)
                .query("syn_code", "syn_name", "syn_no", "optional_name",
                        "syn_code", "syn_name", "optional_name");
    }
}
