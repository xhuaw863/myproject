package com.yb.hi.controller.community;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.community.CatalogMapApplyReq;
import com.yb.hi.dto.community.CatalogMapAutoReq;
import com.yb.hi.dto.community.CatalogMapClearReq;
import com.yb.hi.entity.community.HisYbMapLog;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.entity.SysOrg;
import com.yb.hi.platform.mapper.SysOrgMapper;
import com.yb.hi.service.community.CatalogMapService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 三目录医保对照接口(医疗机构目录 -> 标准字典医保目录)。
 * 读接口对已登录用户开放; 写接口(apply/auto/clear) requireLeadOrg 守卫——仅租户牵头机构(org_level=1)的 ADMIN 可写。
 */
@RestController
@RequestMapping("/api/catalog-map")
public class CatalogMapController {

    private final CatalogMapService catalogMapService;
    private final SysOrgMapper orgMapper;

    public CatalogMapController(CatalogMapService catalogMapService, SysOrgMapper orgMapper) {
        this.catalogMapService = catalogMapService;
        this.orgMapper = orgMapper;
    }

    /** 覆盖率看板: 每目录 {total, mapped, unmapped} */
    @GetMapping("/summary")
    public R<Map<String, Object>> summary() {
        return R.ok(catalogMapService.summary());
    }

    /** 院内工作队列分页: catalog=drug|cons|charge, mapped=0仅未对照/1仅已对照/空全部 */
    @GetMapping("/items")
    public R<IPage<Map<String, Object>>> items(@RequestParam String catalog,
                                               @RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "20") long size,
                                               @RequestParam(required = false) Integer mapped,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String itemType) {
        return R.ok(catalogMapService.items(catalog, page, size, mapped, keyword, itemType));
    }

    /** 打分候选: 选中院内条目后拉取标准字典候选(score>=0.5 前 limit) */
    @GetMapping("/candidates")
    public R<List<Map<String, Object>>> candidates(@RequestParam String catalog,
                                                   @RequestParam Long itemId,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(defaultValue = "20") int limit) {
        return R.ok(catalogMapService.candidates(catalog, itemId, keyword, limit));
    }

    /** 人工/预览确认写入对照(幂等) */
    @PostMapping("/apply")
    public R<Integer> apply(@RequestBody CatalogMapApplyReq req) {
        requireLeadOrg();
        if (req == null || req.getItems() == null || req.getItems().isEmpty()) {
            return R.ok(0);
        }
        return R.ok(catalogMapService.apply(req.getCatalog(), req.getItems()));
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
        return R.ok(catalogMapService.auto(req.getCatalog(), req.getItemIds(), req.getThreshold(), dryRun));
    }

    /** 清除对照(医保码置空, 留痕 CLEAR) */
    @PostMapping("/clear")
    public R<Integer> clear(@RequestBody CatalogMapClearReq req) {
        requireLeadOrg();
        if (req == null || req.getItemIds() == null || req.getItemIds().isEmpty()) {
            return R.ok(0);
        }
        return R.ok(catalogMapService.clear(req.getCatalog(), req.getItemIds()));
    }

    /** 对照变更留痕分页: catalog/itemId 可选, 按变更时间倒序 */
    @GetMapping("/logs")
    public R<IPage<HisYbMapLog>> logs(@RequestParam(required = false) String catalog,
                                      @RequestParam(required = false) Long itemId,
                                      @RequestParam(defaultValue = "1") long page,
                                      @RequestParam(defaultValue = "20") long size) {
        return R.ok(catalogMapService.logs(catalog, itemId, page, size));
    }

    /** 某时点生效的医保码: at 支持 yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd */
    @GetMapping("/code-at")
    public R<Map<String, Object>> codeAt(@RequestParam String catalog,
                                         @RequestParam Long itemId,
                                         @RequestParam String at) {
        return R.ok(catalogMapService.codeAt(catalog, itemId, parseAt(at)));
    }

    private LocalDateTime parseAt(String at) {
        String s = at == null ? "" : at.trim().replace('T', ' ');
        try {
            if (s.length() <= 10) {
                return LocalDate.parse(s).atStartOfDay();
            }
            return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            throw new BizException(400, "时间格式应为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss: " + at);
        }
    }

    /** 仅租户牵头机构(org_level=1)的 ADMIN 可写对照; 平台超管只读不参与写。 */
    private void requireLeadOrg() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        if (!Roles.ADMIN.equals(lu.getRole())) {
            throw new BizException(403, "仅牵头机构管理员可维护医保目录对照");
        }
        Long orgId = lu.getOrgId();
        if (orgId == null) {
            throw new BizException(403, "当前用户未归属机构, 无法维护医保目录对照");
        }
        SysOrg org = orgMapper.selectById(orgId);
        if (org == null || org.getOrgLevel() == null || org.getOrgLevel() != 1) {
            throw new BizException(403, "仅牵头机构(县级)可维护医保目录对照");
        }
    }
}
