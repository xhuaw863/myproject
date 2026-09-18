package com.yb.hi.service;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.yb.hi.common.FileParseUtil;
import com.yb.hi.common.YbHttpClient;
import com.yb.hi.common.YbResponse;
import com.yb.hi.config.YbConfig;
import com.yb.hi.entity.dict.*;
import com.yb.hi.mapper.dict.DictVersionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 基础字典下载服务
 * 实现接口: 1301-西药中成药目录, 1302-中药饮片目录, 1303-医疗机构制剂目录,
 *          1305-医疗服务项目目录, 1306-医用耗材目录, 1307-疾病与诊断目录
 *
 * 通用流程:
 * 1. 读取本地最大版本号 ver
 * 2. 调用医保交易(input.data.ver), 返回 file_qury_no / filename / data_cnt
 * 3. 通过文件下载接口下载 ZIP, 解压 TXT, 按 TAB 分割为行
 * 4. 行数据转换为实体并批量入库
 * 5. 取最后一条记录的版本号更新本地版本
 */
@Slf4j
@Service
public class DictDownloadService {

    private final YbHttpClient ybHttpClient;
    private final YbConfig ybConfig;
    private final DictVersionMapper dictVersionMapper;

    public DictDownloadService(YbHttpClient ybHttpClient, YbConfig ybConfig, DictVersionMapper dictVersionMapper) {
        this.ybHttpClient = ybHttpClient;
        this.ybConfig = ybConfig;
        this.dictVersionMapper = dictVersionMapper;
    }

    /** 【1301】西药中成药目录下载 */
    public YbResponse downloadDrugCatalog() {
        return downloadDict("1301", "drug_catalog");
    }

    /** 【1302】中药饮片目录下载 */
    public YbResponse downloadTcmCatalog() {
        return downloadDict("1302", "tcm_catalog");
    }

    /** 【1303】医疗机构制剂目录下载 */
    public YbResponse downloadPreparationCatalog() {
        return downloadDict("1303", "preparation_catalog");
    }

    /** 【1305】医疗服务项目目录下载 */
    public YbResponse downloadMedServiceCatalog() {
        return downloadDict("1305", "med_service_catalog");
    }

    /** 【1306】医用耗材目录下载 */
    public YbResponse downloadConsumableCatalog() {
        return downloadDict("1306", "consumable_catalog");
    }

    /** 【1307】疾病与诊断目录下载 */
    public YbResponse downloadDiseaseCatalog() {
        return downloadDict("1307", "disease_catalog");
    }

    /** 下载全部基础字典 */
    public Map<String, Object> downloadAll() {
        Map<String, Object> results = new HashMap<>();
        results.put("1301_西药中成药目录", downloadDrugCatalog());
        results.put("1302_中药饮片目录", downloadTcmCatalog());
        results.put("1303_医疗机构制剂目录", downloadPreparationCatalog());
        results.put("1305_医疗服务项目目录", downloadMedServiceCatalog());
        results.put("1306_医用耗材目录", downloadConsumableCatalog());
        results.put("1307_疾病与诊断目录", downloadDiseaseCatalog());
        return results;
    }

    /**
     * 通用字典下载逻辑
     */
    private YbResponse downloadDict(String infno, String dictType) {
        log.info("开始下载字典: infno={}, type={}", infno, dictType);

        // 1. 获取本地最大版本号
        String localVer = dictVersionMapper.getMaxVersion(dictType);
        if (localVer == null || localVer.isEmpty()) {
            localVer = "0";
        }

        // 2. 构建请求: input 节点为 {"data": {"ver": "xxx"}}
        JSONObject data = new JSONObject();
        data.put("ver", localVer);
        JSONObject input = new JSONObject();
        input.put("data", data);

        YbResponse response = ybHttpClient.call(infno, input);
        if (!response.isSuccess()) {
            log.error("字典下载失败: infno={}, err={}", infno, response.getErrMsg());
            return response;
        }

        // 3. 解析输出
        JSONObject output = response.getOutputObject();
        String fileQueryNo = output.getString("file_qury_no");
        String filename = output.getString("filename");
        String dataCnt = output.getString("data_cnt");
        log.info("字典下载响应: fileQueryNo={}, filename={}, dataCnt={}", fileQueryNo, filename, dataCnt);

        if (fileQueryNo == null || fileQueryNo.isEmpty()) {
            log.info("字典[{}]无增量数据需要下载", dictType);
            return response;
        }

        // 4. 下载文件、解析、入库
        try {
            byte[] fileBytes = ybHttpClient.downloadFile(fileQueryNo);
            String savePath = ybConfig.getDictFilePath() + dictType + "/" + filename;
            FileParseUtil.saveFile(fileBytes, savePath);

            List<String[]> rows = FileParseUtil.parseZipToRows(fileBytes);
            if (!rows.isEmpty()) {
                saveDictData(dictType, rows);
                // 5. 更新本地版本号(取最后一条记录)
                String newVer = extractVersion(dictType, rows.get(rows.size() - 1));
                if (newVer != null && !newVer.isEmpty()) {
                    saveVersion(dictType, infno, newVer);
                }
            }
            log.info("字典[{}]下载完成, 共{}条记录", dictType, rows.size());
        } catch (Exception e) {
            log.error("字典文件下载/解析失败: dictType={}", dictType, e);
        }

        return response;
    }

