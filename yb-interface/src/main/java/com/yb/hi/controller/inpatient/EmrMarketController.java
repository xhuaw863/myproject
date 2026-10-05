package com.yb.hi.controller.inpatient;

import com.yb.hi.entity.inpatient.HisEmrComponentShare;
import com.yb.hi.entity.inpatient.HisEmrTemplate;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.emr.EmrComponentMarketService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 病历组件与模板市场接口(高级版): 市场目录浏览/上架下架/克隆为独立副本/引用反查分析。
 */
@RestController
@RequestMapping("/api/his/emr/market")
public class EmrMarketController {

    private final EmrComponentMarketService marketService;

    public EmrMarketController(EmrComponentMarketService marketService) {
        this.marketService = marketService;
    }

    /** 市场目录(compType/category/keyword 可选, 仅上架行) */
    @GetMapping("/list")
    public R<List<HisEmrComponentShare>> list(@RequestParam(required = false) String compType,
                                              @RequestParam(required = false) String category,
                                              @RequestParam(required = false) String keyword) {
        return marketService.listMarket(compType, category, keyword);
    }

    /** 上架母件为可共享组件(幂等: 已登记则更新元信息并重新上架) */
    @PostMapping("/publish")
    public R<HisEmrComponentShare> publish(@RequestBody HisEmrComponentShare in) {
        return marketService.publish(in);
    }

    /** 下架(登记人本人或管理员) */
    @PutMapping("/{id}/offline")
    public R<Void> offline(@PathVariable Long id) {
        return marketService.offline(id);
    }

    /** 克隆为独立副本(targetScope 0全院/1科室/2个人, 默认2) */
    @PostMapping("/{id}/clone")
    public R<Map<String, Object>> clone(@PathVariable Long id,
                                        @RequestParam(required = false) Integer targetScope) {
        return marketService.cloneToMine(id, targetScope);
    }

    /** 引用反查: 某片段/数据元/图示/宏被哪些模板引用(refType=fragment|element|drawing|macro) */
    @GetMapping("/referenceAnalysis")
    public R<List<HisEmrTemplate>> referenceAnalysis(@RequestParam String refType, @RequestParam String refKey) {
        return marketService.referenceAnalysis(refType, refKey);
    }
}
