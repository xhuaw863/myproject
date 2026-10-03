-- MySQL dump 10.13  Distrib 8.0.28, for Win64 (x86_64)
--
-- Host: localhost    Database: yb_interface
-- ------------------------------------------------------
-- Server version	8.0.28

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `area_code_2021`
--

DROP TABLE IF EXISTS `area_code_2021`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `area_code_2021` (
  `code` bigint unsigned NOT NULL COMMENT '区划代码',
  `name` varchar(128) NOT NULL DEFAULT '' COMMENT '名称',
  `level` tinyint(1) NOT NULL COMMENT '级别1-5,省市县镇村',
  `pcode` bigint DEFAULT NULL COMMENT '父级区划代码',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(区划名称首字母, 自动生成只读)',
  PRIMARY KEY (`code`),
  KEY `name` (`name`),
  KEY `level` (`level`),
  KEY `pcode` (`pcode`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `consumable_catalog`
--

DROP TABLE IF EXISTS `consumable_catalog`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `consumable_catalog` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '绉熸埛ID',
  `med_list_codg` varchar(50) DEFAULT NULL COMMENT '医疗目录编码',
  `cons_name` varchar(500) DEFAULT NULL COMMENT '耗材名称',
  `udi` varchar(100) DEFAULT NULL COMMENT '医疗器械唯一标识码',
  `genname_code` varchar(50) DEFAULT NULL COMMENT '医保通用名代码',
  `genname` varchar(500) DEFAULT NULL COMMENT '医保通用名',
  `prod_model` varchar(200) DEFAULT NULL COMMENT '产品型号',
  `spec_code` varchar(100) DEFAULT NULL COMMENT '规格代码',
  `spec` varchar(255) DEFAULT NULL COMMENT '规格',
  `cons_cat` varchar(50) DEFAULT NULL COMMENT '耗材分类',
  `spec_model` varchar(255) DEFAULT NULL COMMENT '规格型号',
  `min_useunt` varchar(30) DEFAULT NULL COMMENT '最小使用单位',
  `min_salunt` varchar(30) DEFAULT NULL COMMENT '最小销售单位',
  `hi_value_flag` varchar(3) DEFAULT NULL COMMENT '高值耗材标志',
  `vali_flag` varchar(3) DEFAULT NULL COMMENT '有效标志',
  `rid` varchar(40) DEFAULT NULL COMMENT '唯一记录号',
  `ver` varchar(30) DEFAULT NULL COMMENT '版本号',
  `ver_name` varchar(100) DEFAULT NULL COMMENT '版本名称',
  `raw_data` longtext COMMENT '原始数据行(TAB分隔)',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(耗材名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_med_list_codg` (`med_list_codg`),
  KEY `idx_ver` (`ver`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医用耗材目录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `dict_version`
--

DROP TABLE IF EXISTS `dict_version`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `dict_version` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '绉熸埛ID',
  `dict_type` varchar(50) NOT NULL COMMENT '字典类型标识',
  `dict_name` varchar(100) DEFAULT NULL COMMENT '字典名称',
  `infno` varchar(10) DEFAULT NULL COMMENT '对应交易编号',
  `max_ver` varchar(30) NOT NULL DEFAULT '0' COMMENT '本地最大版本号',
  `last_dld_time` datetime DEFAULT NULL COMMENT '最近下载时间',
  `updt_time` datetime DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_dict` (`tenant_id`,`dict_type`)
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字典版本管理表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `disease_catalog`
--

DROP TABLE IF EXISTS `disease_catalog`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `disease_catalog` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '绉熸埛ID',
  `dise_code` varchar(50) DEFAULT NULL COMMENT '西医疾病诊断ID',
  `chapter` varchar(50) DEFAULT NULL COMMENT '章',
  `chapter_name` varchar(200) DEFAULT NULL COMMENT '章名称',
  `cat_code` varchar(50) DEFAULT NULL COMMENT '类目代码',
  `cat_name` varchar(200) DEFAULT NULL COMMENT '类目名称',
  `subcat_code` varchar(50) DEFAULT NULL COMMENT '亚目代码',
  `subcat_name` varchar(200) DEFAULT NULL COMMENT '亚目名称',
  `diag_code` varchar(50) DEFAULT NULL COMMENT '诊断代码',
  `diag_name` varchar(500) DEFAULT NULL COMMENT '诊断名称',
  `use_flag` varchar(10) DEFAULT NULL COMMENT '使用标记',
  `nat_std_diag_code` varchar(50) DEFAULT NULL COMMENT '国标版诊断代码',
  `nat_std_diag_name` varchar(500) DEFAULT NULL COMMENT '国标版诊断名称',
  `clin_diag_code` varchar(50) DEFAULT NULL COMMENT '临床版诊断代码',
  `clin_diag_name` varchar(500) DEFAULT NULL COMMENT '临床版诊断名称',
  `vali_flag` varchar(3) DEFAULT NULL COMMENT '有效标志',
  `rid` varchar(40) DEFAULT NULL COMMENT '唯一记录号',
  `ver` varchar(30) DEFAULT NULL COMMENT '版本号',
  `ver_name` varchar(100) DEFAULT NULL COMMENT '版本名称',
  `raw_data` longtext COMMENT '原始数据行(TAB分隔)',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(诊断名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_diag_code` (`diag_code`),
  KEY `idx_ver` (`ver`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=43 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='疾病与诊断目录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `drug_catalog`
--

DROP TABLE IF EXISTS `drug_catalog`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `drug_catalog` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '绉熸埛ID',
  `med_list_codg` varchar(50) DEFAULT NULL COMMENT '医疗目录编码',
  `drug_prodname` varchar(500) DEFAULT NULL COMMENT '药品商品名',
  `genname_codg` varchar(50) DEFAULT NULL COMMENT '通用名编号',
  `drug_genname` varchar(500) DEFAULT NULL COMMENT '药品通用名',
  `chemname` varchar(200) DEFAULT NULL COMMENT '化学名称',
  `alis` varchar(200) DEFAULT NULL COMMENT '别名',
  `eng_name` varchar(255) DEFAULT NULL COMMENT '英文名称',
  `dosform` varchar(50) DEFAULT NULL COMMENT '药品剂型',
  `dosform_name` varchar(100) DEFAULT NULL COMMENT '药品剂型名称',
  `drug_type` varchar(20) DEFAULT NULL COMMENT '药品类别',
  `drug_type_name` varchar(100) DEFAULT NULL COMMENT '药品类别名称',
  `drug_spec` varchar(255) DEFAULT NULL COMMENT '药品规格',
  `min_useunt` varchar(30) DEFAULT NULL COMMENT '最小使用单位',
  `min_salunt` varchar(30) DEFAULT NULL COMMENT '最小销售单位',
  `min_unt` varchar(30) DEFAULT NULL COMMENT '最小计量单位',
  `min_prcunt` varchar(50) DEFAULT NULL COMMENT '最小计价单位',
  `wubi` varchar(50) DEFAULT NULL COMMENT '五笔助记码',
  `pinyin` varchar(50) DEFAULT NULL COMMENT '拼音助记码',
  `prod_entp_name` varchar(200) DEFAULT NULL COMMENT '生产企业名称',
  `vali_flag` varchar(3) DEFAULT NULL COMMENT '有效标志',
  `rid` varchar(40) DEFAULT NULL COMMENT '唯一记录号',
  `ver` varchar(30) DEFAULT NULL COMMENT '版本号',
  `ver_name` varchar(100) DEFAULT NULL COMMENT '版本名称',
  `raw_data` longtext COMMENT '原始数据行(TAB分隔)',
  PRIMARY KEY (`id`),
  KEY `idx_med_list_codg` (`med_list_codg`),
  KEY `idx_ver` (`ver`),
  KEY `idx_rid` (`rid`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='西药中成药目录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_admission_cert`
--

DROP TABLE IF EXISTS `his_admission_cert`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_admission_cert` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '住院证ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `visit_id` bigint NOT NULL COMMENT '就诊ID',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名',
  `admit_dept_id` bigint DEFAULT NULL COMMENT '拟收治科室ID',
  `admit_dept_name` varchar(100) DEFAULT NULL COMMENT '拟收治科室名称',
  `admit_diagnosis` varchar(500) DEFAULT NULL COMMENT '入院诊断',
  `condition_summary` varchar(1000) DEFAULT NULL COMMENT '病情摘要',
  `admit_purpose` varchar(500) DEFAULT NULL COMMENT '入院目的',
  `urgency` tinyint DEFAULT '1' COMMENT '紧急程度:1-普通 2-急 3-危急',
  `status` tinyint DEFAULT '1' COMMENT '状态:1-已开具 2-已入院 3-已作废',
  `apply_dr_id` bigint DEFAULT NULL COMMENT '开具医师ID',
  `apply_dr_name` varchar(50) DEFAULT NULL COMMENT '开具医师姓名',
  `apply_time` datetime DEFAULT NULL COMMENT '开具时间',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID(开具时就诊科室归属机构)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `admitted_visit_id` bigint DEFAULT NULL COMMENT '消费本证的住院就诊ID(his_inp_visit.id, 已入院时回写)',
  `pre_flag` tinyint DEFAULT '0' COMMENT '预开卡标记:1预开卡锁床 0常规',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_visit` (`visit_id`)
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院证';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_anesthesia`
--

DROP TABLE IF EXISTS `his_anesthesia`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_anesthesia` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `surgery_id` bigint NOT NULL COMMENT '手术ID(his_surgery.id)',
  `anesthesia_type` tinyint DEFAULT NULL COMMENT '麻醉类型:1全麻 2局麻 3椎管内 4神经阻滞 5复合 6其他',
  `anesthesia_method` varchar(100) DEFAULT NULL COMMENT '具体麻醉方式',
  `pre_assessment` text COMMENT '术前评估JSON',
  `induction_time` datetime DEFAULT NULL COMMENT '诱导时间',
  `intubation_time` datetime DEFAULT NULL COMMENT '插管时间',
  `extubation_time` datetime DEFAULT NULL COMMENT '拔管时间',
  `recovery_time` datetime DEFAULT NULL COMMENT '苏醒时间',
  `vital_signs` text COMMENT '生命体征JSON数组',
  `anesthesia_events` text COMMENT '术中事件JSON',
  `post_assessment` text COMMENT '术后评估JSON',
  `status` tinyint DEFAULT '1' COMMENT '状态:1记录中 2已完成',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_surgery` (`surgery_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='麻醉记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_bed`
--

DROP TABLE IF EXISTS `his_bed`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_bed` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `bed_no` varchar(20) NOT NULL COMMENT '床位号',
  `ward_id` bigint NOT NULL COMMENT '病区ID(his_ward.id)',
  `room_no` varchar(20) DEFAULT NULL COMMENT '房间号',
  `bed_type` tinyint DEFAULT '1' COMMENT '床位类型:1普通 2抢救 3监护 4隔离',
  `status` tinyint DEFAULT '0' COMMENT '状态:0空床 1占用 2停用',
  `patient_id` bigint DEFAULT NULL COMMENT '当前患者ID(his_patient.id)',
  `inp_visit_id` bigint DEFAULT NULL COMMENT '当前住院就诊ID(his_inp_visit.id)',
  `daily_price` decimal(10,2) DEFAULT '0.00' COMMENT '床位日费用',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `bed_level` int DEFAULT '1' COMMENT '床位等级:1普通 2单间 3监护 4特需',
  `is_extra_bed` tinyint DEFAULT '0' COMMENT '是否加床:1是 0否',
  `gender_limit` int DEFAULT '0' COMMENT '性别限制:0无 1男 2女',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_ward_bedno` (`tenant_id`,`ward_id`,`bed_no`),
  KEY `idx_ward_status` (`tenant_id`,`ward_id`,`status`),
  KEY `idx_inp_visit` (`inp_visit_id`)
) ENGINE=InnoDB AUTO_INCREMENT=61 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院床位';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_case_front_page`
--

DROP TABLE IF EXISTS `his_case_front_page`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_case_front_page` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `visit_id` bigint NOT NULL COMMENT '就诊ID',
  `patient_id` bigint NOT NULL COMMENT '患者ID',
  `admission_date` datetime DEFAULT NULL COMMENT '入院时间',
  `discharge_date` datetime DEFAULT NULL COMMENT '出院时间',
  `los_days` int DEFAULT NULL COMMENT '住院天数',
  `admission_dept_id` bigint DEFAULT NULL COMMENT '入院科室ID(his_dept.id)',
  `discharge_dept_id` bigint DEFAULT NULL COMMENT '出院科室ID(his_dept.id)',
  `admission_diag_code` varchar(50) DEFAULT NULL COMMENT '入院诊断编码',
  `admission_diag_name` varchar(200) DEFAULT NULL COMMENT '入院诊断名称',
  `discharge_main_diag_code` varchar(50) DEFAULT NULL COMMENT '出院主要诊断编码',
  `discharge_main_diag_name` varchar(200) DEFAULT NULL COMMENT '出院主要诊断名称',
  `discharge_other_diags` text COMMENT '其他出院诊断JSON',
  `pathology_diag` varchar(200) DEFAULT NULL COMMENT '病理诊断',
  `injury_poison_code` varchar(50) DEFAULT NULL COMMENT '损伤中毒编码',
  `operation_records` text COMMENT '手术操作JSON',
  `blood_type` varchar(10) DEFAULT NULL COMMENT '血型',
  `rh` varchar(10) DEFAULT NULL COMMENT 'Rh血型',
  `allergy_drugs` varchar(500) DEFAULT NULL COMMENT '药物过敏史',
  `autopsy` tinyint DEFAULT '0' COMMENT '尸检:0否 1是',
  `total_cost` decimal(12,2) DEFAULT '0.00' COMMENT '总费用',
  `drug_cost` decimal(12,2) DEFAULT '0.00' COMMENT '药品费',
  `exam_cost` decimal(12,2) DEFAULT '0.00' COMMENT '检查费',
  `treatment_cost` decimal(12,2) DEFAULT '0.00' COMMENT '治疗费',
  `bed_cost` decimal(12,2) DEFAULT '0.00' COMMENT '床位费',
  `nursing_cost` decimal(12,2) DEFAULT '0.00' COMMENT '护理费',
  `material_cost` decimal(12,2) DEFAULT '0.00' COMMENT '材料费',
  `other_cost` decimal(12,2) DEFAULT '0.00' COMMENT '其他费用',
  `self_pay` decimal(12,2) DEFAULT '0.00' COMMENT '自付金额',
  `insurance_pay` decimal(12,2) DEFAULT '0.00' COMMENT '医保支付金额',
  `quality_score` int DEFAULT NULL COMMENT '病案质量评分',
  `qc_doctor_id` bigint DEFAULT NULL COMMENT '质控医生ID(his_staff.id)',
  `qc_time` datetime DEFAULT NULL COMMENT '质控时间',
  `status` tinyint DEFAULT '1' COMMENT '状态:1草稿 2已提交 3已审核',
  `doctor_sign_img` varchar(255) DEFAULT NULL COMMENT '医师签名图URL(/uploads/...)',
  `nurse_sign_img` varchar(255) DEFAULT NULL COMMENT '护士签名图URL(/uploads/...)',
  `qc_sign_img` varchar(255) DEFAULT NULL COMMENT '质控签名图URL(/uploads/...)',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tcm_diag` text COMMENT '中医诊断证候组合JSON(P8 拼装: [{diagCode,diagName,syndromeCode,syndromeName}])',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_visit` (`visit_id`,`tenant_id`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病案首页';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_cdss_rule`
--

DROP TABLE IF EXISTS `his_cdss_rule`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_cdss_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `name` varchar(200) NOT NULL COMMENT '规则名称',
  `rule_type` varchar(30) NOT NULL COMMENT '类型:drug_conflict/dose_alert/repeat_exam/critical_value/guideline',
  `condition_expr` text NOT NULL COMMENT '条件表达式JSON',
  `action_message` varchar(500) DEFAULT NULL COMMENT '提示消息',
  `severity` varchar(10) DEFAULT 'info' COMMENT '严重程度:info/warning/block',
  `knowledge_source` varchar(200) DEFAULT NULL COMMENT '知识来源',
  `applicable_depts` text COMMENT '适用科室ID列表JSON',
  `enabled` tinyint DEFAULT '1' COMMENT '是否启用:1启用 0停用',
  `sort_no` int DEFAULT '0' COMMENT '排序号',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `rule_code` varchar(50) DEFAULT NULL COMMENT '规则编码(种子/程序化识别, 租户内唯一)',
  PRIMARY KEY (`id`),
  KEY `idx_cdss_type` (`rule_type`,`enabled`)
) ENGINE=InnoDB AUTO_INCREMENT=17 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CDSS临床决策支持规则';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_charge_addon_rule`
--

DROP TABLE IF EXISTS `his_charge_addon_rule`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_charge_addon_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID(空=租户通用)',
  `dept_id` bigint DEFAULT NULL COMMENT '科室ID(空=全院通用)',
  `item_id` bigint NOT NULL COMMENT '主项目ID(his_base_item.id)',
  `item_name` varchar(200) DEFAULT NULL COMMENT '主项目名称(冗余)',
  `dim_type` varchar(30) NOT NULL COMMENT '加收维度: part/index/consult/herb_process',
  `dim_threshold` int NOT NULL DEFAULT '1' COMMENT '触发阈值',
  `calc_mode` varchar(20) NOT NULL DEFAULT 'fixed' COMMENT '计价方式: fixed/formula',
  `unit_price` decimal(12,2) DEFAULT NULL COMMENT '加收单位价格',
  `formula` varchar(500) DEFAULT NULL COMMENT '计费公式(formula模式)',
  `addon_item_code` varchar(50) DEFAULT NULL COMMENT '加收项编码',
  `addon_item_name` varchar(200) DEFAULT NULL COMMENT '加收项名称',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态:1启用 0停用',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_item` (`tenant_id`,`item_id`,`status`),
  KEY `idx_org_dept` (`tenant_id`,`org_id`,`dept_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='开立项目自动计费(加收)规则';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_charge_bill`
--

DROP TABLE IF EXISTS `his_charge_bill`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_charge_bill` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `bill_no` varchar(30) NOT NULL COMMENT '收费单号',
  `visit_id` bigint DEFAULT NULL COMMENT '就诊ID',
  `registration_id` bigint DEFAULT NULL COMMENT '挂号ID',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名',
  `bill_type` tinyint DEFAULT '1' COMMENT '单据类型:1门诊收费 2门诊退费',
  `total_amount` decimal(12,2) DEFAULT '0.00' COMMENT '总金额',
  `self_pay` decimal(12,2) DEFAULT '0.00' COMMENT '自付金额',
  `fund_pay` decimal(12,2) DEFAULT '0.00' COMMENT '基金支付',
  `cash_pay` decimal(12,2) DEFAULT '0.00' COMMENT '现金支付',
  `acct_pay` decimal(12,2) DEFAULT '0.00' COMMENT '个账支付',
  `setl_id` varchar(50) DEFAULT NULL COMMENT '医保结算ID',
  `status` tinyint DEFAULT '0' COMMENT '状态:0待收费 1已收费 2已退费',
  `charge_by` varchar(50) DEFAULT NULL COMMENT '收费员',
  `charge_time` datetime DEFAULT NULL COMMENT '收费时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `pay_method` varchar(20) DEFAULT NULL COMMENT '主要支付方式:CASH/WECHAT/ALIPAY/CARD/INSURANCE/FREE',
  `origin_bill_id` bigint DEFAULT NULL COMMENT '退费关联原单ID(退费单指向原收费单)',
  `invoice_no` varchar(50) DEFAULT NULL COMMENT '发票号',
  `yb_status` tinyint NOT NULL DEFAULT '0' COMMENT '医保结算状态:0未结算 1结算中 2已结算 3撤销中 4已撤销 9冲正中',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_bill_no` (`tenant_id`,`bill_no`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`,`charge_time`),
  KEY `idx_visit` (`visit_id`)
) ENGINE=InnoDB AUTO_INCREMENT=12486 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='收费单';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_charge_bill_item`
--

DROP TABLE IF EXISTS `his_charge_bill_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_charge_bill_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `bill_id` bigint NOT NULL COMMENT '收费单ID',
  `item_type` tinyint NOT NULL COMMENT '项目类型:1药品 2检查 3治疗 4材料',
  `ref_type` varchar(30) DEFAULT NULL COMMENT '来源类型:prescription_item/order_item',
  `ref_id` bigint DEFAULT NULL COMMENT '来源ID',
  `item_code` varchar(50) DEFAULT NULL COMMENT '项目编码',
  `item_name` varchar(200) NOT NULL COMMENT '项目名称',
  `spec` varchar(200) DEFAULT NULL COMMENT '规格',
  `qty` decimal(12,2) NOT NULL COMMENT '数量',
  `price` decimal(12,4) NOT NULL COMMENT '单价',
  `amount` decimal(12,2) NOT NULL COMMENT '金额',
  `med_list_codg` varchar(50) DEFAULT NULL COMMENT '医保编码',
  `med_list_name` varchar(200) DEFAULT NULL COMMENT '医保名称',
  `ratio` decimal(5,4) DEFAULT '0.0000' COMMENT '自付比例',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `refunded_qty` decimal(12,4) DEFAULT '0.0000' COMMENT '已退数量(部分退费追踪)',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_bill` (`bill_id`)
) ENGINE=InnoDB AUTO_INCREMENT=12388 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='收费明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_charge_item`
--

DROP TABLE IF EXISTS `his_charge_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_charge_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '项目ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `item_code` varchar(40) NOT NULL COMMENT '院内收费项目编码',
  `item_name` varchar(200) NOT NULL COMMENT '院内项目名称',
  `item_type` varchar(20) DEFAULT '诊疗' COMMENT '项目大类:药品/诊疗/耗材/其他',
  `item_cat` varchar(255) DEFAULT NULL COMMENT '细分类别(msi_hb/cat_name 含分类路径)',
  `spec` varchar(200) DEFAULT NULL COMMENT '规格',
  `unit` varchar(30) DEFAULT NULL COMMENT '单位',
  `price` decimal(12,4) DEFAULT '0.0000' COMMENT '单价',
  `med_list_codg` varchar(50) DEFAULT NULL COMMENT '医保医疗目录编码(对照)',
  `medins_list_codg` varchar(50) DEFAULT NULL COMMENT '医保机构目录编码(对照)',
  `med_chrgitm_type` varchar(10) DEFAULT NULL COMMENT '医疗收费项目类别:01-药品 02-诊疗 03-耗材',
  `chrgitm_lv` varchar(10) DEFAULT NULL COMMENT '收费项目等级:01-甲 02-乙 03-丙',
  `selfpay_prop` decimal(5,4) DEFAULT '0.0000' COMMENT '自付比例(0-1)',
  `status` tinyint DEFAULT '1' COMMENT '状态:1-启用 0-停用',
  `memo` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `price_l1` decimal(12,4) DEFAULT NULL COMMENT '一级机构价格(牵头机构统一定义)',
  `price_l2` decimal(12,4) DEFAULT NULL COMMENT '二级机构价格(牵头机构统一定义)',
  `price_l3` decimal(12,4) DEFAULT NULL COMMENT '三级机构价格(牵头机构统一定义)',
  `nat_item_code` varchar(32) DEFAULT NULL COMMENT '全国医疗服务项目编码(std_msi_nat/msi_hb.item_code)',
  `loc_item_code` varchar(32) DEFAULT NULL COMMENT '湖北地方项目编码(std_med_service.loc_item_code)',
  `item_content` varchar(2000) DEFAULT NULL COMMENT '项目内涵',
  `item_excluded` varchar(1000) DEFAULT NULL COMMENT '除外内容',
  `invoice_class` varchar(100) DEFAULT NULL COMMENT '收费票据分类',
  `acct_class` varchar(100) DEFAULT NULL COMMENT '会计科目分类',
  `dept_caty` varchar(100) DEFAULT NULL COMMENT '医疗科室类别',
  `src_type` varchar(30) DEFAULT NULL COMMENT '来源标准字典key(med_service/msi_hb/msi_nat/院内自定义)',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `src_code` varchar(50) DEFAULT NULL COMMENT '来源编码(标准字典行编码)',
  `eff_date` date DEFAULT NULL COMMENT '生效日期',
  `end_date` date DEFAULT NULL COMMENT '作废日期',
  `mr_cost_class` varchar(200) DEFAULT NULL COMMENT '病案首页费用分类(归并), 值域std_mr_cost_class.raw_value',
  `cat_code` varchar(20) DEFAULT NULL COMMENT '物价分类码(std_msi_cat.cat_code, 导入自msi_nat.cat_code)',
  `prev_yb_code` varchar(64) DEFAULT NULL COMMENT '变更前医保码(上一次对照的医保编码)',
  `yb_map_eff_time` datetime DEFAULT NULL COMMENT '医保对照生效时间(当前医保码开始生效时刻)',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(项目名称首字母, 自动生成只读)',
  `abbr_code` varchar(64) DEFAULT NULL COMMENT '自定义简码(人工维护, 选填)',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_tenant_item` (`tenant_id`,`item_code`),
  KEY `idx_med_list_codg` (`med_list_codg`)
) ENGINE=InnoDB AUTO_INCREMENT=33726 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='收费项目(本院目录)表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_charge_pricebak_0925`
--

DROP TABLE IF EXISTS `his_charge_pricebak_0925`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_charge_pricebak_0925` (
  `id` bigint NOT NULL DEFAULT '0' COMMENT '项目ID',
  `price` decimal(12,4) DEFAULT '0.0000' COMMENT '单价',
  `price_l1` decimal(12,4) DEFAULT NULL COMMENT '一级机构价格(牵头机构统一定义)',
  `price_l2` decimal(12,4) DEFAULT NULL COMMENT '二级机构价格(牵头机构统一定义)',
  `price_l3` decimal(12,4) DEFAULT NULL COMMENT '三级机构价格(牵头机构统一定义)'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_chronic_disease`
--

DROP TABLE IF EXISTS `his_chronic_disease`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_chronic_disease` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `patient_id` bigint NOT NULL COMMENT '患者ID(his_patient.id)',
  `patient_name` varchar(100) DEFAULT NULL COMMENT '患者姓名(冗余)',
  `dise_code` varchar(50) NOT NULL COMMENT '病种编码',
  `dise_name` varchar(200) DEFAULT NULL COMMENT '病种名称',
  `dise_type` varchar(20) DEFAULT NULL COMMENT '备案类型:1门特 2门慢',
  `register_no` varchar(50) DEFAULT NULL COMMENT '备案编号',
  `valid_from` date DEFAULT NULL COMMENT '备案有效期起',
  `valid_to` date DEFAULT NULL COMMENT '备案有效期止',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态:1有效 0失效',
  `source` varchar(20) DEFAULT 'manual' COMMENT '来源: manual/import/insutype',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_patient` (`tenant_id`,`patient_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='患者门诊慢特病备案';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_comp_task`
--

DROP TABLE IF EXISTS `his_comp_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_comp_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户(医共体)ID',
  `biz_type` varchar(20) NOT NULL COMMENT '业务类型:CHARGE/REFUND/PARTIAL_REFUND',
  `ref_id` bigint DEFAULT NULL COMMENT '关联业务主键(收费单ID等)',
  `action` varchar(30) NOT NULL COMMENT '动作:RESOLVE_UNKNOWN等',
  `txn_log_id` bigint DEFAULT NULL COMMENT '触发任务的原交易日志ID(his_yb_txn_log.id)',
  `status` varchar(10) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/DONE/DEAD',
  `attempts` int NOT NULL DEFAULT '0' COMMENT '已尝试次数',
  `next_run` datetime NOT NULL COMMENT '下次执行时间(指数退避)',
  `memo` varchar(500) DEFAULT NULL COMMENT '备注/最近一次执行结果',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_comptask_tenant` (`tenant_id`),
  KEY `idx_comptask_next` (`next_run`),
  KEY `idx_comptask_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医保补偿任务(批次4: UNKNOWN交易收敛)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_cons_catalog`
--

DROP TABLE IF EXISTS `his_cons_catalog`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_cons_catalog` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户(医共体)ID',
  `cons_code` varchar(40) NOT NULL COMMENT '院内耗材编码(租户内唯一)',
  `yb_cons_code` varchar(50) DEFAULT NULL COMMENT '医保耗材代码(20位, std_consumable.cons_code)',
  `reg_cert_no` varchar(500) DEFAULT NULL COMMENT '注册证号',
  `name` varchar(300) NOT NULL COMMENT '耗材通用名(std_consumable.hi_genname)',
  `cat1` varchar(100) DEFAULT NULL COMMENT '医保一级分类',
  `cat2` varchar(100) DEFAULT NULL COMMENT '医保二级分类',
  `cat3` varchar(100) DEFAULT NULL COMMENT '医保三级分类',
  `spec_model` varchar(255) DEFAULT NULL COMMENT '规格型号(补充录入)',
  `material` varchar(100) DEFAULT NULL COMMENT '材质',
  `feature` varchar(100) DEFAULT NULL COMMENT '特征',
  `manufacturer` varchar(200) DEFAULT NULL COMMENT '生产企业',
  `min_unit` varchar(20) DEFAULT NULL COMMENT '最小计价单位(个/套)',
  `pack_unit` varchar(20) DEFAULT NULL COMMENT '采购单位(盒/包)',
  `pack_ratio` int DEFAULT NULL COMMENT '包装换算比(采购单位→最小单位)',
  `purchase_price` decimal(12,4) DEFAULT NULL COMMENT '进货价(最小单位)',
  `charge_price` decimal(12,4) DEFAULT NULL COMMENT '收费价(单独收费项)',
  `charge_flag` tinyint DEFAULT '1' COMMENT '收费方式:1单独收费 0包含性(不单独收费)',
  `chrgitm_lv` varchar(20) DEFAULT NULL COMMENT '甲乙丙类编码(cv_code:chrgitm_lv)',
  `chrgitm_lv_name` varchar(50) DEFAULT NULL COMMENT '甲乙丙类名称(字典回填)',
  `chrgitm_lv_src` varchar(50) DEFAULT NULL COMMENT '甲乙丙类来源标识',
  `selfpay_prop` decimal(5,4) DEFAULT NULL COMMENT '自付比例(0-1)',
  `pay_std` varchar(50) DEFAULT NULL COMMENT '医保支付标准',
  `high_value_flag` tinyint DEFAULT '0' COMMENT '高值耗材标志:1是 0否',
  `implant_flag` tinyint DEFAULT '0' COMMENT '植入类标志:1是 0否',
  `sterile_flag` tinyint DEFAULT '0' COMMENT '无菌标志:1是 0否',
  `status` tinyint DEFAULT '1' COMMENT '状态:1启用 0停用',
  `eff_date` date DEFAULT NULL COMMENT '生效日期',
  `end_date` date DEFAULT NULL COMMENT '作废日期',
  `memo` varchar(500) DEFAULT NULL COMMENT '备注',
  `src_type` varchar(30) DEFAULT NULL COMMENT '来源标准字典key(consumable/院内自定义)',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `src_code` varchar(50) DEFAULT NULL COMMENT '来源编码(标准字典行编码)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `prev_yb_code` varchar(64) DEFAULT NULL COMMENT '变更前医保码(上一次对照的医保编码)',
  `yb_map_eff_time` datetime DEFAULT NULL COMMENT '医保对照生效时间(当前医保码开始生效时刻)',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(耗材名称首字母, 自动生成只读)',
  `abbr_code` varchar(64) DEFAULT NULL COMMENT '自定义简码(人工维护, 选填)',
  `manufacturer_code` varchar(40) DEFAULT NULL COMMENT '生产企业编码(std_supplier.sup_code)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_cons_code` (`tenant_id`,`cons_code`),
  KEY `idx_yb_cons_code` (`yb_cons_code`),
  KEY `idx_cons_name` (`name`(80)),
  KEY `idx_cons_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=501 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医共体耗材目录(牵头机构维护)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_consent`
--

DROP TABLE IF EXISTS `his_consent`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_consent` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `visit_id` bigint NOT NULL COMMENT '门诊就诊ID(his_visit.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名',
  `consent_type` int NOT NULL COMMENT '同意书类型:1特殊检查 2特殊治疗 3输血 4自费 5病危',
  `title` varchar(200) NOT NULL COMMENT '同意书标题',
  `content` text COMMENT '同意书内容(告知事项正文)',
  `doctor_id` bigint DEFAULT NULL COMMENT '谈话医师ID(his_staff.id)',
  `doctor_name` varchar(50) DEFAULT NULL COMMENT '谈话医师姓名',
  `patient_sign_name` varchar(50) DEFAULT NULL COMMENT '患者/家属签署姓名',
  `relation` varchar(20) DEFAULT NULL COMMENT '签署人与患者关系(本人/配偶/父母子女等)',
  `witness_name` varchar(50) DEFAULT NULL COMMENT '见证人姓名',
  `sign_time` datetime DEFAULT NULL COMMENT '患者签署时间',
  `doctor_sign_time` datetime DEFAULT NULL COMMENT '医师签署时间',
  `status` int DEFAULT '1' COMMENT '状态:1待签 2已签 3已撤销',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_visit` (`visit_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='门诊知情同意书';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_consult_request`
--

DROP TABLE IF EXISTS `his_consult_request`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_consult_request` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '会诊申请ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `visit_id` bigint NOT NULL COMMENT '就诊ID',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名',
  `apply_dept_id` bigint DEFAULT NULL COMMENT '申请科室ID',
  `apply_dept_name` varchar(100) DEFAULT NULL COMMENT '申请科室名称',
  `apply_dr_id` bigint DEFAULT NULL COMMENT '申请医师ID',
  `apply_dr_name` varchar(50) DEFAULT NULL COMMENT '申请医师姓名',
  `consult_dept_id` bigint DEFAULT NULL COMMENT '受邀会诊科室ID',
  `consult_dept_name` varchar(100) DEFAULT NULL COMMENT '受邀会诊科室名称',
  `consult_purpose` varchar(500) DEFAULT NULL COMMENT '会诊目的',
  `condition_summary` varchar(1000) DEFAULT NULL COMMENT '病情摘要',
  `urgency` tinyint DEFAULT '1' COMMENT '紧急程度:1-普通 2-急 3-紧急',
  `expected_time` datetime DEFAULT NULL COMMENT '期望会诊时间',
  `status` tinyint DEFAULT '1' COMMENT '状态:1-已申请 2-已接受 3-已完成 4-已拒绝',
  `consult_opinion` varchar(1000) DEFAULT NULL COMMENT '会诊意见(受邀科室反馈)',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID(申请科室归属机构)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_visit` (`visit_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会诊申请';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_critical_rule`
--

DROP TABLE IF EXISTS `his_critical_rule`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_critical_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `item_code` varchar(50) NOT NULL COMMENT '项目编码',
  `item_name` varchar(100) DEFAULT NULL COMMENT '项目名称',
  `low_threshold` decimal(12,4) DEFAULT NULL COMMENT '危急低阈值(低于即危急)',
  `high_threshold` decimal(12,4) DEFAULT NULL COMMENT '危急高阈值(高于即危急)',
  `patient_type` varchar(20) DEFAULT NULL COMMENT '患者类型:adult成人/child儿童(空=通用)',
  `is_active` tinyint DEFAULT '1' COMMENT '是否启用:1启用 0停用',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_item_ptype` (`tenant_id`,`item_code`,`patient_type`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=15 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='危急值规则(项目阈值判定)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_critical_value`
--

DROP TABLE IF EXISTS `his_critical_value`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_critical_value` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `report_id` bigint NOT NULL COMMENT '报告ID(his_exam_report.id)',
  `order_id` bigint DEFAULT NULL COMMENT '医嘱单ID(his_order.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `item_code` varchar(50) DEFAULT NULL COMMENT '项目编码',
  `item_name` varchar(100) DEFAULT NULL COMMENT '项目名称',
  `result_value` varchar(50) DEFAULT NULL COMMENT '结果值(触发危急值)',
  `discover_time` datetime DEFAULT NULL COMMENT '发现时间',
  `verify_tech_id` bigint DEFAULT NULL COMMENT '复核技师ID(his_staff.id)',
  `verify_time` datetime DEFAULT NULL COMMENT '复核时间',
  `notify_time` datetime DEFAULT NULL COMMENT '通知时间(电话通知临床)',
  `notify_target` varchar(100) DEFAULT NULL COMMENT '通知对象(医生/护士)',
  `receive_time` datetime DEFAULT NULL COMMENT '接收时间(临床回执)',
  `receive_person` varchar(50) DEFAULT NULL COMMENT '接收人',
  `handle_time` datetime DEFAULT NULL COMMENT '处置时间',
  `handle_measures` varchar(500) DEFAULT NULL COMMENT '处置措施',
  `status` tinyint DEFAULT '0' COMMENT '状态:0待复核 1已通知 2已接收 3已处置',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`),
  KEY `idx_report` (`report_id`),
  KEY `idx_patient` (`tenant_id`,`patient_id`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='危急值记录(通知-接收-处置闭环)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_critical_value_record`
--

DROP TABLE IF EXISTS `his_critical_value_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_critical_value_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `visit_id` bigint NOT NULL COMMENT '就诊ID',
  `patient_id` bigint NOT NULL COMMENT '患者ID',
  `item_code` varchar(50) NOT NULL COMMENT '检验项目编码',
  `item_name` varchar(100) NOT NULL COMMENT '检验项目名称',
  `result_value` varchar(50) NOT NULL COMMENT '检验结果值',
  `unit` varchar(30) DEFAULT NULL COMMENT '单位',
  `ref_range` varchar(100) DEFAULT NULL COMMENT '参考范围',
  `alert_level` tinyint DEFAULT '1' COMMENT '告警级别:1危急 2异常',
  `report_time` datetime NOT NULL COMMENT '报告时间',
  `notify_doctor_time` datetime DEFAULT NULL COMMENT '通知医生时间',
  `confirm_doctor_id` bigint DEFAULT NULL COMMENT '确认医生ID(his_staff.id)',
  `confirm_time` datetime DEFAULT NULL COMMENT '确认时间',
  `handle_measures` text COMMENT '处置措施',
  `handle_time` datetime DEFAULT NULL COMMENT '处置时间',
  `status` tinyint DEFAULT '1' COMMENT '状态:1待通知 2已通知 3已确认 4已处置 5已关闭',
  `notification_id` bigint DEFAULT NULL COMMENT '关联通知ID(his_inp_notification.id)',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_visit_status` (`visit_id`,`status`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='危急值处理记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_critical_value_rule`
--

DROP TABLE IF EXISTS `his_critical_value_rule`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_critical_value_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `item_code` varchar(50) NOT NULL COMMENT '检验项目编码',
  `item_name` varchar(100) NOT NULL COMMENT '检验项目名称',
  `unit` varchar(30) DEFAULT NULL COMMENT '单位',
  `critical_low` decimal(12,4) DEFAULT NULL COMMENT '危急值下限(低于即告警)',
  `critical_high` decimal(12,4) DEFAULT NULL COMMENT '危急值上限(高于即告警)',
  `gender` tinyint DEFAULT '0' COMMENT '性别:0通用 1男 2女',
  `age_min` int DEFAULT NULL COMMENT '年龄下限',
  `age_max` int DEFAULT NULL COMMENT '年龄上限',
  `alert_level` tinyint DEFAULT '1' COMMENT '告警级别:1危急 2异常',
  `enabled` tinyint DEFAULT '1' COMMENT '是否启用',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_item_tenant` (`item_code`,`gender`,`tenant_id`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=1939 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='危急值规则';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_daily_settle`
--

DROP TABLE IF EXISTS `his_daily_settle`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_daily_settle` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `settle_date` date NOT NULL COMMENT '结算日期',
  `operator` varchar(50) DEFAULT NULL COMMENT '操作人',
  `total_count` int DEFAULT '0' COMMENT '收费笔数',
  `total_amount` decimal(12,2) DEFAULT '0.00' COMMENT '总金额',
  `refund_count` int DEFAULT '0' COMMENT '退费笔数',
  `refund_amount` decimal(12,2) DEFAULT '0.00' COMMENT '退费金额',
  `cash_total` decimal(12,2) DEFAULT '0.00' COMMENT '现金合计',
  `fund_total` decimal(12,2) DEFAULT '0.00' COMMENT '基金合计',
  `acct_total` decimal(12,2) DEFAULT '0.00' COMMENT '个账合计',
  `status` tinyint DEFAULT '0' COMMENT '状态:0未日结 1已日结',
  `settle_time` datetime DEFAULT NULL COMMENT '日结时间',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `reg_count` int DEFAULT '0' COMMENT '挂号笔数(净额: 挂号-退号)',
  `reg_amount` decimal(12,2) DEFAULT '0.00' COMMENT '挂号费净额(挂号-退号)',
  `wechat_total` decimal(12,2) DEFAULT '0.00' COMMENT '微信合计(收费-退费净额)',
  `alipay_total` decimal(12,2) DEFAULT '0.00' COMMENT '支付宝合计(收费-退费净额)',
  `card_total` decimal(12,2) DEFAULT '0.00' COMMENT '银行卡合计(收费-退费净额)',
  `free_total` decimal(12,2) DEFAULT '0.00' COMMENT '减免合计(收费-退费净额)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_org_date` (`tenant_id`,`org_id`,`settle_date`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='门诊日结';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_dept`
--

DROP TABLE IF EXISTS `his_dept`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_dept` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '科室ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '归属机构ID(sys_org)',
  `dept_code` varchar(30) NOT NULL COMMENT '科室编码(院内)',
  `dept_name` varchar(100) NOT NULL COMMENT '科室名称',
  `dept_type` varchar(20) DEFAULT '临床' COMMENT '科室类型:临床/医技/行政',
  `dept_caty` varchar(50) DEFAULT NULL COMMENT '医保科别(用于2201)',
  `yb_dept_code` varchar(30) DEFAULT NULL COMMENT '医保科室编码(如与院内不同)',
  `phone` varchar(30) DEFAULT NULL COMMENT '联系电话',
  `loc_desc` varchar(200) DEFAULT NULL COMMENT '位置描述',
  `sort_no` int DEFAULT '0' COMMENT '排序号',
  `status` tinyint DEFAULT '1' COMMENT '状态:1-启用 0-停用',
  `memo` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `dept_caty_name` varchar(100) DEFAULT NULL COMMENT '医保科别名称(cv_code:caty回填)',
  `dept_caty_src` varchar(50) DEFAULT NULL COMMENT '医保科别来源标识',
  `parent_id` bigint DEFAULT '0' COMMENT '上级科室ID(0/null=顶级大类)',
  `dept_category` varchar(100) DEFAULT NULL COMMENT '科室大类(多选,逗号分隔:门诊科室/住院科室/病区护理/医技科室/行政后勤)',
  `dept_level` tinyint DEFAULT '2' COMMENT '层级:1-大类 2-科室 3-窗口/诊室',
  `open_clinic` tinyint DEFAULT '1' COMMENT '门诊开诊:1-开诊 0-未开诊(仅门诊科室大类生效)',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  `abbr_code` varchar(64) DEFAULT NULL COMMENT '自定义简码(人工维护, 选填)',
  `def_pharmacy_west` bigint DEFAULT NULL COMMENT '默认发药药房-西药渠道(his_pharmacy_def.id)',
  `def_pharmacy_tcm` bigint DEFAULT NULL COMMENT '默认发药药房-中药渠道(his_pharmacy_def.id)',
  `cert_audit_required` tinyint DEFAULT '0' COMMENT '诊断证明审核开关:0直接可打印 1需审核',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_tenant_dept` (`tenant_id`,`dept_code`)
) ENGINE=InnoDB AUTO_INCREMENT=1048 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='科室表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_diag_dict`
--

DROP TABLE IF EXISTS `his_diag_dict`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_diag_dict` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户(医共体)ID',
  `dict_type` varchar(10) NOT NULL COMMENT '字典类型:west-西医诊断(ICD-10) tcm-中医诊断 symp-中医症候 oper-手术操作(ICD-9) tumor-肿瘤形态学',
  `code` varchar(40) NOT NULL COMMENT '院内编码(租户内同类型唯一, 导入时取标准字典编码)',
  `name` varchar(200) NOT NULL COMMENT '名称(诊断/术式/症候名)',
  `yb_code` varchar(40) DEFAULT NULL COMMENT '医保编码(国标版源导入时=code, 如E11.900; 仅国标来源时留空待补)',
  `category` varchar(200) DEFAULT NULL COMMENT '类目(标准字典附加列: 章节/系统类目/亚目等)',
  `sort_no` int DEFAULT '0' COMMENT '排序号',
  `status` tinyint DEFAULT '1' COMMENT '状态:1启用 0停用',
  `memo` varchar(500) DEFAULT NULL COMMENT '备注',
  `src_type` varchar(30) DEFAULT NULL COMMENT '来源标准字典key(icd10/icd10_nat/icd9/icd9_nat/morphology/tcm_disease_new/tcm_disease/tcm_syndrome_new/tcm_syndrome)',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档(标准字典行src_doc, 逐行不同)',
  `src_code` varchar(50) DEFAULT NULL COMMENT '来源编码(标准字典行编码)',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  `abbr_code` varchar(64) DEFAULT NULL COMMENT '自定义简码(人工维护, 选填)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_diag_type_code` (`tenant_id`,`dict_type`,`code`),
  KEY `idx_dd_type` (`dict_type`),
  KEY `idx_dd_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=57205 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医共体诊断字典(五类: 西医/中医/症候/手术/肿瘤, 牵头机构维护)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_diag_freq`
--

DROP TABLE IF EXISTS `his_diag_freq`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_diag_freq` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `staff_id` bigint DEFAULT NULL COMMENT '医师ID(his_staff.id, 个人常用维度)',
  `dept_id` bigint DEFAULT NULL COMMENT '科室ID(his_dept.id, 科室高频维度)',
  `diag_code` varchar(50) NOT NULL COMMENT '诊断代码',
  `diag_name` varchar(200) DEFAULT NULL COMMENT '诊断名称',
  `diag_class` varchar(20) DEFAULT NULL COMMENT '诊断类别: west/tcm/symp/oper/tumor',
  `use_count` int NOT NULL DEFAULT '0' COMMENT '累计使用次数',
  `last_time` datetime DEFAULT NULL COMMENT '最近使用时间',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_staff_dept_diag` (`tenant_id`,`staff_id`,`dept_id`,`diag_code`),
  KEY `idx_dept` (`tenant_id`,`dept_id`,`use_count`),
  KEY `idx_staff` (`tenant_id`,`staff_id`,`use_count`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='诊断高频使用沉淀(诊断助手数据源)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_diag_template_link`
--

DROP TABLE IF EXISTS `his_diag_template_link`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_diag_template_link` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `diag_code` varchar(50) NOT NULL COMMENT '诊断代码',
  `diag_name` varchar(200) DEFAULT NULL COMMENT '诊断名称',
  `template_type` varchar(20) NOT NULL COMMENT '模板类型: rx_set处方组套/order_set医嘱组套',
  `template_id` bigint NOT NULL COMMENT '模板ID(his_medical_template.id)',
  `dept_id` bigint DEFAULT NULL COMMENT '适用科室ID(空=通用)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_diag` (`tenant_id`,`diag_code`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='诊断→医嘱/处方模板关联映射';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_diagnosis`
--

DROP TABLE IF EXISTS `his_diagnosis`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_diagnosis` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '诊断ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `visit_id` bigint NOT NULL COMMENT '就诊ID',
  `diag_type` varchar(10) DEFAULT '1' COMMENT '诊断类别:1-门诊诊断',
  `diag_srt_no` int DEFAULT '1' COMMENT '诊断排序号',
  `diag_code` varchar(50) DEFAULT NULL COMMENT '诊断代码(医保疾病目录)',
  `diag_name` varchar(200) DEFAULT NULL COMMENT '诊断名称',
  `maindiag_flag` varchar(2) DEFAULT '0' COMMENT '主诊断标识:0-否 1-是',
  `diag_dept` varchar(100) DEFAULT NULL COMMENT '诊断科室',
  `dise_dor_no` varchar(30) DEFAULT NULL COMMENT '诊断医生编码',
  `dise_dor_name` varchar(50) DEFAULT NULL COMMENT '诊断医生姓名',
  `diag_time` datetime DEFAULT NULL COMMENT '诊断时间',
  `adm_cond` varchar(10) DEFAULT NULL COMMENT '入院病情(门诊可空)',
  `vali_flag` varchar(2) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `diag_class` varchar(20) DEFAULT NULL COMMENT '诊断类别: west/tcm/symp/oper/tumor(源自医共体诊断字典dict_type)',
  `tooth_position` varchar(200) DEFAULT NULL COMMENT '牙位编码(FDI/Palmer, 口腔诊断专用)',
  `syndrome_code` varchar(30) DEFAULT NULL COMMENT '证候编码(his_diag_dict.dict_type=symp; P8 中医诊断拼装写入)',
  `syndrome_name` varchar(100) DEFAULT NULL COMMENT '证候名称(P8 中医诊断拼装写入, 字典回填)',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_visit` (`visit_id`)
) ENGINE=InnoDB AUTO_INCREMENT=15265 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='诊断表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_dict_edit_log`
--

DROP TABLE IF EXISTS `his_dict_edit_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_dict_edit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户(医共体)ID',
  `catalog_type` varchar(20) NOT NULL COMMENT '目录类型:charge/drug/cons',
  `catalog_id` bigint NOT NULL COMMENT '院内条目ID',
  `item_code` varchar(64) DEFAULT NULL COMMENT '院内编码(冗余)',
  `item_name` varchar(200) DEFAULT NULL COMMENT '院内名称(冗余)',
  `field_name` varchar(60) DEFAULT NULL COMMENT '变更字段属性名',
  `field_label` varchar(60) DEFAULT NULL COMMENT '变更字段中文名',
  `old_value` varchar(500) DEFAULT NULL COMMENT '修改前值(展示值)',
  `new_value` varchar(500) DEFAULT NULL COMMENT '修改后值(展示值)',
  `source` varchar(20) DEFAULT NULL COMMENT '来源:编辑/新增',
  `operator` varchar(50) DEFAULT NULL COMMENT '操作人(登录账号)',
  `operator_name` varchar(50) DEFAULT NULL COMMENT '操作人姓名',
  `org_id` bigint DEFAULT NULL COMMENT '操作人归属机构',
  `change_time` datetime NOT NULL COMMENT '变更发生时间',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_delog_tenant` (`tenant_id`),
  KEY `idx_delog_item` (`catalog_type`,`catalog_id`),
  KEY `idx_delog_time` (`change_time`)
) ENGINE=InnoDB AUTO_INCREMENT=43 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一字典字段级修改留痕表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_discharge_pickup`
--

DROP TABLE IF EXISTS `his_discharge_pickup`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_discharge_pickup` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID',
  `dispense_id` bigint DEFAULT NULL COMMENT '关联住院发药记录ID',
  `order_id` bigint DEFAULT NULL COMMENT '来源医嘱ID',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名',
  `drug_catalog_id` bigint DEFAULT NULL COMMENT '药品目录ID',
  `drug_name` varchar(200) DEFAULT NULL COMMENT '药品名称',
  `spec` varchar(100) DEFAULT NULL COMMENT '规格',
  `qty` decimal(12,4) DEFAULT '0.0000' COMMENT '带药量(最小单位)',
  `invoice_no` varchar(40) DEFAULT NULL COMMENT '取药发票号',
  `window_id` bigint DEFAULT NULL COMMENT '发药窗口ID',
  `pickup_status` tinyint DEFAULT '1' COMMENT '状态:1待取药 2已取待发药核 3已二次核发',
  `pickup_by` varchar(50) DEFAULT NULL COMMENT '取药经受人',
  `pickup_time` datetime DEFAULT NULL COMMENT '取药时间',
  `verify2_by` varchar(50) DEFAULT NULL COMMENT '二次核发药师',
  `verify2_time` datetime DEFAULT NULL COMMENT '二次核发时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_visit` (`inp_visit_id`,`pickup_status`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_dispense` (`dispense_id`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='出院带药取药二次核发';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_disease_report`
--

DROP TABLE IF EXISTS `his_disease_report`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_disease_report` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `visit_id` bigint DEFAULT NULL COMMENT '就诊ID(his_visit.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `diag_code` varchar(50) DEFAULT NULL COMMENT '诊断代码',
  `diag_name` varchar(200) DEFAULT NULL COMMENT '诊断名称',
  `report_type` tinyint DEFAULT '1' COMMENT '报卡类型:1法定传染病 2慢性病 3其他',
  `report_no` varchar(50) DEFAULT NULL COMMENT '报卡编号',
  `report_status` tinyint DEFAULT '0' COMMENT '报卡状态:0待报 1已报 2已审核',
  `report_content` varchar(2000) DEFAULT NULL COMMENT '报卡内容摘要',
  `report_time` datetime DEFAULT NULL COMMENT '报告时间',
  `reporter` varchar(50) DEFAULT NULL COMMENT '报告人姓名',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_visit` (`tenant_id`,`visit_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='疾病报卡(与诊断关联留痕)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_dispense`
--

DROP TABLE IF EXISTS `his_dispense`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_dispense` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `dispense_no` varchar(30) NOT NULL COMMENT '发药单号',
  `visit_id` bigint DEFAULT NULL COMMENT '就诊ID',
  `prescription_id` bigint NOT NULL COMMENT '处方ID',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名',
  `doctor_name` varchar(50) DEFAULT NULL COMMENT '医生姓名',
  `dept_name` varchar(100) DEFAULT NULL COMMENT '科室名称',
  `status` tinyint DEFAULT '0' COMMENT '状态:0待发药 1已调配 2已发药 3已退药',
  `dispense_by` varchar(50) DEFAULT NULL COMMENT '发药人',
  `dispense_time` datetime DEFAULT NULL COMMENT '发药时间',
  `check_by` varchar(50) DEFAULT NULL COMMENT '核对人',
  `check_time` datetime DEFAULT NULL COMMENT '核对时间',
  `total_amount` decimal(12,2) DEFAULT '0.00' COMMENT '总金额',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `pharmacy_id` bigint DEFAULT NULL COMMENT '药房ID(his_pharmacy_def.id)',
  `stock_amount` decimal(12,2) DEFAULT NULL COMMENT '实发批次零售金额(发药时按出库批次价汇总, 院内对账)',
  `price_diff` decimal(12,2) DEFAULT NULL COMMENT '价差=实发-计费(不向患者补退, 仅对账)',
  `transfer_from_pharmacy_id` bigint DEFAULT NULL COMMENT '改派来源药房ID(库存不足改派留痕)',
  `window_id` bigint DEFAULT NULL COMMENT '发药窗口ID(his_pharmacy_window.id, P1智能分窗分配结果)',
  `signin_status` tinyint DEFAULT '0' COMMENT '窗口签到状态:0未签到 1已签到(仅签到型窗口有意义)',
  `trace_required` tinyint DEFAULT '0' COMMENT '本单追溯码强制:0否 1是(P3, 窗口级或药品级命中)',
  `trace_scanned` int DEFAULT '0' COMMENT '本单发药已绑定追溯码数(P3)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_dispense_no` (`tenant_id`,`dispense_no`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`,`create_time`),
  KEY `idx_prescription` (`prescription_id`)
) ENGINE=InnoDB AUTO_INCREMENT=8501 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发药记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_dispense_item`
--

DROP TABLE IF EXISTS `his_dispense_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_dispense_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `dispense_id` bigint NOT NULL COMMENT '发药记录ID(his_dispense.id)',
  `prescription_item_id` bigint DEFAULT NULL COMMENT '处方明细ID(his_prescription_item.id, 行级来源)',
  `drug_catalog_id` bigint DEFAULT NULL COMMENT '医共体药品目录ID(his_drug_catalog.id)',
  `drug_code` varchar(50) DEFAULT NULL COMMENT '药品编码',
  `drug_name` varchar(200) DEFAULT NULL COMMENT '药品名称',
  `spec` varchar(200) DEFAULT NULL COMMENT '规格',
  `unit` varchar(20) DEFAULT NULL COMMENT '单位',
  `dispense_qty` decimal(16,4) DEFAULT '0.0000' COMMENT '本行发药数量(最小单位)',
  `billed_price` decimal(16,4) DEFAULT '0.0000' COMMENT '划价快照单价(原价, 退药计价依据)',
  `billed_amount` decimal(12,2) DEFAULT '0.00' COMMENT '划价快照金额(原价, 本行整退时直接引用保证不退不平)',
  `returned_qty` decimal(16,4) DEFAULT '0.0000' COMMENT '本行累计已退数量',
  `returned_amount` decimal(12,2) DEFAULT '0.00' COMMENT '本行累计已退金额',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dispense_item` (`tenant_id`,`dispense_id`,`prescription_item_id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_dispense` (`dispense_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发药明细行(行级部分退)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_dog_bite_register`
--

DROP TABLE IF EXISTS `his_dog_bite_register`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_dog_bite_register` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `visit_id` bigint NOT NULL COMMENT '门诊就诊ID(his_visit.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名',
  `expose_time` datetime DEFAULT NULL COMMENT '暴露(咬伤/抓伤)时间',
  `animal_type` varchar(20) DEFAULT NULL COMMENT '致伤动物:犬/猫/其他',
  `dog_info` varchar(200) DEFAULT NULL COMMENT '动物来源与免疫/观察情况',
  `wound_grade` int DEFAULT NULL COMMENT '伤口分级:1Ⅰ级 2Ⅱ级 3Ⅲ级',
  `wound_parts` varchar(100) DEFAULT NULL COMMENT '暴露部位',
  `wound_count` int DEFAULT '1' COMMENT '伤口数量',
  `wound_handling` varchar(300) DEFAULT NULL COMMENT '伤口处置(冲洗/消毒等)',
  `vaccine_plan` varchar(50) DEFAULT NULL COMMENT '免疫程序:五针法/四针法(2-1-1)',
  `vaccine_first_time` datetime DEFAULT NULL COMMENT '首针时间',
  `vaccine_next_date` date DEFAULT NULL COMMENT '下次接种日期',
  `immunoglobulin` tinyint DEFAULT '0' COMMENT '被动免疫制剂:1已注射 0未注射',
  `doctor_id` bigint DEFAULT NULL COMMENT '登记医师ID(his_staff.id)',
  `doctor_name` varchar(50) DEFAULT NULL COMMENT '登记医师姓名',
  `status` int DEFAULT '1' COMMENT '状态:1已登记 0作废',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_visit` (`visit_id`),
  KEY `idx_patient` (`tenant_id`,`patient_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='犬伤暴露登记';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_drug_catalog`
--

DROP TABLE IF EXISTS `his_drug_catalog`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_drug_catalog` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户(医共体)ID',
  `drug_code` varchar(40) NOT NULL COMMENT '院内药品编码(租户内唯一)',
  `yb_drug_code` varchar(50) DEFAULT NULL COMMENT '医保药品代码(std_drug.drug_code)',
  `drug_std_code` varchar(50) DEFAULT NULL COMMENT '药品本位码',
  `approval_no` varchar(100) DEFAULT NULL COMMENT '批准文号',
  `generic_name` varchar(300) NOT NULL COMMENT '通用名(std_drug.reg_name)',
  `trade_name` varchar(300) DEFAULT NULL COMMENT '商品名',
  `major_class` varchar(50) DEFAULT NULL COMMENT '大类(西药/中成药等)',
  `dosform` varchar(50) DEFAULT NULL COMMENT '剂型编码(cv_code:dosform, 允手工文本)',
  `dosform_name` varchar(100) DEFAULT NULL COMMENT '剂型名称(字典回填)',
  `dosform_src` varchar(50) DEFAULT NULL COMMENT '剂型来源标识',
  `spec` varchar(255) DEFAULT NULL COMMENT '规格(如0.25g*24粒)',
  `manufacturer` varchar(200) DEFAULT NULL COMMENT '生产企业',
  `mkt_holder` varchar(200) DEFAULT NULL COMMENT '上市许可持有人',
  `chrgitm_lv` varchar(20) DEFAULT NULL COMMENT '甲乙丙类编码(cv_code:chrgitm_lv)',
  `chrgitm_lv_name` varchar(50) DEFAULT NULL COMMENT '甲乙丙类名称(字典回填)',
  `chrgitm_lv_src` varchar(50) DEFAULT NULL COMMENT '甲乙丙类来源标识',
  `selfpay_prop` decimal(5,4) DEFAULT NULL COMMENT '自付比例(0-1)',
  `pay_std_prep` varchar(30) DEFAULT NULL COMMENT '医保支付标准(最小制剂单位)',
  `nego_flag` varchar(20) DEFAULT NULL COMMENT '谈判药品标识',
  `msd_flag` varchar(20) DEFAULT NULL COMMENT '门诊特殊疾病对应标识',
  `ltd_self_flag` varchar(20) DEFAULT NULL COMMENT '限定支付范围自费标识',
  `limit_scope` varchar(500) DEFAULT NULL COMMENT '限定支付范围说明',
  `dose_unit` varchar(20) DEFAULT NULL COMMENT '剂量单位(cv_code:dose_unit g/mg/IU/mL等)',
  `unit_dose` decimal(12,4) DEFAULT NULL COMMENT '每最小包装单位含药量(如0.25g/粒)',
  `min_unit` varchar(20) DEFAULT NULL COMMENT '最小包装/发药单位(片/粒/支, 药房计量基准)',
  `pack_unit` varchar(20) DEFAULT NULL COMMENT '采购/大包装单位(盒/瓶/箱, 药库记账单位)',
  `pack_ratio` int DEFAULT NULL COMMENT '包装换算比(大包装→最小单位, 如24粒/盒)',
  `round_rule` tinyint DEFAULT '1' COMMENT '发药取整规则:1向上 2向下 3四舍五入',
  `purchase_price` decimal(12,4) DEFAULT NULL COMMENT '进货价(最小单位)',
  `retail_price` decimal(12,4) DEFAULT NULL COMMENT '零售价(最小单位)',
  `zero_margin` tinyint DEFAULT '1' COMMENT '零差率标志:1是 0否',
  `drug_class` varchar(30) DEFAULT NULL COMMENT '药品管理类别编码(cv_code:drug_class 一般/麻醉/精一/精二/毒性/放射/易制毒)',
  `drug_class_name` varchar(50) DEFAULT NULL COMMENT '药品管理类别名称(字典回填)',
  `drug_class_src` varchar(50) DEFAULT NULL COMMENT '药品管理类别来源标识',
  `abx_grade` varchar(6) DEFAULT NULL COMMENT '抗菌药物分级(hbvalue:HBCV08.50.029 11非限制/12限制/13特殊使用)',
  `abx_grade_name` varchar(50) DEFAULT NULL COMMENT '抗菌药物分级名称(字典回填)',
  `abx_grade_src` varchar(50) DEFAULT NULL COMMENT '抗菌药物分级来源标识',
  `otc_flag` tinyint DEFAULT '0' COMMENT 'OTC标志:1是 0否',
  `essential_flag` tinyint DEFAULT '0' COMMENT '基本药物标志:1是 0否',
  `preg_class` varchar(10) DEFAULT NULL COMMENT '妊娠用药分级(A/B/C/D/X)',
  `skin_test_flag` tinyint DEFAULT '0' COMMENT '皮试标志:1需皮试 0否',
  `storage_cond` varchar(30) DEFAULT NULL COMMENT '储存条件编码(cv_code:storage_cond)',
  `storage_cond_name` varchar(50) DEFAULT NULL COMMENT '储存条件名称(字典回填)',
  `storage_cond_src` varchar(50) DEFAULT NULL COMMENT '储存条件来源标识',
  `max_qty_once` decimal(12,2) DEFAULT NULL COMMENT '单次处方最大量(最小单位, 管制药品)',
  `status` tinyint DEFAULT '1' COMMENT '状态:1启用 0停用',
  `eff_date` date DEFAULT NULL COMMENT '生效日期',
  `end_date` date DEFAULT NULL COMMENT '作废日期',
  `memo` varchar(500) DEFAULT NULL COMMENT '备注',
  `src_type` varchar(30) DEFAULT NULL COMMENT '来源标准字典key(drug/tcm/preparation/院内自定义)',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `src_code` varchar(50) DEFAULT NULL COMMENT '来源编码(标准字典行编码)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `prev_yb_code` varchar(64) DEFAULT NULL COMMENT '变更前医保码(上一次对照的医保编码)',
  `yb_map_eff_time` datetime DEFAULT NULL COMMENT '医保对照生效时间(当前医保码开始生效时刻)',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(通用名首字母, 自动生成只读)',
  `abbr_code` varchar(64) DEFAULT NULL COMMENT '自定义简码(人工维护, 选填)',
  `manufacturer_code` varchar(40) DEFAULT NULL COMMENT '生产企业编码(std_supplier.sup_code)',
  `mkt_holder_code` varchar(40) DEFAULT NULL COMMENT '上市许可持有人编码(std_supplier.sup_code)',
  `commodity_code` varchar(64) DEFAULT NULL COMMENT '商品码/条形码(EAN-13等, 三码校验之一)(P3)',
  `supervision_code` varchar(64) DEFAULT NULL COMMENT '电子监管码(中国药品电子监管码, 三码校验之一)(P3)',
  `trace_flag` tinyint DEFAULT '0' COMMENT '药品级追溯码强制开关:1需扫 0否(P3, 与窗口级trace_required取或)',
  `indication_codes` varchar(500) DEFAULT NULL COMMENT '适应症编码(院内用药规则, 供联审)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_drug_code` (`tenant_id`,`drug_code`),
  KEY `idx_yb_drug_code` (`yb_drug_code`),
  KEY `idx_generic_name` (`generic_name`(80)),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=552 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医共体药品目录(牵头机构维护, 含三级单位包装/剂量换算)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_drug_maintenance`
--

DROP TABLE IF EXISTS `his_drug_maintenance`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_drug_maintenance` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `warehouse_id` bigint DEFAULT NULL COMMENT '药库ID(his_warehouse_def.id)',
  `mnt_no` varchar(30) NOT NULL COMMENT '养护单号(YH+yyyyMMdd+4位)',
  `mnt_date` date DEFAULT NULL COMMENT '养护日期',
  `mnt_type` tinyint DEFAULT '1' COMMENT '建单方式:1手动 2自动 3模板',
  `drug_count` int DEFAULT '0' COMMENT '养护品种行数',
  `abnormal_count` int DEFAULT '0' COMMENT '异常品行数',
  `conclusion` varchar(200) DEFAULT NULL COMMENT '整体结论',
  `mnt_by` varchar(50) DEFAULT NULL COMMENT '养护人',
  `mnt_time` datetime DEFAULT NULL COMMENT '养护完成时间',
  `status` tinyint DEFAULT '0' COMMENT '状态:0草稿 1已完成',
  `template_id` bigint DEFAULT NULL COMMENT '来源模板ID(mnt_type=3)',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_mnt_no` (`tenant_id`,`mnt_no`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_wh` (`tenant_id`,`org_id`,`warehouse_id`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='药品养护单主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_drug_maintenance_item`
--

DROP TABLE IF EXISTS `his_drug_maintenance_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_drug_maintenance_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `mnt_id` bigint NOT NULL COMMENT '养护单ID',
  `drug_catalog_id` bigint DEFAULT NULL COMMENT '药品目录ID',
  `drug_code` varchar(50) DEFAULT NULL COMMENT '药品编码',
  `drug_name` varchar(200) DEFAULT NULL COMMENT '药品名称',
  `spec` varchar(200) DEFAULT NULL COMMENT '规格',
  `batch_no` varchar(50) DEFAULT NULL COMMENT '批次号',
  `manufacturer` varchar(200) DEFAULT NULL COMMENT '生产厂家',
  `dosform` varchar(50) DEFAULT NULL COMMENT '剂型',
  `storage_cond` varchar(100) DEFAULT NULL COMMENT '储存条件',
  `qty` decimal(14,2) DEFAULT '0.00' COMMENT '在库数量',
  `exp_date` date DEFAULT NULL COMMENT '有效期',
  `measure` varchar(200) DEFAULT NULL COMMENT '养护措施',
  `result` tinyint DEFAULT '1' COMMENT '养护结果:1合格 2异常',
  `handler` varchar(50) DEFAULT NULL COMMENT '养护人',
  `conclusion` varchar(200) DEFAULT NULL COMMENT '结论/异常描述',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_mnt` (`mnt_id`)
) ENGINE=InnoDB AUTO_INCREMENT=23 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='药品养护单明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_drug_price_adjust`
--

DROP TABLE IF EXISTS `his_drug_price_adjust`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_drug_price_adjust` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID(空=全医共体调价)',
  `adjust_no` varchar(30) NOT NULL COMMENT '调价单号(TJ+yyyyMMdd+4位)',
  `scope` varchar(20) DEFAULT 'DRUG' COMMENT '范围:ALL/DRUG',
  `effective_date` date DEFAULT NULL COMMENT '生效日期',
  `status` tinyint DEFAULT '0' COMMENT '状态:0草稿 1已生效 2已作废',
  `reason` varchar(500) DEFAULT NULL COMMENT '调价原因',
  `operator` varchar(50) DEFAULT NULL COMMENT '操作人',
  `effect_time` datetime DEFAULT NULL COMMENT '生效时间',
  `total_diff_amount` decimal(14,2) DEFAULT '0.00' COMMENT '在库金额影响合计',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `price_domain` varchar(16) NOT NULL DEFAULT 'ALL' COMMENT '调价域: CATALOG目录/WAREHOUSE药库/PHARMACY药房/ALL全部(默认向后兼容)',
  `target_warehouse_id` bigint DEFAULT NULL COMMENT '药库域目标库位(his_warehouse_def.id)',
  `target_pharmacy_id` bigint DEFAULT NULL COMMENT '药房域目标药房(his_pharmacy_def.id)',
  `auto_effect` tinyint NOT NULL DEFAULT '0' COMMENT '到生效日是否自动生效: 1=调度器认领生效, 0=仅手动',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_adjust_no` (`tenant_id`,`adjust_no`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='药品调价单(草稿→生效批次)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_drug_price_adjust_item`
--

DROP TABLE IF EXISTS `his_drug_price_adjust_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_drug_price_adjust_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `price_adjust_id` bigint NOT NULL COMMENT '调价单ID',
  `drug_catalog_id` bigint DEFAULT NULL COMMENT '药品目录ID',
  `drug_code` varchar(50) DEFAULT NULL COMMENT '药品编码(快照)',
  `drug_name` varchar(200) DEFAULT NULL COMMENT '药品名称(快照)',
  `spec` varchar(200) DEFAULT NULL COMMENT '规格(快照)',
  `old_purchase` decimal(12,4) DEFAULT NULL COMMENT '原进价',
  `new_purchase` decimal(12,4) DEFAULT NULL COMMENT '新进价',
  `old_retail` decimal(12,4) DEFAULT NULL COMMENT '原零售价',
  `new_retail` decimal(12,4) DEFAULT NULL COMMENT '新零售价',
  `impact_stock_qty` decimal(14,2) DEFAULT '0.00' COMMENT '当前在库数量(预览影响)',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_adjust` (`price_adjust_id`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='调价明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_drug_return`
--

DROP TABLE IF EXISTS `his_drug_return`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_drug_return` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `return_no` varchar(30) NOT NULL COMMENT '退药单号',
  `dispense_id` bigint NOT NULL COMMENT '发药记录ID',
  `visit_id` bigint DEFAULT NULL COMMENT '就诊ID',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名',
  `reason` varchar(500) DEFAULT NULL COMMENT '退药原因',
  `status` tinyint DEFAULT '0' COMMENT '状态:0待审核 1已退药 2已驳回',
  `return_amount` decimal(12,2) DEFAULT '0.00' COMMENT '退药金额',
  `approve_by` varchar(50) DEFAULT NULL COMMENT '审批人',
  `approve_time` datetime DEFAULT NULL COMMENT '审批时间',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `keep_ward_flag` tinyint DEFAULT '0' COMMENT '退药去向:0退回药房回库 1暂存病区不回收供下次发药冲抵(P4)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_return_no` (`tenant_id`,`return_no`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`),
  KEY `idx_dispense` (`dispense_id`)
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退药记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_drug_return_item`
--

DROP TABLE IF EXISTS `his_drug_return_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_drug_return_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `return_id` bigint NOT NULL COMMENT '退药记录ID(his_drug_return.id)',
  `dispense_item_id` bigint NOT NULL COMMENT '发药明细行ID(his_dispense_item.id)',
  `drug_catalog_id` bigint DEFAULT NULL COMMENT '医共体药品目录ID',
  `drug_code` varchar(50) DEFAULT NULL COMMENT '药品编码',
  `drug_name` varchar(200) DEFAULT NULL COMMENT '药品名称',
  `spec` varchar(200) DEFAULT NULL COMMENT '规格',
  `unit` varchar(20) DEFAULT NULL COMMENT '单位',
  `return_qty` decimal(16,4) DEFAULT '0.0000' COMMENT '本次该行退药数量',
  `return_amount` decimal(12,2) DEFAULT '0.00' COMMENT '本次该行退药金额(按划价原价)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_return` (`return_id`),
  KEY `idx_dispense_item` (`dispense_item_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退药明细行(行级部分退)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_drug_stock`
--

DROP TABLE IF EXISTS `his_drug_stock`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_drug_stock` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `drug_catalog_id` bigint NOT NULL COMMENT '药品目录ID',
  `drug_code` varchar(50) NOT NULL COMMENT '药品编码',
  `drug_name` varchar(200) NOT NULL COMMENT '药品名称',
  `spec` varchar(200) DEFAULT NULL COMMENT '规格',
  `dosform` varchar(50) DEFAULT NULL COMMENT '剂型',
  `batch_no` varchar(50) NOT NULL COMMENT '批次号',
  `manufacturer` varchar(200) DEFAULT NULL COMMENT '生产厂家',
  `qty` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '库存数量',
  `cost_price` decimal(12,4) DEFAULT NULL COMMENT '进价',
  `retail_price` decimal(12,4) DEFAULT NULL COMMENT '零售价',
  `prod_date` date DEFAULT NULL COMMENT '生产日期',
  `exp_date` date DEFAULT NULL COMMENT '有效期',
  `warn_qty` decimal(12,2) DEFAULT '10.00' COMMENT '预警量',
  `status` tinyint DEFAULT '1' COMMENT '状态:1正常 0停用',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `warehouse_id` bigint DEFAULT NULL COMMENT '药库ID(his_warehouse_def.id)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_org_wh_drug_batch` (`tenant_id`,`org_id`,`warehouse_id`,`drug_catalog_id`,`batch_no`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org` (`tenant_id`,`org_id`)
) ENGINE=InnoDB AUTO_INCREMENT=96 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='药品库存(批次级)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_drug_trace_code`
--

DROP TABLE IF EXISTS `his_drug_trace_code`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_drug_trace_code` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `location_id` bigint DEFAULT NULL COMMENT '所属库位ID(药库/药房库存位)',
  `drug_catalog_id` bigint DEFAULT NULL COMMENT '药品目录ID',
  `drug_code` varchar(50) DEFAULT NULL COMMENT '药品编码(快照)',
  `batch_no` varchar(50) DEFAULT NULL COMMENT '批次号(快照)',
  `trace_code` varchar(64) NOT NULL COMMENT '医保药品追溯码',
  `status` tinyint DEFAULT '0' COMMENT '状态:0在库 1已发药 2已退货 3已报废/调拨在途 9已上报',
  `min_pack_qty` decimal(12,2) DEFAULT '1.00' COMMENT '最小包装数量',
  `ref_bill_type` varchar(20) DEFAULT NULL COMMENT '关联单据类型(in/dispense/return/transfer)',
  `ref_bill_id` bigint DEFAULT NULL COMMENT '关联单据ID',
  `patient_id` bigint DEFAULT NULL COMMENT '发药绑定患者ID',
  `visit_id` bigint DEFAULT NULL COMMENT '发药绑定就诊ID',
  `dispense_id` bigint DEFAULT NULL COMMENT '发药记录ID',
  `upload_status` tinyint DEFAULT '0' COMMENT '报送状态:0未报送 1报送中 2失败待补 9已报送(批次5 M1归一)',
  `upload_time` datetime DEFAULT NULL COMMENT '报送时间',
  `upload_receipt` varchar(200) DEFAULT NULL COMMENT '报送回执( Mock)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `upload_msgid` varchar(40) DEFAULT NULL COMMENT '2404报送发送方报文ID(UNKNOWN复核/重发凭据, 批次5 M1)',
  `upload_batch_no` varchar(40) DEFAULT NULL COMMENT '2404报送批次/平台回执报文ID(批次5 M1)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_trace` (`tenant_id`,`trace_code`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_loc_drug` (`tenant_id`,`location_id`,`drug_catalog_id`),
  KEY `idx_status` (`tenant_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=51 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医保药品追溯码';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_emr_audit_log`
--

DROP TABLE IF EXISTS `his_emr_audit_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_emr_audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `record_id` bigint DEFAULT NULL COMMENT '关联病历ID',
  `scope` tinyint DEFAULT '1' COMMENT '适用范围:1住院 2门诊',
  `action` varchar(32) DEFAULT NULL COMMENT '动作:CREATE/UPDATE/VIEW/PRINT/SIGN/DELETE/SUBMIT/AUDIT',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID(his_staff.id)',
  `operator_name` varchar(64) DEFAULT NULL COMMENT '操作人姓名(冗余留痕)',
  `detail` text COMMENT '变更摘要JSON',
  `ip_address` varchar(64) DEFAULT NULL COMMENT '操作来源IP',
  `create_time` datetime DEFAULT NULL COMMENT '操作时间',
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_audit_record` (`record_id`,`scope`),
  KEY `idx_audit_operator` (`operator_id`,`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=2106309109031133187 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病历操作审计日志';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_emr_dataset`
--

DROP TABLE IF EXISTS `his_emr_dataset`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_emr_dataset` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `code` varchar(50) NOT NULL COMMENT '数据集编码',
  `name` varchar(200) NOT NULL COMMENT '数据集名称',
  `scope` tinyint DEFAULT '0' COMMENT '适用范围:0全部 1住院 2门诊 3护理',
  `description` varchar(500) DEFAULT NULL COMMENT '描述',
  `status` tinyint DEFAULT '1' COMMENT '状态:1启用 0停用',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_ds_code` (`code`),
  KEY `idx_ds_scope` (`scope`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病历数据集(章节/小节/数据元结构)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_emr_dataset_element`
--

DROP TABLE IF EXISTS `his_emr_dataset_element`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_emr_dataset_element` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `dataset_id` bigint NOT NULL COMMENT '所属数据集ID(his_emr_dataset.id)',
  `chapter_key` varchar(50) NOT NULL COMMENT '章节key',
  `chapter_name` varchar(100) NOT NULL COMMENT '章节名称',
  `section_key` varchar(50) DEFAULT NULL COMMENT '小节key',
  `section_name` varchar(100) DEFAULT NULL COMMENT '小节名称',
  `field_key` varchar(100) NOT NULL COMMENT '数据元key(全局唯一标识)',
  `field_name` varchar(200) NOT NULL COMMENT '数据元名称',
  `field_type` varchar(30) NOT NULL COMMENT '类型:text/number/date/datetime/select/multiselect/checkbox/dict/textarea',
  `dict_source` varchar(100) DEFAULT NULL COMMENT '字典来源(dict_type或自定义值域编码)',
  `default_value` varchar(500) DEFAULT NULL COMMENT '默认值',
  `required` tinyint DEFAULT '0' COMMENT '是否必填:1是 0否',
  `readonly` tinyint DEFAULT '0' COMMENT '是否只读:1是 0否',
  `no_copy` tinyint DEFAULT '0' COMMENT '防复制标志:1禁止 0允许',
  `print_hidden` tinyint DEFAULT '0' COMMENT '打印隐藏:1隐藏 0显示',
  `max_length` int DEFAULT NULL COMMENT '最大长度',
  `validation_rule` varchar(500) DEFAULT NULL COMMENT '校验规则(正则或表达式)',
  `sort_no` int DEFAULT '0' COMMENT '排序号',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_dse_dataset` (`dataset_id`),
  KEY `idx_dse_field` (`field_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病历数据集数据元';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_emr_drawing_template`
--

DROP TABLE IF EXISTS `his_emr_drawing_template`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_emr_drawing_template` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `code` varchar(50) NOT NULL COMMENT '模板编码',
  `title` varchar(200) NOT NULL COMMENT '模板名称',
  `category` varchar(50) NOT NULL COMMENT '类别:body_front/body_back/head/oral/hand/foot/wound/custom',
  `svg_template` text NOT NULL COMMENT 'SVG模板内容(标注底图)',
  `description` varchar(500) DEFAULT NULL COMMENT '说明',
  `status` int DEFAULT '1' COMMENT '状态:1启用 0停用',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_dt_category` (`category`),
  KEY `idx_dt_code` (`code`)
) ENGINE=InnoDB AUTO_INCREMENT=17 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医学图示模板(SVG)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_emr_element`
--

DROP TABLE IF EXISTS `his_emr_element`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_emr_element` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `scope` tinyint DEFAULT '1' COMMENT '适用范围:1住院 2门诊',
  `record_id` bigint DEFAULT NULL COMMENT '病历记录ID(住院his_inp_medical_record.id;门诊可空)',
  `visit_id` bigint DEFAULT NULL COMMENT '就诊ID(住院his_inp_visit.id/门诊his_visit.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `dept_id` bigint DEFAULT NULL COMMENT '科室ID(his_dept.id)',
  `doctor_id` bigint DEFAULT NULL COMMENT '医生/书写人ID(his_staff.id)',
  `record_type` int DEFAULT NULL COMMENT '记录类型(对应record_type/template_category)',
  `template_id` bigint DEFAULT NULL COMMENT '来源模板ID(his_emr_template.id)',
  `field_key` varchar(100) NOT NULL COMMENT '字段键(structure中的fieldKey)',
  `field_label` varchar(100) DEFAULT NULL COMMENT '字段名称(冗余便于展示/导出)',
  `term_code` varchar(50) DEFAULT NULL COMMENT '术语/值域编码(select/dict命中时)',
  `dict_source` varchar(50) DEFAULT NULL COMMENT '字典来源标识',
  `value_text` varchar(2000) DEFAULT NULL COMMENT '文本值',
  `value_num` decimal(18,4) DEFAULT NULL COMMENT '数值值',
  `value_date` datetime DEFAULT NULL COMMENT '日期/时间值',
  `value_unit` varchar(30) DEFAULT NULL COMMENT '单位(数值/体征)',
  `sort_no` int DEFAULT '0' COMMENT '同字段多值序号(table/array元素序)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_elem_scope_visit` (`scope`,`visit_id`),
  KEY `idx_elem_record` (`scope`,`record_id`),
  KEY `idx_elem_patient` (`patient_id`),
  KEY `idx_elem_field_text` (`field_key`,`value_text`(64)),
  KEY `idx_elem_field_num` (`field_key`,`value_num`),
  KEY `idx_elem_dept` (`dept_id`),
  KEY `idx_elem_doctor` (`doctor_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2106194073138827268 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病历数据元(要素)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_emr_fragment`
--

DROP TABLE IF EXISTS `his_emr_fragment`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_emr_fragment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `code` varchar(50) NOT NULL COMMENT '片段编码',
  `title` varchar(200) NOT NULL COMMENT '片段名称',
  `scope_level` int DEFAULT '0' COMMENT '作用域层级:0全院 1科室 2个人',
  `dept_id` bigint DEFAULT NULL COMMENT '归属科室ID(his_dept.id, scope_level=1 时使用)',
  `staff_id` bigint DEFAULT NULL COMMENT '归属职工ID(his_staff.id, scope_level=2 时使用)',
  `document` text COMMENT 'Tiptap ProseMirror JSON内容',
  `version` int DEFAULT '1' COMMENT '版本号(每次更新自增)',
  `status` int DEFAULT '1' COMMENT '状态:1启用 0停用',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_frag_code` (`code`),
  KEY `idx_frag_scope` (`scope_level`,`dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病历片段(可复用文档块)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_emr_macro`
--

DROP TABLE IF EXISTS `his_emr_macro`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_emr_macro` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `macro_code` varchar(50) NOT NULL COMMENT '宏变量编码',
  `macro_name` varchar(100) NOT NULL COMMENT '宏变量名称',
  `data_source` int DEFAULT NULL COMMENT '数据来源:1患者 2就诊 3诊断 4医嘱 5检验 6体征',
  `source_field` varchar(100) DEFAULT NULL COMMENT '来源字段',
  `format_pattern` varchar(200) DEFAULT NULL COMMENT '格式化模式',
  `description` varchar(500) DEFAULT NULL COMMENT '说明',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_macro_code` (`macro_code`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=2104508688000167939 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病历宏变量定义';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_emr_nlg_template`
--

DROP TABLE IF EXISTS `his_emr_nlg_template`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_emr_nlg_template` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `scope` tinyint DEFAULT '0' COMMENT '适用范围:0全部 1住院 2门诊',
  `section_key` varchar(50) NOT NULL COMMENT '病历章节key',
  `section_name` varchar(100) DEFAULT NULL COMMENT '章节名称',
  `template_text` text NOT NULL COMMENT '生成模板(含占位符如{field_key})',
  `connectors` text COMMENT '连接词配置JSON',
  `sort_rules` text COMMENT '语序规则JSON',
  `enabled` tinyint DEFAULT '1' COMMENT '是否启用:1启用 0停用',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_nlg_section` (`scope`,`section_key`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病历NLG生成模板';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_emr_phrase`
--

DROP TABLE IF EXISTS `his_emr_phrase`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_emr_phrase` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `category` varchar(32) DEFAULT NULL COMMENT '分类:chief_complaint/present_illness/past_history/physical_exam/diagnosis/treatment/nursing',
  `content` text COMMENT '常用语内容',
  `scope` tinyint DEFAULT '0' COMMENT '作用域:0全局 1科室 2个人',
  `dept_code` varchar(32) DEFAULT NULL COMMENT '科室编码(scope=1时使用)',
  `creator_id` bigint DEFAULT NULL COMMENT '创建人ID(his_staff.id, scope=2个人常用语)',
  `usage_count` int DEFAULT '0' COMMENT '使用次数(热度排序)',
  `enabled` tinyint DEFAULT '1' COMMENT '是否启用:1启用 0停用',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_phrase_cat` (`category`,`scope`,`dept_code`)
) ENGINE=InnoDB AUTO_INCREMENT=29 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病历常用语';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_emr_qc_defect`
--

DROP TABLE IF EXISTS `his_emr_qc_defect`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_emr_qc_defect` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint DEFAULT '0' COMMENT '租户ID',
  `org_id` bigint DEFAULT '0' COMMENT '机构ID',
  `record_id` bigint DEFAULT NULL COMMENT '病历记录ID(his_inp_medical_record.id)',
  `visit_id` bigint DEFAULT NULL COMMENT '就诊ID(住院his_inp_visit.id/门诊his_visit.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `rule_id` bigint DEFAULT NULL COMMENT '质控规则ID(his_emr_quality_rule.id)',
  `rule_code` varchar(50) DEFAULT NULL COMMENT '规则编码(冗余, 便于追溯)',
  `rule_name` varchar(200) DEFAULT NULL COMMENT '规则名称(冗余, 便于展示)',
  `defect_type` varchar(30) DEFAULT NULL COMMENT '缺陷类型:时效/完整/逻辑/规范/内涵/首页',
  `defect_desc` varchar(500) DEFAULT NULL COMMENT '缺陷描述',
  `deduct_score` decimal(5,2) DEFAULT '0.00' COMMENT '扣分分值',
  `severity` tinyint DEFAULT '1' COMMENT '严重程度:1提醒/2拦截/3禁止',
  `qc_stage` tinyint DEFAULT '1' COMMENT '质控环节:1运行/2归档',
  `auto_generated` tinyint DEFAULT '1' COMMENT '来源:0人工/1自动',
  `status` tinyint DEFAULT '0' COMMENT '状态:0未整改/1已整改/2已申诉/3申诉驳回/4豁免',
  `rectify_time` datetime DEFAULT NULL COMMENT '整改时间',
  `rectify_note` varchar(500) DEFAULT NULL COMMENT '整改说明',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_defect_record` (`record_id`),
  KEY `idx_defect_visit` (`visit_id`),
  KEY `idx_defect_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病历质控缺陷项';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_emr_qc_node`
--

DROP TABLE IF EXISTS `his_emr_qc_node`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_emr_qc_node` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint DEFAULT '0' COMMENT '租户ID',
  `org_id` bigint DEFAULT '0' COMMENT '机构ID',
  `record_id` bigint DEFAULT NULL COMMENT '病历记录ID(his_inp_medical_record.id)',
  `visit_id` bigint DEFAULT NULL COMMENT '就诊ID(住院his_inp_visit.id/门诊his_visit.id)',
  `node_type` varchar(30) DEFAULT NULL COMMENT '节点类型:创建/提交/签名/归档/质控/整改/申诉',
  `node_desc` varchar(500) DEFAULT NULL COMMENT '节点描述',
  `score_before` decimal(5,2) DEFAULT NULL COMMENT '操作前评分',
  `score_after` decimal(5,2) DEFAULT NULL COMMENT '操作后评分',
  `operator_id` bigint DEFAULT NULL COMMENT '操作人ID(his_staff.id)',
  `operator_name` varchar(50) DEFAULT NULL COMMENT '操作人姓名',
  `create_time` datetime DEFAULT NULL COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_node_record` (`record_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病历质控节点日志';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_emr_qc_notice`
--

DROP TABLE IF EXISTS `his_emr_qc_notice`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_emr_qc_notice` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint DEFAULT '0' COMMENT '租户ID',
  `org_id` bigint DEFAULT '0' COMMENT '机构ID',
  `notice_no` varchar(50) DEFAULT NULL COMMENT '通知单编号',
  `visit_id` bigint DEFAULT NULL COMMENT '就诊ID(住院his_inp_visit.id/门诊his_visit.id)',
  `record_id` bigint DEFAULT NULL COMMENT '病历记录ID(his_inp_medical_record.id)',
  `dept_id` bigint DEFAULT NULL COMMENT '责任科室ID(his_dept.id)',
  `dept_name` varchar(100) DEFAULT NULL COMMENT '责任科室名称(冗余)',
  `medical_group` varchar(100) DEFAULT NULL COMMENT '医疗组(责任单元)',
  `defect_ids` text COMMENT '关联缺陷ID列表JSON数组(his_emr_qc_defect.id)',
  `total_deduct` decimal(5,2) DEFAULT '0.00' COMMENT '累计扣分',
  `grade_before` varchar(10) DEFAULT NULL COMMENT '整改前等级(甲/乙/丙)',
  `require_rectify_date` date DEFAULT NULL COMMENT '要求整改截止日期',
  `status` tinyint DEFAULT '0' COMMENT '状态:0下发/1已读/2整改中/3已整改/4已复核/5已关闭/6申诉中',
  `issuer_id` bigint DEFAULT NULL COMMENT '下发人ID(his_staff.id)',
  `issuer_name` varchar(50) DEFAULT NULL COMMENT '下发人姓名',
  `issue_time` datetime DEFAULT NULL COMMENT '下发时间',
  `rectify_note` text COMMENT '整改说明',
  `rectify_time` datetime DEFAULT NULL COMMENT '整改完成时间',
  `reviewer_id` bigint DEFAULT NULL COMMENT '复核人ID(his_staff.id)',
  `reviewer_name` varchar(50) DEFAULT NULL COMMENT '复核人姓名',
  `review_time` datetime DEFAULT NULL COMMENT '复核时间',
  `review_result` varchar(200) DEFAULT NULL COMMENT '复核结果',
  `appeal_reason` text COMMENT '申诉理由',
  `appeal_time` datetime DEFAULT NULL COMMENT '申诉时间',
  `appeal_result` varchar(200) DEFAULT NULL COMMENT '申诉处理结果',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_notice_dept` (`dept_id`),
  KEY `idx_notice_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病历质控整改通知单';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_emr_quality_rule`
--

DROP TABLE IF EXISTS `his_emr_quality_rule`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_emr_quality_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `rule_code` varchar(50) NOT NULL COMMENT '规则编码',
  `rule_name` varchar(100) NOT NULL COMMENT '规则名称',
  `record_type` int DEFAULT NULL COMMENT '适用记录类型(his_inp_medical_record.record_type)',
  `rule_type` int DEFAULT NULL COMMENT '规则类型:1完整性 2时限性 3逻辑性 4规范性',
  `rule_config` text COMMENT '检查条件JSON',
  `deduct_score` decimal(3,1) DEFAULT NULL COMMENT '扣分分值',
  `severity` int DEFAULT NULL COMMENT '严重程度:1警告 2扣分 3一票否决',
  `description` varchar(500) DEFAULT NULL COMMENT '规则说明',
  `status` int DEFAULT '1' COMMENT '状态:1启用 0停用',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `qc_stage` tinyint DEFAULT '1' COMMENT '质控环节:1运行/2归档/0通用',
  `control_level` tinyint DEFAULT '1' COMMENT '控制级别:1提醒/2拦截/3禁止',
  `rule_category` varchar(50) DEFAULT NULL COMMENT '内涵子类:item_value/item_compare/disease/calculation/event',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2106281324246011907 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病历质控规则';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_emr_score_standard`
--

DROP TABLE IF EXISTS `his_emr_score_standard`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_emr_score_standard` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint DEFAULT '0' COMMENT '租户ID',
  `org_id` bigint DEFAULT '0' COMMENT '机构ID',
  `standard_code` varchar(50) DEFAULT NULL COMMENT '标准编码',
  `standard_name` varchar(200) DEFAULT NULL COMMENT '标准名称',
  `record_type` int DEFAULT NULL COMMENT '病历类型(null=全类型)',
  `category` varchar(30) DEFAULT NULL COMMENT '分类:时效/完整/逻辑/规范/内涵',
  `sub_category` varchar(50) DEFAULT NULL COMMENT '子类',
  `base_score` decimal(5,2) DEFAULT '0.00' COMMENT '基准分',
  `weight` decimal(3,2) DEFAULT '1.00' COMMENT '权重',
  `description` varchar(500) DEFAULT NULL COMMENT '标准说明',
  `eval_expression` text COMMENT 'SpEL/JSON表达式',
  `status` tinyint DEFAULT '1' COMMENT '状态:1启用 0停用',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `idx_standard_code` (`standard_code`,`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病历质控评分标准(卫健委)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_emr_signature`
--

DROP TABLE IF EXISTS `his_emr_signature`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_emr_signature` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `scope` tinyint DEFAULT '1' COMMENT '适用范围:1住院 2门诊',
  `record_id` bigint DEFAULT NULL COMMENT '住院病历ID(his_inp_medical_record.id)',
  `visit_id` bigint DEFAULT NULL COMMENT '就诊ID(住院his_inp_visit/门诊his_visit)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `stage` varchar(30) DEFAULT NULL COMMENT '签名环节:author/resident/attending/director(住院) doctor(门诊)',
  `signer_id` bigint DEFAULT NULL COMMENT '签名人(his_staff.id)',
  `signer_name` varchar(50) DEFAULT NULL COMMENT '签名人姓名',
  `digest` varchar(128) DEFAULT NULL COMMENT 'SM3摘要hex(content+structure规范化)',
  `sig_value` text COMMENT 'SM2签名值(hex)',
  `cert_sn` varchar(100) DEFAULT NULL COMMENT '证书/签章编号(sys_org.sign_no占位)',
  `provider` varchar(30) DEFAULT NULL COMMENT '签名提供者:sm2/ca/tsa',
  `sign_img` varchar(500) DEFAULT NULL COMMENT '签名图URL',
  `sign_time` datetime DEFAULT NULL COMMENT '签名时间',
  `valid` tinyint DEFAULT '1' COMMENT '是否当前有效:1有效 0已被重签取代',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `sign_mode` tinyint DEFAULT '1' COMMENT '签名方式:1文字/2图片/3CA数字签名',
  `sign_image` text COMMENT '手写签名图片base64',
  `ca_cert_sn` varchar(100) DEFAULT NULL COMMENT 'CA证书序列号',
  `ca_signature_value` text COMMENT 'CA签名值',
  `ca_timestamp` varchar(50) DEFAULT NULL COMMENT 'CA时间戳',
  `ca_cert_data` text COMMENT 'CA证书数据',
  `ca_original_hash` varchar(128) DEFAULT NULL COMMENT '签名原文哈希(SHA-256)',
  `verify_result` tinyint DEFAULT NULL COMMENT '验签结果:0未验/1通过/2失败',
  `verify_time` datetime DEFAULT NULL COMMENT '验签时间',
  `patient_sign_image` text COMMENT '患者签名图片base64',
  `patient_sign_time` datetime DEFAULT NULL COMMENT '患者签名时间',
  `family_sign_image` text COMMENT '家属签名图片base64',
  `family_sign_time` datetime DEFAULT NULL COMMENT '家属签名时间',
  PRIMARY KEY (`id`),
  KEY `idx_sig_scope_record` (`scope`,`record_id`),
  KEY `idx_sig_scope_visit` (`scope`,`visit_id`),
  KEY `idx_sig_signer` (`signer_id`),
  KEY `idx_sig_patient` (`patient_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2105516662764113923 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病历可靠电子签名(SM2)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_emr_signature_rule`
--

DROP TABLE IF EXISTS `his_emr_signature_rule`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_emr_signature_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `record_type` int DEFAULT NULL COMMENT '病历类型:1入院记录 2首次病程 3日常病程 4查房记录 5术前小结 6手术记录 7术后病程 8出院小结 9死亡记录 10病案首页 11交接班 12转科 13知情同意 14讨论 15会诊',
  `stage` varchar(32) DEFAULT NULL COMMENT '签名环节:author/resident/attending/director',
  `stage_order` int DEFAULT '0' COMMENT '签名顺序(升序)',
  `required` tinyint DEFAULT '1' COMMENT '是否必需:1是 0否',
  `title_code_min` varchar(16) DEFAULT NULL COMMENT '最低职称档(CV08.30.005, 空=不限)',
  `title_code_max` varchar(16) DEFAULT NULL COMMENT '最高职称档(CV08.30.005, 空=不限)',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_sigrule_type` (`record_type`,`org_id`)
) ENGINE=InnoDB AUTO_INCREMENT=55 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病历签名规则链';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_emr_template`
--

DROP TABLE IF EXISTS `his_emr_template`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_emr_template` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `template_code` varchar(50) NOT NULL COMMENT '模板编码',
  `template_name` varchar(100) NOT NULL COMMENT '模板名称',
  `record_type` int DEFAULT NULL COMMENT '记录类型(对应his_inp_medical_record.record_type的9种类型)',
  `template_category` int DEFAULT NULL COMMENT '模板类别:1入院记录 2首次病程 3日常病程 4上级查房 5手术记录 6术后病程 7出院小结 8死亡记录 9病危通知',
  `fields` text COMMENT '字段定义JSON',
  `dept_id` bigint DEFAULT '0' COMMENT '科室ID(his_dept.id, 0=全院)',
  `version` int DEFAULT '1' COMMENT '版本号',
  `status` int DEFAULT '1' COMMENT '状态:1启用 0停用',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `scope` tinyint DEFAULT '1' COMMENT '适用范围:1住院 2门诊',
  `layout` text COMMENT '布局定义JSON(分节/栅格, 设计器产出)',
  `staff_id` bigint DEFAULT NULL COMMENT '个人模板归属职工ID(his_staff.id, null=科室/全院)',
  `parent_template_id` bigint DEFAULT NULL COMMENT '父模板ID(三级继承)',
  `scope_level` tinyint DEFAULT '0' COMMENT '模板层级:0全院 1科室 2个人',
  `locked_sections` text COMMENT '母板锁定的章节key列表JSON',
  `document` text COMMENT 'Tiptap ProseMirror JSON文档',
  `print_script` text COMMENT '打印格式脚本',
  `dataset_id` bigint DEFAULT NULL COMMENT '关联数据集ID(his_emr_dataset.id)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_emr_tpl_code` (`template_code`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=2106313186452455426 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病历结构化模板';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_emr_version`
--

DROP TABLE IF EXISTS `his_emr_version`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_emr_version` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `record_id` bigint NOT NULL COMMENT '关联his_inp_medical_record',
  `version_no` int NOT NULL COMMENT '版本号',
  `content_snapshot` text COMMENT '内容快照',
  `structure_snapshot` text COMMENT '结构化数据快照',
  `operator_id` bigint NOT NULL COMMENT '操作人ID(sys_user.id)',
  `operator_name` varchar(50) NOT NULL COMMENT '操作人姓名',
  `operate_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  `operate_type` varchar(20) NOT NULL COMMENT '操作:save/submit/audit',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `scope` tinyint DEFAULT '1' COMMENT '适用范围:1住院 2门诊',
  `ref_id` bigint DEFAULT NULL COMMENT '业务主键(scope=1病历id, scope=2门诊就诊id)',
  PRIMARY KEY (`id`),
  KEY `idx_record` (`record_id`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=33 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病历版本快照';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_emr_webhook_subscription`
--

DROP TABLE IF EXISTS `his_emr_webhook_subscription`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_emr_webhook_subscription` (
  `id` bigint NOT NULL COMMENT '主键(雪花)',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `subscriber_name` varchar(100) DEFAULT NULL COMMENT '订阅方名称',
  `callback_url` varchar(500) NOT NULL COMMENT '回调地址',
  `event_types` varchar(500) DEFAULT NULL COMMENT '订阅事件类型(逗号分隔)',
  `secret_key` varchar(100) DEFAULT NULL COMMENT 'HMAC签名密钥',
  `status` tinyint DEFAULT '1' COMMENT '1启用/0禁用',
  `last_push_time` datetime DEFAULT NULL COMMENT '最近推送时间',
  `fail_count` int DEFAULT '0' COMMENT '连续失败次数',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_webhook_org` (`org_id`),
  KEY `idx_webhook_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病历事件Webhook订阅(P7a)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_exam_report`
--

DROP TABLE IF EXISTS `his_exam_report`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_exam_report` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `report_no` varchar(30) NOT NULL COMMENT '报告单号(BG+日期+序号)',
  `order_id` bigint NOT NULL COMMENT '医嘱单ID(his_order.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `report_type` varchar(20) NOT NULL COMMENT '报告类型:exam检查(影像)/lab检验(化验)',
  `findings` text COMMENT '所见(检查所见/检验结果汇总)',
  `conclusion` text COMMENT '结论(检查结论/检验诊断)',
  `key_images` text COMMENT '关键图像(JSON: 图像URL/描述数组)',
  `report_doctor_id` bigint DEFAULT NULL COMMENT '报告医师ID(his_staff.id)',
  `report_time` datetime DEFAULT NULL COMMENT '报告时间',
  `review_doctor_id` bigint DEFAULT NULL COMMENT '审核医师ID(his_staff.id)',
  `review_time` datetime DEFAULT NULL COMMENT '审核时间',
  `status` tinyint DEFAULT '0' COMMENT '状态:0草稿 1已报告 2已审核 3已作废',
  `critical_flag` tinyint DEFAULT '0' COMMENT '危急值标志:1有 0无',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `revoke_by` bigint DEFAULT NULL COMMENT '撤回人(his_staff.id)',
  `revoke_time` datetime DEFAULT NULL COMMENT '撤回时间',
  `revoke_reason` varchar(255) DEFAULT NULL COMMENT '撤回原因',
  `pacs_study_uid` varchar(128) DEFAULT NULL COMMENT 'DICOM StudyInstanceUID(外部PACS影像挂接键)',
  `pacs_server` varchar(128) DEFAULT NULL COMMENT 'PACS服务标识/来源(区分多PACS实例)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_report_no` (`tenant_id`,`report_no`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`),
  KEY `idx_order` (`order_id`),
  KEY `idx_patient` (`tenant_id`,`patient_id`),
  KEY `idx_critical` (`tenant_id`,`critical_flag`)
) ENGINE=InnoDB AUTO_INCREMENT=9000001790878369519 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='检查/检验报告(报告-审核双签)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_exam_result_item`
--

DROP TABLE IF EXISTS `his_exam_result_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_exam_result_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `report_id` bigint NOT NULL COMMENT '报告ID(his_exam_report.id)',
  `item_code` varchar(50) NOT NULL COMMENT '项目编码',
  `item_name` varchar(100) NOT NULL COMMENT '项目名称',
  `result_value` varchar(100) DEFAULT NULL COMMENT '结果值',
  `result_unit` varchar(30) DEFAULT NULL COMMENT '结果单位',
  `ref_range_low` decimal(12,4) DEFAULT NULL COMMENT '参考范围下限',
  `ref_range_high` decimal(12,4) DEFAULT NULL COMMENT '参考范围上限',
  `abnormal_flag` tinyint DEFAULT '0' COMMENT '异常标志:0正常 1偏高 2偏低 3危急值',
  `remark` varchar(200) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_report` (`report_id`),
  KEY `idx_item_code` (`tenant_id`,`item_code`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='检验结果明细项';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_fee_type_dict`
--

DROP TABLE IF EXISTS `his_fee_type_dict`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_fee_type_dict` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户(医共体)ID',
  `org_id` bigint NOT NULL COMMENT '机构ID(机构级自定义, sys_org.id)',
  `code` varchar(20) NOT NULL COMMENT '费别编码(机构内唯一; 内置项沿用历史值 self/insurance 免存量回迁)',
  `name` varchar(100) NOT NULL COMMENT '费别名称(自费/医保/公费/本院职工等)',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  `scope` varchar(20) NOT NULL DEFAULT 'BOTH' COMMENT '适用场景:OTP门诊 IPT住院 BOTH通用(多选逗号分隔)',
  `channel` varchar(20) DEFAULT NULL COMMENT '结算通道:INSURANCE医保 SELF自费 GOV公费 UNIT单位 HOSP本院 HELP救助 OTHER(本期仅作展示/数据, 不改2201触发条件)',
  `insutype` varchar(10) DEFAULT NULL COMMENT '默认医保险种(仅INSURANCE通道)',
  `auto_flag` tinyint NOT NULL DEFAULT '0' COMMENT '系统内置:1不可删/编码锁定 0自定义',
  `ctl_flag` tinyint NOT NULL DEFAULT '0' COMMENT '控费开关:1启用控费规则',
  `ctl_hard` tinyint NOT NULL DEFAULT '0' COMMENT '控费强度:0超阈提示 1强阻断',
  `ctl_scene` varchar(20) DEFAULT NULL COMMENT '控费适用场景:OTP/IPT/BOTH(同 scope 格式)',
  `ctl_amount` decimal(12,2) DEFAULT NULL COMMENT '门诊次均限额(元)',
  `ctl_ipt_amount` decimal(12,2) DEFAULT NULL COMMENT '住院次均限额(元)',
  `ctl_day_amount` decimal(12,2) DEFAULT NULL COMMENT '住院日均限额(元)',
  `selfpay_rate` decimal(5,2) DEFAULT NULL COMMENT '目录自付比例上叠加的院内比例(0~100, 空=不加)',
  `prepay_rate` decimal(5,2) DEFAULT NULL COMMENT '住院预交金测算比例(%, 空=不测算)',
  `discount_mode` varchar(10) DEFAULT 'NONE' COMMENT '优惠方式:NONE无 RATE按比例 AMOUNT固定减免 FULL全免',
  `discount_rate` decimal(5,2) DEFAULT NULL COMMENT '优惠比例(discount_mode=RATE 时生效, 如 50=减半)',
  `discount_amount` decimal(10,2) DEFAULT NULL COMMENT '固定减免金额(discount_mode=AMOUNT 时生效)',
  `discount_json` text COMMENT '优惠细规则JSON(预留: 号别×项目类别矩阵等)',
  `pay_limit_json` text COMMENT '支付方式白名单(按场景): {"otp":["CASH"],"ipt":["CASH","DEPOSIT"]}, 空=不限',
  `sort_no` int DEFAULT '0' COMMENT '排序号',
  `status` tinyint DEFAULT '1' COMMENT '状态:1启用 0停用',
  `memo` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_fee_type_org_code` (`tenant_id`,`org_id`,`code`),
  KEY `idx_ftd_scope` (`scope`,`status`,`deleted`),
  KEY `idx_ftd_org` (`org_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1026 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='患者费别字典(机构级自定义, 含控费/自付比例/优惠/支付白名单)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_fever_register`
--

DROP TABLE IF EXISTS `his_fever_register`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_fever_register` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `visit_id` bigint DEFAULT NULL COMMENT '就诊ID(his_visit.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `patient_name` varchar(100) DEFAULT NULL COMMENT '患者姓名',
  `temperature` decimal(4,1) DEFAULT NULL COMMENT '体温(℃)',
  `exposure_history` varchar(500) DEFAULT NULL COMMENT '流行病学接触史',
  `disposition` varchar(200) DEFAULT NULL COMMENT '处理去向',
  `register_time` datetime DEFAULT NULL COMMENT '登记时间',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_visit` (`tenant_id`,`visit_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发热病人登记';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_green_channel_credit`
--

DROP TABLE IF EXISTS `his_green_channel_credit`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_green_channel_credit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `patient_id` bigint NOT NULL COMMENT '患者ID',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名',
  `visit_id` bigint DEFAULT NULL COMMENT '开通时就诊ID(his_visit.id)',
  `credit_limit` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '信用额度(元)',
  `used_amount` decimal(10,2) DEFAULT '0.00' COMMENT '已使用额度(元)',
  `reason` varchar(200) DEFAULT NULL COMMENT '开通原因(急危重症/证件缺失等)',
  `status` int DEFAULT '1' COMMENT '状态:1启用 0关闭',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_patient` (`tenant_id`,`patient_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='绿色通道信用额度(先诊疗后付费)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_infusion_record`
--

DROP TABLE IF EXISTS `his_infusion_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_infusion_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `exec_id` bigint NOT NULL COMMENT '执行记录ID(his_nurse_exec.id)',
  `seat_no` varchar(20) DEFAULT NULL COMMENT '输液座位号',
  `solution` varchar(200) DEFAULT NULL COMMENT '溶液(液体名称与容量)',
  `drip_rate` int DEFAULT NULL COMMENT '滴速(滴/分)',
  `puncture_time` datetime DEFAULT NULL COMMENT '穿刺时间',
  `puncture_site` varchar(50) DEFAULT NULL COMMENT '穿刺部位(左手背等)',
  `puncture_nurse_id` bigint DEFAULT NULL COMMENT '穿刺护士ID',
  `remove_time` datetime DEFAULT NULL COMMENT '拔针时间',
  `remove_nurse_id` bigint DEFAULT NULL COMMENT '拔针护士ID',
  `patrol_records` text COMMENT '巡回记录(JSON数组: 巡回时间+滴速+情况)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_exec` (`exec_id`),
  KEY `idx_seat` (`tenant_id`,`seat_no`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='输液记录(座位/滴速/穿刺/拔针/巡回)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_inp_allergy`
--

DROP TABLE IF EXISTS `his_inp_allergy`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_inp_allergy` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `patient_id` bigint NOT NULL COMMENT '患者ID(his_patient.id)',
  `allergy_type` int NOT NULL COMMENT '过敏类型:1药物 2食物 3环境 4其他',
  `allergen_name` varchar(100) NOT NULL COMMENT '过敏原名称',
  `allergen_code` varchar(50) DEFAULT NULL COMMENT '过敏原编码',
  `severity` int DEFAULT NULL COMMENT '严重程度:1轻 2中 3重',
  `reaction_desc` varchar(500) DEFAULT NULL COMMENT '过敏反应描述',
  `record_time` datetime DEFAULT NULL COMMENT '记录时间',
  `doctor_id` bigint DEFAULT NULL COMMENT '记录医生ID(his_staff.id)',
  `status` int DEFAULT '1' COMMENT '状态:1有效 0已失效',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_allergy_visit` (`inp_visit_id`),
  KEY `idx_allergy_patient` (`patient_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院患者过敏记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_inp_charge_detail`
--

DROP TABLE IF EXISTS `his_inp_charge_detail`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_inp_charge_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `charge_item_id` bigint DEFAULT NULL COMMENT '收费项目ID',
  `item_name` varchar(200) NOT NULL COMMENT '项目名称',
  `item_code` varchar(50) DEFAULT NULL COMMENT '项目编码',
  `quantity` decimal(10,2) DEFAULT '1.00' COMMENT '数量',
  `unit_price` decimal(10,2) NOT NULL COMMENT '单价',
  `amount` decimal(12,2) NOT NULL COMMENT '金额',
  `charge_date` date DEFAULT NULL COMMENT '记账日期',
  `order_id` bigint DEFAULT NULL COMMENT '关联医嘱ID(his_inp_order.id)',
  `fee_type` tinyint DEFAULT NULL COMMENT '费用类别:1西药 2中药 3检查 4检验 5治疗 6护理 7材料 8床位 9其他',
  `status` tinyint DEFAULT '1' COMMENT '状态:1正常 2退费',
  `operator_id` bigint DEFAULT NULL COMMENT '操作员ID(his_staff.id)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `surgery_id` bigint DEFAULT NULL COMMENT '手术ID(his_surgery.id)',
  `daily_limit` decimal(12,2) DEFAULT NULL COMMENT '日限额',
  `total_limit` decimal(12,2) DEFAULT NULL COMMENT '总限额',
  `approval_status` int DEFAULT '1' COMMENT '审核状态:1待审 2通过 3拒绝',
  `limit_override_reason` varchar(200) DEFAULT NULL COMMENT '超标原因',
  `settle_id` bigint DEFAULT NULL COMMENT '关联结算ID',
  PRIMARY KEY (`id`),
  KEY `idx_visit` (`inp_visit_id`,`charge_date`),
  KEY `idx_visit_fee` (`inp_visit_id`,`fee_type`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2105881252433809411 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院费用明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_inp_consultation`
--

DROP TABLE IF EXISTS `his_inp_consultation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_inp_consultation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `consult_type` int NOT NULL COMMENT '会诊类型:1普通 2急会诊 3MDT',
  `apply_dept_id` bigint DEFAULT NULL COMMENT '申请科室ID(his_dept.id)',
  `apply_doctor_id` bigint DEFAULT NULL COMMENT '申请医师ID(his_staff.id)',
  `target_dept_id` bigint DEFAULT NULL COMMENT '受邀科室ID(his_dept.id)',
  `target_doctor_id` bigint DEFAULT NULL COMMENT '受邀医师ID(his_staff.id)',
  `apply_reason` text COMMENT '申请理由',
  `consult_opinion` text COMMENT '会诊意见',
  `apply_time` datetime DEFAULT NULL COMMENT '申请时间',
  `response_time` datetime DEFAULT NULL COMMENT '受理时间',
  `consult_time` datetime DEFAULT NULL COMMENT '会诊完成时间',
  `status` int DEFAULT '1' COMMENT '状态:1申请 2受理 3完成 4拒绝 5取消',
  `urgency_level` int DEFAULT '1' COMMENT '紧急程度:1普通 2急 3特急',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `video_room_id` varchar(100) DEFAULT NULL COMMENT '视频房间ID',
  `video_record_url` varchar(500) DEFAULT NULL COMMENT '视频录像URL',
  `visit_type` tinyint DEFAULT '1' COMMENT '就诊类型:1住院/2门诊',
  `visit_id` bigint DEFAULT NULL COMMENT '通用就诊ID',
  `consult_category` varchar(20) DEFAULT NULL COMMENT '科内/科间/院外/MDT',
  `response_deadline` datetime DEFAULT NULL COMMENT '响应截止时间',
  `timeout_notified` tinyint DEFAULT '0' COMMENT '超时已通知标记',
  `consult_record_id` bigint DEFAULT NULL COMMENT '关联病历记录ID',
  `order_id` bigint DEFAULT NULL COMMENT '关联医嘱ID',
  `apply_doctor_name` varchar(50) DEFAULT NULL,
  `target_doctor_name` varchar(50) DEFAULT NULL,
  `apply_dept_name` varchar(100) DEFAULT NULL,
  `target_dept_name` varchar(100) DEFAULT NULL,
  `apply_summary` text COMMENT '病情摘要',
  `eval_by_applicant` tinyint DEFAULT NULL COMMENT '发起方评分1-5',
  `eval_by_applicant_note` varchar(500) DEFAULT NULL,
  `eval_by_invitee` tinyint DEFAULT NULL COMMENT '受邀方评分1-5',
  `eval_by_invitee_note` varchar(500) DEFAULT NULL,
  `eval_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_consult_visit` (`inp_visit_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2104832941287292931 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院会诊记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_inp_daily_bill`
--

DROP TABLE IF EXISTS `his_inp_daily_bill`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_inp_daily_bill` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `bill_date` date NOT NULL COMMENT '清单日期',
  `items` text COMMENT '费用项JSON数组',
  `total_amount` decimal(12,2) DEFAULT NULL COMMENT '当日费用合计',
  `cumulative_amount` decimal(12,2) DEFAULT NULL COMMENT '在院累计费用',
  `deposit_balance` decimal(12,2) DEFAULT NULL COMMENT '预交金余额',
  `generated_time` datetime DEFAULT NULL COMMENT '生成时间',
  `printed_flag` tinyint DEFAULT '0' COMMENT '是否已打印:1是 0否',
  `print_time` datetime DEFAULT NULL COMMENT '打印时间',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_daily_bill` (`inp_visit_id`,`bill_date`,`deleted`),
  KEY `idx_daily_bill_date` (`bill_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院每日费用清单';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_inp_deposit`
--

DROP TABLE IF EXISTS `his_inp_deposit`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_inp_deposit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `amount` decimal(12,2) NOT NULL COMMENT '金额',
  `pay_type` varchar(20) DEFAULT 'CASH' COMMENT '支付方式(his_pay_method_dict.code 规范码; 历史数字/小写值经 legacy_codes 归一)',
  `direction` tinyint NOT NULL COMMENT '方向:1缴纳 2退还',
  `balance_after` decimal(12,2) DEFAULT NULL COMMENT '操作后余额',
  `operator_id` bigint DEFAULT NULL COMMENT '操作员ID(his_staff.id)',
  `receipt_no` varchar(50) DEFAULT NULL COMMENT '收据号',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_visit` (`inp_visit_id`),
  KEY `idx_visit_time` (`inp_visit_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院预交金流水';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_inp_diagnosis`
--

DROP TABLE IF EXISTS `his_inp_diagnosis`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_inp_diagnosis` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `diag_type` tinyint NOT NULL COMMENT '诊断类型:1入院诊断 2补充诊断 3术后诊断 4出院诊断',
  `diag_code` varchar(30) DEFAULT NULL COMMENT '诊断编码(ICD-10)',
  `diag_name` varchar(200) NOT NULL COMMENT '诊断名称',
  `is_main` tinyint DEFAULT '0' COMMENT '是否主诊断:1是 0否',
  `diag_dept_id` bigint DEFAULT NULL COMMENT '诊断科室ID(his_dept.id)',
  `diag_doctor_id` bigint DEFAULT NULL COMMENT '诊断医生ID(his_staff.id)',
  `diag_time` datetime DEFAULT NULL COMMENT '诊断时间',
  `sort_no` int DEFAULT '0' COMMENT '排序号',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `admit_condition` int DEFAULT NULL COMMENT '入院病情:1危急 2严重 3一般 4不适用',
  `complication_flag` tinyint DEFAULT '0' COMMENT '并发症标志:1是 0否',
  `tooth_position` varchar(200) DEFAULT NULL COMMENT '牙位编码(FDI/Palmer, 口腔诊断专用)',
  `syndrome_code` varchar(30) DEFAULT NULL COMMENT '证候编码(his_diag_dict.dict_type=symp; P8 中医诊断拼装)',
  `syndrome_name` varchar(100) DEFAULT NULL COMMENT '证候名称(P8 中医诊断拼装, 字典回填)',
  PRIMARY KEY (`id`),
  KEY `idx_visit` (`inp_visit_id`),
  KEY `idx_visit_type` (`inp_visit_id`,`diag_type`)
) ENGINE=InnoDB AUTO_INCREMENT=9100000000000300073 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院诊断';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_inp_dispense`
--

DROP TABLE IF EXISTS `his_inp_dispense`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_inp_dispense` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `dispense_no` varchar(30) NOT NULL COMMENT '住院发药单号',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `order_id` bigint NOT NULL COMMENT '医嘱ID(his_inp_order.id)',
  `drug_catalog_id` bigint DEFAULT NULL COMMENT '实发药品目录ID(his_drug_catalog.id, 替换后为替品)',
  `orig_drug_catalog_id` bigint DEFAULT NULL COMMENT '原医嘱药品目录ID(未替换时与实发相同)',
  `drug_code` varchar(50) DEFAULT NULL COMMENT '药品编码',
  `drug_name` varchar(200) DEFAULT NULL COMMENT '药品名称',
  `spec` varchar(100) DEFAULT NULL COMMENT '规格',
  `unit` varchar(20) DEFAULT NULL COMMENT '最小单位',
  `replace_flag` tinyint DEFAULT '0' COMMENT '缺药替换:1是 0否',
  `replace_scope` tinyint DEFAULT NULL COMMENT '替换范围:1仅本次 2本次及后续全部',
  `replace_reason` varchar(200) DEFAULT NULL COMMENT '替换原因(缺药/禁用等)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名',
  `dept_id` bigint DEFAULT NULL COMMENT '科室ID',
  `dept_name` varchar(100) DEFAULT NULL COMMENT '科室名称',
  `ward_id` bigint DEFAULT NULL COMMENT '病区ID(his_ward.id)',
  `ward_name` varchar(100) DEFAULT NULL COMMENT '病区名称',
  `bed_no` varchar(30) DEFAULT NULL COMMENT '床号',
  `pharmacy_id` bigint DEFAULT NULL COMMENT '发药药房ID(his_pharmacy_def.id)',
  `round_rule` tinyint DEFAULT NULL COMMENT '取整规则快照:1向上 2向下 3四舍五入',
  `pack_ratio` int DEFAULT NULL COMMENT '包装换算比快照(大包装→最小单位)',
  `pack_qty` int DEFAULT NULL COMMENT '发药包数(整包装)',
  `should_qty` decimal(12,4) DEFAULT '0.0000' COMMENT '应发量(医嘱量, 最小单位)',
  `offset_qty` decimal(12,4) DEFAULT '0.0000' COMMENT '冲抵量(病区暂存抵扣应发)',
  `actual_qty` decimal(12,4) DEFAULT '0.0000' COMMENT '实发量(取整后-冲抵, 最小单位)',
  `over_qty` decimal(12,4) DEFAULT '0.0000' COMMENT '多发量(整包装取整溢出部分)',
  `stock_out_id` bigint DEFAULT NULL COMMENT '关联出库单ID(his_stock_out.id)',
  `is_discharge_pick` tinyint DEFAULT '0' COMMENT '出院带药:1是 0否',
  `pickup_status` tinyint DEFAULT '0' COMMENT '取药核发:0无需 1待取药 2已取待发药核 3已二次核发',
  `return_qty` decimal(12,4) DEFAULT '0.0000' COMMENT '累计退回/暂存量',
  `status` tinyint DEFAULT '1' COMMENT '状态:1已发药 2部分退药 3全部退药',
  `dispense_by` varchar(50) DEFAULT NULL COMMENT '发药人',
  `dispense_time` datetime DEFAULT NULL COMMENT '发药时间',
  `check_by` varchar(50) DEFAULT NULL COMMENT '核对人',
  `check_time` datetime DEFAULT NULL COMMENT '核对时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_inp_disp_no` (`tenant_id`,`dispense_no`),
  KEY `idx_visit` (`inp_visit_id`),
  KEY `idx_order` (`order_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`,`dispense_time`),
  KEY `idx_ward` (`tenant_id`,`ward_id`),
  KEY `idx_drug` (`drug_catalog_id`)
) ENGINE=InnoDB AUTO_INCREMENT=923000000007 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院发药记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_inp_fee_alert`
--

DROP TABLE IF EXISTS `his_inp_fee_alert`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_inp_fee_alert` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `alert_type` int NOT NULL COMMENT '预警类型:1日限额 2总限额 3预交金不足 4大额费用',
  `charge_detail_id` bigint DEFAULT NULL COMMENT '触发费用明细ID(his_inp_charge_detail.id)',
  `alert_amount` decimal(12,2) DEFAULT NULL COMMENT '预警时金额',
  `threshold_amount` decimal(12,2) DEFAULT NULL COMMENT '阈值金额',
  `handler_id` bigint DEFAULT NULL COMMENT '处理人ID(his_staff.id)',
  `handle_time` datetime DEFAULT NULL COMMENT '处理时间',
  `handle_result` int DEFAULT NULL COMMENT '处理结果:1放行 2拦截',
  `override_reason` varchar(500) DEFAULT NULL COMMENT '放行/超标原因',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_fee_alert_visit` (`inp_visit_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2104823846257876995 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院费用预警记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_inp_informed_consent`
--

DROP TABLE IF EXISTS `his_inp_informed_consent`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_inp_informed_consent` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `consent_type` int NOT NULL COMMENT '同意书类型:1手术 2麻醉 3输血 4特殊检查 5特殊治疗 6自费 7病危',
  `title` varchar(200) NOT NULL COMMENT '同意书标题',
  `template_id` bigint DEFAULT NULL COMMENT '打印模板ID(his_print_template.id)',
  `content` text COMMENT '同意书内容JSON',
  `patient_sign_time` datetime DEFAULT NULL COMMENT '患者/家属签字时间',
  `doctor_sign_time` datetime DEFAULT NULL COMMENT '医师签字时间',
  `witness_sign_time` datetime DEFAULT NULL COMMENT '见证人签字时间',
  `doctor_id` bigint DEFAULT NULL COMMENT '谈话医师ID(his_staff.id)',
  `status` int DEFAULT '1' COMMENT '状态:1待签 2已签 3已撤销',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_consent_visit` (`inp_visit_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2105121087220654083 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院知情同意书';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_inp_io_record`
--

DROP TABLE IF EXISTS `his_inp_io_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_inp_io_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `visit_id` bigint NOT NULL COMMENT '就诊ID(his_inp_visit.id)',
  `patient_id` bigint NOT NULL COMMENT '患者ID(his_patient.id)',
  `record_time` datetime NOT NULL COMMENT '记录时间',
  `io_type` tinyint NOT NULL COMMENT '1进量 2出量',
  `category` varchar(50) NOT NULL COMMENT '类别:饮水/静脉/尿量/引流等',
  `volume` decimal(10,2) NOT NULL COMMENT '量(ml)',
  `route` varchar(50) DEFAULT NULL COMMENT '途径',
  `note` varchar(200) DEFAULT NULL COMMENT '备注',
  `nurse_id` bigint DEFAULT NULL COMMENT '登记护士ID(his_staff.id)',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_visit_date` (`visit_id`,`record_time`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院出入量记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_inp_medical_record`
--

DROP TABLE IF EXISTS `his_inp_medical_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_inp_medical_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `record_type` tinyint NOT NULL COMMENT '记录类型:1入院记录 2首次病程 3日常病程 4查房记录 5术前小结 6手术记录 7术后病程 8出院小结 9死亡记录',
  `title` varchar(200) DEFAULT NULL COMMENT '标题',
  `content` text COMMENT '内容(JSON)',
  `record_time` datetime DEFAULT NULL COMMENT '记录时间',
  `doctor_id` bigint DEFAULT NULL COMMENT '记录医生ID(his_staff.id)',
  `audit_doctor_id` bigint DEFAULT NULL COMMENT '审核医生ID(his_staff.id)',
  `audit_time` datetime DEFAULT NULL COMMENT '审核时间',
  `status` tinyint DEFAULT '1' COMMENT '状态:1草稿 2已提交 3已审核',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `template_id` bigint DEFAULT NULL COMMENT '结构化模板ID(his_emr_template.id)',
  `structure_data` text COMMENT '结构化数据JSON',
  `deadline_time` datetime DEFAULT NULL COMMENT '书写截止时间(时限性质控)',
  `quality_score` decimal(5,2) DEFAULT NULL COMMENT '质控评分',
  `quality_detail` text COMMENT '质控明细JSON',
  `attending_doctor_id` bigint DEFAULT NULL COMMENT '上级医师ID(his_staff.id)',
  `round_level` int DEFAULT NULL COMMENT '查房级别:1住院医师 2主治 3主任',
  `attending_sign_id` bigint DEFAULT NULL COMMENT '主治签名医师ID',
  `attending_sign_time` datetime DEFAULT NULL COMMENT '主治签名时间',
  `director_sign_id` bigint DEFAULT NULL COMMENT '主任签名医师ID',
  `director_sign_time` datetime DEFAULT NULL COMMENT '主任签名时间',
  `archive_time` datetime DEFAULT NULL COMMENT '归档时间',
  `archive_by` varchar(50) DEFAULT NULL COMMENT '归档操作人',
  `seal_time` datetime DEFAULT NULL COMMENT '封存时间',
  `seal_by` varchar(50) DEFAULT NULL COMMENT '封存操作人',
  `seal_reason` varchar(500) DEFAULT NULL COMMENT '封存原因',
  `recall_time` datetime DEFAULT NULL COMMENT '最近召回时间',
  `recall_by` varchar(50) DEFAULT NULL COMMENT '召回操作人',
  `recall_reason` varchar(500) DEFAULT NULL COMMENT '召回原因',
  `recall_approved` tinyint DEFAULT NULL COMMENT '召回审批:0待审/1通过/2驳回',
  `recall_approver` varchar(50) DEFAULT NULL COMMENT '召回审批人',
  `pdf_path` varchar(500) DEFAULT NULL COMMENT '归档PDF路径',
  `pdf_generated_time` datetime DEFAULT NULL COMMENT '归档PDF生成时间',
  PRIMARY KEY (`id`),
  KEY `idx_visit` (`inp_visit_id`,`record_type`),
  KEY `idx_doctor` (`doctor_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2106194160489402370 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院病历';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_inp_notification`
--

DROP TABLE IF EXISTS `his_inp_notification`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_inp_notification` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '租户ID',
  `org_id` bigint NOT NULL DEFAULT '0' COMMENT '机构ID',
  `user_id` bigint NOT NULL COMMENT '接收用户ID(sys_user.id)',
  `notify_type` int NOT NULL COMMENT '通知类型:1医嘱 2会诊 3病历 4预警 5评估 6系统',
  `title` varchar(200) NOT NULL COMMENT '标题',
  `content` varchar(500) DEFAULT NULL COMMENT '内容',
  `ref_type` varchar(50) DEFAULT NULL COMMENT '关联业务类型',
  `ref_id` bigint DEFAULT NULL COMMENT '关联业务ID',
  `is_read` tinyint NOT NULL DEFAULT '0' COMMENT '是否已读:1是 0否',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `read_time` datetime DEFAULT NULL COMMENT '读取时间',
  `create_by` varchar(50) DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_user_read` (`user_id`,`is_read`,`deleted`),
  KEY `idx_type` (`notify_type`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=2105604307414454275 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院通知';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_inp_nursing_record`
--

DROP TABLE IF EXISTS `his_inp_nursing_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_inp_nursing_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `record_type` tinyint NOT NULL COMMENT '记录类型:1体温单 2护理评估 3护理计划 4护理措施 5护理总结',
  `content` text COMMENT '内容(JSON)',
  `record_time` datetime DEFAULT NULL COMMENT '记录时间',
  `nurse_id` bigint DEFAULT NULL COMMENT '护士ID(his_staff.id)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `scale_code` varchar(30) DEFAULT NULL COMMENT '量表编码(his_nursing_scale_def.scale_code)',
  `scale_score` decimal(5,1) DEFAULT NULL COMMENT '量表评分',
  `scale_detail` text COMMENT '量表明细JSON',
  `plan_template_id` bigint DEFAULT NULL COMMENT '护理计划模板ID(his_nursing_plan_template.id)',
  `template_id` bigint DEFAULT NULL COMMENT '护理文书模板ID(his_nursing_template.id, P4a-5)',
  `structure_data` text COMMENT '结构化字段扁平JSON(富文本双轨派生, P4a-5)',
  PRIMARY KEY (`id`),
  KEY `idx_visit` (`inp_visit_id`,`record_type`),
  KEY `idx_nurse` (`nurse_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2104839071279419395 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院护理记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_inp_order`
--

DROP TABLE IF EXISTS `his_inp_order`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_inp_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `order_type` tinyint NOT NULL COMMENT '医嘱类型:1长期 2临时',
  `order_category` tinyint NOT NULL COMMENT '医嘱分类:1药品 2检查 3检验 4治疗 5护理 6膳食 7其他',
  `order_content` varchar(500) NOT NULL COMMENT '医嘱内容',
  `charge_item_id` bigint DEFAULT NULL COMMENT '收费项目ID',
  `drug_id` bigint DEFAULT NULL COMMENT '药品ID(药品目录)',
  `spec` varchar(100) DEFAULT NULL COMMENT '规格',
  `dosage` varchar(50) DEFAULT NULL COMMENT '剂量',
  `dosage_unit` varchar(20) DEFAULT NULL COMMENT '剂量单位',
  `usage_code` varchar(20) DEFAULT NULL COMMENT '用法编码',
  `freq_code` varchar(20) DEFAULT NULL COMMENT '频次编码',
  `start_time` datetime DEFAULT NULL COMMENT '开始时间',
  `stop_time` datetime DEFAULT NULL COMMENT '停止时间',
  `order_status` tinyint DEFAULT '1' COMMENT '状态:1新开 2已审核 3执行中 4已完成 5已停止 6已作废',
  `doctor_id` bigint DEFAULT NULL COMMENT '开嘱医生ID(his_staff.id)',
  `audit_nurse_id` bigint DEFAULT NULL COMMENT '审核护士ID(his_staff.id)',
  `audit_time` datetime DEFAULT NULL COMMENT '审核时间',
  `stop_doctor_id` bigint DEFAULT NULL COMMENT '停嘱医生ID(his_staff.id)',
  `stop_nurse_id` bigint DEFAULT NULL COMMENT '停嘱护士确认ID(his_staff.id)',
  `group_no` varchar(30) DEFAULT NULL COMMENT '成组医嘱号',
  `quantity` decimal(10,2) DEFAULT NULL COMMENT '数量',
  `unit_price` decimal(10,2) DEFAULT NULL COMMENT '单价',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `pathway_instance_id` bigint DEFAULT NULL COMMENT '临床路径实例ID(his_pathway_instance.id)',
  `order_template_id` bigint DEFAULT NULL COMMENT '来源医嘱模板ID(his_order_template.id)',
  `order_set_id` bigint DEFAULT NULL COMMENT '来源医嘱套餐ID(his_order_template.id, 套餐型)',
  `high_alert_flag` tinyint DEFAULT '0' COMMENT '高警示药品:1是 0否',
  `double_check_flag` tinyint DEFAULT '0' COMMENT '需双人核对:1是 0否',
  `rational_check_result` text COMMENT '合理用药审查结果JSON',
  `pharm_audit_status` tinyint DEFAULT '0' COMMENT '药审状态:0无需 1待审 2通过 3驳回',
  `pharm_audit_id` bigint DEFAULT NULL COMMENT '审核药师ID(his_staff.id)',
  `pharm_audit_time` datetime DEFAULT NULL COMMENT '药审时间',
  `pharm_reject_reason` varchar(500) DEFAULT NULL COMMENT '驳回原因',
  `source_order_id` bigint DEFAULT NULL COMMENT '续开来源医嘱ID',
  `dispense_status` tinyint DEFAULT '0' COMMENT '住院发药状态:0未发药 1已发药(P4, 仅药品类医嘱使用)',
  `order_dept_id` bigint DEFAULT NULL COMMENT '开单科室ID(his_dept.id, 开立时取开嘱医生所属科室)',
  `surgery_id` bigint DEFAULT NULL COMMENT '手术ID(his_surgery.id, 术中/术后医嘱)',
  `surgery_apply_id` bigint DEFAULT NULL COMMENT '手术申请单ID(his_surgery_apply.id, 申请阶段术前医嘱)',
  `order_phase` tinyint DEFAULT NULL COMMENT '手术医嘱阶段:1术前 2术中 3术后(普通医嘱为空)',
  `proxy_doctor_id` bigint DEFAULT NULL COMMENT '代开目标医生ID(his_staff.id, 权限按其口径校验; 实际开单人记 create_by)',
  `proxy_reason` varchar(255) DEFAULT NULL COMMENT '代开原因留痕',
  `send_pharm_status` tinyint DEFAULT NULL COMMENT '手术类药品医嘱发送药房闸门:0未发送 1已发送 2已撤回(普通医嘱为空自动入队)',
  `return_apply_flag` tinyint DEFAULT '0' COMMENT '手术侧退药申请标志:0无 1申请中 2已退药(P3a, 药房退药闭环留痕)',
  `return_apply_reason` varchar(200) DEFAULT NULL COMMENT '退药申请原因',
  `return_apply_time` datetime DEFAULT NULL COMMENT '退药申请提交时间',
  `return_apply_by` varchar(50) DEFAULT NULL COMMENT '退药申请人(登录用户名)',
  PRIMARY KEY (`id`),
  KEY `idx_visit` (`inp_visit_id`,`order_status`),
  KEY `idx_visit_type` (`inp_visit_id`,`order_type`),
  KEY `idx_doctor` (`doctor_id`),
  KEY `idx_group` (`group_no`)
) ENGINE=InnoDB AUTO_INCREMENT=2105881252433809410 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院医嘱';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_inp_order_exec`
--

DROP TABLE IF EXISTS `his_inp_order_exec`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_inp_order_exec` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `order_id` bigint NOT NULL COMMENT '医嘱ID(his_inp_order.id)',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `plan_time` datetime DEFAULT NULL COMMENT '计划执行时间',
  `exec_time` datetime DEFAULT NULL COMMENT '实际执行时间',
  `exec_nurse_id` bigint DEFAULT NULL COMMENT '执行护士ID(his_staff.id)',
  `exec_status` tinyint DEFAULT '1' COMMENT '状态:1待执行 2已执行 3未执行',
  `exec_remark` varchar(500) DEFAULT NULL COMMENT '执行备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_order` (`order_id`),
  KEY `idx_visit` (`inp_visit_id`),
  KEY `idx_status_plan` (`tenant_id`,`org_id`,`exec_status`,`plan_time`)
) ENGINE=InnoDB AUTO_INCREMENT=2105863225784999938 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院医嘱执行记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_inp_report_snapshot`
--

DROP TABLE IF EXISTS `his_inp_report_snapshot`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_inp_report_snapshot` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `report_type` int NOT NULL COMMENT '报表类型:1床位 2费用 3科室 4住院日 5DRG',
  `report_date` date NOT NULL COMMENT '报表日期',
  `dept_id` bigint DEFAULT NULL COMMENT '科室ID(his_dept.id)',
  `ward_id` bigint DEFAULT NULL COMMENT '病区ID(his_ward.id)',
  `data` text COMMENT '报表数据JSON',
  `generated_time` datetime DEFAULT NULL COMMENT '生成时间',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_report_type_date` (`report_type`,`report_date`)
) ENGINE=InnoDB AUTO_INCREMENT=2105816015718387715 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院报表快照';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_inp_settle`
--

DROP TABLE IF EXISTS `his_inp_settle`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_inp_settle` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `settle_no` varchar(30) DEFAULT NULL COMMENT '结算单号',
  `total_amount` decimal(12,2) DEFAULT NULL COMMENT '总金额',
  `self_pay` decimal(12,2) DEFAULT '0.00' COMMENT '自付金额',
  `fund_pay` decimal(12,2) DEFAULT '0.00' COMMENT '基金支付',
  `cash_pay` decimal(12,2) DEFAULT '0.00' COMMENT '现金支付',
  `acct_pay` decimal(12,2) DEFAULT '0.00' COMMENT '个账支付',
  `deposit_deduct` decimal(12,2) DEFAULT '0.00' COMMENT '预交金抵扣',
  `refund_amount` decimal(12,2) DEFAULT '0.00' COMMENT '退还金额',
  `settle_type` tinyint DEFAULT NULL COMMENT '结算类型:1出院结算 2中途结算 3退费',
  `yb_status` tinyint DEFAULT '0' COMMENT '医保状态:0未结算 1结算中 2已结算 3撤销中 4已撤销',
  `settle_time` datetime DEFAULT NULL COMMENT '结算时间',
  `operator_id` bigint DEFAULT NULL COMMENT '操作员ID(his_staff.id)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `drg_group_code` varchar(30) DEFAULT NULL COMMENT 'DRG分组编码',
  `dip_code` varchar(30) DEFAULT NULL COMMENT 'DIP病种编码',
  `pay_method` int DEFAULT NULL COMMENT '支付方式',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_settle_no` (`tenant_id`,`settle_no`),
  KEY `idx_visit` (`inp_visit_id`),
  KEY `idx_yb_status` (`tenant_id`,`org_id`,`yb_status`)
) ENGINE=InnoDB AUTO_INCREMENT=2105956441888735235 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院结算';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_inp_shift_record`
--

DROP TABLE IF EXISTS `his_inp_shift_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_inp_shift_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `ward_id` bigint NOT NULL COMMENT '病区ID(his_ward.id)',
  `shift_date` date NOT NULL COMMENT '交班日期',
  `shift_type` tinyint NOT NULL COMMENT '班次:1白班 2小夜 3大夜',
  `handover_nurse_id` bigint DEFAULT NULL COMMENT '交班护士ID(his_staff.id)',
  `takeover_nurse_id` bigint DEFAULT NULL COMMENT '接班护士ID(his_staff.id)',
  `total_patients` int DEFAULT '0' COMMENT '在院总数',
  `new_admit` int DEFAULT '0' COMMENT '新入院',
  `discharged` int DEFAULT '0' COMMENT '出院',
  `critical_count` int DEFAULT '0' COMMENT '危重人数',
  `content` text COMMENT '交班内容(JSON)',
  `status` tinyint DEFAULT '1' COMMENT '状态:1待接班 2已交接',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `sbar_situation` text COMMENT 'SBAR-情景',
  `sbar_background` text COMMENT 'SBAR-背景',
  `sbar_assessment` text COMMENT 'SBAR评估',
  `sbar_recommendation` text COMMENT 'SBAR建议',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ward_date_type` (`tenant_id`,`ward_id`,`shift_date`,`shift_type`),
  KEY `idx_date` (`tenant_id`,`org_id`,`shift_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病区交接班记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_inp_transfer`
--

DROP TABLE IF EXISTS `his_inp_transfer`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_inp_transfer` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `transfer_type` int NOT NULL COMMENT '申请类型:1转科 2转床 3加床',
  `from_ward_id` bigint DEFAULT NULL COMMENT '原病区ID(his_ward.id)',
  `from_bed_id` bigint DEFAULT NULL COMMENT '原床位ID(his_bed.id)',
  `from_dept_id` bigint DEFAULT NULL COMMENT '原科室ID(his_dept.id)',
  `to_ward_id` bigint DEFAULT NULL COMMENT '目标病区ID(his_ward.id)',
  `to_bed_id` bigint DEFAULT NULL COMMENT '目标床位ID(his_bed.id)',
  `to_dept_id` bigint DEFAULT NULL COMMENT '目标科室ID(his_dept.id)',
  `reason` varchar(500) DEFAULT NULL COMMENT '申请原因',
  `apply_doctor_id` bigint DEFAULT NULL COMMENT '申请医生ID(his_staff.id)',
  `approve_doctor_id` bigint DEFAULT NULL COMMENT '审批医生ID(his_staff.id)',
  `apply_time` datetime DEFAULT NULL COMMENT '申请时间',
  `approve_time` datetime DEFAULT NULL COMMENT '审批时间',
  `status` int DEFAULT '1' COMMENT '状态:1申请 2批准 3拒绝 4已执行 5取消',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_transfer_visit` (`inp_visit_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院转科转床申请';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_inp_visit`
--

DROP TABLE IF EXISTS `his_inp_visit`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_inp_visit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `inp_no` varchar(30) NOT NULL COMMENT '住院号',
  `patient_id` bigint NOT NULL COMMENT '患者ID(his_patient.id)',
  `ward_id` bigint DEFAULT NULL COMMENT '病区ID(his_ward.id)',
  `bed_id` bigint DEFAULT NULL COMMENT '床位ID(his_bed.id)',
  `dept_id` bigint DEFAULT NULL COMMENT '住院科室ID(his_dept.id)',
  `doctor_id` bigint DEFAULT NULL COMMENT '主治医生ID(his_staff.id)',
  `nurse_id` bigint DEFAULT NULL COMMENT '责任护士ID(his_staff.id)',
  `admit_date` datetime DEFAULT NULL COMMENT '入院日期',
  `discharge_date` datetime DEFAULT NULL COMMENT '出院日期',
  `visit_status` tinyint DEFAULT '1' COMMENT '状态:1待入院 2在院 3出院办理中 4已出院 5已取消',
  `admit_diag` varchar(500) DEFAULT NULL COMMENT '入院诊断',
  `total_cost` decimal(12,2) DEFAULT '0.00' COMMENT '总费用',
  `deposit_balance` decimal(12,2) DEFAULT '0.00' COMMENT '预交金余额',
  `med_type` varchar(10) DEFAULT NULL COMMENT '医疗类别(医保)',
  `psn_no` varchar(50) DEFAULT NULL COMMENT '医保人员编号',
  `insutype` varchar(10) DEFAULT NULL COMMENT '险种类型',
  `mdtrt_id` varchar(50) DEFAULT NULL COMMENT '医保就诊ID',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `contact_name` varchar(50) DEFAULT NULL COMMENT '联系人姓名',
  `contact_phone` varchar(20) DEFAULT NULL COMMENT '联系人电话',
  `contact_relation` varchar(20) DEFAULT NULL COMMENT '联系人关系',
  `guarantor_name` varchar(50) DEFAULT NULL COMMENT '担保人姓名',
  `guarantor_phone` varchar(20) DEFAULT NULL COMMENT '担保人电话',
  `guarantor_id_no` varchar(30) DEFAULT NULL COMMENT '担保人身份证号',
  `blood_type` varchar(10) DEFAULT NULL COMMENT '血型',
  `admit_source` int DEFAULT NULL COMMENT '入院来源:1门诊 2急诊 3转诊 4其他',
  `expected_discharge_date` date DEFAULT NULL COMMENT '预计出院日期',
  `deposit_warning_amount` decimal(12,2) DEFAULT NULL COMMENT '预交金预警线',
  `is_quarantine` tinyint DEFAULT '0' COMMENT '是否隔离:1是 0否',
  `nursing_level` int DEFAULT NULL COMMENT '护理等级:1特级 2一级 3二级 4三级',
  `diet_type` varchar(50) DEFAULT NULL COMMENT '饮食类型',
  `condition_level` int DEFAULT NULL COMMENT '病情等级:1危 2重 3一般',
  `drg_group_code` varchar(30) DEFAULT NULL COMMENT 'DRG分组编码',
  `pre_check_items` text COMMENT '预入院检查项JSON',
  `pre_admit_time` datetime DEFAULT NULL COMMENT '预入院登记时间',
  `admission_cert_id` bigint DEFAULT NULL COMMENT '来源住院证ID(his_admission_cert.id, 持证入院溯源)',
  `fee_type` varchar(20) DEFAULT NULL COMMENT '费别编码(his_fee_type_dict.code)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_org_inpno` (`tenant_id`,`org_id`,`inp_no`),
  KEY `idx_patient` (`tenant_id`,`patient_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`visit_status`),
  KEY `idx_ward` (`ward_id`),
  KEY `idx_mdtrt` (`mdtrt_id`)
) ENGINE=InnoDB AUTO_INCREMENT=9100000000000000073 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院就诊主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_invoice`
--

DROP TABLE IF EXISTS `his_invoice`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_invoice` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `invoice_no` varchar(50) NOT NULL COMMENT '发票号(前缀+号池序号)',
  `pool_id` bigint DEFAULT NULL COMMENT '发票号池ID(his_invoice_pool.id)',
  `bill_id` bigint DEFAULT NULL COMMENT '关联收费单ID(his_charge_bill.id)',
  `invoice_type` varchar(20) DEFAULT 'NORMAL' COMMENT '发票类型:NORMAL/VOID/RED',
  `amount` decimal(12,2) DEFAULT '0.00' COMMENT '开票金额',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名',
  `status` tinyint DEFAULT '1' COMMENT '状态:1正常 2已作废 3已红冲',
  `void_reason` varchar(500) DEFAULT NULL COMMENT '作废/红冲原因',
  `void_by` varchar(50) DEFAULT NULL COMMENT '作废/红冲操作人',
  `void_time` datetime DEFAULT NULL COMMENT '作废/红冲时间',
  `original_invoice_id` bigint DEFAULT NULL COMMENT '原发票ID(作废/红冲时指向原发票)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_org_invoice_no` (`tenant_id`,`org_id`,`invoice_no`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_bill` (`bill_id`),
  KEY `idx_pool` (`pool_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1012 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发票(开票记录)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_invoice_pool`
--

DROP TABLE IF EXISTS `his_invoice_pool`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_invoice_pool` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `pool_code` varchar(40) NOT NULL COMMENT '号池编码',
  `invoice_type` varchar(20) NOT NULL COMMENT '发票类型:NORMAL纸质/ELECTRONIC电子',
  `prefix` varchar(20) DEFAULT NULL COMMENT '发票号前缀',
  `start_no` bigint NOT NULL COMMENT '起始号',
  `end_no` bigint NOT NULL COMMENT '结束号',
  `current_no` bigint NOT NULL DEFAULT '0' COMMENT '当前已用号(下一个待取号=currentNo+1)',
  `status` tinyint DEFAULT '0' COMMENT '状态:0未启用 1使用中 2已用完',
  `alloc_by` varchar(50) DEFAULT NULL COMMENT '分配人',
  `alloc_time` datetime DEFAULT NULL COMMENT '分配时间',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_org_pool` (`tenant_id`,`org_id`,`pool_code`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发票号池(机构级发票号段)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_maintenance_template`
--

DROP TABLE IF EXISTS `his_maintenance_template`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_maintenance_template` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `template_name` varchar(100) NOT NULL COMMENT '模板名称',
  `warehouse_id` bigint DEFAULT NULL COMMENT '适用药库(空=全部)',
  `dosform` varchar(50) DEFAULT NULL COMMENT '筛选:剂型',
  `storage_cond` varchar(100) DEFAULT NULL COMMENT '筛选:储存条件',
  `drug_keyword` varchar(100) DEFAULT NULL COMMENT '筛选:药品名称/编码关键字',
  `default_measure` varchar(200) DEFAULT NULL COMMENT '默认养护措施',
  `status` tinyint DEFAULT '1' COMMENT '状态:1启用 0停用',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_tpl_name` (`tenant_id`,`org_id`,`template_name`,`deleted`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='药品养护模板(可复用筛选条件+默认养护措施)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_med_dict`
--

DROP TABLE IF EXISTS `his_med_dict`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_med_dict` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户(医共体)ID',
  `dict_type` varchar(10) NOT NULL COMMENT '字典类型:usage-用法(给药途径) freq-用药频次',
  `code` varchar(40) NOT NULL COMMENT '院内编码(租户内同类型唯一)',
  `name` varchar(100) NOT NULL COMMENT '名称(如口服/静脉注射; 每天三次tid)',
  `yb_code` varchar(20) DEFAULT NULL COMMENT '医保值域编码(用法:drug_medc_way_code/CV06.00.102; 频次:used_frqu/CV06.00.228)',
  `daily_times` decimal(8,4) DEFAULT NULL COMMENT '每日次数(仅频次; 驱动发药量换算, 支持小数如0.5=隔日)',
  `sort_no` int DEFAULT '0' COMMENT '排序号',
  `status` tinyint DEFAULT '1' COMMENT '状态:1启用 0停用',
  `memo` varchar(500) DEFAULT NULL COMMENT '备注',
  `src_type` varchar(30) DEFAULT NULL COMMENT '来源标准字典key(cv_code/hbvalue/院内自定义)',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `src_code` varchar(50) DEFAULT NULL COMMENT '来源编码(标准值域行编码)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  `abbr_code` varchar(64) DEFAULT NULL COMMENT '自定义简码(人工维护, 选填)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_type_code` (`tenant_id`,`dict_type`,`code`),
  KEY `idx_md_type` (`dict_type`),
  KEY `idx_md_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=28 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医共体用药字典(用法/用药频次, 牵头机构维护)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_med_type_dict`
--

DROP TABLE IF EXISTS `his_med_type_dict`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_med_type_dict` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户(医共体)ID',
  `code` varchar(20) NOT NULL COMMENT '医疗类别码(=医保 med_type 码, 租户内唯一, 权威不可改)',
  `name` varchar(100) NOT NULL COMMENT '名称(普通门诊/急诊/门诊慢特病/普通住院...)',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  `otp_use_flag` tinyint NOT NULL DEFAULT '0' COMMENT '门诊使用:1启用 0停用',
  `ipt_use_flag` tinyint NOT NULL DEFAULT '0' COMMENT '住院使用:1启用 0停用',
  `open_levels` varchar(20) DEFAULT '1,2,3' COMMENT '开放机构级别(orgLevel token 逗号集:1县/2乡/3村)',
  `yb_code` varchar(20) DEFAULT NULL COMMENT '医保码(=code)',
  `src_type` varchar(20) DEFAULT NULL COMMENT '溯源类型:cv_code',
  `src_doc` varchar(100) DEFAULT NULL COMMENT '溯源文档/字典组',
  `src_code` varchar(64) DEFAULT NULL COMMENT '溯源原始码',
  `sort_no` int DEFAULT '0' COMMENT '排序号',
  `status` tinyint DEFAULT '1' COMMENT '状态:1启用 0停用',
  `memo` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_med_type` (`tenant_id`,`code`),
  KEY `idx_mtd_status` (`status`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=344 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医疗类别字典(医共体级, 医保 med_type 导入叠加门诊/住院启停与按级别开放)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_medical_cert`
--

DROP TABLE IF EXISTS `his_medical_cert`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_medical_cert` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '证明ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `visit_id` bigint NOT NULL COMMENT '就诊ID',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名',
  `cert_type` tinyint DEFAULT '1' COMMENT '证明类型:1-诊断证明 2-病假条 3-转诊证明',
  `diagnosis` varchar(500) DEFAULT NULL COMMENT '诊断',
  `cert_content` varchar(2000) DEFAULT NULL COMMENT '证明内容',
  `sick_leave_days` int DEFAULT NULL COMMENT '建议病假天数',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `issue_dr_id` bigint DEFAULT NULL COMMENT '开具医师ID',
  `issue_dr_name` varchar(50) DEFAULT NULL COMMENT '开具医师姓名',
  `issue_time` datetime DEFAULT NULL COMMENT '开具时间',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID(开具科室归属机构)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `audit_status` tinyint DEFAULT '0' COMMENT '审核状态:0无须审核 1待审 2通过 3驳回',
  `auditor_id` bigint DEFAULT NULL COMMENT '审核人ID',
  `auditor_name` varchar(50) DEFAULT NULL COMMENT '审核人姓名',
  `audit_time` datetime DEFAULT NULL COMMENT '审核时间',
  `audit_remark` varchar(500) DEFAULT NULL COMMENT '审核意见/驳回原因',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_visit` (`visit_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='诊断证明';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_medical_record`
--

DROP TABLE IF EXISTS `his_medical_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_medical_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '病历ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `visit_id` bigint NOT NULL COMMENT '就诊ID',
  `subjective` varchar(2000) DEFAULT NULL COMMENT 'S-主观资料(主诉/现病史)',
  `objective` varchar(2000) DEFAULT NULL COMMENT 'O-客观资料(查体)',
  `assessment` varchar(2000) DEFAULT NULL COMMENT 'A-评估(诊断)',
  `plan` varchar(2000) DEFAULT NULL COMMENT 'P-计划(处理)',
  `dr_name` varchar(50) DEFAULT NULL COMMENT '书写医师',
  `dr_sign` varchar(50) DEFAULT NULL COMMENT '医师签名',
  `record_time` datetime DEFAULT NULL COMMENT '记录时间',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `allergy_history` varchar(500) DEFAULT NULL COMMENT '过敏史',
  `aux_exam` varchar(1000) DEFAULT NULL COMMENT '辅助检查',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_visit` (`visit_id`)
) ENGINE=InnoDB AUTO_INCREMENT=12447 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='门诊病历表(SOAP)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_medical_template`
--

DROP TABLE IF EXISTS `his_medical_template`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_medical_template` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint NOT NULL,
  `template_type` varchar(20) NOT NULL COMMENT 'soap/rx_set/order_set/fragment',
  `name` varchar(100) NOT NULL,
  `dept_id` bigint DEFAULT NULL COMMENT '科室级(null=全院)',
  `staff_id` bigint DEFAULT NULL COMMENT '个人级(null=科室/全院)',
  `content` text NOT NULL COMMENT 'JSON',
  `sort_order` int DEFAULT '0',
  `status` tinyint DEFAULT '1',
  `create_by` varchar(50) DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `is_fav` tinyint DEFAULT '0' COMMENT '收藏标记:1收藏(列表置顶) 0普通',
  PRIMARY KEY (`id`),
  KEY `idx_template_scope` (`tenant_id`,`template_type`,`staff_id`,`dept_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医生工作站医疗模板';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_mr_annotation`
--

DROP TABLE IF EXISTS `his_mr_annotation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_mr_annotation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `visit_id` bigint NOT NULL COMMENT '就诊ID(his_inp_visit.id)',
  `catalog_id` bigint DEFAULT NULL COMMENT '编目主表ID(his_mr_catalog.id)',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID(sys_org.id)',
  `parent_id` bigint DEFAULT NULL COMMENT '父批注ID(回复线程,NULL=顶层)',
  `from_staff_id` bigint DEFAULT NULL COMMENT '批注人(his_staff.id)',
  `from_staff_name` varchar(100) DEFAULT NULL COMMENT '批注人姓名',
  `to_staff_id` bigint DEFAULT NULL COMMENT '接收人(his_staff.id,可空)',
  `to_staff_name` varchar(100) DEFAULT NULL COMMENT '接收人姓名',
  `ann_type` varchar(20) DEFAULT NULL COMMENT '类型:feedback反馈 ask询问 reply答复 other其他',
  `target_field` varchar(100) DEFAULT NULL COMMENT '关联/定位字段键',
  `content` varchar(1000) DEFAULT NULL COMMENT '批注内容',
  `resolved` tinyint DEFAULT '0' COMMENT '是否已处理:0未处理 1已处理',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mr_ann_catalog` (`catalog_id`,`deleted`),
  KEY `idx_mr_ann_visit` (`visit_id`,`deleted`),
  KEY `idx_mr_ann_resolved` (`resolved`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=2105604307351539715 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病案批注与反馈';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_mr_assign`
--

DROP TABLE IF EXISTS `his_mr_assign`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_mr_assign` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `catalog_id` bigint NOT NULL COMMENT '编目主表ID(his_mr_catalog.id)',
  `visit_id` bigint NOT NULL COMMENT '就诊ID',
  `assign_type` tinyint DEFAULT '1' COMMENT '分配方式:1偏好科室 2随机均分',
  `cataloger_id` bigint DEFAULT NULL COMMENT '分配编目员(his_staff.id)',
  `cataloger_name` varchar(100) DEFAULT NULL COMMENT '编目员姓名',
  `priority` int DEFAULT '0' COMMENT '优先级',
  `assign_status` tinyint DEFAULT '1' COMMENT '分配状态:1已分配 2已释放 3已编目',
  `assign_by` varchar(50) DEFAULT NULL COMMENT '分配操作人',
  `assign_time` datetime DEFAULT NULL COMMENT '分配时间',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mr_assign_catalog` (`catalog_id`,`deleted`),
  KEY `idx_mr_assign_cataloger` (`cataloger_id`,`assign_status`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=2105555085474439171 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病案分配';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_mr_base_dict`
--

DROP TABLE IF EXISTS `his_mr_base_dict`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_mr_base_dict` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID(NULL=全局可复用)',
  `dict_type` varchar(20) NOT NULL COMMENT '字典类别:case_base病案基础 wt_base卫统基础 ward病区 med_team医疗小组 holiday节假日',
  `code` varchar(50) NOT NULL COMMENT '编码(节假日为yyyy-MM-dd)',
  `name` varchar(200) NOT NULL COMMENT '名称',
  `parent_code` varchar(50) DEFAULT NULL COMMENT '上级编码(层级,可空)',
  `ext1` varchar(200) DEFAULT NULL COMMENT '附加1(病区所属科室/医疗小组组长等)',
  `ext2` varchar(200) DEFAULT NULL COMMENT '附加2(医疗小组所属科室/节假日类型等)',
  `valid_flag` tinyint DEFAULT '1' COMMENT '有效标志:1启用 0停用',
  `sort_no` int DEFAULT '0' COMMENT '排序',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mr_bd_type` (`dict_type`,`valid_flag`,`deleted`),
  KEY `idx_mr_bd_code` (`dict_type`,`code`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=2105597534737580035 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病案系统维护字典';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_mr_borrow`
--

DROP TABLE IF EXISTS `his_mr_borrow`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_mr_borrow` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `visit_id` bigint NOT NULL COMMENT '就诊ID(his_inp_visit.id)',
  `catalog_id` bigint DEFAULT NULL COMMENT '编目主表ID(his_mr_catalog.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID(sys_org.id)',
  `borrow_no` varchar(50) DEFAULT NULL COMMENT '借阅单号',
  `borrower_id` bigint DEFAULT NULL COMMENT '借阅人(his_staff.id)',
  `borrower_name` varchar(100) DEFAULT NULL COMMENT '借阅人姓名',
  `borrower_dept_id` bigint DEFAULT NULL COMMENT '借阅人科室ID(his_dept.id)',
  `borrower_dept_name` varchar(100) DEFAULT NULL COMMENT '借阅人科室名称',
  `purpose` varchar(500) DEFAULT NULL COMMENT '借阅事由',
  `borrow_time` datetime DEFAULT NULL COMMENT '借出时间',
  `expect_return_date` datetime DEFAULT NULL COMMENT '应归还日期',
  `actual_return_time` datetime DEFAULT NULL COMMENT '实际归还时间',
  `borrow_status` tinyint DEFAULT '1' COMMENT '借阅状态:1借出 2已归还 3逾期',
  `operator_name` varchar(50) DEFAULT NULL COMMENT '经办人姓名',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mr_borrow_visit` (`visit_id`,`deleted`),
  KEY `idx_mr_borrow_status` (`borrow_status`,`expect_return_date`,`deleted`),
  KEY `idx_mr_borrow_no` (`borrow_no`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=2105581757548503042 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病案借阅';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_mr_catalog`
--

DROP TABLE IF EXISTS `his_mr_catalog`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_mr_catalog` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `visit_id` bigint NOT NULL COMMENT '就诊ID(his_inp_visit.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID(sys_org.id)',
  `catalog_no` varchar(50) DEFAULT NULL COMMENT '病案号',
  `source_case_page_id` bigint DEFAULT NULL COMMENT '临床首页ID(his_case_front_page.id)',
  `admission_date` datetime DEFAULT NULL COMMENT '入院时间',
  `discharge_date` datetime DEFAULT NULL COMMENT '出院时间',
  `los_days` int DEFAULT NULL COMMENT '住院天数',
  `admission_dept_id` bigint DEFAULT NULL COMMENT '入院科室ID(his_dept.id)',
  `discharge_dept_id` bigint DEFAULT NULL COMMENT '出院科室ID(his_dept.id)',
  `main_diag_code` varchar(50) DEFAULT NULL COMMENT '出院主要诊断编码(编目修订后)',
  `main_diag_name` varchar(200) DEFAULT NULL COMMENT '出院主要诊断名称',
  `is_tcm` tinyint DEFAULT '0' COMMENT '中医标志:0西医 1中医',
  `catalog_status` tinyint DEFAULT '1' COMMENT '编目状态:1待编目 2编目中 3已编目',
  `audit_status` tinyint DEFAULT '1' COMMENT '审核状态:1未审核 2已审核 3已确认',
  `lock_status` tinyint DEFAULT '0' COMMENT '锁定状态:0未锁 1已锁',
  `cataloger_id` bigint DEFAULT NULL COMMENT '责任编目员(his_staff.id)',
  `cataloger_name` varchar(100) DEFAULT NULL COMMENT '责任编目员姓名',
  `quality_score` int DEFAULT NULL COMMENT '病案首页质量评分',
  `summary` text COMMENT '首页快照JSON(基础/费用字段)',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mr_visit` (`visit_id`,`tenant_id`,`deleted`),
  KEY `idx_mr_catalog_status` (`catalog_status`,`deleted`),
  KEY `idx_mr_discharge_dept` (`discharge_dept_id`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=2105580660821893122 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病案编目主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_mr_change_log`
--

DROP TABLE IF EXISTS `his_mr_change_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_mr_change_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `catalog_id` bigint NOT NULL COMMENT '编目主表ID(his_mr_catalog.id)',
  `visit_id` bigint NOT NULL COMMENT '就诊ID',
  `op_type` varchar(20) DEFAULT NULL COMMENT '操作类型:catalog编目 assign分配 audit审核 confirm确认 lock锁定 unlock解锁',
  `field_key` varchar(100) DEFAULT NULL COMMENT '变更字段键',
  `old_val` varchar(500) DEFAULT NULL COMMENT '变更前值',
  `new_val` varchar(500) DEFAULT NULL COMMENT '变更后值',
  `op_user` varchar(50) DEFAULT NULL COMMENT '操作人',
  `op_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mr_log_catalog` (`catalog_id`,`deleted`),
  KEY `idx_mr_log_visit` (`visit_id`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=2105580660880613379 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病案修改留痕';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_mr_diag`
--

DROP TABLE IF EXISTS `his_mr_diag`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_mr_diag` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `catalog_id` bigint NOT NULL COMMENT '编目主表ID(his_mr_catalog.id)',
  `visit_id` bigint NOT NULL COMMENT '就诊ID',
  `diag_type` varchar(20) NOT NULL COMMENT '诊断类别:outp门急诊 adm入院 dmain出院主 dother出院次 path病理 injure损伤中毒外因 infect院内感染',
  `clinical_code` varchar(50) DEFAULT NULL COMMENT '国临版诊断编码',
  `clinical_name` varchar(200) DEFAULT NULL COMMENT '国临版诊断名称',
  `yb_code` varchar(50) DEFAULT NULL COMMENT '医保版诊断编码',
  `yb_name` varchar(200) DEFAULT NULL COMMENT '医保版诊断名称',
  `yb_sort_no` int DEFAULT NULL COMMENT '医保位序',
  `report_flag` tinyint DEFAULT '0' COMMENT '是否医保上报:0否 1是',
  `gray_flag` tinyint DEFAULT '0' COMMENT '医保灰码:0否 1是',
  `main_flag` tinyint DEFAULT '0' COMMENT '主诊断标志:0否 1是',
  `doctor_desc` varchar(500) DEFAULT NULL COMMENT '医师诊断描述',
  `sort_no` int DEFAULT '0' COMMENT '同类别内序号',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mr_diag_catalog` (`catalog_id`,`diag_type`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=2105580770251284483 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病案编目诊断明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_mr_oper`
--

DROP TABLE IF EXISTS `his_mr_oper`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_mr_oper` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `catalog_id` bigint NOT NULL COMMENT '编目主表ID(his_mr_catalog.id)',
  `visit_id` bigint NOT NULL COMMENT '就诊ID',
  `clinical_code` varchar(50) DEFAULT NULL COMMENT '国临版手术操作编码(ICD-9-CM-3)',
  `clinical_name` varchar(200) DEFAULT NULL COMMENT '国临版手术操作名称',
  `yb_code` varchar(50) DEFAULT NULL COMMENT '医保版手术编码',
  `yb_name` varchar(200) DEFAULT NULL COMMENT '医保版手术名称',
  `yb_sort_no` int DEFAULT NULL COMMENT '医保位序',
  `report_flag` tinyint DEFAULT '0' COMMENT '是否医保上报:0否 1是',
  `main_flag` tinyint DEFAULT '0' COMMENT '主手术标志:0否 1是',
  `gray_flag` tinyint DEFAULT '0' COMMENT '医保灰码:0否 1是',
  `oper_date` datetime DEFAULT NULL COMMENT '手术日期',
  `surgeon_name` varchar(100) DEFAULT NULL COMMENT '手术医师',
  `anesthesia` varchar(100) DEFAULT NULL COMMENT '麻醉方式',
  `incision_type` varchar(50) DEFAULT NULL COMMENT '切口类型',
  `heal_level` varchar(50) DEFAULT NULL COMMENT '愈合等级',
  `sort_no` int DEFAULT '0' COMMENT '序号',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mr_oper_catalog` (`catalog_id`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病案编目手术操作明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_mr_other`
--

DROP TABLE IF EXISTS `his_mr_other`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_mr_other` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `catalog_id` bigint NOT NULL COMMENT '编目主表ID(his_mr_catalog.id)',
  `visit_id` bigint NOT NULL COMMENT '就诊ID',
  `rec_type` varchar(20) NOT NULL COMMENT '记录类别:transfer转科 allergy过敏药物 icu重症监护',
  `code` varchar(50) DEFAULT NULL COMMENT '编码(药物/科室编码等)',
  `name` varchar(200) DEFAULT NULL COMMENT '名称(药物名称/转入转出科室/监护项目)',
  `detail` varchar(500) DEFAULT NULL COMMENT '详情/描述',
  `begin_time` datetime DEFAULT NULL COMMENT '开始时间(入科/入监护/过敏发现)',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间(出科/出监护)',
  `sort_no` int DEFAULT '0' COMMENT '序号',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mr_other_catalog` (`catalog_id`,`rec_type`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病案编目多条扩展记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_mr_quality_err`
--

DROP TABLE IF EXISTS `his_mr_quality_err`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_mr_quality_err` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `catalog_id` bigint NOT NULL COMMENT '编目主表ID(his_mr_catalog.id)',
  `visit_id` bigint NOT NULL COMMENT '就诊ID',
  `batch_no` varchar(50) DEFAULT NULL COMMENT '审核批次号',
  `rule_category` varchar(20) DEFAULT NULL COMMENT '规则类别:强制 非强制',
  `rule_code` varchar(50) DEFAULT NULL COMMENT '规则编码',
  `field_key` varchar(100) DEFAULT NULL COMMENT '定位字段键',
  `error_msg` varchar(500) DEFAULT NULL COMMENT '错误描述',
  `resolved` tinyint DEFAULT '0' COMMENT '是否已修复:0否 1是',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mr_qe_catalog` (`catalog_id`,`resolved`,`deleted`),
  KEY `idx_mr_qe_batch` (`batch_no`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=2105537680761171970 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病案质控审核错误项';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_mr_recall`
--

DROP TABLE IF EXISTS `his_mr_recall`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_mr_recall` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `visit_id` bigint NOT NULL COMMENT '就诊ID(his_inp_visit.id)',
  `catalog_id` bigint DEFAULT NULL COMMENT '编目主表ID(his_mr_catalog.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID(sys_org.id)',
  `barcode` varchar(64) DEFAULT NULL COMMENT '病案条码(扫描录入)',
  `recall_status` tinyint DEFAULT '1' COMMENT '收回状态:1待收回 2已收回 3逾期',
  `due_date` datetime DEFAULT NULL COMMENT '应回收日期(出院后按规则推算)',
  `recall_time` datetime DEFAULT NULL COMMENT '实际回收时间',
  `recall_user_id` bigint DEFAULT NULL COMMENT '回收人(his_staff.id)',
  `recall_user_name` varchar(100) DEFAULT NULL COMMENT '回收人姓名',
  `shelf_flag` tinyint DEFAULT '0' COMMENT '是否已上架:0否 1是',
  `shelf_location` varchar(100) DEFAULT NULL COMMENT '上架库位/架号',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mr_recall_visit` (`visit_id`,`deleted`),
  KEY `idx_mr_recall_status` (`recall_status`,`due_date`,`deleted`),
  KEY `idx_mr_recall_barcode` (`barcode`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=2105580770444222467 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病案收回/回收登记';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_mr_report_batch`
--

DROP TABLE IF EXISTS `his_mr_report_batch`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_mr_report_batch` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID(sys_org.id)',
  `report_type` varchar(20) NOT NULL COMMENT '上报类型:wt卫统4表 hqms HQMS绩效 med_list医保结算清单',
  `batch_no` varchar(50) DEFAULT NULL COMMENT '上报批次号',
  `period_from` date DEFAULT NULL COMMENT '统计起',
  `period_to` date DEFAULT NULL COMMENT '统计止',
  `status` tinyint DEFAULT '1' COMMENT '闭环状态:1待审核 2已审核待转换 3已转换待上报 4已上报 5失败',
  `total_count` int DEFAULT '0' COMMENT '病案总数',
  `ok_count` int DEFAULT '0' COMMENT '审核通过数',
  `err_count` int DEFAULT '0' COMMENT '审核拦截数',
  `reviewer_name` varchar(50) DEFAULT NULL COMMENT '审核人姓名',
  `review_time` datetime DEFAULT NULL COMMENT '审核时间',
  `converter_name` varchar(50) DEFAULT NULL COMMENT '转换人姓名',
  `convert_time` datetime DEFAULT NULL COMMENT '转换时间',
  `submitter_name` varchar(50) DEFAULT NULL COMMENT '上报人姓名',
  `submit_time` datetime DEFAULT NULL COMMENT '上报时间',
  `file_ref` varchar(200) DEFAULT NULL COMMENT '上报文件/数据集引用',
  `fail_reason` varchar(500) DEFAULT NULL COMMENT '失败/拦截原因',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mr_rb_type` (`report_type`,`status`,`deleted`),
  KEY `idx_mr_rb_no` (`batch_no`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=2105597831321010178 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病案上报批次';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_mr_report_item`
--

DROP TABLE IF EXISTS `his_mr_report_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_mr_report_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `batch_id` bigint NOT NULL COMMENT '批次ID(his_mr_report_batch.id)',
  `visit_id` bigint NOT NULL COMMENT '就诊ID(his_inp_visit.id)',
  `catalog_id` bigint DEFAULT NULL COMMENT '编目主表ID(his_mr_catalog.id)',
  `patient_name` varchar(100) DEFAULT NULL COMMENT '患者姓名',
  `inp_no` varchar(50) DEFAULT NULL COMMENT '住院号',
  `main_diag_code` varchar(50) DEFAULT NULL COMMENT '主要诊断编码',
  `check_status` tinyint DEFAULT '1' COMMENT '审核结果:1通过 2拦截',
  `check_msg` varchar(500) DEFAULT NULL COMMENT '审核意见/拦截原因',
  `converted` tinyint DEFAULT '0' COMMENT '是否已转换:0否 1是',
  `reported` tinyint DEFAULT '0' COMMENT '是否已上报:0否 1是',
  `report_msg` varchar(500) DEFAULT NULL COMMENT '上报回执/失败原因',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mr_ri_batch` (`batch_id`,`check_status`,`deleted`),
  KEY `idx_mr_ri_visit` (`visit_id`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=2105597831383924739 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病案上报批次明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_mr_review`
--

DROP TABLE IF EXISTS `his_mr_review`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_mr_review` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `catalog_id` bigint NOT NULL COMMENT '编目主表ID(his_mr_catalog.id)',
  `visit_id` bigint NOT NULL COMMENT '就诊ID',
  `reviewer_id` bigint DEFAULT NULL COMMENT '审核人(sys_user.id)',
  `reviewer_name` varchar(100) DEFAULT NULL COMMENT '审核人姓名',
  `audit_opinion` varchar(500) DEFAULT NULL COMMENT '审核意见',
  `confirm_time` datetime DEFAULT NULL COMMENT '审核确认时间',
  `lock_status` tinyint DEFAULT '0' COMMENT '锁定状态:0未锁 1已锁',
  `lock_time` datetime DEFAULT NULL COMMENT '锁定时间',
  `unlock_reason` varchar(500) DEFAULT NULL COMMENT '解锁原因',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mr_review_catalog` (`catalog_id`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=2105568260538269698 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病案审核确认与锁定';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_mr_workload`
--

DROP TABLE IF EXISTS `his_mr_workload`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_mr_workload` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID(sys_org.id)',
  `period` varchar(7) NOT NULL COMMENT '统计期(yyyy-MM)',
  `dept_id` bigint DEFAULT NULL COMMENT '科室ID(his_dept.id)',
  `dept_name` varchar(100) DEFAULT NULL COMMENT '科室名称',
  `staff_id` bigint DEFAULT NULL COMMENT '责任人(his_staff.id)',
  `staff_name` varchar(100) DEFAULT NULL COMMENT '责任人姓名',
  `category` varchar(20) NOT NULL COMMENT '工作量类别:outp门诊 inp住院病区 tech医技 other其他项',
  `item_code` varchar(50) DEFAULT NULL COMMENT '项目编码',
  `item_name` varchar(200) DEFAULT NULL COMMENT '项目名称',
  `qty` decimal(14,2) DEFAULT '0.00' COMMENT '数量',
  `amount` decimal(14,2) DEFAULT NULL COMMENT '金额(可空)',
  `audit_status` tinyint DEFAULT '1' COMMENT '逻辑审核状态:1待审 2通过 3驳回',
  `audit_user_name` varchar(50) DEFAULT NULL COMMENT '审核人姓名',
  `audit_time` datetime DEFAULT NULL COMMENT '审核时间',
  `audit_opinion` varchar(500) DEFAULT NULL COMMENT '审核意见',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_mr_wl_period` (`period`,`deleted`),
  KEY `idx_mr_wl_dept` (`dept_id`,`category`,`deleted`),
  KEY `idx_mr_wl_audit` (`audit_status`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=2105597534083268611 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病案工作量统计录入';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_newborn`
--

DROP TABLE IF EXISTS `his_newborn`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_newborn` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `mother_inp_visit_id` bigint NOT NULL COMMENT '母亲住院就诊ID(his_inp_visit.id)',
  `surgery_id` bigint DEFAULT NULL COMMENT '分娩手术ID(his_surgery.id, 剖宫产可关联)',
  `baby_patient_id` bigint DEFAULT NULL COMMENT '新生儿建档患者ID(his_patient.id)',
  `baby_inp_visit_id` bigint DEFAULT NULL COMMENT '新生儿住院就诊ID(his_inp_visit.id)',
  `baby_name` varchar(50) DEFAULT NULL COMMENT '新生儿姓名',
  `baby_sex` tinyint DEFAULT NULL COMMENT '性别:1男 2女',
  `birth_time` datetime DEFAULT NULL COMMENT '出生时间',
  `apgar_1` int DEFAULT NULL COMMENT 'Apgar 1分钟评分',
  `apgar_5` int DEFAULT NULL COMMENT 'Apgar 5分钟评分',
  `apgar_10` int DEFAULT NULL COMMENT 'Apgar 10分钟评分',
  `weight_g` int DEFAULT NULL COMMENT '出生体重(克)',
  `height_cm` decimal(5,1) DEFAULT NULL COMMENT '身长(厘米)',
  `birth_type` tinyint DEFAULT '1' COMMENT '分娩方式:1顺产 2剖宫产 3产钳 4臀助 5其他',
  `status` tinyint DEFAULT '1' COMMENT '状态:1在绑 2已转科 3已出院',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_mother` (`mother_inp_visit_id`),
  KEY `idx_surgery` (`surgery_id`),
  KEY `idx_baby_visit` (`baby_inp_visit_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='新生儿建档(P2d 产科分娩一体化)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_nurse_exec`
--

DROP TABLE IF EXISTS `his_nurse_exec`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_nurse_exec` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `visit_id` bigint NOT NULL COMMENT '就诊ID(his_visit.id)',
  `order_id` bigint NOT NULL COMMENT '医嘱单ID(his_order.id)',
  `order_item_id` bigint DEFAULT NULL COMMENT '医嘱明细ID(his_order_item.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `exec_type` varchar(20) DEFAULT NULL COMMENT '执行类型:injection注射/infusion输液/skin_test皮试/other其他',
  `exec_no` varchar(30) NOT NULL COMMENT '执行单号(EX+日期+序号)',
  `exec_status` tinyint DEFAULT '0' COMMENT '执行状态:0待执行 1执行中 2已完成 3已取消',
  `exec_nurse_id` bigint DEFAULT NULL COMMENT '执行护士ID(his_staff.id)',
  `exec_time` datetime DEFAULT NULL COMMENT '执行(开始)时间',
  `verify_nurse_id` bigint DEFAULT NULL COMMENT '核对护士ID(高危操作双人核对)',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间',
  `patient_response` varchar(500) DEFAULT NULL COMMENT '患者反应',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_nexec_no` (`tenant_id`,`exec_no`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`exec_status`,`exec_time`),
  KEY `idx_order` (`order_id`),
  KEY `idx_visit` (`visit_id`)
) ENGINE=InnoDB AUTO_INCREMENT=35 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='护士执行记录(注射/输液/皮试统一台账)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_nursing_clinical_event`
--

DROP TABLE IF EXISTS `his_nursing_clinical_event`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_nursing_clinical_event` (
  `id` bigint NOT NULL COMMENT '主键(雪花)',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `patient_id` bigint NOT NULL COMMENT '患者ID(his_patient.id)',
  `event_type` varchar(30) NOT NULL COMMENT '事件类型:admission/discharge/death/surgery/transfer_in/transfer_out/delivery/resuscitation',
  `event_time` datetime NOT NULL COMMENT '事件时间',
  `event_desc` varchar(500) DEFAULT NULL COMMENT '事件描述',
  `auto_generated` tinyint DEFAULT '0' COMMENT '0手动 1自动生成',
  `source_type` varchar(30) DEFAULT NULL COMMENT '触发源类型:order/visit_status/manual',
  `source_id` bigint DEFAULT NULL COMMENT '触发源ID',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_event_visit` (`inp_visit_id`),
  KEY `idx_event_time` (`event_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='护理临床事件(P4c)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_nursing_consent`
--

DROP TABLE IF EXISTS `his_nursing_consent`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_nursing_consent` (
  `id` bigint NOT NULL COMMENT '主键(雪花)',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `patient_id` bigint NOT NULL COMMENT '患者ID(his_patient.id)',
  `consent_type` varchar(50) NOT NULL COMMENT '告知类型:admission/surgery/anesthesia/blood/special_drug/invasive/fall_risk/other',
  `consent_name` varchar(200) NOT NULL COMMENT '告知书名称',
  `content` text COMMENT '告知内容',
  `patient_signature_base64` longtext COMMENT '患者签名图片',
  `family_signature_base64` longtext COMMENT '家属签名图片',
  `signer_name` varchar(50) DEFAULT NULL COMMENT '签名人姓名',
  `signer_relation` varchar(30) DEFAULT NULL COMMENT '与患者关系',
  `sign_time` datetime DEFAULT NULL COMMENT '签名时间',
  `witness_name` varchar(50) DEFAULT NULL COMMENT '见证人',
  `status` tinyint DEFAULT '0' COMMENT '0待签 1已签 2已撤回',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_consent_visit` (`inp_visit_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='护理告知书同意书(P4c)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_nursing_education`
--

DROP TABLE IF EXISTS `his_nursing_education`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_nursing_education` (
  `id` bigint NOT NULL COMMENT '主键(雪花)',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `patient_id` bigint NOT NULL COMMENT '患者ID(his_patient.id)',
  `knowledge_category` varchar(50) DEFAULT NULL COMMENT '知识分类:admission/disease/medication/diet/exercise/discharge/other',
  `title` varchar(200) DEFAULT NULL COMMENT '宣教标题',
  `content` text COMMENT '宣教内容',
  `education_method` varchar(30) DEFAULT NULL COMMENT '方式:verbal/written/video/demo',
  `evaluation_result` varchar(30) DEFAULT NULL COMMENT '评价:understood/partially/not_understood',
  `educator_id` bigint DEFAULT NULL COMMENT '宣教人ID',
  `educator_name` varchar(50) DEFAULT NULL COMMENT '宣教人',
  `education_time` datetime DEFAULT NULL COMMENT '宣教时间',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_edu_visit` (`inp_visit_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='护理健康宣教(P4c)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_nursing_io_record`
--

DROP TABLE IF EXISTS `his_nursing_io_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_nursing_io_record` (
  `id` bigint NOT NULL COMMENT '主键(雪花)',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `patient_id` bigint NOT NULL COMMENT '患者ID(his_patient.id)',
  `record_time` datetime NOT NULL COMMENT '记录时间',
  `io_type` tinyint NOT NULL COMMENT '1入量 2出量',
  `item_name` varchar(100) NOT NULL COMMENT '项目名称',
  `item_category` varchar(50) DEFAULT NULL COMMENT '项目分类:infusion/oral/urine/drain/gastric/vomit/stool/blood/other',
  `volume_ml` int DEFAULT NULL COMMENT '量(ml)',
  `route` varchar(50) DEFAULT NULL COMMENT '途径',
  `note` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_io_visit` (`inp_visit_id`),
  KEY `idx_io_time` (`record_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='护理出入量记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_nursing_pipe`
--

DROP TABLE IF EXISTS `his_nursing_pipe`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_nursing_pipe` (
  `id` bigint NOT NULL COMMENT '主键(雪花)',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `patient_id` bigint NOT NULL COMMENT '患者ID(his_patient.id)',
  `pipe_type` varchar(50) NOT NULL COMMENT '管道类型:central_venous/urinary/nasogastric/chest_tube/drain/tracheostomy/picc/other',
  `pipe_name` varchar(100) NOT NULL COMMENT '管道名称',
  `insert_time` datetime DEFAULT NULL COMMENT '置管时间',
  `insert_site` varchar(100) DEFAULT NULL COMMENT '置管部位',
  `body_part_svg_data` text COMMENT '人体图SVG标注数据',
  `risk_level` tinyint DEFAULT '1' COMMENT '风险等级:1低 2中 3高',
  `expected_remove_date` date DEFAULT NULL COMMENT '预计拔管日期',
  `actual_remove_time` datetime DEFAULT NULL COMMENT '实际拔管时间',
  `status` tinyint DEFAULT '1' COMMENT '状态:1在管 2已拔 3意外脱出',
  `last_assess_time` datetime DEFAULT NULL COMMENT '最后评估时间',
  `last_replace_time` datetime DEFAULT NULL COMMENT '最后更换时间',
  `note` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_pipe_visit` (`inp_visit_id`),
  KEY `idx_pipe_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='护理管道记录(P4c)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_nursing_plan_instance`
--

DROP TABLE IF EXISTS `his_nursing_plan_instance`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_nursing_plan_instance` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `template_id` bigint DEFAULT NULL COMMENT '护理计划模板ID(his_nursing_plan_template.id)',
  `scale_record_id` bigint DEFAULT NULL COMMENT '触发量表记录ID(his_inp_nursing_record.id)',
  `nursing_diagnosis` varchar(500) DEFAULT NULL COMMENT '护理诊断',
  `nursing_goal` varchar(500) DEFAULT NULL COMMENT '护理目标',
  `planned_interventions` text COMMENT '计划措施JSON',
  `actual_interventions` text COMMENT '实际措施JSON',
  `start_time` datetime DEFAULT NULL COMMENT '开始时间',
  `evaluation_time` datetime DEFAULT NULL COMMENT '评价时间',
  `evaluation_result` varchar(500) DEFAULT NULL COMMENT '评价结果',
  `status` int DEFAULT '1' COMMENT '状态:1执行中 2已评价 3已关闭',
  `nurse_id` bigint DEFAULT NULL COMMENT '责任护士ID(his_staff.id)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_plan_inst_visit` (`inp_visit_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2104564389242273795 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='护理计划实例';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_nursing_plan_template`
--

DROP TABLE IF EXISTS `his_nursing_plan_template`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_nursing_plan_template` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `plan_name` varchar(100) NOT NULL COMMENT '计划名称',
  `trigger_scale_code` varchar(30) DEFAULT NULL COMMENT '触发量表编码(his_nursing_scale_def.scale_code)',
  `trigger_score_range` varchar(50) DEFAULT NULL COMMENT '触发分值区间',
  `nursing_diagnosis` varchar(500) DEFAULT NULL COMMENT '护理诊断',
  `nursing_goal` varchar(500) DEFAULT NULL COMMENT '护理目标',
  `interventions` text COMMENT '护理措施JSON数组',
  `evaluation_criteria` varchar(500) DEFAULT NULL COMMENT '评价标准',
  `dept_id` bigint DEFAULT NULL COMMENT '科室ID(his_dept.id)',
  `status` int DEFAULT '1' COMMENT '状态:1启用 0停用',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2104509304625770498 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='护理计划模板';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_nursing_scale_def`
--

DROP TABLE IF EXISTS `his_nursing_scale_def`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_nursing_scale_def` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `scale_code` varchar(30) NOT NULL COMMENT '量表编码',
  `scale_name` varchar(100) NOT NULL COMMENT '量表名称',
  `scale_type` int DEFAULT NULL COMMENT '量表类型:1入院评估 2专科 3风险',
  `dimensions` text COMMENT '维度定义JSON',
  `score_interpretation` text COMMENT '分数→风险映射JSON',
  `required_frequency` varchar(200) DEFAULT NULL COMMENT '必评频次说明',
  `status` int DEFAULT '1' COMMENT '状态:1启用 0停用',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scale_code` (`scale_code`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='护理评估量表定义';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_nursing_shift_report`
--

DROP TABLE IF EXISTS `his_nursing_shift_report`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_nursing_shift_report` (
  `id` bigint NOT NULL COMMENT '主键(雪花)',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `ward_id` bigint NOT NULL COMMENT '病区ID',
  `shift_type` varchar(10) NOT NULL COMMENT '班次:day/evening/night',
  `shift_date` date NOT NULL COMMENT '交班日期',
  `content` text COMMENT '报告内容JSON',
  `critical_count` int DEFAULT '0' COMMENT '危重人数',
  `new_admit_count` int DEFAULT '0' COMMENT '新入院人数',
  `transfer_in_count` int DEFAULT '0' COMMENT '转入人数',
  `transfer_out_count` int DEFAULT '0' COMMENT '转出人数',
  `surgery_count` int DEFAULT '0' COMMENT '手术人数',
  `total_patients` int DEFAULT '0' COMMENT '在科总人数',
  `reporter_id` bigint DEFAULT NULL COMMENT '交班人ID',
  `reporter_name` varchar(50) DEFAULT NULL COMMENT '交班人',
  `receiver_id` bigint DEFAULT NULL COMMENT '接班人ID',
  `receiver_name` varchar(50) DEFAULT NULL COMMENT '接班人',
  `status` tinyint DEFAULT '0' COMMENT '0草稿 1已交班 2已接班',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_shift_ward` (`ward_id`),
  KEY `idx_shift_date` (`shift_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='护理交班报告(P4c)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_nursing_template`
--

DROP TABLE IF EXISTS `his_nursing_template`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_nursing_template` (
  `id` bigint NOT NULL COMMENT '主键(雪花)',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `code` varchar(50) NOT NULL COMMENT '模板编码',
  `name` varchar(100) NOT NULL COMMENT '模板名称',
  `record_type` varchar(30) DEFAULT NULL COMMENT '文书类型:nursing_record/assessment/transfer/consent/nursing_plan',
  `ward_scope` text COMMENT '适用病区JSON数组(空=全院)',
  `paper_size` varchar(10) DEFAULT 'A4' COMMENT '纸张大小',
  `orientation` varchar(10) DEFAULT 'portrait' COMMENT '打印方向:portrait/landscape',
  `fields` text COMMENT '字段定义JSON',
  `document` longtext COMMENT 'Tiptap JSON文档',
  `print_script` text COMMENT '打印格式脚本',
  `scope_level` int DEFAULT '0' COMMENT '范围:0全院 1病区 2个人',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_ntempl_code` (`code`),
  KEY `idx_ntempl_type` (`record_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='护理文书模板';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_nursing_transfer`
--

DROP TABLE IF EXISTS `his_nursing_transfer`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_nursing_transfer` (
  `id` bigint NOT NULL COMMENT '主键(雪花)',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `patient_id` bigint NOT NULL COMMENT '患者ID(his_patient.id)',
  `transfer_type` varchar(30) NOT NULL COMMENT '类型:dept_transfer/surgery/hemodialysis/intervention/endoscopy',
  `checklist` text COMMENT '交接核查JSON',
  `sender_id` bigint DEFAULT NULL COMMENT '交出人ID',
  `sender_name` varchar(50) DEFAULT NULL COMMENT '交出人',
  `receiver_id` bigint DEFAULT NULL COMMENT '接收人ID',
  `receiver_name` varchar(50) DEFAULT NULL COMMENT '接收人',
  `handover_time` datetime DEFAULT NULL COMMENT '交接时间',
  `from_dept_id` bigint DEFAULT NULL COMMENT '转出科室',
  `to_dept_id` bigint DEFAULT NULL COMMENT '转入科室',
  `status` tinyint DEFAULT '0' COMMENT '0草稿 1已交接 2已确认',
  `note` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_transfer_visit` (`inp_visit_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='护理转运交接单(P4c)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_nursing_vital_sign`
--

DROP TABLE IF EXISTS `his_nursing_vital_sign`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_nursing_vital_sign` (
  `id` bigint NOT NULL COMMENT '主键(雪花)',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `patient_id` bigint NOT NULL COMMENT '患者ID(his_patient.id)',
  `record_time` datetime NOT NULL COMMENT '测量时间',
  `temperature` decimal(4,1) DEFAULT NULL COMMENT '体温(℃)',
  `temp_type` tinyint DEFAULT NULL COMMENT '体温类型:1口温 2腋温 3肛温 4耳温',
  `pulse` int DEFAULT NULL COMMENT '脉搏(次/分)',
  `heart_rate` int DEFAULT NULL COMMENT '心率(次/分)',
  `respiration` int DEFAULT NULL COMMENT '呼吸(次/分)',
  `systolic_bp` int DEFAULT NULL COMMENT '收缩压(mmHg)',
  `diastolic_bp` int DEFAULT NULL COMMENT '舒张压(mmHg)',
  `spo2` int DEFAULT NULL COMMENT '血氧饱和度(%)',
  `blood_glucose` decimal(5,1) DEFAULT NULL COMMENT '血糖(mmol/L)',
  `pain_score` int DEFAULT NULL COMMENT '疼痛评分(0-10)',
  `consciousness` varchar(20) DEFAULT NULL COMMENT '意识状态',
  `weight` decimal(6,2) DEFAULT NULL COMMENT '体重(kg)',
  `height` decimal(5,1) DEFAULT NULL COMMENT '身高(cm)',
  `gcs_score` int DEFAULT NULL COMMENT 'GCS评分(3-15)',
  `mews_score` int DEFAULT NULL COMMENT 'MEWS评分',
  `mews_level` tinyint DEFAULT NULL COMMENT 'MEWS预警:0绿 1黄 2橙 3红',
  `stool_count` int DEFAULT NULL COMMENT '大便次数',
  `urine_ml` int DEFAULT NULL COMMENT '尿量(ml)',
  `drain_ml` int DEFAULT NULL COMMENT '引流量(ml)',
  `note` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_vital_visit` (`inp_visit_id`),
  KEY `idx_vital_time` (`record_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='护理生命体征记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_order`
--

DROP TABLE IF EXISTS `his_order`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '单据ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `visit_id` bigint NOT NULL COMMENT '就诊ID',
  `order_no` varchar(30) NOT NULL COMMENT '单据号',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名',
  `dept_id` bigint DEFAULT NULL COMMENT '开单科室ID',
  `dept_name` varchar(100) DEFAULT NULL COMMENT '开单科室名称',
  `dr_id` bigint DEFAULT NULL COMMENT '医师ID',
  `dr_name` varchar(50) DEFAULT NULL COMMENT '医师姓名',
  `order_type` varchar(20) DEFAULT '检查' COMMENT '单据类型:检查/检验/治疗',
  `diag_name` varchar(500) DEFAULT NULL COMMENT '临床诊断',
  `total_amount` decimal(12,2) DEFAULT '0.00' COMMENT '单据金额',
  `status` tinyint DEFAULT '1' COMMENT '状态:1-已开 2-已执行 3-已退',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `exec_status` tinyint DEFAULT '0' COMMENT '执行状态:0未执行 1已执行',
  `exec_dept_id` bigint DEFAULT NULL COMMENT '执行科室ID(his_dept.id)',
  `paid_flag` tinyint DEFAULT '0' COMMENT '收费标志:0未收费 1已收费',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_visit` (`visit_id`),
  KEY `idx_order_no` (`order_no`)
) ENGINE=InnoDB AUTO_INCREMENT=52 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='检查检验治疗单主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_order_freq`
--

DROP TABLE IF EXISTS `his_order_freq`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_order_freq` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `staff_id` bigint DEFAULT NULL COMMENT '医师ID(个人常用维度)',
  `dept_id` bigint DEFAULT NULL COMMENT '科室ID(科室高频维度)',
  `item_kind` varchar(20) NOT NULL COMMENT '项目类型: rx处方项/order医嘱项',
  `item_code` varchar(50) NOT NULL COMMENT '项目代码',
  `item_name` varchar(200) DEFAULT NULL COMMENT '项目名称',
  `use_count` int NOT NULL DEFAULT '0' COMMENT '累计使用次数',
  `last_time` datetime DEFAULT NULL COMMENT '最近使用时间',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_staff_dept_item` (`tenant_id`,`staff_id`,`dept_id`,`item_kind`,`item_code`),
  KEY `idx_dept` (`tenant_id`,`dept_id`,`use_count`),
  KEY `idx_staff` (`tenant_id`,`staff_id`,`use_count`)
) ENGINE=InnoDB AUTO_INCREMENT=31 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医嘱/处方高频使用沉淀(助手数据源)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_order_item`
--

DROP TABLE IF EXISTS `his_order_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '明细ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `order_id` bigint NOT NULL COMMENT '单据ID',
  `item_id` bigint DEFAULT NULL COMMENT '收费项目ID(his_charge_item)',
  `item_code` varchar(40) DEFAULT NULL COMMENT '院内项目编码',
  `item_name` varchar(200) DEFAULT NULL COMMENT '项目名称',
  `spec` varchar(200) DEFAULT NULL COMMENT '规格',
  `unit` varchar(30) DEFAULT NULL COMMENT '单位',
  `price` decimal(12,4) DEFAULT '0.0000' COMMENT '单价',
  `quantity` decimal(12,2) DEFAULT '0.00' COMMENT '数量',
  `amount` decimal(12,2) DEFAULT '0.00' COMMENT '金额',
  `med_list_codg` varchar(50) DEFAULT NULL COMMENT '医保目录编码',
  `exec_dept` varchar(100) DEFAULT NULL COMMENT '执行科室',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB AUTO_INCREMENT=83 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='检查检验治疗单明细表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_order_template`
--

DROP TABLE IF EXISTS `his_order_template`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_order_template` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `template_name` varchar(100) NOT NULL COMMENT '模板名称',
  `template_type` int DEFAULT '1' COMMENT '模板级别:1个人 2科室 3全院',
  `scope_type` int DEFAULT '1' COMMENT '范围类型:1单条 2套餐',
  `dept_id` bigint DEFAULT NULL COMMENT '科室ID(his_dept.id, 科室级模板)',
  `doctor_id` bigint DEFAULT NULL COMMENT '医生ID(his_staff.id, 个人级模板)',
  `items` text COMMENT '医嘱项JSON数组',
  `disease_code` varchar(30) DEFAULT NULL COMMENT '适用病种编码',
  `usage_count` int DEFAULT '0' COMMENT '使用次数',
  `status` int DEFAULT '1' COMMENT '状态:1启用 0停用',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `apply_scene` tinyint DEFAULT '1' COMMENT '适用场景:1普通住院 2手术医嘱(P2)',
  `surgery_phase` tinyint DEFAULT NULL COMMENT '手术模板目标阶段:1术前 2术中 3术后(apply_scene=2 时有值)',
  PRIMARY KEY (`id`),
  KEY `idx_order_tpl_type` (`template_type`,`scope_type`)
) ENGINE=InnoDB AUTO_INCREMENT=2105816015911325698 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院医嘱模板/套餐';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_org_catalog`
--

DROP TABLE IF EXISTS `his_org_catalog`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_org_catalog` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户(医共体)ID',
  `org_id` bigint NOT NULL COMMENT '机构ID(sys_org)',
  `org_name` varchar(200) DEFAULT NULL COMMENT '机构名称(冗余)',
  `catalog_type` varchar(20) NOT NULL COMMENT '目录类型:charge/drug/cons',
  `catalog_id` bigint NOT NULL COMMENT '医共体目录记录ID',
  `catalog_name` varchar(300) DEFAULT NULL COMMENT '目录名称(冗余)',
  `enabled` tinyint NOT NULL DEFAULT '1' COMMENT '是否开展:1启用 0停用',
  `eff_date` date DEFAULT NULL COMMENT '开展生效日期',
  `memo` varchar(200) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_org_catalog` (`org_id`,`catalog_type`,`catalog_id`),
  KEY `idx_oc_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1676 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='机构开展目录(从医共体目录勾选, 价格不可改)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_outp_agent`
--

DROP TABLE IF EXISTS `his_outp_agent`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_outp_agent` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `visit_id` bigint NOT NULL COMMENT '门诊就诊ID(his_visit.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名',
  `agent_name` varchar(50) NOT NULL COMMENT '代办人姓名',
  `agent_id_card` varchar(30) DEFAULT NULL COMMENT '代办人身份证号',
  `agent_phone` varchar(20) DEFAULT NULL COMMENT '代办人联系电话',
  `relation` varchar(20) NOT NULL COMMENT '与患者关系',
  `reason` varchar(200) DEFAULT NULL COMMENT '代办事由',
  `status` int DEFAULT '1' COMMENT '状态:1有效 0作废',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_visit` (`visit_id`),
  KEY `idx_patient` (`tenant_id`,`patient_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='门诊代办登记';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_pathway_exec`
--

DROP TABLE IF EXISTS `his_pathway_exec`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_pathway_exec` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `instance_id` bigint NOT NULL COMMENT '路径实例ID(his_pathway_instance.id)',
  `task_id` bigint DEFAULT NULL COMMENT '任务ID(his_pathway_task.id)',
  `node_id` bigint DEFAULT NULL COMMENT '节点ID(his_pathway_node.id)',
  `day_no` int DEFAULT NULL COMMENT '第X天',
  `exec_date` date DEFAULT NULL COMMENT '执行日期',
  `exec_status` tinyint DEFAULT '1' COMMENT '执行状态:1待执行 2已执行 3跳过 4变异',
  `order_id` bigint DEFAULT NULL COMMENT '关联医嘱ID(his_inp_order.id)',
  `variance_reason` varchar(500) DEFAULT NULL COMMENT '变异原因',
  `operator_id` bigint DEFAULT NULL COMMENT '操作员ID(his_staff.id)',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `variance_type` varchar(10) DEFAULT NULL COMMENT '变异原因分类码(cv_code:pathway_var_reason)',
  `variance_type_name` varchar(100) DEFAULT NULL COMMENT '变异原因分类名称(字典回填)',
  PRIMARY KEY (`id`),
  KEY `idx_instance` (`instance_id`,`exec_status`),
  KEY `idx_task` (`task_id`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='临床路径执行记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_pathway_instance`
--

DROP TABLE IF EXISTS `his_pathway_instance`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_pathway_instance` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `template_id` bigint NOT NULL COMMENT '模板ID(his_pathway_template.id)',
  `start_date` datetime DEFAULT NULL COMMENT '启动日期',
  `current_day` int DEFAULT '1' COMMENT '当前天数',
  `end_date` datetime DEFAULT NULL COMMENT '结束日期',
  `status` tinyint DEFAULT '1' COMMENT '状态:1进行中 2已完成 3已退出 4暂停',
  `exit_reason` varchar(500) DEFAULT NULL COMMENT '退出原因',
  `doctor_id` bigint DEFAULT NULL COMMENT '主治医生ID(his_staff.id)',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `exit_type` varchar(10) DEFAULT NULL COMMENT '退出原因分类码(cv_code:pathway_exit_reason)',
  `exit_type_name` varchar(100) DEFAULT NULL COMMENT '退出原因分类名称(字典回填)',
  PRIMARY KEY (`id`),
  KEY `idx_visit` (`inp_visit_id`,`status`),
  KEY `idx_template` (`template_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='患者临床路径实例';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_pathway_node`
--

DROP TABLE IF EXISTS `his_pathway_node`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_pathway_node` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `template_id` bigint NOT NULL COMMENT '模板ID(his_pathway_template.id)',
  `day_no` int NOT NULL COMMENT '第X天',
  `node_name` varchar(200) DEFAULT NULL COMMENT '节点名称',
  `node_desc` varchar(500) DEFAULT NULL COMMENT '节点描述',
  `sort_no` int DEFAULT '0' COMMENT '排序号',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_template_day` (`template_id`,`day_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='临床路径节点';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_pathway_task`
--

DROP TABLE IF EXISTS `his_pathway_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_pathway_task` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `node_id` bigint NOT NULL COMMENT '节点ID(his_pathway_node.id)',
  `template_id` bigint NOT NULL COMMENT '模板ID(his_pathway_template.id)',
  `task_type` tinyint DEFAULT '1' COMMENT '任务类型:1医嘱 2护理 3检查 4检验 5宣教',
  `charge_item_id` bigint DEFAULT NULL COMMENT '收费项目ID',
  `drug_id` bigint DEFAULT NULL COMMENT '药品ID(药品目录)',
  `order_type` tinyint DEFAULT NULL COMMENT '医嘱类型:1长期 2临时',
  `order_category` tinyint DEFAULT NULL COMMENT '医嘱分类:1药品 2检查 3检验 4治疗 5护理 6膳食 7其他',
  `order_content` varchar(500) DEFAULT NULL COMMENT '医嘱内容',
  `spec` varchar(100) DEFAULT NULL COMMENT '规格',
  `dosage` varchar(50) DEFAULT NULL COMMENT '剂量',
  `dosage_unit` varchar(20) DEFAULT NULL COMMENT '剂量单位',
  `usage_code` varchar(20) DEFAULT NULL COMMENT '用法编码',
  `freq_code` varchar(20) DEFAULT NULL COMMENT '频次编码',
  `quantity` decimal(10,2) DEFAULT NULL COMMENT '数量',
  `unit_price` decimal(10,2) DEFAULT NULL COMMENT '单价',
  `is_mandatory` tinyint DEFAULT '1' COMMENT '是否必做:1必做 0可选',
  `sort_no` int DEFAULT '0' COMMENT '排序号',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_node` (`node_id`),
  KEY `idx_template` (`template_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='临床路径节点任务(医嘱模板)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_pathway_template`
--

DROP TABLE IF EXISTS `his_pathway_template`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_pathway_template` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `pathway_code` varchar(50) NOT NULL COMMENT '路径编码',
  `pathway_name` varchar(200) NOT NULL COMMENT '路径名称',
  `disease_code` varchar(30) DEFAULT NULL COMMENT '适用诊断编码ICD-10',
  `disease_name` varchar(200) DEFAULT NULL COMMENT '适用诊断名称',
  `dept_id` bigint DEFAULT NULL COMMENT '适用科室ID(his_dept.id)',
  `avg_length` int DEFAULT NULL COMMENT '平均住院日',
  `total_cost` decimal(12,2) DEFAULT NULL COMMENT '预估总费用',
  `version` int DEFAULT '1' COMMENT '版本号',
  `status` tinyint DEFAULT '1' COMMENT '状态:1启用 0停用',
  `description` varchar(1000) DEFAULT NULL COMMENT '路径描述',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`),
  KEY `idx_disease` (`disease_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='临床路径模板';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_patient`
--

DROP TABLE IF EXISTS `his_patient`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_patient` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '患者ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `patient_no` varchar(30) NOT NULL COMMENT '院内患者号(就诊卡号)',
  `psn_no` varchar(30) DEFAULT NULL COMMENT '医保人员编号',
  `name` varchar(50) NOT NULL COMMENT '姓名',
  `gender` varchar(4) DEFAULT NULL COMMENT '性别:男/女',
  `birth_date` datetime DEFAULT NULL COMMENT '出生日期时间(精确到秒)',
  `age` int DEFAULT NULL COMMENT '年龄',
  `id_card` varchar(30) DEFAULT NULL COMMENT '身份证号',
  `phone` varchar(30) DEFAULT NULL COMMENT '联系电话',
  `address` varchar(200) DEFAULT NULL COMMENT '住址',
  `insutype` varchar(10) DEFAULT '310' COMMENT '险种类型:310-职工 390-居民',
  `mdtrt_cert_type` varchar(10) DEFAULT '02' COMMENT '就诊凭证类型:01-电子凭证 02-身份证 03-社保卡',
  `mdtrt_cert_no` varchar(50) DEFAULT NULL COMMENT '就诊凭证编号',
  `insuplc_admdvs` varchar(20) DEFAULT NULL COMMENT '参保地区划',
  `contact_name` varchar(50) DEFAULT NULL COMMENT '联系人',
  `contact_phone` varchar(30) DEFAULT NULL COMMENT '联系人电话',
  `status` tinyint DEFAULT '1' COMMENT '状态:1-正常 0-停用',
  `memo` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `org_id` bigint DEFAULT NULL COMMENT '建档/首诊机构ID',
  `gender_name` varchar(50) DEFAULT NULL COMMENT '性别名称(字典回填)',
  `gender_src` varchar(50) DEFAULT NULL COMMENT '性别字典来源标识',
  `insutype_name` varchar(100) DEFAULT NULL COMMENT '险种名称(字典回填)',
  `insutype_src` varchar(50) DEFAULT NULL COMMENT '险种字典来源标识',
  `mdtrt_cert_type_name` varchar(100) DEFAULT NULL COMMENT '就诊凭证类型名称(字典回填)',
  `mdtrt_cert_type_src` varchar(50) DEFAULT NULL COMMENT '就诊凭证类型字典来源标识',
  `insuplc_admdvs_name` varchar(100) DEFAULT NULL COMMENT '参保地区划名称(字典回填)',
  `insuplc_admdvs_src` varchar(50) DEFAULT NULL COMMENT '参保地区划来源标识',
  `cert_type` varchar(10) DEFAULT NULL COMMENT '身份证件类别编码(cv_code:psn_cert_type)',
  `cert_type_name` varchar(100) DEFAULT NULL COMMENT '身份证件类别名称(字典回填)',
  `cert_type_src` varchar(50) DEFAULT NULL COMMENT '身份证件类别来源标识',
  `nation` varchar(10) DEFAULT NULL COMMENT '民族编码(cv_code:naty)',
  `nation_name` varchar(50) DEFAULT NULL COMMENT '民族名称(字典回填)',
  `nation_src` varchar(50) DEFAULT NULL COMMENT '民族来源标识',
  `nationality` varchar(20) DEFAULT NULL COMMENT '国籍编码(hbvalue:GB/T 2659.1-2022)',
  `nationality_name` varchar(100) DEFAULT NULL COMMENT '国籍名称(字典回填)',
  `nationality_src` varchar(50) DEFAULT NULL COMMENT '国籍来源标识',
  `marital_status` varchar(10) DEFAULT NULL COMMENT '婚姻状况编码(hbvalue:GB/T 2261.2-2003)',
  `marital_status_name` varchar(50) DEFAULT NULL COMMENT '婚姻状况名称(字典回填)',
  `marital_status_src` varchar(50) DEFAULT NULL COMMENT '婚姻状况来源标识',
  `edu_level` varchar(20) DEFAULT NULL COMMENT '文化程度编码(hbvalue:GB/T 4658-2006)',
  `edu_level_name` varchar(50) DEFAULT NULL COMMENT '文化程度名称(字典回填)',
  `edu_level_src` varchar(50) DEFAULT NULL COMMENT '文化程度来源标识',
  `occupation` varchar(20) DEFAULT NULL COMMENT '职业类别编码(hbvalue:CV02.01.202)',
  `occupation_name` varchar(50) DEFAULT NULL COMMENT '职业类别名称(字典回填)',
  `occupation_src` varchar(50) DEFAULT NULL COMMENT '职业类别来源标识',
  `occupation_other` varchar(100) DEFAULT NULL COMMENT '职业类别其他(ZYLBQT)',
  `present_prov` varchar(20) DEFAULT NULL COMMENT '现住址-省编码(area_code_2021)',
  `present_prov_name` varchar(100) DEFAULT NULL COMMENT '现住址-省名称(字典回填)',
  `present_city` varchar(20) DEFAULT NULL COMMENT '现住址-市编码(area_code_2021)',
  `present_city_name` varchar(100) DEFAULT NULL COMMENT '现住址-市名称(字典回填)',
  `present_county` varchar(20) DEFAULT NULL COMMENT '现住址-区县编码(area_code_2021)',
  `present_county_name` varchar(100) DEFAULT NULL COMMENT '现住址-区县名称(字典回填)',
  `present_town` varchar(20) DEFAULT NULL COMMENT '现住址-乡镇/街道编码(area_code_2021)',
  `present_town_name` varchar(100) DEFAULT NULL COMMENT '现住址-乡镇/街道名称(字典回填)',
  `present_src` varchar(50) DEFAULT NULL COMMENT '现住址来源标识(area_code_2021)',
  `present_detail` varchar(255) DEFAULT NULL COMMENT '现住址-详细地址(村/街/路/门牌)',
  `household_addr` varchar(255) DEFAULT NULL COMMENT '户籍地址(文本)',
  `employer` varchar(200) DEFAULT NULL COMMENT '工作单位名称',
  `employer_phone` varchar(30) DEFAULT NULL COMMENT '工作单位电话',
  `employer_addr` varchar(255) DEFAULT NULL COMMENT '工作单位地址(文本)',
  `contact_relation` varchar(20) DEFAULT NULL COMMENT '联系人与患者关系编码(hbvalue:GB/T 4761-2008)',
  `contact_relation_name` varchar(50) DEFAULT NULL COMMENT '联系人与患者关系名称(字典回填)',
  `contact_relation_src` varchar(50) DEFAULT NULL COMMENT '联系人与患者关系来源标识',
  `contact_id_card` varchar(30) DEFAULT NULL COMMENT '联系人身份证件号码',
  `contact_addr` varchar(255) DEFAULT NULL COMMENT '联系人地址(文本)',
  `birth_prov` varchar(20) DEFAULT NULL COMMENT '出生地-省编码(area_code_2021)',
  `birth_prov_name` varchar(100) DEFAULT NULL COMMENT '出生地-省名称(字典回填)',
  `birth_city` varchar(20) DEFAULT NULL COMMENT '出生地-市编码(area_code_2021)',
  `birth_city_name` varchar(100) DEFAULT NULL COMMENT '出生地-市名称(字典回填)',
  `birth_county` varchar(20) DEFAULT NULL COMMENT '出生地-区县编码(area_code_2021)',
  `birth_county_name` varchar(100) DEFAULT NULL COMMENT '出生地-区县名称(字典回填)',
  `birth_town` varchar(20) DEFAULT NULL COMMENT '出生地-乡镇/街道编码(area_code_2021)',
  `birth_town_name` varchar(100) DEFAULT NULL COMMENT '出生地-乡镇/街道名称(字典回填)',
  `birth_src` varchar(50) DEFAULT NULL COMMENT '出生地来源标识(area_code_2021)',
  `birth_detail` varchar(255) DEFAULT NULL COMMENT '出生地-详细地址',
  `household_prov` varchar(20) DEFAULT NULL COMMENT '户籍地址-省编码(area_code_2021)',
  `household_prov_name` varchar(100) DEFAULT NULL COMMENT '户籍地址-省名称(字典回填)',
  `household_city` varchar(20) DEFAULT NULL COMMENT '户籍地址-市编码(area_code_2021)',
  `household_city_name` varchar(100) DEFAULT NULL COMMENT '户籍地址-市名称(字典回填)',
  `household_county` varchar(20) DEFAULT NULL COMMENT '户籍地址-区县编码(area_code_2021)',
  `household_county_name` varchar(100) DEFAULT NULL COMMENT '户籍地址-区县名称(字典回填)',
  `household_town` varchar(20) DEFAULT NULL COMMENT '户籍地址-乡镇/街道编码(area_code_2021)',
  `household_town_name` varchar(100) DEFAULT NULL COMMENT '户籍地址-乡镇/街道名称(字典回填)',
  `household_src` varchar(50) DEFAULT NULL COMMENT '户籍地址来源标识(area_code_2021)',
  `mail_prov` varchar(20) DEFAULT NULL COMMENT '通讯地址-省编码(area_code_2021)',
  `mail_prov_name` varchar(100) DEFAULT NULL COMMENT '通讯地址-省名称(字典回填)',
  `mail_city` varchar(20) DEFAULT NULL COMMENT '通讯地址-市编码(area_code_2021)',
  `mail_city_name` varchar(100) DEFAULT NULL COMMENT '通讯地址-市名称(字典回填)',
  `mail_county` varchar(20) DEFAULT NULL COMMENT '通讯地址-区县编码(area_code_2021)',
  `mail_county_name` varchar(100) DEFAULT NULL COMMENT '通讯地址-区县名称(字典回填)',
  `mail_town` varchar(20) DEFAULT NULL COMMENT '通讯地址-乡镇/街道编码(area_code_2021)',
  `mail_town_name` varchar(100) DEFAULT NULL COMMENT '通讯地址-乡镇/街道名称(字典回填)',
  `mail_src` varchar(50) DEFAULT NULL COMMENT '通讯地址来源标识(area_code_2021)',
  `emp_prov` varchar(20) DEFAULT NULL COMMENT '单位地址-省编码(area_code_2021)',
  `emp_prov_name` varchar(100) DEFAULT NULL COMMENT '单位地址-省名称(字典回填)',
  `emp_city` varchar(20) DEFAULT NULL COMMENT '单位地址-市编码(area_code_2021)',
  `emp_city_name` varchar(100) DEFAULT NULL COMMENT '单位地址-市名称(字典回填)',
  `emp_county` varchar(20) DEFAULT NULL COMMENT '单位地址-区县编码(area_code_2021)',
  `emp_county_name` varchar(100) DEFAULT NULL COMMENT '单位地址-区县名称(字典回填)',
  `emp_town` varchar(20) DEFAULT NULL COMMENT '单位地址-乡镇/街道编码(area_code_2021)',
  `emp_town_name` varchar(100) DEFAULT NULL COMMENT '单位地址-乡镇/街道名称(字典回填)',
  `emp_src` varchar(50) DEFAULT NULL COMMENT '单位地址来源标识(area_code_2021)',
  `contact_prov` varchar(20) DEFAULT NULL COMMENT '联系人地址-省编码(area_code_2021)',
  `contact_prov_name` varchar(100) DEFAULT NULL COMMENT '联系人地址-省名称(字典回填)',
  `contact_city` varchar(20) DEFAULT NULL COMMENT '联系人地址-市编码(area_code_2021)',
  `contact_city_name` varchar(100) DEFAULT NULL COMMENT '联系人地址-市名称(字典回填)',
  `contact_county` varchar(20) DEFAULT NULL COMMENT '联系人地址-区县编码(area_code_2021)',
  `contact_county_name` varchar(100) DEFAULT NULL COMMENT '联系人地址-区县名称(字典回填)',
  `contact_town` varchar(20) DEFAULT NULL COMMENT '联系人地址-乡镇/街道编码(area_code_2021)',
  `contact_town_name` varchar(100) DEFAULT NULL COMMENT '联系人地址-乡镇/街道名称(字典回填)',
  `contact_src` varchar(50) DEFAULT NULL COMMENT '联系人地址来源标识(area_code_2021)',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(患者姓名首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_tenant_patient_no` (`tenant_id`,`patient_no`),
  KEY `idx_id_card` (`id_card`),
  KEY `idx_psn_no` (`psn_no`)
) ENGINE=InnoDB AUTO_INCREMENT=10629 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='患者档案表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_patient_allergy`
--

DROP TABLE IF EXISTS `his_patient_allergy`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_patient_allergy` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `patient_id` bigint NOT NULL COMMENT '患者ID(his_patient.id)',
  `allergen_type` varchar(20) DEFAULT NULL COMMENT '过敏原类型:drug药品/food食物/other其他',
  `allergen_name` varchar(100) NOT NULL COMMENT '过敏原名称',
  `allergen_code` varchar(50) DEFAULT NULL COMMENT '过敏原编码(药品为目录编码)',
  `severity` varchar(20) DEFAULT NULL COMMENT '严重程度:mild轻度/moderate中度/severe重度',
  `source` varchar(20) DEFAULT NULL COMMENT '来源:manual手工/skin_test皮试/doctor医生站',
  `source_id` bigint DEFAULT NULL COMMENT '来源记录ID(如his_skin_test.id)',
  `record_time` datetime DEFAULT NULL COMMENT '登记时间',
  `record_by` bigint DEFAULT NULL COMMENT '登记人ID(his_staff.id)',
  `is_active` tinyint DEFAULT '1' COMMENT '是否有效:1有效 0已失效',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_patient` (`tenant_id`,`patient_id`,`is_active`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='患者过敏记录(多来源汇聚)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_patient_change_log`
--

DROP TABLE IF EXISTS `his_patient_change_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_patient_change_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `patient_id` bigint NOT NULL COMMENT '患者ID',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名(冗余)',
  `batch_no` varchar(40) DEFAULT NULL COMMENT '同一次保存批次号',
  `field_name` varchar(60) DEFAULT NULL COMMENT '变更字段属性名',
  `field_label` varchar(60) DEFAULT NULL COMMENT '变更字段中文名',
  `old_value` varchar(500) DEFAULT NULL COMMENT '修改前',
  `new_value` varchar(500) DEFAULT NULL COMMENT '修改后',
  `source` varchar(20) DEFAULT NULL COMMENT '变更来源:建档/手动修改/医保读卡',
  `change_by_name` varchar(50) DEFAULT NULL COMMENT '修改人姓名',
  `memo` varchar(200) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_patient` (`patient_id`),
  KEY `idx_batch` (`batch_no`)
) ENGINE=InnoDB AUTO_INCREMENT=41 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='患者档案修改记录表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_patient_insu`
--

DROP TABLE IF EXISTS `his_patient_insu`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_patient_insu` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `patient_id` bigint NOT NULL COMMENT '患者ID(his_patient.id)',
  `psn_no` varchar(30) DEFAULT NULL COMMENT '人员编号(1101 baseinfo.psn_no)',
  `balc` decimal(16,2) DEFAULT NULL COMMENT '余额(1101 insuinfo.balc)',
  `insutype` varchar(6) DEFAULT NULL COMMENT '险种类型编码(1101 insuinfo.insutype, cv_code:insutype)',
  `insutype_name` varchar(100) DEFAULT NULL COMMENT '险种名称(字典回填)',
  `insutype_src` varchar(50) DEFAULT NULL COMMENT '险种来源标识',
  `psn_type` varchar(6) DEFAULT NULL COMMENT '人员类别编码(1101 insuinfo.psn_type, cv_code:psn_type)',
  `psn_type_name` varchar(100) DEFAULT NULL COMMENT '人员类别名称(字典回填)',
  `psn_type_src` varchar(50) DEFAULT NULL COMMENT '人员类别来源标识',
  `psn_insu_stas` varchar(6) DEFAULT NULL COMMENT '人员参保状态编码(1101 insuinfo.psn_insu_stas, cv_code:psn_insu_stas)',
  `psn_insu_stas_name` varchar(100) DEFAULT NULL COMMENT '人员参保状态名称(字典回填)',
  `psn_insu_stas_src` varchar(50) DEFAULT NULL COMMENT '人员参保状态来源标识',
  `psn_insu_date` date DEFAULT NULL COMMENT '个人参保日期(1101 insuinfo.psn_insu_date)',
  `paus_insu_date` date DEFAULT NULL COMMENT '暂停参保日期(1101 insuinfo.paus_insu_date, null=当前在保)',
  `cvlserv_flag` varchar(3) DEFAULT NULL COMMENT '公务员标志编码(1101 insuinfo.cvlserv_flag, cv_code:cvlserv_flag)',
  `cvlserv_flag_name` varchar(100) DEFAULT NULL COMMENT '公务员标志名称(字典回填)',
  `cvlserv_flag_src` varchar(50) DEFAULT NULL COMMENT '公务员标志来源标识',
  `insuplc_admdvs` varchar(20) DEFAULT NULL COMMENT '参保地医保区划编码(1101 insuinfo.insuplc_admdvs, area_code_2021)',
  `insuplc_admdvs_name` varchar(100) DEFAULT NULL COMMENT '参保地医保区划名称(字典回填)',
  `insuplc_admdvs_src` varchar(50) DEFAULT NULL COMMENT '参保地医保区划来源标识',
  `emp_name` varchar(200) DEFAULT NULL COMMENT '单位名称(1101 insuinfo.emp_name, 参保单位)',
  `src` varchar(30) DEFAULT NULL COMMENT '记录来源: 医保读卡(1101)/手工',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_patient` (`patient_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='患者医保参保信息表(【1101】输出节点insuinfo完整记录, 一人可多条)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_pay_method_dict`
--

DROP TABLE IF EXISTS `his_pay_method_dict`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_pay_method_dict` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户(医共体)ID',
  `org_id` bigint NOT NULL COMMENT '机构ID(机构级自定义, sys_org.id)',
  `code` varchar(20) NOT NULL COMMENT '支付方式规范码(机构内唯一, 大写: CASH/WECHAT/...; 新数据统一落此码)',
  `name` varchar(100) NOT NULL COMMENT '名称(现金/微信/院内预交金/职工签账...)',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  `scope` varchar(20) NOT NULL DEFAULT 'BOTH' COMMENT '适用场景:OTP门诊 IPT住院 BOTH通用(多选逗号分隔)',
  `legacy_codes` varchar(60) DEFAULT NULL COMMENT '历史旧值映射(逗号分隔, 如 cash,1; 供存量数据标签回显与录入归一)',
  `pay_kind` varchar(20) DEFAULT NULL COMMENT '分类:CASH现金 ELECTRONIC电子 CREDIT记账 FREE减免 DEPOSIT预交金 INSURANCE医保',
  `change_flag` tinyint NOT NULL DEFAULT '0' COMMENT '需找零:1是(现金/POS)',
  `deposit_flag` tinyint NOT NULL DEFAULT '0' COMMENT '可充住院预交金:1是(scope含IPT才有意义)',
  `dayend_flag` tinyint NOT NULL DEFAULT '1' COMMENT '纳入日结:1是',
  `refund_way` varchar(20) DEFAULT NULL COMMENT '退费方式:ORIGIN原路退回 CASH现金退 ACCOUNT退预交金',
  `auto_flag` tinyint NOT NULL DEFAULT '0' COMMENT '系统内置:1不可删/编码锁定 0自定义',
  `sort_no` int DEFAULT '0' COMMENT '排序号',
  `status` tinyint DEFAULT '1' COMMENT '状态:1启用 0停用',
  `memo` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_method_org_code` (`tenant_id`,`org_id`,`code`),
  KEY `idx_pmd_scope` (`scope`,`status`,`deleted`),
  KEY `idx_pmd_org` (`org_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3070 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付方式字典(机构级自定义, 门诊住院统一, 混合支付/预交金取数源)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_payment_detail`
--

DROP TABLE IF EXISTS `his_payment_detail`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_payment_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `bill_id` bigint NOT NULL COMMENT '收费单ID(his_charge_bill.id)',
  `pay_method` varchar(20) NOT NULL COMMENT '支付方式:CASH/WECHAT/ALIPAY/CARD/INSURANCE/FREE',
  `amount` decimal(12,2) NOT NULL COMMENT '支付金额',
  `pay_ref` varchar(100) DEFAULT NULL COMMENT '支付流水号',
  `pay_time` datetime DEFAULT NULL COMMENT '支付时间',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_bill` (`bill_id`)
) ENGINE=InnoDB AUTO_INCREMENT=12313 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付明细(收费单混合支付)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_pharmacy_cross_config`
--

DROP TABLE IF EXISTS `his_pharmacy_cross_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_pharmacy_cross_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `source_pharmacy_id` bigint NOT NULL COMMENT '源药房ID(his_pharmacy_def.id)',
  `target_pharmacy_id` bigint NOT NULL COMMENT '目标药房ID(his_pharmacy_def.id)',
  `allow_cross_status` tinyint DEFAULT '0' COMMENT '允许跨状态发药:1是 0否',
  `enabled` tinyint DEFAULT '1' COMMENT '启用:1是 0否',
  `remark` varchar(200) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_source_target` (`tenant_id`,`source_pharmacy_id`,`target_pharmacy_id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_source` (`tenant_id`,`source_pharmacy_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='跨药房发药配置';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_pharmacy_def`
--

DROP TABLE IF EXISTS `his_pharmacy_def`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_pharmacy_def` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `code` varchar(40) NOT NULL COMMENT '药房编码',
  `name` varchar(100) NOT NULL COMMENT '药房名称(门诊药房/住院药房/中药房)',
  `pharmacy_type` varchar(20) DEFAULT NULL COMMENT '药房类型:OUTPATIENT/INPATIENT/TCM',
  `warehouse_id` bigint DEFAULT NULL COMMENT '关联药库ID(his_warehouse_def.id)',
  `location` varchar(200) DEFAULT NULL COMMENT '药房位置',
  `status` tinyint DEFAULT '1' COMMENT '状态:1启用 0停用',
  `sort_no` int DEFAULT '0' COMMENT '排序号',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `stock_location_id` bigint DEFAULT NULL COMMENT '本药房库存位ID(PHARMACY型 his_warehouse_def.id)',
  `dept_id` bigint DEFAULT NULL COMMENT '归属科室(his_dept.id, 一一对应; 空=历史未绑定)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_org_code` (`tenant_id`,`org_id`,`code`),
  UNIQUE KEY `uk_dept` (`tenant_id`,`dept_id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org` (`tenant_id`,`org_id`)
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='药房定义(机构级多药房)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_pharmacy_drug_price`
--

DROP TABLE IF EXISTS `his_pharmacy_drug_price`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_pharmacy_drug_price` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `pharmacy_id` bigint NOT NULL COMMENT '药房ID(his_pharmacy_def.id)',
  `drug_catalog_id` bigint NOT NULL COMMENT '医共体药品目录ID(his_drug_catalog.id)',
  `retail_price` decimal(12,6) NOT NULL COMMENT '药房零售价(最小单位, 覆盖目录价)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_org_ph_drug` (`tenant_id`,`org_id`,`pharmacy_id`,`drug_catalog_id`),
  KEY `idx_pharmacy` (`tenant_id`,`pharmacy_id`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='药房药品定价(药房维度覆盖价)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_pharmacy_window`
--

DROP TABLE IF EXISTS `his_pharmacy_window`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_pharmacy_window` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `pharmacy_id` bigint NOT NULL COMMENT '所属药房ID(his_pharmacy_def.id)',
  `code` varchar(40) NOT NULL COMMENT '窗口编码',
  `name` varchar(100) NOT NULL COMMENT '窗口名称',
  `window_type` varchar(20) DEFAULT NULL COMMENT '窗口类型:WEST西药/CHINESE_PATENT中成药/HERB草药/NARCOTIC精麻/TOXIC毒性/DECOCT代煎/EXPRESS快递',
  `assign_strategy` tinyint DEFAULT '1' COMMENT '分配策略:1剩余量最小 2平均轮询 3定向',
  `is_default` tinyint DEFAULT '0' COMMENT '兜底默认窗口:1是 0否',
  `open_status` tinyint DEFAULT '1' COMMENT '开窗状态:1开 0关',
  `signin_required` tinyint DEFAULT '0' COMMENT '发药前需患者签到:1是 0否',
  `trace_required` tinyint DEFAULT '0' COMMENT '窗口级追溯码强制:1是 0否',
  `sort_no` int DEFAULT '0' COMMENT '排序号',
  `status` tinyint DEFAULT '1' COMMENT '状态:1启用 0停用',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_pharmacy_code` (`tenant_id`,`pharmacy_id`,`code`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_pharmacy_open` (`tenant_id`,`pharmacy_id`,`open_status`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=24 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发药窗口定义';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_pre_consult`
--

DROP TABLE IF EXISTS `his_pre_consult`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_pre_consult` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `visit_id` bigint DEFAULT NULL COMMENT '就诊ID(his_visit.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `content_json` text COMMENT '预问诊内容JSON(症状/部位/时长/自述)',
  `recorder` varchar(50) DEFAULT NULL COMMENT '录入人',
  `recorder_id` bigint DEFAULT NULL COMMENT '录入人ID',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_visit` (`tenant_id`,`visit_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='诊前预问诊记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_prescription`
--

DROP TABLE IF EXISTS `his_prescription`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_prescription` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '处方ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `visit_id` bigint NOT NULL COMMENT '就诊ID',
  `rx_no` varchar(30) NOT NULL COMMENT '处方号',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名',
  `dept_id` bigint DEFAULT NULL COMMENT '科室ID',
  `dept_name` varchar(100) DEFAULT NULL COMMENT '科室名称',
  `dr_id` bigint DEFAULT NULL COMMENT '医师ID',
  `dr_name` varchar(50) DEFAULT NULL COMMENT '医师姓名',
  `rx_type` varchar(20) DEFAULT '西药' COMMENT '处方类型:西药/中药',
  `diag_name` varchar(500) DEFAULT NULL COMMENT '临床诊断',
  `total_amount` decimal(12,2) DEFAULT '0.00' COMMENT '处方金额',
  `status` tinyint DEFAULT '1' COMMENT '状态:1-已开 2-已发药 3-已退药',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `dispense_status` tinyint DEFAULT '0' COMMENT '发药状态:0未发药 1已发药 2已退药',
  `pharmacy_id` bigint DEFAULT NULL COMMENT '发药药房ID(his_pharmacy_def.id, 开方绑定/改派更新)',
  `transfer_from_pharmacy_id` bigint DEFAULT NULL COMMENT '改派来源药房ID(发药时随转至发药记录留痕)',
  `audit_status` tinyint DEFAULT '0' COMMENT '处方审核状态:0无需 1待审 2通过 3驳回(P2门诊药审)',
  `audit_src` varchar(10) DEFAULT NULL COMMENT '审核来源:manual人工/auto自动(P2)',
  `audit_by` varchar(50) DEFAULT NULL COMMENT '审核药师姓名(P2)',
  `audit_time` datetime DEFAULT NULL COMMENT '审核时间(P2)',
  `reject_reason` varchar(500) DEFAULT NULL COMMENT '审核驳回原因(P2)',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_visit` (`visit_id`),
  KEY `idx_rx_no` (`rx_no`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=12504 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='处方主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_prescription_item`
--

DROP TABLE IF EXISTS `his_prescription_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_prescription_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '明细ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `prescription_id` bigint NOT NULL COMMENT '处方ID',
  `item_id` bigint DEFAULT NULL COMMENT '收费项目ID(his_charge_item)',
  `item_code` varchar(40) DEFAULT NULL COMMENT '院内项目编码',
  `item_name` varchar(200) DEFAULT NULL COMMENT '项目名称',
  `spec` varchar(200) DEFAULT NULL COMMENT '规格',
  `unit` varchar(30) DEFAULT NULL COMMENT '单位',
  `price` decimal(12,4) DEFAULT '0.0000' COMMENT '单价',
  `quantity` decimal(12,2) DEFAULT '0.00' COMMENT '数量',
  `amount` decimal(12,2) DEFAULT '0.00' COMMENT '金额',
  `dosage` varchar(50) DEFAULT NULL COMMENT '单次剂量',
  `dosage_unit` varchar(20) DEFAULT NULL COMMENT '剂量单位',
  `usage_method` varchar(50) DEFAULT NULL COMMENT '用法',
  `frequency` varchar(50) DEFAULT NULL COMMENT '频次',
  `administration` varchar(50) DEFAULT NULL COMMENT '给药途径',
  `group_no` varchar(20) DEFAULT NULL COMMENT '用药组号',
  `days` int DEFAULT NULL COMMENT '用药天数',
  `med_list_codg` varchar(50) DEFAULT NULL COMMENT '医保目录编码',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `drug_id` bigint DEFAULT NULL COMMENT '医共体药品目录ID(his_drug_catalog.id)',
  `unit_dose` decimal(12,4) DEFAULT NULL COMMENT '每最小包装单位含药量(换算快照)',
  `pack_ratio` int DEFAULT NULL COMMENT '包装换算比(大包装→最小单位, 快照)',
  `round_rule` tinyint DEFAULT NULL COMMENT '发药取整规则快照:1向上 2向下 3四舍五入',
  `decoction` varchar(30) DEFAULT NULL COMMENT '煎法: 先煎/后煎/包煎/烊化等',
  `processing` varchar(30) DEFAULT NULL COMMENT '炮制: 炒/炙/煅/蒸等',
  `therapy` varchar(50) DEFAULT NULL COMMENT '治法: 汗/吐/下/和/温/清/消/补等',
  `herb_form` varchar(20) DEFAULT NULL COMMENT '药剂形式: 饮片/颗粒/成药/自备',
  `multiple_base` int DEFAULT '0' COMMENT '倍数基础量(0=不启用), 单味剂量须为其整数倍',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_prescription` (`prescription_id`)
) ENGINE=InnoDB AUTO_INCREMENT=12525 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='处方明细表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_price_adjust`
--

DROP TABLE IF EXISTS `his_price_adjust`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_price_adjust` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户(医共体)ID',
  `catalog_type` varchar(20) NOT NULL COMMENT '目录类型:charge/drug/cons',
  `catalog_id` bigint NOT NULL COMMENT '目录记录ID',
  `catalog_name` varchar(300) DEFAULT NULL COMMENT '目录名称(冗余)',
  `price_field` varchar(30) DEFAULT NULL COMMENT '调价字段:price_l1/l2/l3或purchase_price/retail_price/charge_price',
  `price_label` varchar(50) DEFAULT NULL COMMENT '调价字段中文名',
  `org_level` tinyint DEFAULT NULL COMMENT '收费项目价格档次:1/2/3(药耗为空)',
  `old_price` decimal(12,4) DEFAULT NULL COMMENT '原价',
  `new_price` decimal(12,4) DEFAULT NULL COMMENT '新价',
  `adjust_doc_no` varchar(100) DEFAULT NULL COMMENT '调价文号',
  `eff_date` date DEFAULT NULL COMMENT '生效日期',
  `reason` varchar(500) DEFAULT NULL COMMENT '调价原因',
  `operator_name` varchar(50) DEFAULT NULL COMMENT '操作人姓名',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_pa_tenant` (`tenant_id`),
  KEY `idx_pa_catalog` (`catalog_type`,`catalog_id`)
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医共体目录调价留痕表(调价文号+生效日期必录)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_print_template`
--

DROP TABLE IF EXISTS `his_print_template`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_print_template` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `template_code` varchar(50) NOT NULL COMMENT '模板编码',
  `template_name` varchar(100) NOT NULL COMMENT '模板名称',
  `template_type` int DEFAULT NULL COMMENT '模板类型:1日清单 2结算单 3医嘱单 4护理记录单 5体温单 6病历 7腕带 8知情同意书',
  `paper_size` varchar(20) DEFAULT 'A4' COMMENT '纸张尺寸',
  `orientation` varchar(20) DEFAULT 'portrait' COMMENT '方向:portrait纵向 landscape横向',
  `template_content` text COMMENT 'HTML模板内容',
  `header_html` text COMMENT '页眉HTML',
  `footer_html` text COMMENT '页脚HTML',
  `css_style` text COMMENT 'CSS样式',
  `version` int DEFAULT '1' COMMENT '版本号',
  `status` int DEFAULT '1' COMMENT '状态:1启用 0停用',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_print_tpl_code` (`template_code`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=166 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='打印模板';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_purchase_order`
--

DROP TABLE IF EXISTS `his_purchase_order`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_purchase_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `order_no` varchar(30) NOT NULL COMMENT '订单号(CO+yyyyMMdd+4位)',
  `plan_id` bigint DEFAULT NULL COMMENT '来源计划单ID',
  `warehouse_id` bigint DEFAULT NULL COMMENT '收货药库ID',
  `supplier_id` bigint DEFAULT NULL COMMENT '供应商ID',
  `status` tinyint DEFAULT '0' COMMENT '状态:0草稿 1已下单 2部分到货 3已完成 -2作废',
  `total_amount` decimal(14,2) DEFAULT '0.00' COMMENT '订单金额合计',
  `upload_status` tinyint DEFAULT '0' COMMENT '集采/统采上传:0未上传 9已上传(Mock)',
  `upload_time` datetime DEFAULT NULL COMMENT '上传时间',
  `upload_receipt` varchar(200) DEFAULT NULL COMMENT '上传回执(Mock)',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_order_no` (`tenant_id`,`order_no`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`),
  KEY `idx_supplier` (`tenant_id`,`supplier_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='药品采购订单主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_purchase_order_item`
--

DROP TABLE IF EXISTS `his_purchase_order_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_purchase_order_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `plan_item_id` bigint DEFAULT NULL COMMENT '来源计划明细ID',
  `drug_catalog_id` bigint DEFAULT NULL COMMENT '药品目录ID',
  `drug_code` varchar(50) DEFAULT NULL COMMENT '药品编码(快照)',
  `drug_name` varchar(200) DEFAULT NULL COMMENT '药品名称(快照)',
  `spec` varchar(200) DEFAULT NULL COMMENT '规格(快照)',
  `manufacturer` varchar(200) DEFAULT NULL COMMENT '生产企业(快照)',
  `qty` decimal(12,2) DEFAULT '0.00' COMMENT '订购数量',
  `qty_received` decimal(12,2) DEFAULT '0.00' COMMENT '已到货入库数量',
  `price` decimal(12,4) DEFAULT NULL COMMENT '进价',
  `amount` decimal(14,2) DEFAULT NULL COMMENT '金额',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_order` (`order_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='药品采购订单明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_purchase_plan`
--

DROP TABLE IF EXISTS `his_purchase_plan`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_purchase_plan` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `plan_no` varchar(30) NOT NULL COMMENT '计划单号(CH+yyyyMMdd+4位)',
  `warehouse_id` bigint DEFAULT NULL COMMENT '目标药库ID',
  `supplier_id` bigint DEFAULT NULL COMMENT '供应商ID(空=多供应商, 转订单时指定)',
  `gen_type` varchar(10) DEFAULT 'manual' COMMENT '生成方式:auto智能/manual手工',
  `status` tinyint DEFAULT '0' COMMENT '状态:0草稿 1待审 2已审 3已驳回 9已转订单 -2作废',
  `total_amount` decimal(14,2) DEFAULT '0.00' COMMENT '计划金额合计',
  `submit_by` varchar(50) DEFAULT NULL COMMENT '提交人',
  `submit_time` datetime DEFAULT NULL COMMENT '提交时间',
  `approve_by` varchar(50) DEFAULT NULL COMMENT '审批人',
  `approve_time` datetime DEFAULT NULL COMMENT '审批时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_plan_no` (`tenant_id`,`plan_no`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='药品采购计划主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_purchase_plan_item`
--

DROP TABLE IF EXISTS `his_purchase_plan_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_purchase_plan_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `plan_id` bigint NOT NULL COMMENT '计划单ID',
  `drug_catalog_id` bigint DEFAULT NULL COMMENT '药品目录ID',
  `drug_code` varchar(50) DEFAULT NULL COMMENT '药品编码(快照)',
  `drug_name` varchar(200) DEFAULT NULL COMMENT '药品名称(快照)',
  `spec` varchar(200) DEFAULT NULL COMMENT '规格(快照)',
  `manufacturer` varchar(200) DEFAULT NULL COMMENT '生产企业(快照)',
  `major_class` varchar(50) DEFAULT NULL COMMENT '药品大类(快照, 筛选/分类)',
  `cur_stock` decimal(12,2) DEFAULT '0.00' COMMENT '当前库存(快照)',
  `lo_qty` decimal(12,2) DEFAULT NULL COMMENT '低储标准(快照)',
  `hi_qty` decimal(12,2) DEFAULT NULL COMMENT '高储标准(快照)',
  `last_month_in` decimal(12,2) DEFAULT '0.00' COMMENT '上月入库量(快照)',
  `last_month_out` decimal(12,2) DEFAULT '0.00' COMMENT '上月出库量(快照)',
  `dispense_qty` decimal(12,2) DEFAULT '0.00' COMMENT '药房发药量(快照)',
  `abc_class` varchar(2) DEFAULT NULL COMMENT 'ABC类别 A/B/C',
  `qty_suggest` decimal(12,2) DEFAULT '0.00' COMMENT '建议采购数量',
  `price` decimal(12,4) DEFAULT NULL COMMENT '预估进价',
  `amount` decimal(14,2) DEFAULT NULL COMMENT '预估金额',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_plan` (`plan_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='药品采购计划明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_purchase_rule`
--

DROP TABLE IF EXISTS `his_purchase_rule`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_purchase_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `warehouse_id` bigint DEFAULT NULL COMMENT '适用药库ID(空=全院默认)',
  `rule_name` varchar(100) DEFAULT NULL COMMENT '规则名称',
  `refer_months` int DEFAULT '1' COMMENT '参考月数(入出库/发药统计窗口)',
  `lo_qty` decimal(12,2) DEFAULT NULL COMMENT '低储标准(补货点)',
  `hi_qty` decimal(12,2) DEFAULT NULL COMMENT '高储标准',
  `dispense_weight` decimal(4,2) DEFAULT '1.00' COMMENT '发药量权重',
  `stockout_weight` decimal(4,2) DEFAULT '1.00' COMMENT '出库量权重',
  `abc_a_ratio` decimal(4,2) DEFAULT '0.80' COMMENT 'ABC分类A累计占比',
  `abc_b_ratio` decimal(4,2) DEFAULT '0.95' COMMENT 'ABC分类B累计占比',
  `enabled` tinyint DEFAULT '1' COMMENT '启用:1是 0否',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_wh` (`tenant_id`,`org_id`,`warehouse_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能采购规则配置';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_recon_diff`
--

DROP TABLE IF EXISTS `his_recon_diff`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_recon_diff` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户(医共体)ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID(租户级对账为空)',
  `recon_task_id` bigint DEFAULT NULL COMMENT '对账任务ID(his_recon_task.id)',
  `stmt_date` date DEFAULT NULL COMMENT '对账日期',
  `setl_id` varchar(30) DEFAULT NULL COMMENT '结算ID(中心端多条时为空, 规范表201说明7)',
  `mdtrt_id` varchar(30) DEFAULT NULL COMMENT '就诊ID(中心端多条时为空)',
  `psn_no` varchar(30) DEFAULT NULL COMMENT '人员编号',
  `msgid` varchar(50) DEFAULT NULL COMMENT '原交易报文ID',
  `stmt_rslt` varchar(6) DEFAULT NULL COMMENT '核对结果(表201stmt_rslt)',
  `refd_setl_flag` varchar(3) DEFAULT NULL COMMENT '退费结算标志(3位)',
  `memo` varchar(500) DEFAULT NULL COMMENT '说明(表201memo)',
  `medfee_sumamt` decimal(16,2) DEFAULT '0.00' COMMENT '医疗费总额-平台',
  `fund_pay_sumamt` decimal(16,2) DEFAULT '0.00' COMMENT '基金支付总额-平台',
  `acct_pay` decimal(16,2) DEFAULT '0.00' COMMENT '个账支付-平台',
  `status` tinyint DEFAULT '0' COMMENT '处理状态:0待处理 1已核对 2已平账',
  `handle_memo` varchar(500) DEFAULT NULL COMMENT '处理备注',
  `handle_time` datetime DEFAULT NULL COMMENT '处理时间',
  `handle_by` varchar(50) DEFAULT NULL COMMENT '处理人',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_recondiff_tenant` (`tenant_id`),
  KEY `idx_recondiff_task` (`recon_task_id`),
  KEY `idx_recondiff_status` (`tenant_id`,`org_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=12271 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医保对账差异明细(批次4 M3)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_recon_task`
--

DROP TABLE IF EXISTS `his_recon_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_recon_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户(医共体)ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID(租户级对账为空)',
  `stmt_date` date NOT NULL COMMENT '对账日期(T-1结算日)',
  `insutype` varchar(6) DEFAULT NULL COMMENT '险种类型(3201分组维度)',
  `recon_type` varchar(10) NOT NULL COMMENT '对账类型:TOTAL(3201)/DETAIL(3202)',
  `result` varchar(2) NOT NULL COMMENT '结果:1平 2不平 9失败',
  `medfee_local` decimal(16,2) DEFAULT '0.00' COMMENT '医疗费总额-院内口径',
  `medfee_remote` decimal(16,2) DEFAULT '0.00' COMMENT '医疗费总额-平台回执口径',
  `fund_local` decimal(16,2) DEFAULT '0.00' COMMENT '基金支付总额-院内口径',
  `fund_remote` decimal(16,2) DEFAULT '0.00' COMMENT '基金支付总额-平台回执口径',
  `acct_local` decimal(16,2) DEFAULT '0.00' COMMENT '个账支付-院内口径(3202为现金)',
  `acct_remote` decimal(16,2) DEFAULT '0.00' COMMENT '个账支付-平台回执口径',
  `cnt_local` int DEFAULT '0' COMMENT '结算笔数-院内口径',
  `cnt_remote` int DEFAULT '0' COMMENT '结算笔数-平台回执口径',
  `file_qury_no` varchar(30) DEFAULT NULL COMMENT '3202明细文件查询号(9101返回)',
  `stmt_rslt` varchar(500) DEFAULT NULL COMMENT '平台回执(表197stmt_rslt/stmt_rslt_dscr或差异说明)',
  `recon_time` datetime DEFAULT NULL COMMENT '对账执行时间',
  `memo` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_recontask_tenant` (`tenant_id`),
  KEY `idx_recontask_date` (`tenant_id`,`org_id`,`stmt_date`),
  KEY `idx_recontask_type` (`recon_type`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医保对账任务(批次4 M3: 3201/3202)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_referral`
--

DROP TABLE IF EXISTS `his_referral`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_referral` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID(转出/接收方归属)',
  `visit_id` bigint DEFAULT NULL COMMENT '关联门诊就诊ID(his_visit.id)',
  `patient_id` bigint NOT NULL COMMENT '患者ID',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名',
  `direction` int NOT NULL DEFAULT '1' COMMENT '方向:1上转/转出 2下转/接收',
  `to_hospital` varchar(100) NOT NULL COMMENT '目标医院',
  `to_dept` varchar(50) DEFAULT NULL COMMENT '目标科室',
  `reason` varchar(500) DEFAULT NULL COMMENT '转诊原因/病情',
  `summary` text COMMENT '病情摘要(转诊单正文)',
  `contact_phone` varchar(20) DEFAULT NULL COMMENT '联系电话',
  `status` int DEFAULT '1' COMMENT '状态:1已申请 2已接收 3已完成 4已取消',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_patient` (`tenant_id`,`patient_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='门诊转诊登记';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_reg_payment`
--

DROP TABLE IF EXISTS `his_reg_payment`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_reg_payment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID(科室未归属机构的历史数据为空)',
  `registration_id` bigint NOT NULL COMMENT '挂号记录ID(his_registration.id)',
  `reg_no` varchar(30) DEFAULT NULL COMMENT '挂号单号',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名',
  `direction` tinyint NOT NULL COMMENT '流水方向:1挂号收款 -1退号退款',
  `amount` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '金额(正数, 方向由direction表达)',
  `pay_method` varchar(20) DEFAULT NULL COMMENT '支付方式全渠道:CASH/WECHAT/ALIPAY/CARD/INSURANCE/FREE',
  `mdtrt_id` varchar(50) DEFAULT NULL COMMENT '医保就诊ID(挂号医保结算时落mdtrt_id)',
  `biz_time` datetime DEFAULT NULL COMMENT '业务时间(挂号/退号时间)',
  `operator` varchar(50) DEFAULT NULL COMMENT '操作人',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_regpay_tenant` (`tenant_id`),
  KEY `idx_regpay_org_time` (`tenant_id`,`org_id`,`biz_time`),
  KEY `idx_regpay_reg` (`registration_id`),
  KEY `idx_regpay_method` (`tenant_id`,`org_id`,`pay_method`)
) ENGINE=InnoDB AUTO_INCREMENT=20569 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='挂号收费流水(P1-18)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_registration`
--

DROP TABLE IF EXISTS `his_registration`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_registration` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '挂号ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `reg_no` varchar(30) NOT NULL COMMENT '挂号单号',
  `patient_id` bigint NOT NULL COMMENT '患者ID',
  `patient_no` varchar(30) DEFAULT NULL COMMENT '院内患者号',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名(冗余)',
  `psn_no` varchar(30) DEFAULT NULL COMMENT '医保人员编号',
  `insutype` varchar(10) DEFAULT NULL COMMENT '险种类型',
  `mdtrt_cert_type` varchar(10) DEFAULT NULL COMMENT '就诊凭证类型',
  `mdtrt_cert_no` varchar(50) DEFAULT NULL COMMENT '就诊凭证编号',
  `dept_id` bigint DEFAULT NULL COMMENT '科室ID',
  `dept_code` varchar(30) DEFAULT NULL COMMENT '科室编码',
  `dept_name` varchar(100) DEFAULT NULL COMMENT '科室名称',
  `caty` varchar(20) DEFAULT NULL COMMENT '科别(医保)',
  `staff_id` bigint DEFAULT NULL COMMENT '医师ID',
  `atddr_no` varchar(30) DEFAULT NULL COMMENT '医师医保编码',
  `dr_name` varchar(50) DEFAULT NULL COMMENT '医师姓名',
  `schedule_id` bigint DEFAULT NULL COMMENT '排班ID',
  `work_date` date DEFAULT NULL COMMENT '出诊日期',
  `time_type` varchar(10) DEFAULT NULL COMMENT '时段:am/pm/night',
  `reg_level_code` varchar(30) DEFAULT NULL COMMENT '号别编码',
  `reg_level_name` varchar(50) DEFAULT NULL COMMENT '号别名称',
  `reg_fee` decimal(10,2) DEFAULT '0.00' COMMENT '挂号费',
  `med_type` varchar(10) DEFAULT '11' COMMENT '医疗类别:11-普通门诊',
  `ipt_otp_no` varchar(30) DEFAULT NULL COMMENT '院内就诊流水号(门诊号)',
  `mdtrt_id` varchar(30) DEFAULT NULL COMMENT '医保就诊ID(2201回填)',
  `reg_time` datetime DEFAULT NULL COMMENT '挂号时间',
  `status` tinyint DEFAULT '1' COMMENT '状态:1-已挂号 2-已退号 3-已就诊',
  `cancel_time` datetime DEFAULT NULL COMMENT '退号时间',
  `cancel_reason` varchar(200) DEFAULT NULL COMMENT '退号原因',
  `operator` varchar(50) DEFAULT NULL COMMENT '挂号员',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `fee_type` varchar(10) DEFAULT NULL COMMENT '费别编码',
  `discount_type` varchar(20) DEFAULT NULL COMMENT '减免类型编码(none/age70free等)',
  `discount_reason` varchar(200) DEFAULT NULL COMMENT '减免原因说明',
  `discount_amount` decimal(10,2) DEFAULT '0.00' COMMENT '减免金额',
  `actual_fee` decimal(10,2) DEFAULT NULL COMMENT '实收金额(挂号费-减免金额)',
  `pay_method` varchar(20) DEFAULT NULL COMMENT '支付方式(free=免费)',
  `pay_detail` varchar(500) DEFAULT NULL COMMENT '混合支付明细JSON',
  `queue_no` varchar(20) DEFAULT NULL COMMENT '候诊序号(科室简码+4位流水号)',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_reg_no` (`reg_no`),
  KEY `idx_patient` (`patient_id`),
  KEY `idx_mdtrt` (`mdtrt_id`),
  KEY `idx_ipt_otp` (`ipt_otp_no`),
  KEY `idx_work_date` (`work_date`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=20643 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='挂号记录表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_requisition`
--

DROP TABLE IF EXISTS `his_requisition`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_requisition` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `req_no` varchar(30) NOT NULL COMMENT '请领单号(QL+yyyyMMdd+4位)',
  `pharmacy_id` bigint DEFAULT NULL COMMENT '请领药房ID(his_pharmacy_def.id)',
  `to_warehouse_id` bigint DEFAULT NULL COMMENT '发货来源药库ID(his_warehouse_def.id)',
  `status` tinyint DEFAULT '0' COMMENT '状态:0草稿 1待审核 2已发货 3已收货 -1已驳回 -2已作废',
  `apply_by` varchar(50) DEFAULT NULL COMMENT '申请人',
  `apply_time` datetime DEFAULT NULL COMMENT '申请时间',
  `approve_by` varchar(50) DEFAULT NULL COMMENT '审核人',
  `approve_time` datetime DEFAULT NULL COMMENT '审核时间',
  `receive_by` varchar(50) DEFAULT NULL COMMENT '收货人',
  `receive_time` datetime DEFAULT NULL COMMENT '收货时间',
  `stock_out_id` bigint DEFAULT NULL COMMENT '发货出库单ID(his_stock_out.id)',
  `stock_in_id` bigint DEFAULT NULL COMMENT '收货入库单ID(his_stock_in.id)',
  `total_amount` decimal(12,2) DEFAULT '0.00' COMMENT '请领金额合计',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_req_no` (`tenant_id`,`req_no`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='药品请领单(药房→药库)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_requisition_item`
--

DROP TABLE IF EXISTS `his_requisition_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_requisition_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `requisition_id` bigint NOT NULL COMMENT '请领单ID',
  `drug_catalog_id` bigint DEFAULT NULL COMMENT '药品目录ID',
  `drug_code` varchar(50) DEFAULT NULL COMMENT '药品编码(快照)',
  `drug_name` varchar(200) DEFAULT NULL COMMENT '药品名称(快照)',
  `spec` varchar(200) DEFAULT NULL COMMENT '规格(快照)',
  `batch_no` varchar(50) DEFAULT NULL COMMENT '批次号(发货回填, 空=请领时不指定)',
  `qty_apply` decimal(12,2) DEFAULT '0.00' COMMENT '请领数量',
  `qty_approved` decimal(12,2) DEFAULT NULL COMMENT '审核(发货)数量',
  `qty_received` decimal(12,2) DEFAULT NULL COMMENT '实收数量',
  `retail_price` decimal(12,4) DEFAULT NULL COMMENT '零售价(快照)',
  `amount` decimal(12,2) DEFAULT NULL COMMENT '小计金额',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_requisition` (`requisition_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='药品请领明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_rx_review`
--

DROP TABLE IF EXISTS `his_rx_review`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_rx_review` (
  `id` bigint NOT NULL COMMENT '主键(雪花)',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID(his_patient.id)',
  `order_id` bigint DEFAULT NULL COMMENT '被点评医嘱ID(his_inp_order.id)',
  `doctor_id` bigint DEFAULT NULL COMMENT '被点评开嘱医生ID(his_staff.id)',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID(发起时取就诊归属机构)',
  `rx_item_snapshot` text COMMENT '医嘱要素快照JSON(发起点评时冻结)',
  `review_staff_id` bigint DEFAULT NULL COMMENT '点评医师/药师ID(his_staff.id, 点评留痕)',
  `review_time` datetime DEFAULT NULL COMMENT '点评时间',
  `result` tinyint DEFAULT NULL COMMENT '点评结论:1合理 2不规范 3不合理',
  `problem_type` varchar(50) DEFAULT NULL COMMENT '问题类型编码(适应证/选药/剂量/用法/相互作用/重复给药/禁忌/其他)',
  `score` int DEFAULT NULL COMMENT '点评评分(0-100)',
  `comment` varchar(500) DEFAULT NULL COMMENT '点评意见',
  `status` tinyint DEFAULT '1' COMMENT '点评状态:1待点评 2已点评',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `auto_findings` text COMMENT '规则引擎命中明细JSON(发起点评时自动打分留痕)',
  `auto_result` int DEFAULT NULL COMMENT '引擎建议结论:1合理 2不规范 3不合理',
  `auto_score` int DEFAULT NULL COMMENT '引擎建议评分(0-100)',
  `auto_problem_type` varchar(50) DEFAULT NULL COMMENT '引擎建议问题类型编码',
  `auto_evaluated` tinyint DEFAULT '0' COMMENT '是否已自动预打分:1是 0否',
  PRIMARY KEY (`id`),
  KEY `idx_rr_visit` (`inp_visit_id`,`deleted`),
  KEY `idx_rr_reviewer` (`review_staff_id`,`deleted`),
  KEY `idx_rr_status` (`status`,`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院处方点评单(T2阶段3)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_rx_review_rule`
--

DROP TABLE IF EXISTS `his_rx_review_rule`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_rx_review_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `rule_code` varchar(50) NOT NULL COMMENT '规则编码(唯一)',
  `rule_name` varchar(100) NOT NULL COMMENT '规则名称',
  `rule_type` varchar(50) NOT NULL COMMENT '规则类型(引擎分派: abx_under_level/abx_no_auth/abx_expiry/duplicate_drug/long_abx_duration)',
  `severity` tinyint DEFAULT '2' COMMENT '严重度:1提示 2不规范 3不合理',
  `result_hint` tinyint DEFAULT '2' COMMENT '建议结论:1合理 2不规范 3不合理',
  `problem_type_hint` varchar(50) DEFAULT NULL COMMENT '建议问题类型编码',
  `score_deduct` int DEFAULT '0' COMMENT '命中扣分值',
  `enabled` tinyint DEFAULT '1' COMMENT '启用:1启用 0停用',
  `params` varchar(500) DEFAULT NULL COMMENT '规则参数JSON(如 {"days":14})',
  `memo` varchar(255) DEFAULT NULL COMMENT '备注',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID(空=全院级)',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rule_code` (`rule_code`,`tenant_id`,`deleted`),
  KEY `idx_rule_type` (`rule_type`,`enabled`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=242 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='处方点评自动规则(T2阶段5-2)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_rx_split_rule`
--

DROP TABLE IF EXISTS `his_rx_split_rule`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_rx_split_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID(空=租户通用)',
  `dept_id` bigint DEFAULT NULL COMMENT '科室ID(空=全院通用)',
  `rule_name` varchar(100) DEFAULT NULL COMMENT '规则名称',
  `split_dim` varchar(30) NOT NULL COMMENT '拆方维度: usage/insutype/chronic_dise/special_drug/pharmacy',
  `dim_value` varchar(100) DEFAULT NULL COMMENT '维度匹配值',
  `priority` int NOT NULL DEFAULT '100' COMMENT '拆分优先级(小者优先)',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '状态:1启用 0停用',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_org_dept` (`tenant_id`,`org_id`,`dept_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='处方自动拆方规则';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_schedule`
--

DROP TABLE IF EXISTS `his_schedule`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_schedule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '排班ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `dept_id` bigint NOT NULL COMMENT '科室ID',
  `staff_id` bigint NOT NULL COMMENT '职工(医师)ID',
  `work_date` date NOT NULL COMMENT '出诊日期',
  `time_type` varchar(10) DEFAULT 'am' COMMENT '时段:am-上午 pm-下午 night-晚间',
  `reg_level_code` varchar(30) DEFAULT '01' COMMENT '号别编码',
  `reg_level_name` varchar(50) DEFAULT '普通号' COMMENT '号别名称:普通号/副主任/主任',
  `reg_fee` decimal(10,2) DEFAULT '0.00' COMMENT '挂号费',
  `total_num` int DEFAULT '0' COMMENT '总号源数',
  `left_num` int DEFAULT '0' COMMENT '剩余号源数',
  `status` tinyint DEFAULT '1' COMMENT '状态:1-开放 0-停诊',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `room` varchar(50) DEFAULT NULL COMMENT '诊室',
  `template_id` bigint DEFAULT NULL COMMENT '来源模板ID',
  `stop_reason` varchar(200) DEFAULT NULL COMMENT '停诊原因',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_staff_date` (`staff_id`,`work_date`),
  KEY `idx_dept_date` (`dept_id`,`work_date`)
) ENGINE=InnoDB AUTO_INCREMENT=200 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='排班号源表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_schedule_template`
--

DROP TABLE IF EXISTS `his_schedule_template`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_schedule_template` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `template_name` varchar(100) NOT NULL COMMENT '模板名称',
  `dept_id` bigint NOT NULL COMMENT '科室ID',
  `staff_id` bigint NOT NULL COMMENT '医师ID',
  `staff_name` varchar(50) DEFAULT NULL COMMENT '医师姓名',
  `dept_name` varchar(100) DEFAULT NULL COMMENT '科室名称',
  `weekday` tinyint NOT NULL COMMENT '星期几:1周一~7周日',
  `time_type` varchar(10) NOT NULL DEFAULT 'am' COMMENT '时段:am/pm/night',
  `reg_level_code` varchar(30) DEFAULT '01' COMMENT '号别编码',
  `reg_level_name` varchar(50) DEFAULT '普通号' COMMENT '号别名称',
  `reg_fee` decimal(10,2) DEFAULT '0.00' COMMENT '挂号费',
  `total_num` int DEFAULT '30' COMMENT '号源数',
  `room` varchar(50) DEFAULT NULL COMMENT '诊室',
  `status` tinyint DEFAULT '1' COMMENT '1启用 0停用',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_dept_staff` (`tenant_id`,`dept_id`,`staff_id`)
) ENGINE=InnoDB AUTO_INCREMENT=57 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='周排班模板';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_signature_log`
--

DROP TABLE IF EXISTS `his_signature_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_signature_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '签名用户ID(sys_user.id)',
  `user_name` varchar(50) NOT NULL COMMENT '签名用户姓名',
  `action_type` varchar(50) NOT NULL COMMENT '签名场景:order_submit/record_submit/pharm_audit/case_page/exec_verify',
  `ref_type` varchar(50) DEFAULT NULL COMMENT '关联类型',
  `ref_id` bigint DEFAULT NULL COMMENT '关联ID',
  `sign_img_url` varchar(255) DEFAULT NULL COMMENT '签名图片URL(/uploads/...)',
  `ip_address` varchar(50) DEFAULT NULL COMMENT '签名来源IP',
  `sign_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '签名时间',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_ref` (`ref_type`,`ref_id`,`tenant_id`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='电子签名日志';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_skin_test`
--

DROP TABLE IF EXISTS `his_skin_test`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_skin_test` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `exec_id` bigint NOT NULL COMMENT '执行记录ID(his_nurse_exec.id)',
  `drug_name` varchar(100) NOT NULL COMMENT '皮试药品名称',
  `drug_id` bigint DEFAULT NULL COMMENT '药品目录ID(his_drug_catalog.id)',
  `test_dose` varchar(50) DEFAULT NULL COMMENT '皮试剂量(如0.1mL)',
  `observe_start` datetime DEFAULT NULL COMMENT '观察开始时间',
  `observe_end` datetime DEFAULT NULL COMMENT '观察结束时间(开始+20分钟)',
  `result` tinyint DEFAULT '0' COMMENT '皮试结果:0观察中 1阴性 2阳性 3未做',
  `result_desc` varchar(500) DEFAULT NULL COMMENT '结果描述(局部反应等)',
  `notify_doctor_time` datetime DEFAULT NULL COMMENT '通知医生时间(阳性时必录)',
  `doctor_confirm_time` datetime DEFAULT NULL COMMENT '医生确认时间',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_exec` (`exec_id`),
  KEY `idx_drug` (`drug_id`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='皮试记录(观察窗与结果判定)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_specimen`
--

DROP TABLE IF EXISTS `his_specimen`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_specimen` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `barcode` varchar(30) NOT NULL COMMENT '标本条码号',
  `order_id` bigint NOT NULL COMMENT '医嘱单ID(his_order.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `specimen_type` varchar(20) DEFAULT NULL COMMENT '标本类型:blood血/urine尿/stool便/sputum痰/other其他',
  `tube_color` varchar(20) DEFAULT NULL COMMENT '采血管颜色(红/紫/蓝/黑/绿/灰/黄)',
  `collect_nurse_id` bigint DEFAULT NULL COMMENT '采集护士ID(his_staff.id)',
  `collect_time` datetime DEFAULT NULL COMMENT '采集时间',
  `receive_tech_id` bigint DEFAULT NULL COMMENT '签收技师ID(his_staff.id)',
  `receive_time` datetime DEFAULT NULL COMMENT '签收时间',
  `status` tinyint DEFAULT '0' COMMENT '状态:0待采集 1已采集 2已签收 3已拒收 4已出报告',
  `reject_reason` varchar(200) DEFAULT NULL COMMENT '拒收原因(溶血/量不足等)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_barcode` (`tenant_id`,`barcode`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`),
  KEY `idx_order` (`order_id`),
  KEY `idx_patient` (`tenant_id`,`patient_id`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='检验标本(采集/签收/拒收)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_staff`
--

DROP TABLE IF EXISTS `his_staff`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_staff` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '职工ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `staff_no` varchar(30) NOT NULL COMMENT '职工工号(院内)',
  `staff_name` varchar(50) NOT NULL COMMENT '姓名',
  `staff_type` varchar(20) DEFAULT '医师' COMMENT '职工类别:医师/护士/药师/技师/管理',
  `gender` varchar(4) DEFAULT NULL COMMENT '性别:男/女',
  `title_code` varchar(30) DEFAULT NULL COMMENT '职称编码',
  `title_name` varchar(50) DEFAULT NULL COMMENT '职称名称',
  `dept_id` bigint DEFAULT NULL COMMENT '所属科室ID',
  `atddr_no` varchar(30) DEFAULT NULL COMMENT '主治医师医保编码(2201/2203)',
  `dise_dor_no` varchar(30) DEFAULT NULL COMMENT '诊断医师医保编码',
  `id_card` varchar(30) DEFAULT NULL COMMENT '身份证号',
  `phone` varchar(30) DEFAULT NULL COMMENT '联系电话',
  `can_register` tinyint DEFAULT '0' COMMENT '是否可挂号:1-是 0-否',
  `reg_fee` decimal(10,2) DEFAULT '0.00' COMMENT '默认挂号费(诊查费)',
  `sort_no` int DEFAULT '0' COMMENT '排序号',
  `status` tinyint DEFAULT '1' COMMENT '状态:1-在职 0-停用',
  `memo` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `org_id` bigint DEFAULT NULL COMMENT '职工归属机构ID',
  `gender_name` varchar(50) DEFAULT NULL COMMENT '性别名称(字典回填)',
  `gender_src` varchar(50) DEFAULT NULL COMMENT '性别字典来源标识',
  `staff_type_name` varchar(50) DEFAULT NULL COMMENT '职工类别名称',
  `staff_type_src` varchar(50) DEFAULT NULL COMMENT '职工类别来源标识',
  `title_src` varchar(50) DEFAULT NULL COMMENT '职称字典来源标识',
  `med_insur_code` varchar(30) DEFAULT NULL COMMENT '国家医保业务编码(医师/药师/护士)',
  `prac_cate` varchar(4) DEFAULT NULL COMMENT '执业类别编码(whvalue:CT98.00.024 临床/口腔/公共卫生/中医)',
  `prac_cate_name` varchar(50) DEFAULT NULL COMMENT '执业类别名称(字典回填)',
  `prac_cate_src` varchar(50) DEFAULT NULL COMMENT '执业类别来源标识',
  `dr_qual_cert_no` varchar(50) DEFAULT NULL COMMENT '医师资格证号',
  `prac_cert_no` varchar(50) DEFAULT NULL COMMENT '医师执业证书编码',
  `birth_date` date DEFAULT NULL COMMENT '出生日期',
  `avatar_url` varchar(255) DEFAULT NULL COMMENT '头像图片URL(/uploads/...)',
  `sign_img_url` varchar(255) DEFAULT NULL COMMENT '签名图片URL(电子处方/文档签名)',
  `rx_right` tinyint DEFAULT '0' COMMENT '处方权(总):1-具备 0-无',
  `narcotic_right` tinyint DEFAULT '0' COMMENT '麻醉药品处方权:1-有 0-无',
  `psych1_right` tinyint DEFAULT '0' COMMENT '第一类精神药品处方权:1-有 0-无',
  `psych2_right` tinyint DEFAULT '0' COMMENT '第二类精神药品处方权:1-有 0-无',
  `antibiotic_level` varchar(6) DEFAULT NULL COMMENT '抗菌药物处方权级别(hbvalue:HBCV08.50.029 11/12/13)',
  `antibiotic_level_name` varchar(50) DEFAULT NULL COMMENT '抗菌药物处方权级别名称(字典回填)',
  `antibiotic_level_src` varchar(50) DEFAULT NULL COMMENT '抗菌药物处方权级别来源标识',
  `rx_auth_org` varchar(100) DEFAULT NULL COMMENT '处方权授权机构(医务科/授权部门)',
  `rx_auth_no` varchar(50) DEFAULT NULL COMMENT '处方权授权文号',
  `rx_auth_date` date DEFAULT NULL COMMENT '处方权授权日期',
  `rx_valid_until` date DEFAULT NULL COMMENT '处方权有效期至(到期需复训)',
  `surgery_level` varchar(6) DEFAULT NULL COMMENT '手术级别权限(cv_code:oprn_lv_code 1-4级/5其他)',
  `surgery_level_name` varchar(50) DEFAULT NULL COMMENT '手术级别权限名称(字典回填)',
  `surgery_level_src` varchar(50) DEFAULT NULL COMMENT '手术级别权限来源标识',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(姓名首字母, 自动生成只读)',
  `abbr_code` varchar(64) DEFAULT NULL COMMENT '自定义简码(人工维护, 选填)',
  `qual_intro` varchar(1000) DEFAULT NULL COMMENT '资质介绍(专业特长/学术任职/从业经历等说明)',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_tenant_staff` (`tenant_id`,`staff_no`),
  KEY `idx_dept` (`dept_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5532 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='职工表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_staff_rx_auth`
--

DROP TABLE IF EXISTS `his_staff_rx_auth`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_staff_rx_auth` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `staff_id` bigint NOT NULL COMMENT '职工ID(his_staff.id)',
  `auth_kind` varchar(20) NOT NULL COMMENT '权限类别: abx抗菌分级/narcotic麻醉/psych1精一/psych2精二',
  `auth_code` varchar(20) NOT NULL COMMENT '类别内编码(抗菌 11/12/13; 专项填 1)',
  `auth_name` varchar(50) DEFAULT NULL COMMENT '权限名称(字典回填)',
  `valid_from` date DEFAULT NULL COMMENT '生效日期',
  `valid_until` date DEFAULT NULL COMMENT '有效期至(到期失效, 需复训再授权)',
  `auth_org` varchar(100) DEFAULT NULL COMMENT '授权机构',
  `auth_no` varchar(100) DEFAULT NULL COMMENT '授权文号',
  `status` tinyint DEFAULT '1' COMMENT '状态:1有效 0注销',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID(跟随职工归属)',
  `memo` varchar(255) DEFAULT NULL COMMENT '备注',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `deleted` tinyint DEFAULT '0',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_sra_staff` (`staff_id`,`deleted`),
  KEY `idx_sra_kind` (`staff_id`,`auth_kind`,`deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医师处方权限按级授权明细(T2阶段5-3)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_stock_accept`
--

DROP TABLE IF EXISTS `his_stock_accept`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_stock_accept` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `accept_no` varchar(30) NOT NULL COMMENT '验收单号(YS+yyyyMMdd+4位)',
  `warehouse_id` bigint DEFAULT NULL COMMENT '药库ID',
  `supplier_id` bigint DEFAULT NULL COMMENT '供应商ID(集中验收按供应商归集)',
  `accept_type` tinyint DEFAULT '1' COMMENT '验收方式:1单张入库 2按供应商集中',
  `stock_in_id` bigint DEFAULT NULL COMMENT '单张验收指向的入库单ID(集中验收为空)',
  `bill_count` int DEFAULT '0' COMMENT '验收覆盖入库单张数',
  `total_amount` decimal(14,2) DEFAULT '0.00' COMMENT '验收金额合计',
  `conclusion` tinyint DEFAULT '1' COMMENT '验收结论:1合格 2异常',
  `accept_by` varchar(50) DEFAULT NULL COMMENT '验收人',
  `accept_time` datetime DEFAULT NULL COMMENT '验收时间',
  `status` tinyint DEFAULT '0' COMMENT '状态:0草稿 1已验收',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_accept_no` (`tenant_id`,`accept_no`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`),
  KEY `idx_supplier` (`tenant_id`,`supplier_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='财务验收单主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_stock_accept_item`
--

DROP TABLE IF EXISTS `his_stock_accept_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_stock_accept_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `accept_id` bigint NOT NULL COMMENT '验收单ID',
  `stock_in_id` bigint DEFAULT NULL COMMENT '来源入库单ID',
  `drug_catalog_id` bigint DEFAULT NULL COMMENT '药品目录ID',
  `drug_code` varchar(50) DEFAULT NULL COMMENT '药品编码(快照)',
  `drug_name` varchar(200) DEFAULT NULL COMMENT '药品名称(快照)',
  `spec` varchar(200) DEFAULT NULL COMMENT '规格(快照)',
  `batch_no` varchar(50) DEFAULT NULL COMMENT '批号(快照)',
  `manufacturer` varchar(200) DEFAULT NULL COMMENT '生产企业(快照)',
  `qty` decimal(12,2) DEFAULT '0.00' COMMENT '验收数量',
  `cost_price` decimal(12,4) DEFAULT NULL COMMENT '进价',
  `amount` decimal(14,2) DEFAULT NULL COMMENT '金额',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_accept` (`accept_id`),
  KEY `idx_stock_in` (`stock_in_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='财务验收单明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_stock_balance`
--

DROP TABLE IF EXISTS `his_stock_balance`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_stock_balance` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `warehouse_id` bigint DEFAULT NULL COMMENT '药库ID',
  `stock_out_id` bigint DEFAULT NULL COMMENT '触发出库单ID',
  `stock_out_item_id` bigint DEFAULT NULL COMMENT '触发出库明细ID',
  `stock_in_id` bigint DEFAULT NULL COMMENT '来源入库单ID(未验收)',
  `stock_in_item_id` bigint DEFAULT NULL COMMENT '来源入库明细ID',
  `drug_catalog_id` bigint DEFAULT NULL COMMENT '药品目录ID',
  `drug_name` varchar(200) DEFAULT NULL COMMENT '药品名称(快照)',
  `batch_no` varchar(50) DEFAULT NULL COMMENT '批号(快照)',
  `orig_in_price` decimal(12,4) DEFAULT NULL COMMENT '原挂账进价',
  `actual_in_price` decimal(12,4) DEFAULT NULL COMMENT '实际出库结转进价',
  `qty` decimal(12,2) DEFAULT '0.00' COMMENT '冲抵数量',
  `diff_amount` decimal(14,2) DEFAULT '0.00' COMMENT '进价差=（实际-原）*数量',
  `balance_date` date DEFAULT NULL COMMENT '平账日期',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_date` (`tenant_id`,`org_id`,`balance_date`),
  KEY `idx_out_item` (`stock_out_item_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='未验收药品出库平账记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_stock_check`
--

DROP TABLE IF EXISTS `his_stock_check`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_stock_check` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `warehouse_id` bigint DEFAULT NULL COMMENT '药库ID(his_warehouse_def.id)',
  `check_no` varchar(30) NOT NULL COMMENT '盘点单号(PD+yyyyMMdd+4位)',
  `check_date` date DEFAULT NULL COMMENT '盘点日期',
  `status` tinyint DEFAULT '0' COMMENT '状态:0进行中 1已完成 2已作废',
  `check_by` varchar(50) DEFAULT NULL COMMENT '盘点人',
  `confirm_by` varchar(50) DEFAULT NULL COMMENT '确认人',
  `confirm_time` datetime DEFAULT NULL COMMENT '确认时间',
  `profit_amount` decimal(12,2) DEFAULT '0.00' COMMENT '盘盈金额合计',
  `loss_amount` decimal(12,2) DEFAULT '0.00' COMMENT '盘亏金额合计',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_check_no` (`tenant_id`,`check_no`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='盘点单';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_stock_check_item`
--

DROP TABLE IF EXISTS `his_stock_check_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_stock_check_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `stock_check_id` bigint NOT NULL COMMENT '盘点单ID',
  `drug_stock_id` bigint DEFAULT NULL COMMENT '库存批次ID(his_drug_stock.id)',
  `drug_catalog_id` bigint DEFAULT NULL COMMENT '药品目录ID',
  `drug_name` varchar(200) DEFAULT NULL COMMENT '药品名称(快照)',
  `spec` varchar(200) DEFAULT NULL COMMENT '规格(快照)',
  `batch_no` varchar(50) DEFAULT NULL COMMENT '批次号(快照)',
  `system_qty` decimal(12,2) DEFAULT '0.00' COMMENT '系统账面数量',
  `actual_qty` decimal(12,2) DEFAULT NULL COMMENT '实盘数量',
  `diff_qty` decimal(12,2) DEFAULT NULL COMMENT '差异数量(实盘-账面; 正=盘盈 负=盘亏)',
  `cost_price` decimal(12,4) DEFAULT NULL COMMENT '进价(快照)',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_stock_check` (`stock_check_id`)
) ENGINE=InnoDB AUTO_INCREMENT=31 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='盘点明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_stock_in`
--

DROP TABLE IF EXISTS `his_stock_in`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_stock_in` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `in_no` varchar(30) NOT NULL COMMENT '入库单号',
  `in_type` tinyint NOT NULL COMMENT '入库类型:1采购 2退药回库 3盘盈 4调拨入',
  `supplier` varchar(200) DEFAULT NULL COMMENT '供应商',
  `supplier_contact` varchar(50) DEFAULT NULL COMMENT '供应商联系方式',
  `total_amount` decimal(12,2) DEFAULT '0.00' COMMENT '总金额',
  `status` tinyint DEFAULT '0' COMMENT '状态:0草稿 1已确认 2已作废',
  `confirm_by` varchar(50) DEFAULT NULL COMMENT '确认人',
  `confirm_time` datetime DEFAULT NULL COMMENT '确认时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `warehouse_id` bigint DEFAULT NULL COMMENT '药库ID(his_warehouse_def.id)',
  `purchase_mode` tinyint DEFAULT '1' COMMENT '购入方式:1正常 2挂账 3票未到(仅单据)',
  `purchase_order_id` bigint DEFAULT NULL COMMENT '来源采购订单ID(his_purchase_order.id)',
  `invoice_no` varchar(50) DEFAULT NULL COMMENT '发票号',
  `invoice_date` date DEFAULT NULL COMMENT '发票日期',
  `target_warehouse_id` bigint DEFAULT NULL COMMENT '定向出库目标库ID(确认入库后自动调拨至该库)',
  `accept_status` tinyint DEFAULT '1' COMMENT '财务验收:0未验收 1已验收',
  `reversed_flag` tinyint DEFAULT '0' COMMENT '是否已冲红:1是 0否',
  `red_of_id` bigint DEFAULT NULL COMMENT '红字冲账单指向的原入库单ID',
  `supplier_id` bigint DEFAULT NULL COMMENT '供应商ID(his_supplier.id, 新单与 supplier 文本双写)',
  `paid_amount` decimal(14,2) DEFAULT '0.00' COMMENT '已付金额(供应商付款回写)',
  `paid_status` tinyint DEFAULT '0' COMMENT '付款状态:0未付 1部分 2已付',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_in_no` (`tenant_id`,`in_no`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=49 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='入库单';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_stock_in_item`
--

DROP TABLE IF EXISTS `his_stock_in_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_stock_in_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `stock_in_id` bigint NOT NULL COMMENT '入库单ID',
  `drug_catalog_id` bigint NOT NULL COMMENT '药品目录ID',
  `drug_code` varchar(50) NOT NULL COMMENT '药品编码',
  `drug_name` varchar(200) NOT NULL COMMENT '药品名称',
  `spec` varchar(200) DEFAULT NULL COMMENT '规格',
  `batch_no` varchar(50) NOT NULL COMMENT '批次号',
  `manufacturer` varchar(200) DEFAULT NULL COMMENT '生产厂家',
  `qty` decimal(12,2) NOT NULL COMMENT '数量',
  `cost_price` decimal(12,4) DEFAULT NULL COMMENT '进价',
  `retail_price` decimal(12,4) DEFAULT NULL COMMENT '零售价',
  `prod_date` date DEFAULT NULL COMMENT '生产日期',
  `exp_date` date DEFAULT NULL COMMENT '有效期',
  `amount` decimal(12,2) DEFAULT NULL COMMENT '小计金额',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `pack_qty` decimal(12,2) DEFAULT NULL COMMENT '大包装数(多单位录入)',
  `pack_ratio` int DEFAULT NULL COMMENT '包装换算比快照(大包装→最小单位)',
  `min_qty` decimal(12,2) DEFAULT NULL COMMENT '最小单位量=大包装数*包装比',
  `purchase_price` decimal(12,4) DEFAULT NULL COMMENT '挂账进价(待核)',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_stock_in` (`stock_in_id`)
) ENGINE=InnoDB AUTO_INCREMENT=62 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='入库明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_stock_month_end`
--

DROP TABLE IF EXISTS `his_stock_month_end`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_stock_month_end` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `warehouse_id` bigint DEFAULT NULL COMMENT '药库ID(空=全院口径)',
  `period_start` date NOT NULL COMMENT '本期起始日(上次月结终止+1)',
  `period_end` date NOT NULL COMMENT '本期终止日',
  `acct_standard` tinyint DEFAULT '1' COMMENT '记账标准:1进价 3零售价(批发价2后补)',
  `status` tinyint DEFAULT '0' COMMENT '状态:0进行中 1已月结 -1已取消',
  `opening_amount` decimal(16,2) DEFAULT '0.00' COMMENT '期初金额(财务账)',
  `closing_amount` decimal(16,2) DEFAULT '0.00' COMMENT '期末金额(财务账)',
  `income_amount` decimal(16,2) DEFAULT '0.00' COMMENT '本期收入(入库)金额',
  `expense_amount` decimal(16,2) DEFAULT '0.00' COMMENT '本期支出(出库)金额',
  `opening_qty` decimal(16,2) DEFAULT '0.00' COMMENT '期初数量(实物账)',
  `closing_qty` decimal(16,2) DEFAULT '0.00' COMMENT '期末数量(实物账)',
  `in_qty` decimal(16,2) DEFAULT '0.00' COMMENT '本期入库数量',
  `out_qty` decimal(16,2) DEFAULT '0.00' COMMENT '本期出库数量',
  `drug_count` int DEFAULT '0' COMMENT '参与结账品种数',
  `confirm_by` varchar(50) DEFAULT NULL COMMENT '月结人',
  `confirm_time` datetime DEFAULT NULL COMMENT '月结时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_wh_period` (`tenant_id`,`org_id`,`warehouse_id`,`period_end`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库房月结记录(财务账+实物账双轨)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_stock_out`
--

DROP TABLE IF EXISTS `his_stock_out`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_stock_out` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `out_no` varchar(30) NOT NULL COMMENT '出库单号',
  `out_type` tinyint NOT NULL COMMENT '出库类型:1处方发药 2报损 3盘亏 4调拨出',
  `ref_id` bigint DEFAULT NULL COMMENT '关联单据ID',
  `ref_no` varchar(50) DEFAULT NULL COMMENT '关联单据号',
  `total_amount` decimal(12,2) DEFAULT '0.00' COMMENT '总金额',
  `status` tinyint DEFAULT '0' COMMENT '状态:0草稿 1已确认 2已作废',
  `confirm_by` varchar(50) DEFAULT NULL COMMENT '确认人',
  `confirm_time` datetime DEFAULT NULL COMMENT '确认时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `warehouse_id` bigint DEFAULT NULL COMMENT '药库ID(his_warehouse_def.id)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_out_no` (`tenant_id`,`out_no`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=8533 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='出库单';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_stock_out_item`
--

DROP TABLE IF EXISTS `his_stock_out_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_stock_out_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `stock_out_id` bigint NOT NULL COMMENT '出库单ID',
  `drug_stock_id` bigint DEFAULT NULL COMMENT '库存批次ID',
  `drug_catalog_id` bigint NOT NULL COMMENT '药品目录ID',
  `drug_code` varchar(50) NOT NULL COMMENT '药品编码',
  `drug_name` varchar(200) NOT NULL COMMENT '药品名称',
  `spec` varchar(200) DEFAULT NULL COMMENT '规格',
  `batch_no` varchar(50) DEFAULT NULL COMMENT '批次号',
  `qty` decimal(12,2) NOT NULL COMMENT '数量',
  `cost_price` decimal(12,4) DEFAULT NULL COMMENT '进价',
  `retail_price` decimal(12,4) DEFAULT NULL COMMENT '零售价',
  `amount` decimal(12,2) DEFAULT NULL COMMENT '小计金额',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_stock_out` (`stock_out_id`)
) ENGINE=InnoDB AUTO_INCREMENT=8536 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='出库明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_supplier`
--

DROP TABLE IF EXISTS `his_supplier`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_supplier` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `supplier_code` varchar(50) NOT NULL COMMENT '供应商编码(租户内唯一)',
  `supplier_name` varchar(200) NOT NULL COMMENT '供应商名称',
  `contact` varchar(50) DEFAULT NULL COMMENT '联系人',
  `phone` varchar(50) DEFAULT NULL COMMENT '联系电话',
  `address` varchar(200) DEFAULT NULL COMMENT '地址',
  `settle_cycle` int DEFAULT NULL COMMENT '结算周期(天)',
  `jt_flag` tinyint DEFAULT '0' COMMENT '集采供应商标识:1是 0否',
  `status` tinyint DEFAULT '1' COMMENT '状态:1启用 0停用',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `std_sup_code` varchar(40) DEFAULT NULL COMMENT '引用全局企业字典std_supplier.sup_code(可空,从字典导入时回填)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_supplier_code` (`tenant_id`,`supplier_code`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商主数据';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_supplier_payment`
--

DROP TABLE IF EXISTS `his_supplier_payment`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_supplier_payment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `pay_no` varchar(30) NOT NULL COMMENT '付款单号(FK+yyyyMMdd+4位)',
  `supplier_id` bigint DEFAULT NULL COMMENT '供应商ID(his_supplier.id)',
  `pay_date` date DEFAULT NULL COMMENT '付款日期',
  `pay_method` tinyint DEFAULT '1' COMMENT '付款方式:1全额 2输入总额 3部分分摊',
  `amount` decimal(14,2) DEFAULT '0.00' COMMENT '本次付款总额',
  `pay_channel` varchar(50) DEFAULT NULL COMMENT '支付渠道/方式备注',
  `status` tinyint DEFAULT '0' COMMENT '状态:0草稿 1已确认',
  `confirm_by` varchar(50) DEFAULT NULL COMMENT '确认人',
  `confirm_time` datetime DEFAULT NULL COMMENT '确认时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_pay_no` (`tenant_id`,`pay_no`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`),
  KEY `idx_supplier` (`tenant_id`,`supplier_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商付款单主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_supplier_payment_item`
--

DROP TABLE IF EXISTS `his_supplier_payment_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_supplier_payment_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `payment_id` bigint NOT NULL COMMENT '付款单ID',
  `stock_in_id` bigint DEFAULT NULL COMMENT '被结算入库单ID',
  `stock_in_no` varchar(30) DEFAULT NULL COMMENT '入库单号(快照)',
  `in_amount` decimal(14,2) DEFAULT '0.00' COMMENT '入库应付额(快照)',
  `paid_before` decimal(14,2) DEFAULT '0.00' COMMENT '结算前已付额(快照)',
  `paid_amount` decimal(14,2) DEFAULT '0.00' COMMENT '本次分摊付款额',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_payment` (`payment_id`),
  KEY `idx_stock_in` (`stock_in_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商付款单明细(关联被结算入库单与分摊额)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_surgery`
--

DROP TABLE IF EXISTS `his_surgery`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_surgery` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `inp_visit_id` bigint DEFAULT NULL COMMENT '住院就诊ID(his_inp_visit.id, 门诊/日间手术为空)',
  `surgery_code` varchar(50) DEFAULT NULL COMMENT '手术编码ICD-9-CM-3',
  `surgery_name` varchar(200) NOT NULL COMMENT '手术名称',
  `surgery_level` tinyint DEFAULT NULL COMMENT '手术级别:1一级 2二级 3三级 4四级',
  `surgeon_id` bigint DEFAULT NULL COMMENT '主刀医师ID(his_staff.id)',
  `first_assistant_id` bigint DEFAULT NULL COMMENT '一助ID(his_staff.id)',
  `second_assistant_id` bigint DEFAULT NULL COMMENT '二助ID(his_staff.id)',
  `anesthesiologist_id` bigint DEFAULT NULL COMMENT '麻醉医师ID(his_staff.id)',
  `anesthesia_nurse_id` bigint DEFAULT NULL COMMENT '麻醉护士ID(his_staff.id)',
  `instrument_nurse_id` bigint DEFAULT NULL COMMENT '器械护士ID(his_staff.id)',
  `circulating_nurse_id` bigint DEFAULT NULL COMMENT '巡回护士ID(his_staff.id)',
  `room_no` varchar(20) DEFAULT NULL COMMENT '手术间号',
  `schedule_date` date DEFAULT NULL COMMENT '手术日期',
  `schedule_time` varchar(20) DEFAULT NULL COMMENT '手术时间段',
  `start_time` datetime DEFAULT NULL COMMENT '实际开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '实际结束时间',
  `incision_time` datetime DEFAULT NULL COMMENT '切皮时间',
  `suture_time` datetime DEFAULT NULL COMMENT '缝合时间',
  `asa_grade` tinyint DEFAULT NULL COMMENT 'ASA分级:1-5',
  `incision_type` tinyint DEFAULT NULL COMMENT '切口类型:1清洁 2清洁污染 3污染 4感染',
  `status` tinyint DEFAULT '1' COMMENT '状态:1申请 2排程 3术中 4术后 5完成 6取消',
  `dept_id` bigint DEFAULT NULL COMMENT '手术科室ID(his_dept.id)',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `approval_status` int DEFAULT '0' COMMENT '审批状态:0无需 1待审 2通过 3拒绝',
  `approval_doctor_id` bigint DEFAULT NULL COMMENT '审批医师ID(his_staff.id)',
  `safety_checklist` text COMMENT 'WHO手术安全核查JSON',
  `apply_id` bigint DEFAULT NULL COMMENT '手术申请单ID(his_surgery_apply.id)',
  `visit_type` tinyint DEFAULT '1' COMMENT '就诊类型:1住院 2门诊 3日间',
  `visit_id` bigint DEFAULT NULL COMMENT '门诊就诊ID(his_visit.id, visit_type=2/3)',
  `register_time` datetime DEFAULT NULL COMMENT '手术室报到登记时间',
  `deadline_type` tinyint DEFAULT '1' COMMENT '手术时限:1择期 2限期 3急诊',
  `module_type` tinyint DEFAULT '1' COMMENT '一体化模块(预留):1手术室 2DSA 3产科分娩 4内镜 5麻醉治疗',
  PRIMARY KEY (`id`),
  KEY `idx_visit` (`inp_visit_id`,`status`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`),
  KEY `idx_surgeon` (`surgeon_id`),
  KEY `idx_schedule` (`tenant_id`,`org_id`,`schedule_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='手术记录主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_surgery_apply`
--

DROP TABLE IF EXISTS `his_surgery_apply`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_surgery_apply` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `apply_no` varchar(50) NOT NULL COMMENT '申请单号(SQ+yyyyMMdd+序号)',
  `visit_type` tinyint DEFAULT '1' COMMENT '就诊类型:1住院 2门诊 3日间',
  `inp_visit_id` bigint DEFAULT NULL COMMENT '住院就诊ID(visit_type=1)',
  `visit_id` bigint DEFAULT NULL COMMENT '门诊就诊ID(his_visit.id, visit_type=2/3)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名快照',
  `gender` varchar(10) DEFAULT NULL COMMENT '性别快照',
  `age` varchar(20) DEFAULT NULL COMMENT '年龄快照',
  `medical_no` varchar(50) DEFAULT NULL COMMENT '病历号/住院号快照',
  `bed_no` varchar(20) DEFAULT NULL COMMENT '床号快照',
  `phone` varchar(30) DEFAULT NULL COMMENT '联系电话',
  `apply_dept_id` bigint DEFAULT NULL COMMENT '申请科室ID',
  `apply_dept_name` varchar(100) DEFAULT NULL COMMENT '申请科室名称',
  `surgery_code` varchar(50) DEFAULT NULL COMMENT '手术编码ICD-9-CM-3',
  `surgery_name` varchar(200) NOT NULL COMMENT '手术名称',
  `surgery_level` tinyint DEFAULT NULL COMMENT '手术级别:1-4',
  `anesthesia_type` tinyint DEFAULT NULL COMMENT '拟麻醉方式:1全麻 2局麻 3椎管内 4神经阻滞 5复合 6其他',
  `surgeon_id` bigint DEFAULT NULL COMMENT '拟主刀医师ID(his_staff.id)',
  `surgeon_name` varchar(50) DEFAULT NULL COMMENT '拟主刀医师姓名',
  `expect_time` datetime DEFAULT NULL COMMENT '医生期望手术时间(精确到分)',
  `deadline_type` tinyint DEFAULT '1' COMMENT '手术时限:1择期 2限期 3急诊',
  `pre_op_diag` varchar(500) DEFAULT NULL COMMENT '术前诊断',
  `apply_reason` varchar(1000) DEFAULT NULL COMMENT '手术经过/病情简介',
  `special_req` varchar(500) DEFAULT NULL COMMENT '特殊要求(体位/特殊耗材等)',
  `apply_by_id` bigint DEFAULT NULL COMMENT '申请医师ID(his_staff.id)',
  `apply_by_name` varchar(50) DEFAULT NULL COMMENT '申请医师姓名',
  `apply_time` datetime DEFAULT NULL COMMENT '申请时间',
  `status` tinyint DEFAULT '1' COMMENT '状态:1待复核 2已复核待安排 3已退回 4已安排 5已完成 6已作废',
  `reconfirm_by` varchar(64) DEFAULT NULL COMMENT '复核护士',
  `reconfirm_time` datetime DEFAULT NULL COMMENT '复核时间',
  `reject_reason` varchar(500) DEFAULT NULL COMMENT '退回原因',
  `cancel_reason` varchar(500) DEFAULT NULL COMMENT '作废原因',
  `surgery_id` bigint DEFAULT NULL COMMENT '手术ID(his_surgery.id, 安排后回填)',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_apply_no` (`apply_no`,`deleted`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`visit_type`,`status`),
  KEY `idx_inp_visit` (`inp_visit_id`),
  KEY `idx_visit` (`visit_id`),
  KEY `idx_surgery` (`surgery_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='手术申请单';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_surgery_auth_rule`
--

DROP TABLE IF EXISTS `his_surgery_auth_rule`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_surgery_auth_rule` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `rule_name` varchar(100) NOT NULL COMMENT '规则名称',
  `rule_type` tinyint NOT NULL COMMENT '规则类型:1按手术等级 2按自定义分类',
  `surgery_level` tinyint DEFAULT NULL COMMENT '等级模式:受限最低手术级别(≥该级需权限校验)',
  `surgery_codes` text COMMENT '自定义模式:手术编码/名称逗号清单',
  `allow_staff_ids` text COMMENT '允许主刀的职工ID清单(his_staff.id, 逗号分隔)',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `status` tinyint DEFAULT '1' COMMENT '状态:1启用 0停用',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_org_type` (`tenant_id`,`org_id`,`rule_type`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='手术操作权限规则';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_surgery_fee`
--

DROP TABLE IF EXISTS `his_surgery_fee`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_surgery_fee` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `surgery_id` bigint NOT NULL COMMENT '手术ID(his_surgery.id)',
  `inp_visit_id` bigint DEFAULT NULL COMMENT '住院就诊ID(门诊/日间手术费用改双写 his_order_item)',
  `charge_item_id` bigint DEFAULT NULL COMMENT '收费项目ID',
  `item_name` varchar(200) NOT NULL COMMENT '项目名称',
  `item_code` varchar(50) DEFAULT NULL COMMENT '项目编码',
  `fee_category` tinyint DEFAULT NULL COMMENT '费用分类:1手术费 2麻醉费 3监测费 4耗材费 5药品费 6其他',
  `quantity` decimal(10,2) DEFAULT '1.00' COMMENT '数量',
  `unit_price` decimal(10,2) NOT NULL COMMENT '单价',
  `amount` decimal(12,2) NOT NULL COMMENT '金额',
  `charge_time` datetime DEFAULT NULL COMMENT '记账时间',
  `auto_flag` tinyint DEFAULT '0' COMMENT '自动计时:1是 0手动',
  `duration_minutes` int DEFAULT NULL COMMENT '计时分钟数',
  `operator_id` bigint DEFAULT NULL COMMENT '操作员ID(his_staff.id)',
  `status` tinyint DEFAULT '1' COMMENT '状态:1正常 2退费',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `visit_type` tinyint DEFAULT '1' COMMENT '就诊类型:1住院 2门诊 3日间',
  `visit_id` bigint DEFAULT NULL COMMENT '门诊就诊ID(his_visit.id)',
  `order_id` bigint DEFAULT NULL COMMENT '门诊双写单据ID(his_order.id)',
  `order_item_id` bigint DEFAULT NULL COMMENT '门诊双写明细ID(his_order_item.id)',
  PRIMARY KEY (`id`),
  KEY `idx_surgery` (`surgery_id`,`fee_category`),
  KEY `idx_visit` (`inp_visit_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='手术费用明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_surgery_fee_tpl`
--

DROP TABLE IF EXISTS `his_surgery_fee_tpl`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_surgery_fee_tpl` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `tpl_name` varchar(100) NOT NULL COMMENT '模板名称',
  `tpl_level` tinyint DEFAULT '1' COMMENT '级别:1个人 2科室 3全院',
  `owner_staff_id` bigint DEFAULT NULL COMMENT '归属职工ID(个人级)',
  `dept_id` bigint DEFAULT NULL COMMENT '归属科室ID(科室级)',
  `surgery_code` varchar(50) DEFAULT NULL COMMENT '关联手术编码(可空=通用模板)',
  `surgery_name` varchar(200) DEFAULT NULL COMMENT '关联手术名称',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `status` tinyint DEFAULT '1' COMMENT '状态:1启用 0停用',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_org_level` (`tenant_id`,`org_id`,`tpl_level`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='手术费用模板';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_surgery_fee_tpl_item`
--

DROP TABLE IF EXISTS `his_surgery_fee_tpl_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_surgery_fee_tpl_item` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `tpl_id` bigint NOT NULL COMMENT '模板ID(his_surgery_fee_tpl.id)',
  `charge_item_id` bigint DEFAULT NULL COMMENT '收费项目ID',
  `item_name` varchar(200) NOT NULL COMMENT '项目名称',
  `item_code` varchar(50) DEFAULT NULL COMMENT '项目编码',
  `fee_category` tinyint DEFAULT '1' COMMENT '费用分类:1手术费 2麻醉费 3监测费 4耗材费 5药品费 6其他',
  `quantity` decimal(10,2) DEFAULT '1.00' COMMENT '数量',
  `unit_price` decimal(10,2) DEFAULT NULL COMMENT '单价',
  `amount` decimal(12,2) DEFAULT NULL COMMENT '金额',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tpl` (`tpl_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='手术费用模板明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_surgery_material`
--

DROP TABLE IF EXISTS `his_surgery_material`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_surgery_material` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `surgery_id` bigint NOT NULL COMMENT '手术ID(his_surgery.id)',
  `material_name` varchar(200) NOT NULL COMMENT '耗材名称',
  `material_code` varchar(50) DEFAULT NULL COMMENT '耗材编码',
  `spec` varchar(100) DEFAULT NULL COMMENT '规格',
  `batch_no` varchar(50) DEFAULT NULL COMMENT '批号',
  `quantity` decimal(10,2) DEFAULT '1.00' COMMENT '数量',
  `unit_price` decimal(10,2) DEFAULT NULL COMMENT '单价',
  `amount` decimal(12,2) DEFAULT NULL COMMENT '金额',
  `supplier` varchar(200) DEFAULT NULL COMMENT '供应商',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_surgery` (`surgery_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='手术耗材';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_surgery_module_ext`
--

DROP TABLE IF EXISTS `his_surgery_module_ext`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_surgery_module_ext` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `surgery_id` bigint NOT NULL COMMENT '手术ID(his_surgery.id)',
  `module_type` tinyint DEFAULT NULL COMMENT '一体化模块:1手术室 2DSA 3产科分娩 4内镜 5麻醉治疗',
  `dsa_equipment` varchar(50) DEFAULT NULL COMMENT 'DSA造影设备',
  `dsa_contrast` varchar(100) DEFAULT NULL COMMENT 'DSA对比剂',
  `dsa_radiation_dose` decimal(10,2) DEFAULT NULL COMMENT 'DSA辐射剂量(mGy)',
  `endo_scope_type` varchar(50) DEFAULT NULL COMMENT '内镜镜种',
  `endo_biopsy_cnt` int DEFAULT NULL COMMENT '内镜活检数',
  `obst_gestational_week` varchar(20) DEFAULT NULL COMMENT '产科孕周',
  `obst_birth_type` tinyint DEFAULT NULL COMMENT '分娩方式:1顺产 2剖宫产 3产钳',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ext_surgery` (`surgery_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='手术一体化专属字段(P4b 旁挂)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_surgery_notify`
--

DROP TABLE IF EXISTS `his_surgery_notify`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_surgery_notify` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `apply_id` bigint DEFAULT NULL COMMENT '申请单ID(可空)',
  `surgery_id` bigint DEFAULT NULL COMMENT '手术ID(可空)',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名',
  `phone` varchar(30) DEFAULT NULL COMMENT '联系电话',
  `notify_type` tinyint NOT NULL COMMENT '通知类型:1预约成功 2安排变动 3术前提醒',
  `channel` tinyint DEFAULT '1' COMMENT '渠道:1短信 2电话 3诊间',
  `content` varchar(1000) DEFAULT NULL COMMENT '通知内容',
  `status` tinyint DEFAULT '1' COMMENT '状态:1待通知 2已通知 3已回复',
  `reply_content` varchar(500) DEFAULT NULL COMMENT '患者回复内容',
  `send_by` varchar(64) DEFAULT NULL COMMENT '发送/处理人',
  `send_time` datetime DEFAULT NULL COMMENT '发送时间',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `retry_count` int DEFAULT '0' COMMENT '通知下发重试次数',
  `gateway_msg_id` varchar(120) DEFAULT NULL COMMENT '短信/APP网关回执ID(真实通道回填)',
  `send_status` tinyint DEFAULT '0' COMMENT '下发送达状态:0待提交 1已提交网关 2已送达 3送达失败(P4a)',
  `delivered_time` datetime DEFAULT NULL COMMENT '送达时间(P4a 回查回填)',
  `error_msg` varchar(200) DEFAULT NULL COMMENT '下发/送达失败原因(P4a)',
  PRIMARY KEY (`id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`,`notify_type`),
  KEY `idx_apply` (`apply_id`),
  KEY `idx_surgery` (`surgery_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='手术通知记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_surgery_notify_log`
--

DROP TABLE IF EXISTS `his_surgery_notify_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_surgery_notify_log` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `notify_id` bigint NOT NULL COMMENT '通知ID(his_surgery_notify.id)',
  `phone` varchar(30) DEFAULT NULL COMMENT '接收号码快照',
  `channel` tinyint DEFAULT NULL COMMENT '渠道快照:1短信 2电话 3诊间 4自助机 5APP 6公众号',
  `content` varchar(500) DEFAULT NULL COMMENT '下发内容快照',
  `gateway_msg_id` varchar(120) DEFAULT NULL COMMENT '网关回执ID(Mock/Http 回填)',
  `send_status` tinyint DEFAULT '0' COMMENT '本行留痕状态:1已提交 2已送达 3失败',
  `error_msg` varchar(200) DEFAULT NULL COMMENT '失败原因',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_nlog_notify` (`notify_id`),
  KEY `idx_nlog_tenant_time` (`tenant_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='手术通知下发留痕(P4a outbox)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_surgery_pacu`
--

DROP TABLE IF EXISTS `his_surgery_pacu`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_surgery_pacu` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `surgery_id` bigint NOT NULL COMMENT '手术ID(his_surgery.id)',
  `inp_visit_id` bigint DEFAULT NULL COMMENT '住院就诊ID(his_inp_visit.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID快照(his_patient.id)',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名快照',
  `inp_no` varchar(30) DEFAULT NULL COMMENT '住院号快照',
  `admit_time` datetime DEFAULT NULL COMMENT '入复苏时间',
  `admit_nurse_id` bigint DEFAULT NULL COMMENT '入复苏护士ID(his_staff.id)',
  `admit_note` varchar(200) DEFAULT NULL COMMENT '入复苏备注',
  `aldrete_admit` tinyint DEFAULT NULL COMMENT '入复苏Aldrete评分(0-10)',
  `vitals_json` text COMMENT '生命体征时间点数组JSON[{t,hp,hr,p,s,tm}]',
  `status` tinyint DEFAULT '1' COMMENT '状态:1复苏中 2已出',
  `discharge_time` datetime DEFAULT NULL COMMENT '出复苏时间',
  `discharge_nurse_id` bigint DEFAULT NULL COMMENT '出复苏护士ID(his_staff.id)',
  `aldrete_discharge` tinyint DEFAULT NULL COMMENT '出复苏Aldrete评分(0-10, <9须填理由)',
  `discharge_dest` tinyint DEFAULT NULL COMMENT '出复苏去向:1回病房 2转ICU 3门诊随访',
  `discharge_note` varchar(200) DEFAULT NULL COMMENT '出复苏备注(Aldrete<9时为低分理由)',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_pacu_surgery` (`surgery_id`),
  KEY `idx_pacu_org_status` (`tenant_id`,`org_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='术毕复苏(PACU)单(P3b 独立旁路单据)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_surgery_room`
--

DROP TABLE IF EXISTS `his_surgery_room`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_surgery_room` (
  `id` bigint NOT NULL COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `room_code` varchar(30) NOT NULL COMMENT '资源编码(唯一)',
  `room_name` varchar(50) DEFAULT NULL COMMENT '资源名称',
  `room_type` tinyint DEFAULT '1' COMMENT '资源类型:1手术间 2DSA机房 3内镜室 4产房',
  `dept_id` bigint DEFAULT NULL COMMENT '归属科室ID',
  `status` tinyint DEFAULT '1' COMMENT '状态:1可用 0停用',
  `remark` varchar(200) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(64) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(64) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_room_code` (`tenant_id`,`room_code`),
  KEY `idx_room_org_type` (`org_id`,`room_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='手术/机房/内镜室/产房资源(P4b 一体化)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_transfer`
--

DROP TABLE IF EXISTS `his_transfer`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_transfer` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `transfer_no` varchar(30) NOT NULL COMMENT '调拨单号(DB+yyyyMMdd+4位)',
  `from_location_id` bigint DEFAULT NULL COMMENT '调出库位ID(药库或药房库存位)',
  `to_location_id` bigint DEFAULT NULL COMMENT '调入库位ID',
  `kind` varchar(24) DEFAULT NULL COMMENT '调拨类型:WH2PHARMACY/PHARMACY2PHARMACY/WH2WH',
  `status` tinyint DEFAULT '0' COMMENT '状态:0草稿 1待调出确认 2已调出 3已调入 -2已作废',
  `ship_by` varchar(50) DEFAULT NULL COMMENT '调出人',
  `ship_time` datetime DEFAULT NULL COMMENT '调出时间',
  `receive_by` varchar(50) DEFAULT NULL COMMENT '调入人',
  `receive_time` datetime DEFAULT NULL COMMENT '调入时间',
  `stock_out_id` bigint DEFAULT NULL COMMENT '调出出库单ID',
  `stock_in_id` bigint DEFAULT NULL COMMENT '调入入库单ID',
  `total_amount` decimal(12,2) DEFAULT '0.00' COMMENT '调拨金额合计',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_transfer_no` (`tenant_id`,`transfer_no`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存调拨单';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_transfer_item`
--

DROP TABLE IF EXISTS `his_transfer_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_transfer_item` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `transfer_id` bigint NOT NULL COMMENT '调拨单ID',
  `drug_catalog_id` bigint DEFAULT NULL COMMENT '药品目录ID',
  `drug_code` varchar(50) DEFAULT NULL COMMENT '药品编码(快照)',
  `drug_name` varchar(200) DEFAULT NULL COMMENT '药品名称(快照)',
  `spec` varchar(200) DEFAULT NULL COMMENT '规格(快照)',
  `batch_no` varchar(50) DEFAULT NULL COMMENT '批次号(随行调拨)',
  `qty` decimal(12,2) DEFAULT '0.00' COMMENT '调拨数量',
  `cost_price` decimal(12,4) DEFAULT NULL COMMENT '进价(快照)',
  `retail_price` decimal(12,4) DEFAULT NULL COMMENT '零售价(快照)',
  `amount` decimal(12,2) DEFAULT NULL COMMENT '小计金额',
  `create_time` datetime DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_transfer` (`transfer_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存调拨明细';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_treatment_equipment`
--

DROP TABLE IF EXISTS `his_treatment_equipment`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_treatment_equipment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `equip_code` varchar(50) NOT NULL COMMENT '设备编码',
  `equip_name` varchar(100) NOT NULL COMMENT '设备名称',
  `equip_type` varchar(50) DEFAULT NULL COMMENT '设备类型(理疗/康复/中医传统)',
  `dept_id` bigint DEFAULT NULL COMMENT '归属科室ID(his_dept.id)',
  `status` tinyint DEFAULT '1' COMMENT '状态:1正常 0停用',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_org_equip` (`tenant_id`,`org_id`,`equip_code`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org` (`tenant_id`,`org_id`)
) ENGINE=InnoDB AUTO_INCREMENT=15 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='治疗设备(机构级台账)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_treatment_exec`
--

DROP TABLE IF EXISTS `his_treatment_exec`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_treatment_exec` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `exec_no` varchar(30) NOT NULL COMMENT '治疗执行单号(ZX+日期+序号)',
  `plan_id` bigint DEFAULT NULL COMMENT '治疗计划ID(his_treatment_plan.id)',
  `order_id` bigint DEFAULT NULL COMMENT '医嘱单ID(his_order.id)',
  `order_item_id` bigint DEFAULT NULL COMMENT '医嘱明细ID(his_order_item.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `session_index` int DEFAULT NULL COMMENT '疗程内序次(第几次)',
  `exec_date` date DEFAULT NULL COMMENT '执行日期',
  `exec_therapist_id` bigint DEFAULT NULL COMMENT '治疗师ID(his_staff.id)',
  `equipment_code` varchar(50) DEFAULT NULL COMMENT '设备编码(his_treatment_equipment.equip_code)',
  `duration_min` int DEFAULT NULL COMMENT '治疗时长(分钟)',
  `parameters` text COMMENT '治疗参数(JSON)',
  `patient_response` varchar(500) DEFAULT NULL COMMENT '患者反应',
  `checkin_time` datetime DEFAULT NULL COMMENT '患者签到时间(非空=已签到, 排队口径)',
  `cancel_reason` varchar(200) DEFAULT NULL COMMENT '取消原因',
  `exec_status` tinyint DEFAULT '0' COMMENT '执行状态:0待执行 1执行中 2已完成 3已取消',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_texec_no` (`tenant_id`,`exec_no`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`exec_status`),
  KEY `idx_plan` (`plan_id`),
  KEY `idx_patient` (`tenant_id`,`patient_id`)
) ENGINE=InnoDB AUTO_INCREMENT=42 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='治疗执行记录(逐次留痕)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_treatment_plan`
--

DROP TABLE IF EXISTS `his_treatment_plan`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_treatment_plan` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `plan_no` varchar(30) NOT NULL COMMENT '治疗计划号(ZL+日期+序号)',
  `visit_id` bigint DEFAULT NULL COMMENT '就诊ID(his_visit.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `doctor_id` bigint DEFAULT NULL COMMENT '开单医师ID(his_staff.id)',
  `order_id` bigint NOT NULL COMMENT '医嘱单ID(his_order.id)',
  `item_code` varchar(50) DEFAULT NULL COMMENT '收费项目编码(his_charge_item.item_code)',
  `item_name` varchar(100) NOT NULL COMMENT '治疗项目名称',
  `category` varchar(20) DEFAULT NULL COMMENT '治疗类别:physiotherapy理疗/rehab康复/tcm中医传统',
  `total_sessions` int NOT NULL DEFAULT '1' COMMENT '总次数(疗程)',
  `completed_sessions` int DEFAULT '0' COMMENT '已完成次数',
  `frequency` varchar(50) DEFAULT NULL COMMENT '频次(如每日1次/隔日1次)',
  `start_date` date DEFAULT NULL COMMENT '开始日期',
  `expire_date` date DEFAULT NULL COMMENT '失效日期',
  `status` tinyint DEFAULT '0' COMMENT '状态:0执行中 1已完成 2已终止',
  `terminate_reason` varchar(200) DEFAULT NULL COMMENT '终止原因',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_plan_no` (`tenant_id`,`plan_no`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`),
  KEY `idx_order` (`order_id`),
  KEY `idx_patient` (`tenant_id`,`patient_id`)
) ENGINE=InnoDB AUTO_INCREMENT=25 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='治疗计划(疗程医嘱)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_upload_status`
--

DROP TABLE IF EXISTS `his_upload_status`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_upload_status` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '租户(医共体)ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `biz_type` varchar(10) DEFAULT NULL COMMENT '业务类型: REG/VISIT/RX/FEE/SETL/CANCEL',
  `biz_id` bigint DEFAULT NULL COMMENT '业务主键(如就诊ID)',
  `mdtrt_id` varchar(30) DEFAULT NULL COMMENT '医保就诊ID',
  `status` tinyint DEFAULT '0' COMMENT '状态: 0待传 1已传 2失败待补 3已撤销',
  `retry_count` int DEFAULT '0' COMMENT '已重试次数(max 6 次转人工)',
  `next_retry` datetime DEFAULT NULL COMMENT '下次重试时间(指数退避)',
  `last_err` varchar(500) DEFAULT NULL COMMENT '最近失败原因',
  `msgid` varchar(40) DEFAULT NULL COMMENT '成功报文ID(回执)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz` (`tenant_id`,`biz_type`,`biz_id`),
  KEY `idx_retry` (`status`,`retry_count`,`next_retry`)
) ENGINE=InnoDB AUTO_INCREMENT=12449 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医保上传状态机(批次4 M5: 上传管线逐单状态)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_user_display_pref`
--

DROP TABLE IF EXISTS `his_user_display_pref`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_user_display_pref` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `user_id` bigint NOT NULL COMMENT '用户ID(sys_user.id)',
  `scene` varchar(50) NOT NULL COMMENT '场景: dw_banner患者信息栏/dw_layout布局偏好',
  `config_json` text COMMENT '显示配置JSON(字段开关与顺序)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_user_scene` (`tenant_id`,`user_id`,`scene`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医生站用户显示偏好';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_val_dict`
--

DROP TABLE IF EXISTS `his_val_dict`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_val_dict` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户(医共体)ID',
  `dict_type` varchar(64) NOT NULL COMMENT '值域类别=标准源键:分组码, 如 cv_code:gend / hbvalue:HBCV08.50.029',
  `type_name` varchar(200) DEFAULT NULL COMMENT '值域中文名(导入时取标准字典组名)',
  `code` varchar(64) NOT NULL COMMENT '值编码(租户内同类唯一, 导入取标准值域码)',
  `name` varchar(200) NOT NULL COMMENT '值名称',
  `yb_code` varchar(64) DEFAULT NULL COMMENT '医保值域编码(cv_code 源导入时=code; 卫健/湖北源留空)',
  `sort_no` int DEFAULT '0' COMMENT '排序号',
  `status` tinyint DEFAULT '1' COMMENT '状态:1启用 0停用',
  `memo` varchar(500) DEFAULT NULL COMMENT '备注',
  `src_type` varchar(30) DEFAULT NULL COMMENT '来源标准字典key(cv_code/wst364/hbvalue/whvalue)',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `src_code` varchar(64) DEFAULT NULL COMMENT '来源编码(标准值域行编码)',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  `abbr_code` varchar(64) DEFAULT NULL COMMENT '自定义简码(人工维护, 选填)',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_val_type_code` (`tenant_id`,`dict_type`,`code`),
  KEY `idx_vd_type` (`dict_type`),
  KEY `idx_vd_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=1725 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医共体值域字典(业务自由值域统一取数源, 牵头机构维护)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_visit`
--

DROP TABLE IF EXISTS `his_visit`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_visit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '就诊ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `registration_id` bigint DEFAULT NULL COMMENT '挂号记录ID',
  `reg_no` varchar(30) DEFAULT NULL COMMENT '挂号单号',
  `mdtrt_id` varchar(30) DEFAULT NULL COMMENT '医保就诊ID',
  `ipt_otp_no` varchar(30) DEFAULT NULL COMMENT '院内就诊流水号',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `patient_no` varchar(30) DEFAULT NULL COMMENT '院内患者号',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名',
  `gender` varchar(4) DEFAULT NULL COMMENT '性别',
  `age` int DEFAULT NULL COMMENT '年龄',
  `psn_no` varchar(30) DEFAULT NULL COMMENT '医保人员编号',
  `insutype` varchar(10) DEFAULT NULL COMMENT '险种类型',
  `dept_id` bigint DEFAULT NULL COMMENT '科室ID',
  `dept_code` varchar(30) DEFAULT NULL COMMENT '科室编码',
  `dept_name` varchar(100) DEFAULT NULL COMMENT '科室名称',
  `staff_id` bigint DEFAULT NULL COMMENT '医师ID',
  `atddr_no` varchar(30) DEFAULT NULL COMMENT '医师医保编码',
  `dr_name` varchar(50) DEFAULT NULL COMMENT '医师姓名',
  `work_date` date DEFAULT NULL COMMENT '就诊日期',
  `visit_status` tinyint DEFAULT '1' COMMENT '就诊状态:1-候诊 2-接诊中 3-已完成 4-已取消',
  `chief_complaint` varchar(500) DEFAULT NULL COMMENT '主诉',
  `present_illness` varchar(1000) DEFAULT NULL COMMENT '现病史',
  `past_history` varchar(1000) DEFAULT NULL COMMENT '既往史',
  `physical_exam` varchar(1000) DEFAULT NULL COMMENT '体格检查',
  `treatment_opinion` varchar(1000) DEFAULT NULL COMMENT '处理意见',
  `visit_time` datetime DEFAULT NULL COMMENT '接诊时间',
  `finish_time` datetime DEFAULT NULL COMMENT '完成时间',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `charge_status` tinyint DEFAULT '0' COMMENT '收费状态:0未收费 1已收费 2已退费',
  `queue_no` varchar(20) DEFAULT NULL COMMENT '候诊序号(自挂号记录同步)',
  `med_type` varchar(10) DEFAULT NULL COMMENT '医疗类别(2203 medType, 自挂号同步: 11普通门诊 14急诊)',
  `allergy_history` varchar(500) DEFAULT NULL COMMENT '过敏史',
  `aux_exam` varchar(1000) DEFAULT NULL COMMENT '辅助检查',
  `dise_type_code` varchar(20) DEFAULT NULL COMMENT '病种类型代码(2203 mdtrtinfo.dise_type_code)',
  `birctrl_type` varchar(10) DEFAULT NULL COMMENT '计划生育手术类别(2203 mdtrtinfo.birctrl_type)',
  `birctrl_matn_date` date DEFAULT NULL COMMENT '计划生育手术或生育日期(2203 mdtrtinfo.birctrl_matn_date)',
  `followup_date` date DEFAULT NULL COMMENT '随访日期',
  `followup_note` varchar(500) DEFAULT NULL COMMENT '随访备注',
  `structure` text COMMENT '结构化病历JSON(his_emr_template scope=2 fields 取值)',
  `emr_template_id` bigint DEFAULT NULL COMMENT '结构化病历模板ID(his_emr_template.id)',
  `disposition` tinyint DEFAULT NULL COMMENT '诊后去向:1离院 2转科 3转留观 4转院',
  `disposition_dept_id` bigint DEFAULT NULL COMMENT '转科目标科室ID(his_dept.id)',
  `disposition_note` varchar(500) DEFAULT NULL COMMENT '去向备注',
  `content` longtext COMMENT 'AES-256加密Tiptap JSON文档',
  `emr_format` tinyint DEFAULT '0' COMMENT '病历格式:0扁平JSON旧格式 1Tiptap',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_registration` (`registration_id`),
  KEY `idx_patient` (`patient_id`),
  KEY `idx_status_date` (`visit_status`,`work_date`),
  KEY `idx_staff` (`staff_id`)
) ENGINE=InnoDB AUTO_INCREMENT=20645 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='就诊记录表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_vital_sign`
--

DROP TABLE IF EXISTS `his_vital_sign`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_vital_sign` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint DEFAULT NULL COMMENT '机构ID',
  `visit_id` bigint DEFAULT NULL COMMENT '就诊ID(his_visit.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `systolic` int DEFAULT NULL COMMENT '收缩压(mmHg)',
  `diastolic` int DEFAULT NULL COMMENT '舒张压(mmHg)',
  `pulse` int DEFAULT NULL COMMENT '脉搏(次/分)',
  `temperature` decimal(4,1) DEFAULT NULL COMMENT '体温(℃)',
  `respiration` int DEFAULT NULL COMMENT '呼吸(次/分)',
  `blood_glucose` decimal(6,2) DEFAULT NULL COMMENT '血糖(mmol/L)',
  `blood_ketone` decimal(6,2) DEFAULT NULL COMMENT '血酮(mmol/L)',
  `weight` decimal(6,2) DEFAULT NULL COMMENT '体重(kg)',
  `height` decimal(6,2) DEFAULT NULL COMMENT '身高(cm)',
  `source` varchar(20) DEFAULT '手工' COMMENT '来源:设备/手工',
  `meas_time` datetime DEFAULT NULL COMMENT '测量时间',
  `recorder_id` bigint DEFAULT NULL COMMENT '录入人ID',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_patient_meas` (`tenant_id`,`patient_id`,`meas_time`),
  KEY `idx_visit` (`tenant_id`,`visit_id`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='生命体征(含血糖血酮趋势源)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_ward`
--

DROP TABLE IF EXISTS `his_ward`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_ward` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `ward_name` varchar(100) NOT NULL COMMENT '病区名称',
  `ward_code` varchar(50) DEFAULT NULL COMMENT '病区编码',
  `dept_id` bigint DEFAULT NULL COMMENT '关联科室ID(his_dept.id)',
  `building` varchar(100) DEFAULT NULL COMMENT '楼栋',
  `floor` varchar(20) DEFAULT NULL COMMENT '楼层',
  `bed_count` int DEFAULT '0' COMMENT '床位数',
  `status` tinyint DEFAULT '1' COMMENT '状态:1启用 0停用',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_org_wardcode` (`tenant_id`,`org_id`,`ward_code`),
  KEY `idx_org_status` (`tenant_id`,`org_id`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院病区';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_ward_staging`
--

DROP TABLE IF EXISTS `his_ward_staging`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_ward_staging` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `inp_visit_id` bigint NOT NULL COMMENT '住院就诊ID',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `patient_name` varchar(50) DEFAULT NULL COMMENT '患者姓名',
  `ward_id` bigint DEFAULT NULL COMMENT '病区ID',
  `ward_name` varchar(100) DEFAULT NULL COMMENT '病区名称',
  `drug_catalog_id` bigint NOT NULL COMMENT '药品目录ID',
  `drug_code` varchar(50) DEFAULT NULL COMMENT '药品编码',
  `drug_name` varchar(200) DEFAULT NULL COMMENT '药品名称',
  `spec` varchar(100) DEFAULT NULL COMMENT '规格',
  `unit` varchar(20) DEFAULT NULL COMMENT '最小单位',
  `staged_qty` decimal(12,4) DEFAULT '0.0000' COMMENT '暂存量(未退回可冲抵)',
  `used_qty` decimal(12,4) DEFAULT '0.0000' COMMENT '已冲抵量',
  `source_dispense_id` bigint DEFAULT NULL COMMENT '来源发药记录ID',
  `source_return_id` bigint DEFAULT NULL COMMENT '来源退药单ID',
  `status` tinyint DEFAULT '0' COMMENT '状态:0有效 1已冲抵完',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_visit_drug` (`inp_visit_id`,`drug_catalog_id`,`status`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='住院病区暂存冲抵台账';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_warehouse_def`
--

DROP TABLE IF EXISTS `his_warehouse_def`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_warehouse_def` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `code` varchar(40) NOT NULL COMMENT '仓库编码(如WH-WEST-01)',
  `name` varchar(100) NOT NULL COMMENT '仓库名称(如西药库)',
  `warehouse_type` varchar(20) DEFAULT NULL COMMENT '仓库类型:WESTERN/TCM/MIXED',
  `location` varchar(200) DEFAULT NULL COMMENT '库房位置',
  `manager` varchar(50) DEFAULT NULL COMMENT '负责人',
  `status` tinyint DEFAULT '1' COMMENT '状态:1启用 0停用',
  `sort_no` int DEFAULT '0' COMMENT '排序号',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `kind` varchar(20) NOT NULL DEFAULT 'WAREHOUSE' COMMENT '库存位类型:WAREHOUSE药库/PHARMACY药房库位',
  `ref_pharmacy_id` bigint DEFAULT NULL COMMENT 'PHARMACY型回指药房ID(his_pharmacy_def.id)',
  `dept_id` bigint DEFAULT NULL COMMENT '归属科室(his_dept.id, 仅kind=WAREHOUSE; 一一对应; 空=历史未绑定)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_org_code` (`tenant_id`,`org_id`,`code`),
  UNIQUE KEY `uk_dept` (`tenant_id`,`dept_id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_org` (`tenant_id`,`org_id`)
) ENGINE=InnoDB AUTO_INCREMENT=24 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='药库定义(机构级多药库)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_window_dept_rule`
--

DROP TABLE IF EXISTS `his_window_dept_rule`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_window_dept_rule` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `dept_id` bigint NOT NULL COMMENT '开单科室ID(his_dept.id)',
  `window_id` bigint NOT NULL COMMENT '定向窗口ID(his_pharmacy_window.id)',
  `remark` varchar(200) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_dept_window` (`tenant_id`,`dept_id`,`window_id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_dept` (`tenant_id`,`dept_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='开单科室→窗口定向规则';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_window_signin`
--

DROP TABLE IF EXISTS `his_window_signin`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_window_signin` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `window_id` bigint NOT NULL COMMENT '窗口ID(his_pharmacy_window.id)',
  `pharmacy_id` bigint DEFAULT NULL COMMENT '药房ID(his_pharmacy_def.id)',
  `patient_id` bigint DEFAULT NULL COMMENT '患者ID',
  `visit_id` bigint DEFAULT NULL COMMENT '就诊ID',
  `prescription_id` bigint DEFAULT NULL COMMENT '处方ID',
  `signin_no` varchar(40) DEFAULT NULL COMMENT '签到凭证号(条码/刷卡/发票号)',
  `signin_status` tinyint DEFAULT '1' COMMENT '签到状态:1已签到 0已取消',
  `signin_by` varchar(50) DEFAULT NULL COMMENT '签到操作人',
  `signin_time` datetime DEFAULT NULL COMMENT '签到时间',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_window_patient` (`tenant_id`,`window_id`,`patient_id`,`signin_status`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发药窗口患者签到';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_window_workstation`
--

DROP TABLE IF EXISTS `his_window_workstation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_window_workstation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `org_id` bigint NOT NULL COMMENT '机构ID',
  `window_id` bigint NOT NULL COMMENT '窗口ID(his_pharmacy_window.id)',
  `user_id` bigint DEFAULT NULL COMMENT '关联登录用户ID(sys_user.id)',
  `staff_id` bigint DEFAULT NULL COMMENT '关联职工ID(his_staff.id)',
  `remark` varchar(200) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_window_user` (`tenant_id`,`window_id`,`user_id`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_window` (`tenant_id`,`window_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='发药工作站↔窗口关联';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_yb_map_log`
--

DROP TABLE IF EXISTS `his_yb_map_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_yb_map_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL COMMENT '租户(医共体)ID',
  `catalog_type` varchar(20) NOT NULL COMMENT '目录类型:charge/drug/cons',
  `catalog_id` bigint NOT NULL COMMENT '院内条目ID',
  `item_code` varchar(64) DEFAULT NULL COMMENT '院内编码(冗余)',
  `item_name` varchar(200) DEFAULT NULL COMMENT '院内名称(冗余)',
  `old_code` varchar(64) DEFAULT NULL COMMENT '变更前医保码(空=首次对照)',
  `new_code` varchar(64) DEFAULT NULL COMMENT '变更后医保码(空=清除对照)',
  `change_type` varchar(20) NOT NULL COMMENT '变更类型:MAP新增对照/CHANGE变更对照/CLEAR清除对照',
  `score` double DEFAULT NULL COMMENT '匹配置信度(自动对照)',
  `src` varchar(20) DEFAULT NULL COMMENT '对照方式:manual人工/auto自动',
  `operator` varchar(50) DEFAULT NULL COMMENT '操作人(登录账号)',
  `operator_name` varchar(50) DEFAULT NULL COMMENT '操作人姓名',
  `org_id` bigint DEFAULT NULL COMMENT '操作人归属机构',
  `change_time` datetime NOT NULL COMMENT '变更发生时间(即新对照生效时间)',
  `memo` varchar(200) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_ymlog_tenant` (`tenant_id`),
  KEY `idx_ymlog_item` (`catalog_type`,`catalog_id`),
  KEY `idx_ymlog_time` (`change_time`)
) ENGINE=InnoDB AUTO_INCREMENT=22386 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医保对照变更留痕表(生效时间+变更前医保码可回溯)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_yb_txn_log`
--

DROP TABLE IF EXISTS `his_yb_txn_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_yb_txn_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint DEFAULT NULL COMMENT '租户(医共体)ID',
  `org_id` bigint DEFAULT NULL COMMENT '发起机构ID',
  `infno` varchar(10) NOT NULL COMMENT '医保交易编号(2204/2206/2207/2208/2601/3201/3202/9101等)',
  `msgid` varchar(50) NOT NULL COMMENT '发送方报文ID(机构编号12+时间14+顺序号4)',
  `mdtrt_id` varchar(50) DEFAULT NULL COMMENT '医保就诊ID(自input抽取)',
  `psn_no` varchar(50) DEFAULT NULL COMMENT '人员编号(自input抽取)',
  `setl_id` varchar(50) DEFAULT NULL COMMENT '结算ID(2207/2208响应回填)',
  `chrg_bchno` varchar(50) DEFAULT NULL COMMENT '收费批次号(自input抽取)',
  `bill_id` bigint DEFAULT NULL COMMENT '关联院内收费单ID',
  `status` varchar(10) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待回执/SUCCESS成功/FAIL明确失败/UNKNOWN结果未知',
  `err_msg` varchar(500) DEFAULT NULL COMMENT '平台错误信息',
  `input_json` mediumtext COMMENT '请求报文(含input节点)',
  `output_json` mediumtext COMMENT '响应报文',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_ybtxn_tenant` (`tenant_id`),
  KEY `idx_ybtxn_msgid` (`msgid`),
  KEY `idx_ybtxn_setl` (`setl_id`),
  KEY `idx_ybtxn_mdtrt` (`mdtrt_id`),
  KEY `idx_ybtxn_infno` (`infno`)
) ENGINE=InnoDB AUTO_INCREMENT=57569 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医保出站交易日志(批次4: 结果三分与2601冲正凭据)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `his_yb_upload_queue`
--

DROP TABLE IF EXISTS `his_yb_upload_queue`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `his_yb_upload_queue` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '租户(医共体)ID',
  `catalog_type` varchar(10) DEFAULT NULL COMMENT '目录类型: charge/drug/cons',
  `catalog_id` bigint DEFAULT NULL COMMENT '院内条目ID',
  `item_code` varchar(100) DEFAULT NULL COMMENT '院内编码=fixmedins_hilist_id',
  `item_name` varchar(200) DEFAULT NULL COMMENT '院内名称=fixmedins_hilist_name',
  `list_type` varchar(30) DEFAULT NULL COMMENT '目录类别(3301/3302 必填, 与平台确认后配置)',
  `old_code` varchar(50) DEFAULT NULL COMMENT '变更前医保码(撤销用)',
  `new_code` varchar(50) DEFAULT NULL COMMENT '变更后医保码(上传用)',
  `action` varchar(10) DEFAULT NULL COMMENT '动作: MAP/CHANGE/CLEAR',
  `status` tinyint DEFAULT '0' COMMENT '状态: 0待传 1已传 2失败',
  `batch_no` varchar(40) DEFAULT NULL COMMENT '上传批次号(平台回执报文ID)',
  `upload_time` datetime DEFAULT NULL COMMENT '上传时间',
  `last_err` varchar(500) DEFAULT NULL COMMENT '失败原因',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_upload_status` (`tenant_id`,`status`),
  KEY `idx_upload_catalog` (`tenant_id`,`catalog_type`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=123 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医保对照上传队列(批次4 M4: 3301/3302)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `med_service_catalog`
--

DROP TABLE IF EXISTS `med_service_catalog`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `med_service_catalog` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '绉熸埛ID',
  `med_list_codg` varchar(50) DEFAULT NULL COMMENT '医疗目录编码',
  `prcunt` varchar(50) DEFAULT NULL COMMENT '计价单位',
  `prcunt_name` varchar(100) DEFAULT NULL COMMENT '计价单位名称',
  `item_explain` varchar(2000) DEFAULT NULL COMMENT '诊疗项目说明',
  `item_excluded` varchar(1000) DEFAULT NULL COMMENT '诊疗除外内容',
  `item_connotation` varchar(2000) DEFAULT NULL COMMENT '诊疗项目内涵',
  `vali_flag` varchar(3) DEFAULT NULL COMMENT '有效标志',
  `memo` varchar(500) DEFAULT NULL COMMENT '备注',
  `item_cat` varchar(50) DEFAULT NULL COMMENT '服务项目类别',
  `item_name` varchar(500) DEFAULT NULL COMMENT '医疗服务项目名称',
  `item_explain2` varchar(2000) DEFAULT NULL COMMENT '项目说明',
  `rid` varchar(40) DEFAULT NULL COMMENT '唯一记录号',
  `ver` varchar(30) DEFAULT NULL COMMENT '版本号',
  `ver_name` varchar(100) DEFAULT NULL COMMENT '版本名称',
  `raw_data` longtext COMMENT '原始数据行(TAB分隔)',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(项目名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_med_list_codg` (`med_list_codg`),
  KEY `idx_ver` (`ver`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医疗服务项目目录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `preparation_catalog`
--

DROP TABLE IF EXISTS `preparation_catalog`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `preparation_catalog` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '绉熸埛ID',
  `med_list_codg` varchar(50) DEFAULT NULL COMMENT '医疗目录编码',
  `drug_prodname` varchar(500) DEFAULT NULL COMMENT '药品商品名',
  `alis` varchar(200) DEFAULT NULL COMMENT '别名',
  `dosform` varchar(50) DEFAULT NULL COMMENT '剂型',
  `dosform_name` varchar(100) DEFAULT NULL COMMENT '剂型名称',
  `ing` varchar(1000) DEFAULT NULL COMMENT '成分',
  `efcc_atd` varchar(1000) DEFAULT NULL COMMENT '功能主治',
  `drug_spec` varchar(255) DEFAULT NULL COMMENT '药品规格',
  `drug_type` varchar(20) DEFAULT NULL COMMENT '药品类别',
  `drug_type_name` varchar(100) DEFAULT NULL COMMENT '药品类别名称',
  `prod_entp_name` varchar(200) DEFAULT NULL COMMENT '生产企业名称',
  `vali_flag` varchar(3) DEFAULT NULL COMMENT '有效标志',
  `rid` varchar(40) DEFAULT NULL COMMENT '唯一记录号',
  `ver` varchar(30) DEFAULT NULL COMMENT '版本号',
  `ver_name` varchar(100) DEFAULT NULL COMMENT '版本名称',
  `raw_data` longtext COMMENT '原始数据行(TAB分隔)',
  PRIMARY KEY (`id`),
  KEY `idx_med_list_codg` (`med_list_codg`),
  KEY `idx_ver` (`ver`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医疗机构制剂目录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `setl_record`
--

DROP TABLE IF EXISTS `setl_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `setl_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '绉熸埛ID',
  `setl_id` varchar(30) DEFAULT NULL COMMENT '结算ID',
  `mdtrt_id` varchar(30) DEFAULT NULL COMMENT '就诊ID',
  `psn_no` varchar(30) DEFAULT NULL COMMENT '人员编号',
  `psn_name` varchar(50) DEFAULT NULL COMMENT '人员姓名',
  `insutype` varchar(6) DEFAULT NULL COMMENT '险种类型',
  `med_type` varchar(6) DEFAULT NULL COMMENT '医疗类别',
  `biz_type` varchar(20) DEFAULT NULL COMMENT '业务类型 outpatient/inpatient',
  `infno` varchar(10) DEFAULT NULL COMMENT '交易编号',
  `setl_time` varchar(30) DEFAULT NULL COMMENT '结算时间',
  `medfee_sumamt` decimal(16,2) DEFAULT NULL COMMENT '医疗费总额',
  `fund_pay_sumamt` decimal(16,2) DEFAULT NULL COMMENT '基金支付总额',
  `psn_part_amt` decimal(16,2) DEFAULT NULL COMMENT '个人负担总金额',
  `acct_pay` decimal(16,2) DEFAULT NULL COMMENT '个人账户支出',
  `psn_cash_pay` decimal(16,2) DEFAULT NULL COMMENT '个人现金支出',
  `status` varchar(3) DEFAULT '1' COMMENT '状态 1-已结算 0-已撤销',
  `setlinfo_json` longtext COMMENT '结算信息原始JSON',
  `crte_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_setl_id` (`setl_id`),
  KEY `idx_mdtrt_id` (`mdtrt_id`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=12282 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='结算记录表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `staff_dept_grant`
--

DROP TABLE IF EXISTS `staff_dept_grant`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `staff_dept_grant` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `staff_id` bigint NOT NULL,
  `org_id` bigint NOT NULL,
  `dept_id` bigint NOT NULL,
  `valid_from` date DEFAULT NULL,
  `valid_to` date DEFAULT NULL,
  `grant_by` varchar(50) DEFAULT NULL,
  `remark` varchar(200) DEFAULT NULL,
  `tenant_id` bigint DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_sg_staff_org` (`staff_id`,`org_id`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='科室临调授权(带期限)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `staff_employment`
--

DROP TABLE IF EXISTS `staff_employment`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `staff_employment` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `staff_id` bigint NOT NULL,
  `org_id` bigint NOT NULL,
  `dept_id` bigint NOT NULL,
  `is_primary` tinyint DEFAULT '0',
  `tenant_id` bigint DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_staff_org_dept` (`staff_id`,`org_id`,`dept_id`),
  KEY `idx_se_staff` (`staff_id`),
  KEY `idx_se_staff_org` (`staff_id`,`org_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5477 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='员工任职(多点执业/兼科室)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_acct_class`
--

DROP TABLE IF EXISTS `std_acct_class`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_acct_class` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `class_code` varchar(20) DEFAULT NULL COMMENT '分类编码(字典内序号,源文无编码)',
  `class_name` varchar(100) DEFAULT NULL COMMENT '会计科目分类名称',
  `invoice_class` varchar(100) DEFAULT NULL COMMENT '对应收费票据分类',
  `item_count` varchar(20) DEFAULT NULL COMMENT '归集医疗服务项目数(源文件出现次数)',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '物价标准' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_acct_code` (`class_code`),
  KEY `idx_acct_name` (`class_name`),
  KEY `idx_acct_std_type` (`std_type`)
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会计科目分类(源:医疗服务项目相关财务归集口径规范)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_cons_item_rel`
--

DROP TABLE IF EXISTS `std_cons_item_rel`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_cons_item_rel` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `seq` varchar(20) DEFAULT NULL COMMENT '序号',
  `code` varchar(50) DEFAULT NULL COMMENT '编码',
  `diag_item_code` varchar(50) DEFAULT NULL COMMENT '诊疗项目编码',
  `item_cat_name` varchar(500) DEFAULT NULL COMMENT '类别(项目)名称',
  `cons_variety` varchar(200) DEFAULT NULL COMMENT '医用材料品种',
  `cons_code20` varchar(50) DEFAULT NULL COMMENT '耗材代码20位',
  `cat1` varchar(100) DEFAULT NULL COMMENT '一级分类',
  `cat2` varchar(100) DEFAULT NULL COMMENT '二级分类',
  `cat3` varchar(100) DEFAULT NULL COMMENT '三级分类',
  `hi_genname` varchar(200) DEFAULT NULL COMMENT '医保通用名',
  `material` varchar(100) DEFAULT NULL COMMENT '材质',
  `feature` varchar(100) DEFAULT NULL COMMENT '特征',
  `cons_entp` varchar(200) DEFAULT NULL COMMENT '耗材企业',
  `policy_flag` varchar(20) DEFAULT NULL COMMENT '政策标识',
  `reg_cert_no` varchar(200) DEFAULT NULL COMMENT '注册证号',
  `cons_type` varchar(30) DEFAULT NULL COMMENT '耗材类型',
  `data_source` varchar(50) DEFAULT NULL COMMENT '数据来源',
  `memo` varchar(200) DEFAULT NULL COMMENT '备注',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '医保字典' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_cons_code20` (`cons_code20`),
  KEY `idx_diag_item_code` (`diag_item_code`)
) ENGINE=InnoDB AUTO_INCREMENT=250618 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准字典-耗材与医疗服务项目对应关系';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_consumable`
--

DROP TABLE IF EXISTS `std_consumable`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_consumable` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `seq` varchar(20) DEFAULT NULL COMMENT '序号',
  `cons_code` varchar(50) DEFAULT NULL COMMENT '耗材代码(20位)',
  `cat1` varchar(100) DEFAULT NULL COMMENT '一级分类',
  `cat2` varchar(100) DEFAULT NULL COMMENT '二级分类',
  `cat3` varchar(100) DEFAULT NULL COMMENT '三级分类',
  `hi_genname` varchar(200) DEFAULT NULL COMMENT '医保通用名',
  `material` varchar(100) DEFAULT NULL COMMENT '材质',
  `feature` varchar(100) DEFAULT NULL COMMENT '特征',
  `cons_entp` varchar(200) DEFAULT NULL COMMENT '耗材企业',
  `policy_flag` varchar(20) DEFAULT NULL COMMENT '政策标识区',
  `pay_std` varchar(50) DEFAULT NULL COMMENT '支付标准',
  `reg_cert_no` varchar(500) DEFAULT NULL COMMENT '注册证号',
  `cons_type` varchar(30) DEFAULT NULL COMMENT '耗材类型',
  `data_source` varchar(50) DEFAULT NULL COMMENT '数据来源',
  `chg_log` varchar(500) DEFAULT NULL COMMENT '变更日志',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '医保字典' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_cons_code` (`cons_code`),
  KEY `idx_hi_genname` (`hi_genname`(80))
) ENGINE=InnoDB AUTO_INCREMENT=97431 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准字典-医用耗材(20位)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_cv_code`
--

DROP TABLE IF EXISTS `std_cv_code`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_cv_code` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `dict_code` varchar(64) DEFAULT NULL COMMENT '字典类型代码',
  `dict_name` varchar(200) DEFAULT NULL COMMENT '字典类型名称',
  `val_code` varchar(64) DEFAULT NULL COMMENT '国家字典值代码',
  `val_name` varchar(500) DEFAULT NULL COMMENT '国家字典值名称',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '医保字典' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_cv_dict_code` (`dict_code`),
  KEY `idx_cv_val_code` (`val_code`),
  KEY `idx_cv_dict_name` (`dict_name`),
  KEY `idx_cv_std_type` (`std_type`)
) ENGINE=InnoDB AUTO_INCREMENT=7888 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医保字典值域代码(接口规范第6章)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_dict_version`
--

DROP TABLE IF EXISTS `std_dict_version`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_dict_version` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `dict_key` varchar(40) NOT NULL COMMENT '字典标识',
  `dict_name` varchar(100) DEFAULT NULL COMMENT '字典名称',
  `source_file` varchar(500) DEFAULT NULL COMMENT '源文件名',
  `sheet` varchar(100) DEFAULT NULL COMMENT '源sheet(序号或名称)',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `row_count` bigint DEFAULT '0' COMMENT '导入行数',
  `status` varchar(20) DEFAULT NULL COMMENT '状态(SUCCESS/FAIL)',
  `message` varchar(1000) DEFAULT NULL COMMENT '结果信息',
  `import_time` datetime DEFAULT NULL COMMENT '导入时间',
  PRIMARY KEY (`id`),
  KEY `idx_dict_key` (`dict_key`)
) ENGINE=InnoDB AUTO_INCREMENT=69 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准字典导入登记表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_drug`
--

DROP TABLE IF EXISTS `std_drug`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_drug` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `seq` varchar(20) DEFAULT NULL COMMENT '序号',
  `major_class` varchar(50) DEFAULT NULL COMMENT '大类名称',
  `drug_code` varchar(50) DEFAULT NULL COMMENT '药品代码(医保)',
  `reg_name` varchar(500) DEFAULT NULL COMMENT '注册名称',
  `trade_name` varchar(500) DEFAULT NULL COMMENT '商品名称',
  `reg_dosform` varchar(100) DEFAULT NULL COMMENT '注册剂型',
  `act_dosform` varchar(100) DEFAULT NULL COMMENT '实际剂型',
  `reg_spec` varchar(255) DEFAULT NULL COMMENT '注册规格',
  `act_spec` varchar(255) DEFAULT NULL COMMENT '实际规格',
  `pack_material` varchar(100) DEFAULT NULL COMMENT '包装材质',
  `min_pack_qty` varchar(30) DEFAULT NULL COMMENT '最小包装数量',
  `min_prep_unit` varchar(30) DEFAULT NULL COMMENT '最小制剂单位',
  `min_pack_unit` varchar(30) DEFAULT NULL COMMENT '最小包装单位',
  `drug_entp` varchar(200) DEFAULT NULL COMMENT '药品企业',
  `mkt_holder` varchar(200) DEFAULT NULL COMMENT '上市药品持有人',
  `approval_no` varchar(100) DEFAULT NULL COMMENT '批准文号',
  `drug_std_code` varchar(50) DEFAULT NULL COMMENT '药品本位码',
  `market_status` varchar(30) DEFAULT NULL COMMENT '市场状态',
  `subpack_entp` varchar(200) DEFAULT NULL COMMENT '分包装企业名称',
  `hi_drug_name` varchar(500) DEFAULT NULL COMMENT '医保药品名称',
  `chrgitm_lv` varchar(20) DEFAULT NULL COMMENT '甲乙丙类标识',
  `hi_dosform` varchar(100) DEFAULT NULL COMMENT '医保剂型',
  `gen_no` varchar(50) DEFAULT NULL COMMENT '编号',
  `memo` varchar(500) DEFAULT NULL COMMENT '备注',
  `msd_flag` varchar(20) DEFAULT NULL COMMENT '是否对应门诊特殊疾病',
  `nego_flag` varchar(20) DEFAULT NULL COMMENT '协议期内谈判药品标识',
  `nego_start` varchar(30) DEFAULT NULL COMMENT '谈判药品协议有效期起始日期',
  `nego_end` varchar(30) DEFAULT NULL COMMENT '谈判药品协议有效期截止日期',
  `ltd_self_flag` varchar(20) DEFAULT NULL COMMENT '限定支付范围药品自费标识',
  `pay_std_prep` varchar(30) DEFAULT NULL COMMENT '医保支付标准(最小制剂单位)',
  `pay_std_pack` varchar(30) DEFAULT NULL COMMENT '医保支付标准(最小包装单位)',
  `data_source` varchar(50) DEFAULT NULL COMMENT '数据来源',
  `chg_field` varchar(200) DEFAULT NULL COMMENT '修改字段',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '医保字典' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_drug_code` (`drug_code`),
  KEY `idx_drug_std_code` (`drug_std_code`),
  KEY `idx_approval_no` (`approval_no`),
  KEY `idx_reg_name` (`reg_name`(100)),
  KEY `idx_drug_std_type` (`std_type`)
) ENGINE=InnoDB AUTO_INCREMENT=243878 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准字典-西药中成药';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_hbvalue_code`
--

DROP TABLE IF EXISTS `std_hbvalue_code`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_hbvalue_code` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `section_no` varchar(20) DEFAULT NULL COMMENT '规范节号(如3.1)',
  `chapter_name` varchar(100) DEFAULT NULL COMMENT '所属章名称(如人口学及社会经济学特征)',
  `dict_code` varchar(60) DEFAULT NULL COMMENT '值域代码表标识(如CV02.01.101/GB/T 2261.1-2003/HBCV…)',
  `dict_name` varchar(200) DEFAULT NULL COMMENT '值域代码表名称(如身份证件类别代码)',
  `val_code` varchar(50) DEFAULT NULL COMMENT '值',
  `val_name` varchar(200) DEFAULT NULL COMMENT '值含义',
  `remark` varchar(1000) DEFAULT NULL COMMENT '说明/备注',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '卫生健康标准' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_hbvalue_dict_code` (`dict_code`),
  KEY `idx_hbvalue_val_code` (`val_code`),
  KEY `idx_hbvalue_dict_name` (`dict_name`),
  KEY `idx_hbvalue_section` (`section_no`),
  KEY `idx_hbvalue_std_type` (`std_type`)
) ENGINE=InnoDB AUTO_INCREMENT=5063 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='湖北省健康医疗大数据采集规范-数据元值域代码';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_icd10`
--

DROP TABLE IF EXISTS `std_icd10`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_icd10` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `chapter` varchar(20) DEFAULT NULL COMMENT '章',
  `chapter_code_range` varchar(50) DEFAULT NULL COMMENT '章代码范围',
  `chapter_name` varchar(200) DEFAULT NULL COMMENT '章的名称',
  `section_code_range` varchar(50) DEFAULT NULL COMMENT '节代码范围',
  `section_name` varchar(200) DEFAULT NULL COMMENT '节名称',
  `cat_code` varchar(50) DEFAULT NULL COMMENT '类目代码',
  `cat_name` varchar(200) DEFAULT NULL COMMENT '类目名称',
  `subcat_code` varchar(50) DEFAULT NULL COMMENT '亚目代码',
  `subcat_name` varchar(500) DEFAULT NULL COMMENT '亚目名称',
  `diag_code` varchar(50) DEFAULT NULL COMMENT '诊断代码',
  `diag_name` varchar(500) DEFAULT NULL COMMENT '诊断名称',
  `src_sheet` varchar(50) DEFAULT NULL COMMENT '来源sheet',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '医保字典' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_diag_code` (`diag_code`),
  KEY `idx_diag_name` (`diag_name`(80)),
  KEY `idx_icd10_std_type` (`std_type`)
) ENGINE=InnoDB AUTO_INCREMENT=34227 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准字典-医保ICD10疾病诊断';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_icd10_nat`
--

DROP TABLE IF EXISTS `std_icd10_nat`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_icd10_nat` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `main_code` varchar(50) DEFAULT NULL COMMENT '主要编码',
  `add_code` varchar(50) DEFAULT NULL COMMENT '附加编码',
  `disease_name` varchar(500) DEFAULT NULL COMMENT '疾病名称',
  `src` varchar(50) DEFAULT NULL COMMENT '来源',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '国家临床标准' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_main_code` (`main_code`),
  KEY `idx_disease_name` (`disease_name`(80))
) ENGINE=InnoDB AUTO_INCREMENT=37295 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准字典-国家临床版疾病分类与代码';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_icd9`
--

DROP TABLE IF EXISTS `std_icd9`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_icd9` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `chapter` varchar(20) DEFAULT NULL COMMENT '章',
  `chapter_name` varchar(200) DEFAULT NULL COMMENT '章的名称',
  `cat_code` varchar(50) DEFAULT NULL COMMENT '类目代码',
  `cat_name` varchar(200) DEFAULT NULL COMMENT '类目名称',
  `subcat_code` varchar(50) DEFAULT NULL COMMENT '亚目代码',
  `subcat_name` varchar(200) DEFAULT NULL COMMENT '亚目名称',
  `detail_code` varchar(50) DEFAULT NULL COMMENT '细目代码',
  `detail_name` varchar(500) DEFAULT NULL COMMENT '细目名称',
  `oper_code` varchar(50) DEFAULT NULL COMMENT '手术操作代码',
  `oper_name` varchar(500) DEFAULT NULL COMMENT '手术操作名称',
  `src_sheet` varchar(50) DEFAULT NULL COMMENT '来源sheet',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '医保字典' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_oper_code` (`oper_code`),
  KEY `idx_oper_name` (`oper_name`(80))
) ENGINE=InnoDB AUTO_INCREMENT=13688 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准字典-医保ICD9手术操作';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_icd9_nat`
--

DROP TABLE IF EXISTS `std_icd9_nat`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_icd9_nat` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `main_code` varchar(50) DEFAULT NULL COMMENT '主要编码/手术操作编码',
  `add_code` varchar(50) DEFAULT NULL COMMENT '附加编码',
  `oper_name` varchar(500) DEFAULT NULL COMMENT '手术操作名称',
  `oper_cat` varchar(100) DEFAULT NULL COMMENT '类别',
  `input_option` varchar(50) DEFAULT NULL COMMENT '录入选项',
  `src` varchar(50) DEFAULT NULL COMMENT '来源',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '国家临床标准' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_main_code` (`main_code`),
  KEY `idx_oper_name` (`oper_name`(80))
) ENGINE=InnoDB AUTO_INCREMENT=26590 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准字典-国家临床版手术操作分类与代码';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_invoice_class`
--

DROP TABLE IF EXISTS `std_invoice_class`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_invoice_class` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `class_code` varchar(20) DEFAULT NULL COMMENT '分类编码(字典内序号,源文无编码)',
  `class_name` varchar(100) DEFAULT NULL COMMENT '收费票据分类名称',
  `acct_class` varchar(100) DEFAULT NULL COMMENT '对应会计科目分类',
  `item_count` varchar(20) DEFAULT NULL COMMENT '归集医疗服务项目数(源文件出现次数)',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '物价标准' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_inv_code` (`class_code`),
  KEY `idx_inv_name` (`class_name`),
  KEY `idx_inv_std_type` (`std_type`)
) ENGINE=InnoDB AUTO_INCREMENT=16 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='收费票据分类(源:医疗服务项目相关财务归集口径规范)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_ivd`
--

DROP TABLE IF EXISTS `std_ivd`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_ivd` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `seq` varchar(20) DEFAULT NULL COMMENT '序号',
  `ivd_code` varchar(50) DEFAULT NULL COMMENT '医保体外诊断试剂分类代码',
  `cat1` varchar(100) DEFAULT NULL COMMENT '一级分类',
  `cat2` varchar(100) DEFAULT NULL COMMENT '二级分类',
  `cat3` varchar(100) DEFAULT NULL COMMENT '三级分类',
  `test_class` varchar(100) DEFAULT NULL COMMENT '检测类别',
  `test_index` varchar(200) DEFAULT NULL COMMENT '检测指标',
  `app_mode` varchar(50) DEFAULT NULL COMMENT '应用方式',
  `test_type` varchar(50) DEFAULT NULL COMMENT '检测类型',
  `test_item` varchar(200) DEFAULT NULL COMMENT '检测项',
  `entp_name` varchar(200) DEFAULT NULL COMMENT '企业名称',
  `result_attr` varchar(50) DEFAULT NULL COMMENT '检测结果属性',
  `prod_name` varchar(500) DEFAULT NULL COMMENT '单件产品名称',
  `reg_record_no` varchar(200) DEFAULT NULL COMMENT '注册备案号',
  `pack_spec` varchar(200) DEFAULT NULL COMMENT '包装规格',
  `pack_unit` varchar(50) DEFAULT NULL COMMENT '包装计量单位',
  `volume_ml` varchar(30) DEFAULT NULL COMMENT '容量(ml)',
  `human_dose` varchar(30) DEFAULT NULL COMMENT '人份',
  `other` varchar(200) DEFAULT NULL COMMENT '其他',
  `applicable_inst` varchar(200) DEFAULT NULL COMMENT '适用仪器',
  `udi` varchar(100) DEFAULT NULL COMMENT 'UDI',
  `data_source` varchar(50) DEFAULT NULL COMMENT '数据来源',
  `chg_log` varchar(200) DEFAULT NULL COMMENT '变更日志',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '医保字典' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_ivd_code` (`ivd_code`),
  KEY `idx_prod_name` (`prod_name`(80))
) ENGINE=InnoDB AUTO_INCREMENT=2234 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准字典-体外诊断试剂';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_med_service`
--

DROP TABLE IF EXISTS `std_med_service`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_med_service` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `seq` varchar(20) DEFAULT NULL COMMENT '序号',
  `nat_item_code` varchar(50) DEFAULT NULL COMMENT '国家医疗服务项目代码',
  `nat_item_name` varchar(500) DEFAULT NULL COMMENT '国家医疗服务项目名称',
  `loc_item_code` varchar(80) DEFAULT NULL COMMENT '地方医疗服务项目代码',
  `loc_item_name` varchar(1000) DEFAULT NULL COMMENT '地方医疗服务项目名称',
  `item_connotation` varchar(2000) DEFAULT NULL COMMENT '项目内涵',
  `item_excluded` varchar(1000) DEFAULT NULL COMMENT '除外内容',
  `prc_unit` varchar(50) DEFAULT NULL COMMENT '计价单位',
  `item_explain` varchar(2000) DEFAULT NULL COMMENT '项目说明',
  `policy_flag` varchar(50) DEFAULT NULL COMMENT '政策标识',
  `pay_std` varchar(50) DEFAULT NULL COMMENT '支付标准',
  `memo` varchar(500) DEFAULT NULL COMMENT '备注',
  `data_source` varchar(50) DEFAULT NULL COMMENT '数据来源',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '医保字典' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_nat_item_code` (`nat_item_code`),
  KEY `idx_loc_item_code` (`loc_item_code`),
  KEY `idx_nat_item_name` (`nat_item_name`(80))
) ENGINE=InnoDB AUTO_INCREMENT=6460 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准字典-医疗服务项目';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_morphology`
--

DROP TABLE IF EXISTS `std_morphology`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_morphology` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `morph_code` varchar(50) DEFAULT NULL COMMENT '肿瘤形态学编码/形态学分类代码',
  `morph_name` varchar(200) DEFAULT NULL COMMENT '肿瘤形态学名称/形态学分类',
  `tumor_type_code` varchar(50) DEFAULT NULL COMMENT '肿瘤/细胞类型代码',
  `tumor_type_name` varchar(200) DEFAULT NULL COMMENT '肿瘤/细胞类型',
  `src` varchar(50) DEFAULT NULL COMMENT '来源',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '国家临床标准' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_morph_code` (`morph_code`)
) ENGINE=InnoDB AUTO_INCREMENT=2922 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准字典-肿瘤形态学编码';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_mr_cost_class`
--

DROP TABLE IF EXISTS `std_mr_cost_class`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_mr_cost_class` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `cat_no` varchar(10) DEFAULT NULL COMMENT '大类序号(1-5)',
  `cat_name` varchar(100) DEFAULT NULL COMMENT '大类名称(综合医疗服务类/诊断类/治疗类/康复类/中医类)',
  `item_code` varchar(20) DEFAULT NULL COMMENT '费用分项编码(源文括号序号1~12,中医手术费/诊断费无编码为空)',
  `item_name` varchar(200) DEFAULT NULL COMMENT '费用分项名称',
  `raw_value` varchar(300) DEFAULT NULL COMMENT '源文件原始完整值(大类:分项 形式)',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '物价标准' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_mrcost_cat_no` (`cat_no`),
  KEY `idx_mrcost_item_code` (`item_code`),
  KEY `idx_mrcost_item_name` (`item_name`),
  KEY `idx_mrcost_std_type` (`std_type`)
) ENGINE=InnoDB AUTO_INCREMENT=18 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='病案首页费用分类(源:医疗服务项目相关财务归集口径规范)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_msi_cat`
--

DROP TABLE IF EXISTS `std_msi_cat`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_msi_cat` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `cat_code` varchar(20) NOT NULL COMMENT '分类码(原生字母码: 类1/章2/节3字母, 区间取首段)',
  `cat_name` varchar(200) DEFAULT NULL COMMENT '分类名称(类/章/节)',
  `parent_code` varchar(20) DEFAULT NULL COMMENT '上级分类码(类为空)',
  `lv` tinyint DEFAULT NULL COMMENT '层级: 1-类 2-章 3-节',
  `item_count` int DEFAULT NULL COMMENT '下属项目数(节含组, 章含节与直属)',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '物价标准' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msic_cat_code` (`cat_code`),
  KEY `idx_msic_parent` (`parent_code`),
  KEY `idx_msic_lv` (`lv`)
) ENGINE=InnoDB AUTO_INCREMENT=517 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医疗服务项目物价分类(2023技术规范类/章/节三级)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_msi_fin`
--

DROP TABLE IF EXISTS `std_msi_fin`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_msi_fin` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `code_2023` varchar(32) DEFAULT NULL COMMENT '2023年版项目编码(层级行/续行为空)',
  `name_2023` varchar(300) DEFAULT NULL COMMENT '2023年版项目名称',
  `code_2012` varchar(32) DEFAULT NULL COMMENT '2012年版项目编码',
  `name_2012` varchar(300) DEFAULT NULL COMMENT '2012年版项目名称',
  `code_2001` varchar(32) DEFAULT NULL COMMENT '2001/2007年版项目编码',
  `name_2001` varchar(300) DEFAULT NULL COMMENT '2001/2007年版项目名称',
  `invoice_class` varchar(100) DEFAULT NULL COMMENT '收费票据分类',
  `acct_class` varchar(100) DEFAULT NULL COMMENT '会计科目分类',
  `mr_cost_class` varchar(300) DEFAULT NULL COMMENT '病案首页费用分类',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '物价标准' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_msif_code_2023` (`code_2023`),
  KEY `idx_msif_code_2012` (`code_2012`),
  KEY `idx_msif_code_2001` (`code_2001`),
  KEY `idx_msif_name_2023` (`name_2023`),
  KEY `idx_msif_std_type` (`std_type`)
) ENGINE=InnoDB AUTO_INCREMENT=13192 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医疗服务项目相关财务归集口径规范原样表行';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_msi_hb`
--

DROP TABLE IF EXISTS `std_msi_hb`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_msi_hb` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `item_code` varchar(32) DEFAULT NULL COMMENT '编码(9位基础码或9位+字母后缀子项码)',
  `item_name` varchar(300) DEFAULT NULL COMMENT '项目名称',
  `item_content` varchar(2000) DEFAULT NULL COMMENT '项目内涵',
  `excluded` varchar(1000) DEFAULT NULL COMMENT '除外内容',
  `unit` varchar(50) DEFAULT NULL COMMENT '计价单位',
  `pay_cat` varchar(50) DEFAULT NULL COMMENT '医保支付类别(甲类/乙类/自费)',
  `item_explain` varchar(1000) DEFAULT NULL COMMENT '说明',
  `remark` varchar(1000) DEFAULT NULL COMMENT '备注',
  `trial` varchar(50) DEFAULT NULL COMMENT '试行项目标志',
  `cat_name` varchar(300) DEFAULT NULL COMMENT '分类路径(类>章>节)',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '物价标准' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_msih_item_code` (`item_code`),
  KEY `idx_msih_item_name` (`item_name`),
  KEY `idx_msih_std_type` (`std_type`)
) ENGINE=InnoDB AUTO_INCREMENT=4665 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='湖北省医疗服务价格项目及医保支付目录(2023版)原样';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_msi_nat`
--

DROP TABLE IF EXISTS `std_msi_nat`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_msi_nat` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `item_code` varchar(32) DEFAULT NULL COMMENT '项目编码(8位字母数字混合)',
  `item_name` varchar(300) DEFAULT NULL COMMENT '项目名称(中文)',
  `item_name_en` varchar(500) DEFAULT NULL COMMENT '项目名称(英文)',
  `item_content` varchar(2000) DEFAULT NULL COMMENT '项目内涵',
  `consumable_req` varchar(1000) DEFAULT NULL COMMENT '必需耗材',
  `consumable_opt` varchar(1000) DEFAULT NULL COMMENT '可选耗材',
  `consumable_low` varchar(500) DEFAULT NULL COMMENT '低值耗材分档',
  `hr_time` varchar(200) DEFAULT NULL COMMENT '基本人力消耗及耗时',
  `tech_difficulty` varchar(100) DEFAULT NULL COMMENT '技术难度',
  `risk_level` varchar(100) DEFAULT NULL COMMENT '风险程度',
  `hr_value` varchar(50) DEFAULT NULL COMMENT '人力资源消耗相对值',
  `unit` varchar(50) DEFAULT NULL COMMENT '计量单位',
  `remark` varchar(1000) DEFAULT NULL COMMENT '说明',
  `adjust_coef` varchar(200) DEFAULT NULL COMMENT '特殊情况资源消耗调整系数',
  `invoice_class` varchar(100) DEFAULT NULL COMMENT '收费票据分类',
  `acct_class` varchar(100) DEFAULT NULL COMMENT '会计科目分类',
  `mr_cost_class` varchar(300) DEFAULT NULL COMMENT '病案首页费用分类',
  `cat_name` varchar(300) DEFAULT NULL COMMENT '分类路径(类>章>节>组)',
  `cat_code` varchar(20) DEFAULT NULL COMMENT '分类码(std_msi_cat.cat_code, 节级优先, 无节时章级, 附录为空)',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '物价标准' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_msin_item_code` (`item_code`),
  KEY `idx_msin_item_name` (`item_name`),
  KEY `idx_msin_std_type` (`std_type`),
  KEY `idx_msin_cat_code` (`cat_code`)
) ENGINE=InnoDB AUTO_INCREMENT=11518 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='全国医疗服务项目技术规范(2023年版)原样全列';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_preparation`
--

DROP TABLE IF EXISTS `std_preparation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_preparation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `seq` varchar(20) DEFAULT NULL COMMENT '序号',
  `chg_log` varchar(200) DEFAULT NULL COMMENT '变更日志',
  `policy_flag` varchar(50) DEFAULT NULL COMMENT '政策标识',
  `region` varchar(50) DEFAULT NULL COMMENT '地区',
  `prep_code` varchar(50) DEFAULT NULL COMMENT '制剂代码',
  `applicant` varchar(200) DEFAULT NULL COMMENT '申请人单位名称',
  `prep_class` varchar(50) DEFAULT NULL COMMENT '制剂类别',
  `prep_name` varchar(500) DEFAULT NULL COMMENT '制剂名称',
  `dosform` varchar(100) DEFAULT NULL COMMENT '剂型',
  `spec` varchar(255) DEFAULT NULL COMMENT '规格',
  `min_pack_qty` varchar(30) DEFAULT NULL COMMENT '最小包装数量',
  `min_pack_unit` varchar(30) DEFAULT NULL COMMENT '最小包装单位',
  `min_prep_unit` varchar(30) DEFAULT NULL COMMENT '最小制剂单位',
  `pack_material` varchar(100) DEFAULT NULL COMMENT '包装材质',
  `entrust_entp` varchar(200) DEFAULT NULL COMMENT '委托制剂配制单位名称',
  `entrust_addr` varchar(500) DEFAULT NULL COMMENT '委托制剂配置地址',
  `approval_no` varchar(100) DEFAULT NULL COMMENT '批准文号',
  `approval_valid_date` varchar(40) DEFAULT NULL COMMENT '批准文号有效期',
  `license_no` varchar(100) DEFAULT NULL COMMENT '许可证编号',
  `exec_std` varchar(200) DEFAULT NULL COMMENT '执行标准',
  `indication` varchar(2000) DEFAULT NULL COMMENT '适应症/功能主治',
  `usage_method` varchar(1000) DEFAULT NULL COMMENT '用法用量',
  `child_use` varchar(500) DEFAULT NULL COMMENT '儿童用药',
  `elder_use` varchar(500) DEFAULT NULL COMMENT '老年患者用药',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '医保字典' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_prep_code` (`prep_code`),
  KEY `idx_prep_name` (`prep_name`(80))
) ENGINE=InnoDB AUTO_INCREMENT=1872 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准字典-医疗机构制剂';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_supplier`
--

DROP TABLE IF EXISTS `std_supplier`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_supplier` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `sup_code` varchar(40) NOT NULL COMMENT '供货商编码(企业去重后生成, 全局唯一)',
  `sup_name` varchar(300) NOT NULL COMMENT '企业名称(供货商名称)',
  `sup_type` varchar(60) DEFAULT NULL COMMENT '企业类型(生产企业/上市许可持有人/生产兼持有人/经营企业等)',
  `src_catalog` varchar(120) DEFAULT NULL COMMENT '来源目录(drug/consumable/ivd/preparation, 可多值逗号分隔)',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '医保字典' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(企业名称首字母, 自动生成只读)',
  `vali_flag` char(1) DEFAULT '1' COMMENT '有效标志:1有效 0作废',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  `sup_short_name` varchar(100) DEFAULT NULL COMMENT '企业简称(列表检索/显示)',
  `uscc` varchar(32) DEFAULT NULL COMMENT '统一社会信用代码(三证合一)',
  `legal_person` varchar(60) DEFAULT NULL COMMENT '法定代表人',
  `reg_capital` varchar(40) DEFAULT NULL COMMENT '注册资本(万元)',
  `estab_date` varchar(20) DEFAULT NULL COMMENT '成立日期',
  `reg_address` varchar(200) DEFAULT NULL COMMENT '注册地址',
  `business_scope` varchar(500) DEFAULT NULL COMMENT '生产/经营范围',
  `license_no` varchar(64) DEFAULT NULL COMMENT '药品生产/经营许可证号',
  `license_expiry` varchar(20) DEFAULT NULL COMMENT '许可证有效期',
  `license_authority` varchar(120) DEFAULT NULL COMMENT '许可证发证机关',
  `gmp_gsp_no` varchar(64) DEFAULT NULL COMMENT 'GMP/GSP证书号',
  `gmp_gsp_expiry` varchar(20) DEFAULT NULL COMMENT 'GMP/GSP证书有效期',
  `contact_person` varchar(60) DEFAULT NULL COMMENT '联系人',
  `contact_phone` varchar(60) DEFAULT NULL COMMENT '联系电话',
  `fax` varchar(40) DEFAULT NULL COMMENT '传真',
  `email` varchar(120) DEFAULT NULL COMMENT '电子邮箱',
  `bank_name` varchar(160) DEFAULT NULL COMMENT '开户银行',
  `bank_account` varchar(64) DEFAULT NULL COMMENT '银行账号',
  `tax_no` varchar(32) DEFAULT NULL COMMENT '纳税人识别号',
  `invoice_title` varchar(160) DEFAULT NULL COMMENT '发票抬头',
  `two_ticket_flag` varchar(2) DEFAULT NULL COMMENT '是否两票制:1是 0否',
  `platform_code` varchar(64) DEFAULT NULL COMMENT '招采/挂网平台编码',
  `delivery_area` varchar(200) DEFAULT NULL COMMENT '配送区域',
  `blacklist_flag` varchar(2) DEFAULT NULL COMMENT '失信/黑名单标志:1是 0否',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sup_code` (`sup_code`),
  KEY `idx_sup_name` (`sup_name`(100)),
  KEY `idx_sup_py` (`py_code`)
) ENGINE=InnoDB AUTO_INCREMENT=37520 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供货商(企业)字典(医保各目录企业去重汇总, 全局共享)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_supplier_file`
--

DROP TABLE IF EXISTS `std_supplier_file`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_supplier_file` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `sup_code` varchar(40) NOT NULL COMMENT '关联企业编码(std_supplier.sup_code)',
  `doc_type` varchar(40) DEFAULT NULL COMMENT '资料类型:许可证/GMP/GSP/营业执照/其他',
  `file_name` varchar(300) NOT NULL COMMENT '原始文件名',
  `file_path` varchar(500) NOT NULL COMMENT '存储相对路径',
  `file_size` bigint DEFAULT '0' COMMENT '文件大小(字节)',
  `mime_type` varchar(100) DEFAULT NULL COMMENT 'MIME类型',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `upload_by` varchar(50) DEFAULT NULL COMMENT '上传人',
  `upload_time` datetime DEFAULT NULL COMMENT '上传时间',
  `deleted` tinyint DEFAULT '0' COMMENT '软删:1已删 0正常',
  PRIMARY KEY (`id`),
  KEY `idx_sup_code` (`sup_code`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='企业资质证照附件(全局共享, 关联std_supplier)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_tcm`
--

DROP TABLE IF EXISTS `std_tcm`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_tcm` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `seq` varchar(20) DEFAULT NULL COMMENT '序号',
  `major_class` varchar(50) DEFAULT NULL COMMENT '大类名称',
  `nat_tcm_code` varchar(50) DEFAULT NULL COMMENT '国家中药饮片代码',
  `tcm_name` varchar(200) DEFAULT NULL COMMENT '中药饮片名称',
  `material_name` varchar(200) DEFAULT NULL COMMENT '药材名称',
  `proc_method` varchar(500) DEFAULT NULL COMMENT '炮制方法',
  `efcc_class` varchar(100) DEFAULT NULL COMMENT '功效分类',
  `material_family` varchar(100) DEFAULT NULL COMMENT '药材科(族)来源',
  `material_species` varchar(200) DEFAULT NULL COMMENT '药材种来源',
  `medi_part` varchar(100) DEFAULT NULL COMMENT '药用部位',
  `nature_meridian` varchar(200) DEFAULT NULL COMMENT '性味与归经',
  `func_indication` varchar(2000) DEFAULT NULL COMMENT '功能与主治',
  `usage_dosage` varchar(500) DEFAULT NULL COMMENT '用法与用量',
  `pay_policy` varchar(200) DEFAULT NULL COMMENT '医保支付政策',
  `chrgitm_lv` varchar(20) DEFAULT NULL COMMENT '甲乙丙类标识',
  `data_source` varchar(50) DEFAULT NULL COMMENT '数据来源',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '医保字典' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_nat_tcm_code` (`nat_tcm_code`),
  KEY `idx_tcm_name` (`tcm_name`(80))
) ENGINE=InnoDB AUTO_INCREMENT=10822 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准字典-中药饮片';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_tcm_disease`
--

DROP TABLE IF EXISTS `std_tcm_disease`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_tcm_disease` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `dept_cat_code` varchar(20) DEFAULT NULL COMMENT '科别类目代码',
  `dept_cat_name` varchar(100) DEFAULT NULL COMMENT '科别类目名称',
  `spec_sys_code` varchar(20) DEFAULT NULL COMMENT '专科系统分类目代码',
  `spec_sys_name` varchar(100) DEFAULT NULL COMMENT '专科系统分类目名称',
  `dis_class_code` varchar(20) DEFAULT NULL COMMENT '疾病分类代码',
  `dis_class_name` varchar(200) DEFAULT NULL COMMENT '疾病分类名称',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '中医标准' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_dis_class_code` (`dis_class_code`),
  KEY `idx_dis_class_name` (`dis_class_name`(80))
) ENGINE=InnoDB AUTO_INCREMENT=625 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准字典-中医疾病分类与代码';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_tcm_disease_new`
--

DROP TABLE IF EXISTS `std_tcm_disease_new`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_tcm_disease_new` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `seq` int DEFAULT NULL COMMENT '序号',
  `dis_code` varchar(30) DEFAULT NULL COMMENT '新版疾病代码(如A01.01.01)',
  `dis_no` varchar(30) DEFAULT NULL COMMENT '编号(第1部分章节号,如2.1.1)',
  `dis_name` varchar(200) DEFAULT NULL COMMENT '新版疾病名称(如感冒)',
  `optional_name` varchar(200) DEFAULT NULL COMMENT '可选用词(同义/近义词)',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '中医标准' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_tcmdnew_dis_code` (`dis_code`),
  KEY `idx_tcmdnew_dis_name` (`dis_name`),
  KEY `idx_tcmdnew_std_type` (`std_type`)
) ENGINE=InnoDB AUTO_INCREMENT=1370 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='中医疾病分类与代码(新版GB/T 15657-2021第1部分)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_tcm_mapping`
--

DROP TABLE IF EXISTS `std_tcm_mapping`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_tcm_mapping` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `map_type` varchar(20) DEFAULT NULL COMMENT '映射类型(疾病/证候/治法)',
  `seq` varchar(20) DEFAULT NULL COMMENT '序号',
  `new_code` varchar(50) DEFAULT NULL COMMENT '修订版代码',
  `new_no` varchar(50) DEFAULT NULL COMMENT '编号',
  `new_name` varchar(200) DEFAULT NULL COMMENT '修订版名称',
  `new_optional` varchar(200) DEFAULT NULL COMMENT '修订版可选用词',
  `old_code` varchar(50) DEFAULT NULL COMMENT '原代码',
  `old_name` varchar(200) DEFAULT NULL COMMENT '原名称',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '中医标准' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_map_type` (`map_type`),
  KEY `idx_new_code` (`new_code`)
) ENGINE=InnoDB AUTO_INCREMENT=4598 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准字典-中医新老对照';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_tcm_syndrome`
--

DROP TABLE IF EXISTS `std_tcm_syndrome`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_tcm_syndrome` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `syn_cat_code` varchar(20) DEFAULT NULL COMMENT '证候类目代码',
  `syn_cat_name` varchar(100) DEFAULT NULL COMMENT '证候类目名称',
  `syn_attr_code` varchar(20) DEFAULT NULL COMMENT '证候属性代码',
  `syn_attr_name` varchar(100) DEFAULT NULL COMMENT '证候属性',
  `syn_class_code` varchar(20) DEFAULT NULL COMMENT '证候分类代码',
  `syn_class_name` varchar(200) DEFAULT NULL COMMENT '证候分类名称',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '中医标准' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_syn_class_code` (`syn_class_code`),
  KEY `idx_syn_class_name` (`syn_class_name`(80))
) ENGINE=InnoDB AUTO_INCREMENT=1625 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准字典-中医证候分类与代码';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_tcm_syndrome_new`
--

DROP TABLE IF EXISTS `std_tcm_syndrome_new`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_tcm_syndrome_new` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `seq` int DEFAULT NULL COMMENT '序号',
  `syn_code` varchar(30) DEFAULT NULL COMMENT '新版证候代码(如B01.03.01)',
  `syn_no` varchar(30) DEFAULT NULL COMMENT '编号(第2部分章节号,如2.3.1)',
  `syn_name` varchar(200) DEFAULT NULL COMMENT '新版证候名称(如表虚证)',
  `optional_name` varchar(200) DEFAULT NULL COMMENT '可选用词(同义/近义词)',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '中医标准' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_tcmsnew_syn_code` (`syn_code`),
  KEY `idx_tcmsnew_syn_name` (`syn_name`),
  KEY `idx_tcmsnew_std_type` (`std_type`)
) ENGINE=InnoDB AUTO_INCREMENT=2061 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='中医证候分类与代码(新版GB/T 15657-2021第2部分)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_whvalue_code`
--

DROP TABLE IF EXISTS `std_whvalue_code`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_whvalue_code` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `chapter_name` varchar(100) DEFAULT NULL COMMENT '所属章名称(如人口学及社会经济学特征)',
  `dict_code` varchar(60) DEFAULT NULL COMMENT '值域代码表标识(如CT01.00.002/CV02.01.101/GB/T 2261.1-2003/WH…)',
  `dict_name` varchar(200) DEFAULT NULL COMMENT '值域代码表名称(如身份证件类别)',
  `val_code` varchar(50) DEFAULT NULL COMMENT '值',
  `val_name` varchar(200) DEFAULT NULL COMMENT '值含义',
  `remark` varchar(1000) DEFAULT NULL COMMENT '说明',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '卫生健康标准' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_whvalue_dict_code` (`dict_code`),
  KEY `idx_whvalue_val_code` (`val_code`),
  KEY `idx_whvalue_dict_name` (`dict_name`),
  KEY `idx_whvalue_std_type` (`std_type`)
) ENGINE=InnoDB AUTO_INCREMENT=11185 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='武汉市全民健康信息平台数据元值域代码规范';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `std_wst364_code`
--

DROP TABLE IF EXISTS `std_wst364_code`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `std_wst364_code` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `cv_code` varchar(30) DEFAULT NULL COMMENT '值域代码表标识(如CV02.01.101)',
  `cv_name` varchar(200) DEFAULT NULL COMMENT '值域代码表名称',
  `val_code` varchar(30) DEFAULT NULL COMMENT '值',
  `val_name` varchar(200) DEFAULT NULL COMMENT '值含义',
  `remark` varchar(1000) DEFAULT NULL COMMENT '说明',
  `part_no` varchar(10) DEFAULT NULL COMMENT '所属部分号(第N部分)',
  `part_name` varchar(100) DEFAULT NULL COMMENT '所属部分名称',
  `ver` varchar(30) DEFAULT NULL COMMENT '数据版本',
  `std_type` varchar(30) DEFAULT '卫生健康标准' COMMENT '字典标准类型',
  `src_doc` varchar(200) DEFAULT NULL COMMENT '来源文档',
  `vali_flag` varchar(3) DEFAULT '1' COMMENT '有效标志:1-有效 0-无效',
  `begn_time` datetime DEFAULT NULL COMMENT '生效时间',
  `end_time` datetime DEFAULT NULL COMMENT '作废时间',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  KEY `idx_wst364_cv_code` (`cv_code`),
  KEY `idx_wst364_val_code` (`val_code`),
  KEY `idx_wst364_cv_name` (`cv_name`),
  KEY `idx_wst364_std_type` (`std_type`)
) ENGINE=InnoDB AUTO_INCREMENT=4026 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='WS/T 364-2023 卫生健康信息数据元值域代码';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_menu`
--

DROP TABLE IF EXISTS `sys_menu`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_menu` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
  `parent_id` bigint DEFAULT '0' COMMENT '父菜单ID(0=顶级)',
  `menu_key` varchar(50) NOT NULL COMMENT '菜单键(=前端组件路由键)',
  `menu_name` varchar(50) NOT NULL COMMENT '菜单名称',
  `menu_type` tinyint DEFAULT '2' COMMENT '类型:1-目录 2-菜单',
  `comp` varchar(50) DEFAULT NULL COMMENT '前端HIS.views组件名(目录为空)',
  `phase` varchar(10) DEFAULT NULL COMMENT '建设阶段占位(无comp时显示建设中)',
  `icon` varchar(50) DEFAULT NULL COMMENT '图标',
  `sort_no` int DEFAULT '0' COMMENT '排序号',
  `visible` tinyint DEFAULT '1' COMMENT '是否显示:1-是 0-否',
  `status` tinyint DEFAULT '1' COMMENT '状态:1-启用 0-停用',
  `create_by` varchar(50) DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(50) DEFAULT NULL COMMENT '更新人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除:0-正常 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_menu_key` (`menu_key`),
  KEY `idx_parent` (`parent_id`)
) ENGINE=InnoDB AUTO_INCREMENT=195 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜单/权限(全局真源)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_org`
--

DROP TABLE IF EXISTS `sys_org`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_org` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '机构ID',
  `tenant_id` bigint NOT NULL COMMENT '租户(医共体)ID',
  `org_code` varchar(50) NOT NULL COMMENT '机构编码(医共体内唯一)',
  `org_name` varchar(200) NOT NULL COMMENT '机构名称',
  `org_level` tinyint DEFAULT '1' COMMENT '机构级别:1-县级(牵头) 2-乡镇 3-村',
  `is_lead` tinyint NOT NULL DEFAULT '0' COMMENT '是否牵头机构:1-牵头(每医共体唯一) 0-成员',
  `parent_id` bigint DEFAULT '0' COMMENT '上级机构ID(县级为0)',
  `org_type` varchar(30) DEFAULT NULL COMMENT '机构类型:综合医院/乡镇卫生院/村卫生室等',
  `fixmedins_code` varchar(30) DEFAULT NULL COMMENT '定点医药机构编号(机构级)',
  `admvs_code` varchar(20) DEFAULT NULL COMMENT '行政区划代码(关联area_code_2021)',
  `leader` varchar(50) DEFAULT NULL COMMENT '负责人',
  `phone` varchar(30) DEFAULT NULL COMMENT '联系电话',
  `address` varchar(300) DEFAULT NULL COMMENT '机构地址',
  `sort_no` int DEFAULT '0' COMMENT '排序号',
  `status` tinyint DEFAULT '1' COMMENT '状态:1-启用 0-停用',
  `create_by` varchar(50) DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(50) DEFAULT NULL COMMENT '更新人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除:0-正常 1-删除',
  `org_type_name` varchar(100) DEFAULT NULL COMMENT '机构类型名称(字典回填)',
  `org_type_src` varchar(50) DEFAULT NULL COMMENT '机构类型字典来源标识',
  `fixmedins_name` varchar(200) DEFAULT NULL COMMENT '定点医药机构名称(医保登记名)',
  `uscc` varchar(50) DEFAULT NULL COMMENT '统一社会信用代码',
  `fixmedins_type` varchar(6) DEFAULT NULL COMMENT '定点医疗服务机构类型编码(cv_code:fixmedins_type)',
  `fixmedins_type_name` varchar(100) DEFAULT NULL COMMENT '定点医疗服务机构类型名称(字典回填)',
  `fixmedins_type_src` varchar(50) DEFAULT NULL COMMENT '定点机构类型来源标识',
  `hosp_lv` varchar(6) DEFAULT NULL COMMENT '医院等级编码(cv_code:hosp_lv)',
  `hosp_lv_name` varchar(100) DEFAULT NULL COMMENT '医院等级名称(字典回填)',
  `hosp_lv_src` varchar(50) DEFAULT NULL COMMENT '医院等级来源标识',
  `pd_license_no` varchar(50) DEFAULT NULL COMMENT '医疗机构执业许可证号',
  `bed_cnt` int DEFAULT NULL COMMENT '编制床位数',
  `mdtrtarea_admvs` varchar(20) DEFAULT NULL COMMENT '医保接口-就医地区划(机构级)',
  `insuplc_admdvs` varchar(20) DEFAULT NULL COMMENT '医保接口-参保地区划(机构级, 存储备用)',
  `api_url` varchar(255) DEFAULT NULL COMMENT '医保接口地址(机构级)',
  `file_download_url` varchar(255) DEFAULT NULL COMMENT '医保文件下载地址(机构级)',
  `recer_sys_code` varchar(50) DEFAULT NULL COMMENT '接收系统编码(机构级)',
  `infver` varchar(20) DEFAULT NULL COMMENT '接口版本号(机构级)',
  `opter_type` varchar(10) DEFAULT NULL COMMENT '经办人类别(机构级)',
  `opter` varchar(50) DEFAULT NULL COMMENT '经办人编号(机构级)',
  `opter_name` varchar(100) DEFAULT NULL COMMENT '经办人姓名(机构级)',
  `sign_no` varchar(100) DEFAULT NULL COMMENT '签名号(机构级)',
  `sm2_private_key` text COMMENT 'SM2私钥(机构级)',
  `sm2_public_key` text COMMENT 'SM2公钥(机构级)',
  `enc_type` varchar(20) DEFAULT NULL COMMENT '加密方式(机构级)',
  `mock_enabled` tinyint DEFAULT NULL COMMENT '模拟平台模式:1-模拟 0-真实(空=继承租户/全局)',
  `price_lv` tinyint DEFAULT NULL COMMENT '收费价格档次:1/2/3(医共体分级价格执行档)',
  `py_code` varchar(64) DEFAULT NULL COMMENT '拼音简码(机构名称首字母, 自动生成只读)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_org_code` (`tenant_id`,`org_code`),
  KEY `idx_parent` (`parent_id`)
) ENGINE=InnoDB AUTO_INCREMENT=442 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='医共体机构树(县乡村三级)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_param`
--

DROP TABLE IF EXISTS `sys_param`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_param` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint DEFAULT '0' COMMENT '租户ID(0=全局行, 跨租户可见)',
  `param_key` varchar(100) NOT NULL COMMENT '参数键',
  `param_value` text COMMENT '参数值(按 data_type 解析)',
  `scope_level` tinyint DEFAULT '0' COMMENT '作用域层级:0全局 1租户 2机构 3科室',
  `scope_id` bigint DEFAULT '0' COMMENT '作用域对象ID(配合 scope_level, 0=无)',
  `group_code` varchar(50) DEFAULT NULL COMMENT '分组编码(sys_param_group.group_code)',
  `param_name` varchar(200) DEFAULT NULL COMMENT '参数名称',
  `data_type` varchar(20) DEFAULT 'string' COMMENT '数据类型:string/int/decimal/bool/enum',
  `default_value` text COMMENT '默认值',
  `enum_options` varchar(500) DEFAULT NULL COMMENT '枚举选项(逗号分隔, data_type=enum 时有效)',
  `min_value` decimal(12,4) DEFAULT NULL COMMENT '最小值约束(数值型)',
  `max_value` decimal(12,4) DEFAULT NULL COMMENT '最大值约束(数值型)',
  `required` tinyint DEFAULT '0' COMMENT '是否必填:1是 0否',
  `allow_scope` varchar(20) DEFAULT '0,1,2,3' COMMENT '允许的作用域层级列表(逗号分隔)',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_param_scope` (`param_key`,`scope_level`,`scope_id`)
) ENGINE=InnoDB AUTO_INCREMENT=61 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统参数(tenant_id=0 为全局行, 已入 IGNORE_TABLES 手动隔离)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_param_group`
--

DROP TABLE IF EXISTS `sys_param_group`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_param_group` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `group_code` varchar(50) NOT NULL COMMENT '分组编码(唯一)',
  `group_name` varchar(100) DEFAULT NULL COMMENT '分组名称',
  `sort_no` int DEFAULT '0' COMMENT '排序号',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `create_by` varchar(50) DEFAULT NULL,
  `create_time` datetime DEFAULT NULL,
  `update_by` varchar(50) DEFAULT NULL,
  `update_time` datetime DEFAULT NULL,
  `deleted` tinyint DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_code` (`group_code`)
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统参数分组(全局共享, 无 tenant_id)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_role`
--

DROP TABLE IF EXISTS `sys_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `tenant_id` bigint DEFAULT NULL COMMENT '租户ID(NULL=全局预置角色)',
  `role_code` varchar(50) NOT NULL COMMENT '角色编码',
  `role_name` varchar(50) NOT NULL COMMENT '角色名称',
  `role_type` tinyint DEFAULT '2' COMMENT '类型:1-全局预置 2-租户自定义',
  `all_menus` tinyint DEFAULT '0' COMMENT '是否拥有全部菜单:1-是(ADMIN/SUPER_ADMIN)',
  `remark` varchar(200) DEFAULT NULL COMMENT '备注',
  `status` tinyint DEFAULT '1' COMMENT '状态:1-启用 0-停用',
  `create_by` varchar(50) DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(50) DEFAULT NULL COMMENT '更新人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除:0-正常 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_role_code` (`tenant_id`,`role_code`)
) ENGINE=InnoDB AUTO_INCREMENT=28 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色(全局预置+租户自定义)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_role_menu`
--

DROP TABLE IF EXISTS `sys_role_menu`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_menu` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `role_id` bigint NOT NULL COMMENT '角色ID',
  `menu_id` bigint NOT NULL COMMENT '菜单ID',
  `tenant_id` bigint DEFAULT NULL COMMENT '租户ID(冗余,随角色)',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu` (`role_id`,`menu_id`),
  KEY `idx_role` (`role_id`)
) ENGINE=InnoDB AUTO_INCREMENT=166 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色-菜单关联';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_tenant`
--

DROP TABLE IF EXISTS `sys_tenant`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_tenant` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '租户ID(主键)',
  `tenant_code` varchar(50) NOT NULL COMMENT '医院登录码(唯一)',
  `tenant_name` varchar(200) NOT NULL COMMENT '医院名称',
  `fixmedins_code` varchar(30) DEFAULT NULL COMMENT '定点医药机构编号',
  `fixmedins_name` varchar(200) DEFAULT NULL COMMENT '定点医药机构名称',
  `mdtrtarea_admvs` varchar(20) DEFAULT NULL COMMENT '就诊地区行政区划',
  `insuplc_admdvs` varchar(20) DEFAULT NULL COMMENT '参保地行政区划',
  `api_url` varchar(500) DEFAULT NULL COMMENT '医保交易接口地址',
  `file_download_url` varchar(500) DEFAULT NULL COMMENT '医保文件下载地址',
  `recer_sys_code` varchar(30) DEFAULT 'HIS' COMMENT '受理系统编号',
  `infver` varchar(20) DEFAULT 'V1.0' COMMENT '接口版本号',
  `opter_type` varchar(10) DEFAULT '2' COMMENT '经办人类别',
  `opter` varchar(50) DEFAULT NULL COMMENT '经办人编码',
  `opter_name` varchar(50) DEFAULT NULL COMMENT '经办人姓名',
  `sign_no` varchar(100) DEFAULT NULL COMMENT '签名证书编号',
  `sm2_private_key` varchar(500) DEFAULT NULL COMMENT 'SM2私钥',
  `sm2_public_key` varchar(500) DEFAULT NULL COMMENT 'SM2公钥',
  `enc_type` varchar(20) DEFAULT NULL COMMENT '加密类型',
  `mock_enabled` tinyint DEFAULT '1' COMMENT '模拟平台模式:1-模拟 0-真实',
  `status` tinyint DEFAULT '1' COMMENT '状态:1-启用 0-停用',
  `expire_time` datetime DEFAULT NULL COMMENT '服务到期时间',
  `contact` varchar(50) DEFAULT NULL COMMENT '联系人',
  `phone` varchar(30) DEFAULT NULL COMMENT '联系电话',
  `address` varchar(300) DEFAULT NULL COMMENT '医院地址',
  `create_by` varchar(50) DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(50) DEFAULT NULL COMMENT '更新人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除:0-正常 1-删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_code` (`tenant_code`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户(医院)表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_user`
--

DROP TABLE IF EXISTS `sys_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `tenant_id` bigint NOT NULL COMMENT '租户ID',
  `username` varchar(50) NOT NULL COMMENT '登录账号',
  `password` varchar(100) NOT NULL COMMENT '密码(BCrypt)',
  `real_name` varchar(50) DEFAULT NULL COMMENT '姓名',
  `role` varchar(30) NOT NULL DEFAULT 'DOCTOR' COMMENT '角色:ADMIN/REGISTRAR/DOCTOR/PHARMACIST/CASHIER/NURSE',
  `staff_id` bigint DEFAULT NULL COMMENT '关联职工ID',
  `dept_id` bigint DEFAULT NULL COMMENT '关联科室ID',
  `phone` varchar(30) DEFAULT NULL COMMENT '手机号',
  `status` tinyint DEFAULT '1' COMMENT '状态:1-启用 0-停用',
  `create_by` varchar(50) DEFAULT NULL COMMENT '创建人',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(50) DEFAULT NULL COMMENT '更新人',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `deleted` tinyint DEFAULT '0' COMMENT '逻辑删除:0-正常 1-删除',
  `org_id` bigint DEFAULT NULL COMMENT '归属机构ID',
  `role_id` bigint DEFAULT NULL COMMENT '角色ID(sys_role)',
  `dept_scope` varchar(500) DEFAULT NULL COMMENT '授权科室范围(逗号分隔dept_id, 空=仅主属科室)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_username` (`tenant_id`,`username`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=4805 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_user_org`
--

DROP TABLE IF EXISTS `sys_user_org`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user_org` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `org_id` bigint NOT NULL,
  `tenant_id` bigint DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_org` (`user_id`,`org_id`),
  KEY `idx_uo_user` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=41 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户可登录机构(多点执业)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `sys_user_role`
--

DROP TABLE IF EXISTS `sys_user_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user_role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `role_id` bigint NOT NULL,
  `tenant_id` bigint DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`,`role_id`),
  KEY `idx_ur_user` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5528 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色关联(一人多角色)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `tcm_catalog`
--

DROP TABLE IF EXISTS `tcm_catalog`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tcm_catalog` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` bigint NOT NULL DEFAULT '0' COMMENT '绉熸埛ID',
  `med_list_codg` varchar(50) DEFAULT NULL COMMENT '医疗目录编码',
  `drug_name` varchar(200) DEFAULT NULL COMMENT '单味药名称',
  `scmp_flag` varchar(3) DEFAULT NULL COMMENT '单复方标志',
  `qual_lv` varchar(20) DEFAULT NULL COMMENT '质量等级',
  `medi_part` varchar(100) DEFAULT NULL COMMENT '药用部位',
  `safe_dose` varchar(100) DEFAULT NULL COMMENT '安全计量',
  `conv_usage` varchar(200) DEFAULT NULL COMMENT '常规用法',
  `nature_flavor` varchar(100) DEFAULT NULL COMMENT '性味',
  `meridian_tropism` varchar(100) DEFAULT NULL COMMENT '归经',
  `variety` varchar(100) DEFAULT NULL COMMENT '品种',
  `vali_flag` varchar(3) DEFAULT NULL COMMENT '有效标志',
  `rid` varchar(40) DEFAULT NULL COMMENT '唯一记录号',
  `ver` varchar(30) DEFAULT NULL COMMENT '版本号',
  `ver_name` varchar(100) DEFAULT NULL COMMENT '版本名称',
  `raw_data` longtext COMMENT '原始数据行(TAB分隔)',
  PRIMARY KEY (`id`),
  KEY `idx_med_list_codg` (`med_list_codg`),
  KEY `idx_ver` (`ver`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='中药饮片目录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Temporary view structure for view `v_emr_element_flat`
--

DROP TABLE IF EXISTS `v_emr_element_flat`;
/*!50001 DROP VIEW IF EXISTS `v_emr_element_flat`*/;
SET @saved_cs_client     = @@character_set_client;
/*!50503 SET character_set_client = utf8mb4 */;
/*!50001 CREATE VIEW `v_emr_element_flat` AS SELECT 
 1 AS `record_id`,
 1 AS `visit_id`,
 1 AS `patient_id`,
 1 AS `patient_name`,
 1 AS `dept_name`,
 1 AS `doctor_name`,
 1 AS `record_type`,
 1 AS `title`,
 1 AS `status`,
 1 AS `content`,
 1 AS `structure_data`,
 1 AS `record_time`,
 1 AS `sign_time`,
 1 AS `archive_time`,
 1 AS `tenant_id`,
 1 AS `org_id`*/;
SET character_set_client = @saved_cs_client;

--
-- Final view structure for view `v_emr_element_flat`
--

/*!50001 DROP VIEW IF EXISTS `v_emr_element_flat`*/;
/*!50001 SET @saved_cs_client          = @@character_set_client */;
/*!50001 SET @saved_cs_results         = @@character_set_results */;
/*!50001 SET @saved_col_connection     = @@collation_connection */;
/*!50001 SET character_set_client      = utf8mb4 */;
/*!50001 SET character_set_results     = utf8mb4 */;
/*!50001 SET collation_connection      = utf8mb4_0900_ai_ci */;
/*!50001 CREATE ALGORITHM=UNDEFINED */
/*!50013 DEFINER=`root`@`localhost` SQL SECURITY DEFINER */
/*!50001 VIEW `v_emr_element_flat` AS select `r`.`id` AS `record_id`,`r`.`inp_visit_id` AS `visit_id`,`v`.`patient_id` AS `patient_id`,`p`.`name` AS `patient_name`,`d`.`dept_name` AS `dept_name`,`s`.`staff_name` AS `doctor_name`,`r`.`record_type` AS `record_type`,`r`.`title` AS `title`,`r`.`status` AS `status`,`r`.`content` AS `content`,`r`.`structure_data` AS `structure_data`,`r`.`record_time` AS `record_time`,`r`.`audit_time` AS `sign_time`,`r`.`archive_time` AS `archive_time`,`r`.`tenant_id` AS `tenant_id`,`r`.`org_id` AS `org_id` from ((((`his_inp_medical_record` `r` left join `his_inp_visit` `v` on(((`r`.`inp_visit_id` = `v`.`id`) and (`v`.`deleted` = 0)))) left join `his_patient` `p` on((`v`.`patient_id` = `p`.`id`))) left join `his_dept` `d` on((`v`.`dept_id` = `d`.`id`))) left join `his_staff` `s` on((`r`.`doctor_id` = `s`.`id`))) where (`r`.`deleted` = 0) */;
/*!50001 SET character_set_client      = @saved_cs_client */;
/*!50001 SET character_set_results     = @saved_cs_results */;
/*!50001 SET collation_connection      = @saved_col_connection */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-10-03 20:11:10
