package com.yb.hi.controller.community;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.community.CatalogMapApplyReq;
import com.yb.hi.dto.community.CatalogMapAutoReq;
import com.yb.hi.dto.community.CatalogMapClearReq;
import com.yb.hi.entity.community.HisYbMapLog;
import com.yb.hi.framework.common.R;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.community.DiagMapService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 医保疾病对照接口(医共体统一诊断字典 -> 医保标准字典)。
 * 参照三目录医保对照(CatalogMapController)。读接口对已登录用户开放;
 * 写接口(apply/auto/clear) requireLeadOrg 守卫——仅租户牵头机构(org_level=1)的 ADMIN 可写。
 * dictType: west-西医疾病 tcm-中医疾病 symp-中医症候 oper-手术编码。
 */
@RestController
@RequestMapping("/api/diag-map")
public class DiagMapController {

    private final DiagMapService diagMapService;
    private final OrgAccessGuard guard;

    public DiagMapController(DiagMapService diagMapService, OrgAccessGuard guard) {
        this.diagMapService = diagMapService;
        this.guard = guard;
    }

    /** 覆盖率看板: 每类别 {total, mapped, unmapped} */
    @GetMapping("/summary")
    public R<Map<String, Object>> summary() {
        return R.ok(diagMapService.summary());
    }

    /** 院内诊断条目分页: dictType=west|tcm|symp|oper, mapped=0仅未对照/1仅已对照/空全部 */
    @GetMapping("/items")
    public R<IPage<Map<String, Object>>> items(@RequestParam String dictType,
                                               @RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "20") long size,
                                               @RequestParam(required = false) Integer mapped,
                                               @RequestParam(required = false) String keyword) {
        return R.ok(diagMapService.items(dictType, page, size, mapped, keyword));
    }

    /** 打分候选: 选中院内条目后拉取标准字典候选(按名称匹配) */
    @GetMapping("/candidates")
    public R<List<Map<String, Object>>> candidates(@RequestParam String dictType,
                                                   @RequestParam Long itemId,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(defaultValue = "20") int limit) {
        return R.ok(diagMapService.candidates(dictType, itemId, keyword, limit));
    }

    /** 人工/预览确认写入对照(幂等); 已对照条目改码属变更对照, 需前端二次确认后带 force=true */
    @PostMapping("/apply")
    public R<Integer> apply(@RequestBody CatalogMapApplyReq req) {
        requireLeadOrg();
        if (req == null || req.getItems() == null || req.getItems().isEmpty()) {
            return R.ok(0);
        }
        return R.ok(diagMapService.apply(req.getCatalog(), req.getItems(),
                HisYbMapLog.SRC_MANUAL, Boolean.TRUE.equals(req.getForce())));
    }

    /** 批量自动对照: dryRun=true 仅预览, 否则写入达阈值项 */
    @PostMapping("/auto")
    public R<Map<String, Object>> auto(@RequestBody CatalogMapAutoReq req) {
        if (req == null) {
            return R.ok(null);
        }
        boolean dryRun = Boolean.TRUE.equals(req.getDryRun());
        if (!dryRun) {
            requireLeadOrg();
        }
        return R.ok(diagMapService.auto(req.getCatalog(), req.getItemIds(), req.getThreshold(), dryRun));
    }

    /** 清除对照(医保码置空, 留痕 CLEAR) */
    @PostMapping("/clear")
    public R<Integer> clear(@RequestBody CatalogMapClearReq req) {
        requireLeadOrg();
        if (req == null || req.getItemIds() == null || req.getItemIds().isEmpty()) {
            return R.ok(0);
        }
        return R.ok(diagMapService.clear(req.getCatalog(), req.getItemIds()));
    }

    /** 对照变更留痕分页: dictType/itemId 可选, kw=院内码/院内名/医保码, start/end=变更日期(含两端) */
    @GetMapping("/logs")
    public R<IPage<HisYbMapLog>> logs(@RequestParam(required = false) String dictType,
                                      @RequestParam(required = false) Long itemId,
                                      @RequestParam(required = false) String kw,
                                      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate start,
                                      @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end,
                                      @RequestParam(defaultValue = "1") long page,
                                      @RequestParam(defaultValue = "20") long size) {
        return R.ok(diagMapService.logs(dictType, itemId, kw, start, end, page, size));
    }

    /** 导出对照结果(xlsx): 与列表同一筛选条件, 一次性导出全部匹配行 */
    @GetMapping("/export")
    public void export(@RequestParam String dictType,
                       @RequestParam(required = false) Integer mapped,
                       @RequestParam(required = false) String keyword,
                       HttpServletResponse resp) throws IOException {
        Map<String, Object> data = diagMapService.exportRows(dictType, mapped, keyword);
        String fname = "医保疾病对照结果_" + typeLabel(dictType) + "_" + LocalDate.now() + ".xlsx";
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        resp.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        @SuppressWarnings("unchecked")
        List<List<String>> head = (List<List<String>>) data.get("head");
        @SuppressWarnings("unchecked")
        List<List<Object>> rows = (List<List<Object>>) data.get("rows");
        EasyExcel.write(resp.getOutputStream()).head(head).sheet("对照结果").doWrite(rows);
    }

    private static String typeLabel(String dictType) {
        if ("west".equals(dictType)) {
            return "西医疾病";
        }
        if ("tcm".equals(dictType)) {
            return "中医疾病";
        }
        if ("symp".equals(dictType)) {
            return "中医症候";
        }
        if ("oper".equals(dictType)) {
            return "手术编码";
        }
        return String.valueOf(dictType);
    }

    /** 仅租户牵头机构(org_level=1)的 ADMIN 可写对照; 平台超管只读不参与写。 */
    private void requireLeadOrg() {
        guard.requireLeadOrg("仅牵头机构管理员可维护医保疾病对照");
    }
}