    /** 本地字典版本号 upsert(按当前租户): 无记录则插入, 有则更新 */
    private void saveVersion(String dictType, String infno, String newVer) {
        DictVersion dv = dictVersionMapper.selectOne(
                new QueryWrapper<DictVersion>().eq("dict_type", dictType).last("LIMIT 1"));
        String now = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        if (dv == null) {
            dv = new DictVersion();
            dv.setDictType(dictType);
            dv.setDictName(dictName(dictType));
            dv.setInfno(infno);
            dv.setMaxVer(newVer);
            dv.setLastDldTime(now);
            dv.setUpdtTime(now);
            dictVersionMapper.insert(dv);
        } else {
            if (dv.getInfno() == null || dv.getInfno().isEmpty()) {
                dv.setInfno(infno);
            }
            dv.setMaxVer(newVer);
            dv.setLastDldTime(now);
            dv.setUpdtTime(now);
            dictVersionMapper.updateById(dv);
        }
    }

    /** 字典类型 -> 中文名称 */
    private String dictName(String dictType) {
        switch (dictType) {
            case "drug_catalog": return "西药中成药目录";
            case "tcm_catalog": return "中药饮片目录";
            case "preparation_catalog": return "医疗机构制剂目录";
            case "med_service_catalog": return "医疗服务项目目录";
            case "consumable_catalog": return "医用耗材目录";
            case "disease_catalog": return "疾病与诊断目录";
            default: return dictType;
        }
    }

    /**
     * 根据字典类型转换并批量入库
     */
    @Transactional(rollbackFor = Exception.class)
    protected void saveDictData(String dictType, List<String[]> rows) {
        switch (dictType) {
            case "drug_catalog":
                Db.saveBatch(toDrugCatalogList(rows));
                break;
            case "tcm_catalog":
                Db.saveBatch(toTcmCatalogList(rows));
                break;
            case "preparation_catalog":
                Db.saveBatch(toPreparationCatalogList(rows));
                break;
            case "med_service_catalog":
                Db.saveBatch(toMedServiceCatalogList(rows));
                break;
            case "consumable_catalog":
                Db.saveBatch(toConsumableCatalogList(rows));
                break;
            case "disease_catalog":
                Db.saveBatch(toDiseaseCatalogList(rows));
                break;
            default:
                log.warn("未知字典类型: {}", dictType);
        }
    }

    /**
     * 从记录中提取版本号(不同字典版本号所在列不同, 索引从0开始)
     */
    private String extractVersion(String dictType, String[] row) {
        switch (dictType) {
            case "drug_catalog":
                return FileParseUtil.getField(row, 82); // 第83列:版本号
            case "tcm_catalog":
                return FileParseUtil.getField(row, 17); // 第18列:版本号
            case "preparation_catalog":
                return FileParseUtil.getField(row, 70); // 第71列:版本号
            case "med_service_catalog":
                return FileParseUtil.getField(row, 14); // 第15列:版本号
            case "consumable_catalog":
                return FileParseUtil.getField(row, 69); // 第70列:版本号
            case "disease_catalog":
                return FileParseUtil.getField(row, 22); // 第23列:版本号
            default:
                return null;
        }
    }

    // ==================== 行数据 -> 实体 转换 ====================

