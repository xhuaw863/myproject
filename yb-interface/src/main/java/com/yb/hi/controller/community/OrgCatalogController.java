package com.yb.hi.controller.community;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.community.OrgCatalogSaveReq;
import com.yb.hi.entity.basedata.HisChargeItem;
import com.yb.hi.entity.community.HisDrugCatalog;
import com.yb.hi.entity.community.HisMedDict;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.community.OrgCatalogService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 机构开展目录(L3)接口: 本机构勾选能开展的项目 + 医生站/收费取数(仅本机构启用项)。
 * 选用维护(启停) requireOrgAdmin(ADMIN/ORG_ADMIN/SUPER_ADMIN); 取数接口对已登录用户开放(供医生站/收费台)。
 */
@RestController
@RequestMapping("/api/org-catalog")
public class OrgCatalogController {

    private final OrgCatalogService service;

    public OrgCatalogController(OrgCatalogService service) {
        this.service = service;
    }

    /** 机构目录选用分页(L2 项目 + 本机构开展状态); enabled=空 全部 / 1 仅已开展 / 0 仅未开展 */
    @GetMapping("/selection")
    public R<Map<String, Object>> selection(@RequestParam String catalogType,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) Integer enabled,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.selectionPage(catalogType, keyword, enabled, page, size));
    }

    /** 导出本院目录选用(xlsx): 与列表同一筛选(目录类型/关键字/开展状态), 一次性导出全部匹配行 */
    @GetMapping("/export")
    @SuppressWarnings("unchecked")
    public void export(@RequestParam String catalogType,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) Integer enabled,
                       HttpServletResponse resp) throws IOException {
        Map<String, Object> data = service.exportRows(catalogType, keyword, enabled);
        String fname = "机构目录选用_" + typeLabel(catalogType) + "_" + LocalDate.now() + ".xlsx";
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        resp.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        List<List<String>> head = (List<List<String>>) data.get("head");
        List<List<Object>> rows = (List<List<Object>>) data.get("rows");
        EasyExcel.write(resp.getOutputStream()).head(head).sheet("机构目录选用").doWrite(rows);
    }

    private static String typeLabel(String t) {
        switch (t == null ? "" : t) {
            case "drug":
                return "药品";
            case "cons":
                return "耗材";
            case "charge":
                return "收费项目";
            case "usage":
                return "用法";
            case "freq":
                return "用药频次";
            default:
                return String.valueOf(t);
        }
    }

    /** 目录项详情(L2 全部非空字段带中文标签), 供选用页「详情」弹窗 */
    @GetMapping("/detail")
    public R<List<Map<String, Object>>> detail(@RequestParam String catalogType,
                                               @RequestParam Long catalogId) {
        return R.ok(service.detail(catalogType, catalogId));
    }

    /** 启停本机构开展状态(单条/批量) */
    @PostMapping("/save")
    public R<Void> save(@RequestBody OrgCatalogSaveReq req) {
        requireOrgAdmin();
        service.save(req);
        return R.ok();
    }

    /** 医生站取数: 本机构可开药药品(含换算字段与零售价); pharmacyId 非空时附药房生效价与在库量(三期按房取数) */
    @GetMapping("/available/drug")
    public R<IPage<HisDrugCatalog>> availableDrug(@RequestParam(required = false) String keyword,
                                                  @RequestParam(defaultValue = "1") long page,
                                                  @RequestParam(defaultValue = "20") long size,
                                                  @RequestParam(required = false) Long pharmacyId) {
        return R.ok(service.availableDrug(keyword, page, size, pharmacyId));
    }

    /** 医生站/收费取数: 本机构可开收费项目(附机构执行价 execPrice) */
    @GetMapping("/available/charge")
    public R<IPage<HisChargeItem>> availableCharge(@RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) String itemType,
                                                   @RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.availableCharge(keyword, itemType, page, size));
    }

    /** 医生站取数: 本机构启用的用药字典(dictType=usage/freq), 供用法/频次下拉 */
    @GetMapping("/available/med-dict")
    public R<List<HisMedDict>> availableMedDict(@RequestParam String dictType) {
        return R.ok(service.availableMedDict(dictType));
    }

    /** 仅机构管理员(牵头 ADMIN / 非牵头 ORG_ADMIN / 超管)可维护本机构开展目录 */
    private void requireOrgAdmin() {
        LoginUser lu = UserContext.get();
        String role = lu == null ? null : lu.getRole();
        if (!Roles.ADMIN.equals(role) && !Roles.SUPER_ADMIN.equals(role) && !Roles.ORG_ADMIN.equals(role)) {
            throw new BizException(403, "仅机构管理员可维护本机构开展目录");
        }
    }
}
