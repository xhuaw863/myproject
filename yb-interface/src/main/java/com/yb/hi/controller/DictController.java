package com.yb.hi.controller;

import com.yb.hi.common.YbResponse;
import com.yb.hi.service.DictDownloadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 基础字典下载接口
 */
@RestController
@RequestMapping("/api/dict")
public class DictController {

    private final DictDownloadService dictDownloadService;

    public DictController(DictDownloadService dictDownloadService) {
        this.dictDownloadService = dictDownloadService;
    }

    /** 【1301】西药中成药目录下载 */
    @GetMapping("/drug")
    public YbResponse drug() {
        return dictDownloadService.downloadDrugCatalog();
    }

    /** 【1302】中药饮片目录下载 */
    @GetMapping("/tcm")
    public YbResponse tcm() {
        return dictDownloadService.downloadTcmCatalog();
    }

    /** 【1303】医疗机构制剂目录下载 */
    @GetMapping("/preparation")
    public YbResponse preparation() {
        return dictDownloadService.downloadPreparationCatalog();
    }

    /** 【1305】医疗服务项目目录下载 */
    @GetMapping("/med-service")
    public YbResponse medService() {
        return dictDownloadService.downloadMedServiceCatalog();
    }

    /** 【1306】医用耗材目录下载 */
    @GetMapping("/consumable")
    public YbResponse consumable() {
        return dictDownloadService.downloadConsumableCatalog();
    }

    /** 【1307】疾病与诊断目录下载 */
    @GetMapping("/disease")
    public YbResponse disease() {
        return dictDownloadService.downloadDiseaseCatalog();
    }

    /** 下载全部基础字典 */
    @GetMapping("/all")
    public Map<String, Object> all() {
        return dictDownloadService.downloadAll();
    }
}