    private List<DrugCatalog> toDrugCatalogList(List<String[]> rows) {
        List<DrugCatalog> list = new ArrayList<>(rows.size());
        for (String[] r : rows) {
            DrugCatalog e = new DrugCatalog();
            e.setMedListCodg(FileParseUtil.getField(r, 0));
            e.setDrugProdname(FileParseUtil.getField(r, 1));
            e.setGennameCodg(FileParseUtil.getField(r, 2));
            e.setDrugGenname(FileParseUtil.getField(r, 3));
            e.setChemname(FileParseUtil.getField(r, 4));
            e.setAlis(FileParseUtil.getField(r, 5));
            e.setEngName(FileParseUtil.getField(r, 6));
            e.setDosform(FileParseUtil.getField(r, 9));
            e.setDosformName(FileParseUtil.getField(r, 10));
            e.setDrugType(FileParseUtil.getField(r, 11));
            e.setDrugTypeName(FileParseUtil.getField(r, 12));
            e.setDrugSpec(FileParseUtil.getField(r, 13));
            e.setMinUseunt(FileParseUtil.getField(r, 38));
            e.setMinSalunt(FileParseUtil.getField(r, 39));
            e.setMinUnt(FileParseUtil.getField(r, 40));
            e.setMinPrcunt(FileParseUtil.getField(r, 48));
            e.setWubi(FileParseUtil.getField(r, 49));
            e.setPinyin(FileParseUtil.getField(r, 50));
            e.setProdEntpName(FileParseUtil.getField(r, 53));
            e.setValiFlag(FileParseUtil.getField(r, 78));
            e.setRid(FileParseUtil.getField(r, 79));
            e.setVer(FileParseUtil.getField(r, 82));
            e.setVerName(FileParseUtil.getField(r, 83));
            e.setRawData(String.join("\t", r));
            list.add(e);
        }
        return list;
    }

    private List<TcmCatalog> toTcmCatalogList(List<String[]> rows) {
        List<TcmCatalog> list = new ArrayList<>(rows.size());
        for (String[] r : rows) {
            TcmCatalog e = new TcmCatalog();
            e.setMedListCodg(FileParseUtil.getField(r, 0));
            e.setDrugName(FileParseUtil.getField(r, 1));
            e.setScmpFlag(FileParseUtil.getField(r, 2));
            e.setQualLv(FileParseUtil.getField(r, 3));
            e.setMediPart(FileParseUtil.getField(r, 5));
            e.setSafeDose(FileParseUtil.getField(r, 6));
            e.setConvUsage(FileParseUtil.getField(r, 7));
            e.setNatureFlavor(FileParseUtil.getField(r, 8));
            e.setMeridianTropism(FileParseUtil.getField(r, 9));
            e.setVariety(FileParseUtil.getField(r, 10));
            e.setValiFlag(FileParseUtil.getField(r, 13));
            e.setRid(FileParseUtil.getField(r, 14));
            e.setVer(FileParseUtil.getField(r, 17));
            e.setVerName(FileParseUtil.getField(r, 18));
            e.setRawData(String.join("\t", r));
            list.add(e);
        }
        return list;
    }

    private List<PreparationCatalog> toPreparationCatalogList(List<String[]> rows) {
        List<PreparationCatalog> list = new ArrayList<>(rows.size());
        for (String[] r : rows) {
            PreparationCatalog e = new PreparationCatalog();
            e.setMedListCodg(FileParseUtil.getField(r, 0));
            e.setDrugProdname(FileParseUtil.getField(r, 1));
            e.setAlis(FileParseUtil.getField(r, 2));
            e.setDosform(FileParseUtil.getField(r, 4));
            e.setDosformName(FileParseUtil.getField(r, 5));
            e.setIng(FileParseUtil.getField(r, 7));
            e.setEfccAtd(FileParseUtil.getField(r, 8));
            e.setDrugSpec(FileParseUtil.getField(r, 10));
            e.setDrugType(FileParseUtil.getField(r, 18));
            e.setDrugTypeName(FileParseUtil.getField(r, 19));
            e.setProdEntpName(FileParseUtil.getField(r, 40));
            e.setValiFlag(FileParseUtil.getField(r, 64));
            e.setRid(FileParseUtil.getField(r, 67));
            e.setVer(FileParseUtil.getField(r, 70));
            e.setVerName(FileParseUtil.getField(r, 71));
            e.setRawData(String.join("\t", r));
            list.add(e);
        }
        return list;
    }

