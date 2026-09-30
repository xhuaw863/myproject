package com.yb.hi.controller.inpatient;

import com.yb.hi.framework.common.R;
import com.yb.hi.service.inpatient.InpCaseFirstPageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 住院病案首页接口(T38): 聚合生成 / 查询 / 草稿保存 / 提交(医师签名) / 质控审核(评分+签名) / 打印数据。
 * <p>机构隔离与状态机(草稿1→已提交2→已审核3)在 Service 层(见 InpCaseFirstPageService);
 * 医师/质控签名图由前端 HIS.SignaturePad 落盘后传 URL 写入。
 */
@RestController
@RequestMapping("/api/his/inp/case-page")
public class InpCaseFirstPageController {

    private final InpCaseFirstPageService caseFirstPageService;

    public InpCaseFirstPageController(InpCaseFirstPageService caseFirstPageService) {
        this.caseFirstPageService = caseFirstPageService;
    }

    /** 聚合生成病案首页(幂等: 草稿重复生成覆盖, 已提交/已审核拒绝) */
    @PostMapping("/generate/{visitId}")
    public R<Map<String, Object>> generate(@PathVariable Long visitId) {
        return R.ok(caseFirstPageService.generateFirstPage(visitId));
    }

    /** 查询病案首页(含患者/科室/医师名称, exists=false 表示尚未生成) */
    @GetMapping("/{visitId}")
    public R<Map<String, Object>> get(@PathVariable Long visitId) {
        return R.ok(caseFirstPageService.getFirstPage(visitId));
    }

    /** 保存草稿(仅 status=1; 白名单字段部分更新) */
    @PutMapping("/{visitId}")
    public R<Void> save(@PathVariable Long visitId, @RequestBody Map<String, Object> data) {
        caseFirstPageService.saveFirstPage(visitId, data);
        return R.ok();
    }

    /** 提交(草稿→已提交): body {doctorSignImg: "签名图URL"} */
    @PostMapping("/{visitId}/submit")
    public R<Void> submit(@PathVariable Long visitId, @RequestBody Map<String, Object> body) {
        String doctorSignImg = body == null ? null : (String) body.get("doctorSignImg");
        caseFirstPageService.submitFirstPage(visitId, doctorSignImg);
        return R.ok();
    }

    /** 质控审核(已提交→已审核): body {score: 95, qcSignImg: "签名图URL"} */
    @PostMapping("/{visitId}/audit")
    public R<Void> audit(@PathVariable Long visitId, @RequestBody Map<String, Object> body) {
        Integer score = body == null ? null : toInt(body.get("score"));
        String qcSignImg = body == null ? null : (String) body.get("qcSignImg");
        caseFirstPageService.auditFirstPage(visitId, score, qcSignImg);
        return R.ok();
    }

    /** 打印数据(查询基础上补打印标题与质控医师姓名, 前端组装 HTML 打印) */
    @GetMapping("/{visitId}/print")
    public R<Map<String, Object>> print(@PathVariable Long visitId) {
        return R.ok(caseFirstPageService.getFirstPageForPrint(visitId));
    }

    /** 宽松数值解析: JSON 数字体可能是 Integer/Long/String, 非法返回 null */
    private Integer toInt(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