    private List<MedServiceCatalog> toMedServiceCatalogList(List<String[]> rows) {
        List<MedServiceCatalog> list = new ArrayList<>(rows.size());
        for (String[] r : rows) {
            MedServiceCatalog e = new MedServiceCatalog();
            e.setMedListCodg(FileParseUtil.getField(r, 0));
            e.setPrcunt(FileParseUtil.getField(r, 1));
            e.setPrcuntName(FileParseUtil.getField(r, 2));
            e.setItemExplain(FileParseUtil.getField(r, 3));
            e.setItemExcluded(FileParseUtil.getField(r, 4));
            e.setItemConnotation(FileParseUtil.getField(r, 5));
            e.setValiFlag(FileParseUtil.getField(r, 6));
            e.setMemo(FileParseUtil.getField(r, 7));
            e.setItemCat(FileParseUtil.getField(r, 8));
            e.setItemName(FileParseUtil.getField(r, 9));
            e.setItemExplain2(FileParseUtil.getField(r, 10));
            e.setRid(FileParseUtil.getField(r, 13));
            e.setVer(FileParseUtil.getField(r, 14));
            e.setVerName(FileParseUtil.getField(r, 15));
            e.setRawData(String.join("\t", r));
            list.add(e);
        }
        return list;
    }

    private List<ConsumableCatalog> toConsumableCatalogList(List<String[]> rows) {
        List<ConsumableCatalog> list = new ArrayList<>(rows.size());
        for (String[] r : rows) {
            ConsumableCatalog e = new ConsumableCatalog();
            e.setMedListCodg(FileParseUtil.getField(r, 0));
            e.setConsName(FileParseUtil.getField(r, 1));
            e.setUdi(FileParseUtil.getField(r, 2));
            e.setGennameCode(FileParseUtil.getField(r, 3));
            e.setGenname(FileParseUtil.getField(r, 4));
            e.setProdModel(FileParseUtil.getField(r, 5));
            e.setSpecCode(FileParseUtil.getField(r, 6));
            e.setSpec(FileParseUtil.getField(r, 7));
            e.setConsCat(FileParseUtil.getField(r, 8));
            e.setSpecModel(FileParseUtil.getField(r, 9));
            e.setMinUseunt(FileParseUtil.getField(r, 17));
            e.setMinSalunt(FileParseUtil.getField(r, 35));
            e.setHiValueFlag(FileParseUtil.getField(r, 36));
            e.setValiFlag(FileParseUtil.getField(r, 67));
            e.setRid(FileParseUtil.getField(r, 68));
            e.setVer(FileParseUtil.getField(r, 69));
            e.setVerName(FileParseUtil.getField(r, 70));
            e.setRawData(String.join("\t", r));
            list.add(e);
        }
        return list;
    }

    private List<DiseaseCatalog> toDiseaseCatalogList(List<String[]> rows) {
        List<DiseaseCatalog> list = new ArrayList<>(rows.size());
        for (String[] r : rows) {
            DiseaseCatalog e = new DiseaseCatalog();
            e.setDiseCode(FileParseUtil.getField(r, 0));
            e.setChapter(FileParseUtil.getField(r, 1));
            e.setChapterName(FileParseUtil.getField(r, 3));
            e.setCatCode(FileParseUtil.getField(r, 6));
            e.setCatName(FileParseUtil.getField(r, 7));
            e.setSubcatCode(FileParseUtil.getField(r, 8));
            e.setSubcatName(FileParseUtil.getField(r, 9));
            e.setDiagCode(FileParseUtil.getField(r, 10));
            e.setDiagName(FileParseUtil.getField(r, 11));
            e.setUseFlag(FileParseUtil.getField(r, 12));
            e.setNatStdDiagCode(FileParseUtil.getField(r, 13));
            e.setNatStdDiagName(FileParseUtil.getField(r, 14));
            e.setClinDiagCode(FileParseUtil.getField(r, 15));
            e.setClinDiagName(FileParseUtil.getField(r, 16));
            e.setValiFlag(FileParseUtil.getField(r, 18));
            e.setRid(FileParseUtil.getField(r, 19));
            e.setVer(FileParseUtil.getField(r, 22));
            e.setVerName(FileParseUtil.getField(r, 23));
            e.setRawData(String.join("\t", r));
            list.add(e);
        }
        return list;
    }
}
