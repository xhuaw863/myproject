package com.yb.hi.controller.pharmacy;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.dto.pharmacy.DispenseReq;
import com.yb.hi.dto.pharmacy.DrugReturnReq;
import com.yb.hi.dto.pharmacy.TransferReq;
import com.yb.hi.entity.pharmacy.HisDispense;
import com.yb.hi.entity.pharmacy.HisDrugReturn;
import com.yb.hi.entity.pharmacy.HisPharmacyDef;
import com.yb.hi.entity.pharmacy.HisPharmacyDrugPrice;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.service.pharmacy.PharmacyDefService;
import com.yb.hi.service.pharmacy.PharmacyPriceService;
import com.yb.hi.service.pharmacy.PharmacyService;
import com.yb.hi.service.warehouse.WarehouseAccessService;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 药房接口(药房工作站): 待发药/发药/发药记录/退药申请与审批/退药记录 + 药房定义维护。
 * 读: 非牵头机构强制本院(scopeOrgId); 发药/退药为日常业务不限牵头(机构隔离由 scopeOrgId 保证);
 * 药房定义写(保存/启停): 仅牵头机构管理员(requireLeadWrite)。
 */
@RestController
@RequestMapping("/api/his/pharmacy")
public class PharmacyController {

    private final PharmacyService pharmacyService;
    private final PharmacyDefService pharmacyDefService;
    private final PharmacyPriceService pharmacyPriceService;
    private final OrgAccessGuard guard;
    private final WarehouseAccessService access;

    public PharmacyController(PharmacyService pharmacyService, PharmacyDefService pharmacyDefService,
                              PharmacyPriceService pharmacyPriceService, OrgAccessGuard guard,
                              WarehouseAccessService access) {
        this.pharmacyService = pharmacyService;
        this.pharmacyDefService = pharmacyDefService;
        this.pharmacyPriceService = pharmacyPriceService;
        this.guard = guard;
        this.access = access;
    }

    /** 待发药列表(dispense_status=0 的处方, 先开先发; pharmacyId 中药房只看中药处方) */
    @GetMapping("/todo")
    public R<IPage<Map<String, Object>>> todo(@RequestParam(required = false) Long orgId,
                                              @RequestParam(required = false) Long pharmacyId,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size) {
        requirePharmacyIfPresent(pharmacyId);
        return R.ok(pharmacyService.todoPage(guard.scopeOrgId(orgId), pharmacyId, keyword, page, size));
    }

    /** 发药详情(处方信息+药品明细+库存匹配, 供发药前核对; 三期: pharmacyId 空按处方绑定药房取数) */
    @GetMapping("/detail/{prescriptionId}")
    public R<Map<String, Object>> detail(@PathVariable Long prescriptionId,
                                         @RequestParam(required = false) Long pharmacyId) {
        requirePharmacyIfPresent(pharmacyId);
        return R.ok(pharmacyService.dispenseDetail(prescriptionId, pharmacyId));
    }

    /** 发药前价差预览(三期): 按绑定药房 FIFO 估算实发金额与价差(计费 vs 实发, 不补退仅对账) */
    @GetMapping("/dispense-preview")
    public R<Map<String, Object>> dispensePreview(@RequestParam Long prescriptionId) {
        return R.ok(pharmacyService.dispensePreview(prescriptionId));
    }

    /** 库存不足预检(三期): 处方逐药 需/存 缺口清单(不扣减) */
    @GetMapping("/shortage")
    public R<Map<String, Object>> shortage(@RequestParam Long prescriptionId,
                                           @RequestParam(required = false) Long pharmacyId) {
        requirePharmacyIfPresent(pharmacyId);
        return R.ok(pharmacyService.shortageInfo(prescriptionId, pharmacyId));
    }

    /** 改派可选药房(三期): 本机构启用药房逐房满足状态/缺口/预估实发金额与价差 */
    @GetMapping("/transfer-options")
    public R<List<Map<String, Object>>> transferOptions(@RequestParam Long prescriptionId) {
        return R.ok(pharmacyService.transferOptions(prescriptionId));
    }

    /** 处方改派发药药房(三期): 仅已收费未发药可改派; 不动费用/发票, 价差落发药记录对账 */
    @PostMapping("/transfer")
    public R<Map<String, Object>> transfer(@RequestBody TransferReq req) {
        requirePharmacyIfPresent(req == null ? null : req.getToPharmacyId());
        return R.ok(pharmacyService.transferPrescription(req));
    }

    /** 执行发药(乐观锁防重复, 出库单确认 FIFO 扣减库存) */
    @PostMapping("/dispense")
    public R<HisDispense> dispense(@RequestBody DispenseReq req) {
        req.setOrgId(guard.scopeOrgId(req.getOrgId()));
        requirePharmacyIfPresent(req.getPharmacyId());
        return R.ok(pharmacyService.doDispense(req));
    }

    /** 发药记录分页(机构/药房/状态/发药日期区间/单号或患者关键字) */
    @GetMapping("/records")
    public R<IPage<HisDispense>> records(@RequestParam(required = false) Long orgId,
                                         @RequestParam(required = false) Long pharmacyId,
                                         @RequestParam(required = false) Integer status,
                                         @RequestParam(required = false) String startDate,
                                         @RequestParam(required = false) String endDate,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "20") long size) {
        requirePharmacyIfPresent(pharmacyId);
        return R.ok(pharmacyService.dispensePage(guard.scopeOrgId(orgId), pharmacyId, status, startDate, endDate, keyword, page, size));
    }

    /** 发药记录导出(xlsx): 与列表同一机构/药房/日期口径 */
    @GetMapping("/export")
    @SuppressWarnings("unchecked")
    public void export(@RequestParam(required = false) Long orgId,
                       @RequestParam(required = false) Long pharmacyId,
                       @RequestParam(required = false) String startDate,
                       @RequestParam(required = false) String endDate,
                       HttpServletResponse resp) throws IOException {
        Map<String, Object> data = pharmacyService.exportDispense(guard.scopeOrgId(orgId), pharmacyId, startDate, endDate);
        String fname = "发药记录_" + LocalDate.now() + ".xlsx";
        String enc = URLEncoder.encode(fname, "UTF-8").replace("+", "%20");
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        resp.setHeader("Access-Control-Expose-Headers", "Content-Disposition");
        EasyExcel.write(resp.getOutputStream())
                .head((List<List<String>>) data.get("head"))
                .sheet("发药记录")
                .doWrite((List<List<Object>>) data.get("rows"));
    }

    /** 退药申请(仅已发药记录, 生成TY单待审核) */
    @PostMapping("/return")
    public R<HisDrugReturn> returnApply(@RequestBody DrugReturnReq req) {
        return R.ok(pharmacyService.returnApply(req));
    }

    /** 退药审批(通过: 入库单确认回补库存+处方置已退药; 驳回: 仅更新状态) */
    @PostMapping("/return/{id}/approve")
    public R<HisDrugReturn> returnApprove(@PathVariable Long id, @RequestParam boolean approved) {
        return R.ok(pharmacyService.returnApprove(id, approved));
    }

    /** 退药记录分页(机构/状态/申请日期区间) */
    @GetMapping("/returns")
    public R<IPage<HisDrugReturn>> returns(@RequestParam(required = false) Long orgId,
                                           @RequestParam(required = false) Integer status,
                                           @RequestParam(required = false) String startDate,
                                           @RequestParam(required = false) String endDate,
                                           @RequestParam(defaultValue = "1") long page,
                                           @RequestParam(defaultValue = "20") long size) {
        return R.ok(pharmacyService.returnPage(guard.scopeOrgId(orgId), status, startDate, endDate, page, size));
    }

    /* ================= 药房定义 ================= */

    /** 药房定义列表(按当前用户授权科室过滤; 机构启用中的药房, 按 sortNo 排序; 首次访问自动创建默认门诊药房) */
    @GetMapping("/pharmacy-def")
    public R<List<HisPharmacyDef>> pharmacyDefList(@RequestParam(required = false) Long orgId) {
        return R.ok(access.accessiblePharmacies(resolveOrgId(orgId)));
    }

    /** 保存药房定义(新增/编辑; code 同机构唯一, 关联药库须存在且启用) */
    @PostMapping("/pharmacy-def")
    public R<HisPharmacyDef> pharmacyDefSave(@RequestBody HisPharmacyDef def) {
        guard.requireLeadWrite();
        def.setOrgId(resolveOrgId(def.getOrgId()));
        return R.ok(pharmacyDefService.save(def));
    }

    /** 药房启停(enabled=true 启用 / false 停用) */
    @PostMapping("/pharmacy-def/{id}/toggle")
    public R<HisPharmacyDef> pharmacyDefToggle(@PathVariable Long id, @RequestParam boolean enabled) {
        guard.requireLeadWrite();
        return R.ok(pharmacyDefService.toggle(id, enabled));
    }

    /* ================= 三期: 科室默认药房解析 / 药房维度定价 ================= */

    /** 科室默认发药药房解析(医生站开方预载): 按 deptId×中西药渠道(rxType); 未配置返回 pharmacyId=null */
    @GetMapping("/resolve-default")
    public R<Map<String, Object>> resolveDefault(@RequestParam Long deptId, @RequestParam String rxType) {
        Long pid = pharmacyService.resolvePharmacyForPrescribe(deptId, rxType, null, resolveOrgId(null));
        HisPharmacyDef def = pharmacyDefService.find(pid);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("pharmacyId", pid);
        out.put("pharmacyName", def == null ? null : def.getName());
        return R.ok(out);
    }

    /** 药房定价分页(按药房列目录药+覆盖价/生效价/库存) */
    @GetMapping("/price/page")
    public R<Map<String, Object>> pricePage(@RequestParam(required = false) Long orgId,
                                            @RequestParam Long pharmacyId,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "20") long size) {
        return R.ok(pharmacyPriceService.pricePage(resolveOrgId(orgId), pharmacyId, keyword, page, size));
    }

    /** 保存药房覆盖价(upsert): 管理员/药师可维护 */
    @PostMapping("/price/save")
    public R<HisPharmacyDrugPrice> priceSave(@RequestBody Map<String, Object> body) {
        requirePriceWrite();
        Long orgId = resolveOrgId(toLong(body.get("orgId")));
        Long pharmacyId = toLong(body.get("pharmacyId"));
        Long drugCatalogId = toLong(body.get("drugCatalogId"));
        Object price = body.get("retailPrice");
        if (price == null) {
            throw new BizException(400, "零售价不能为空");
        }
        BigDecimal retailPrice;
        try {
            retailPrice = new BigDecimal(price.toString().trim());
        } catch (NumberFormatException e) {
            throw new BizException(400, "零售价格式不正确: " + price);
        }
        return R.ok(pharmacyPriceService.save(orgId, pharmacyId, drugCatalogId, retailPrice));
    }

    /** 清空药房覆盖价(回落目录价) */
    @PostMapping("/price/clear")
    public R<Void> priceClear(@RequestBody Map<String, Object> body) {
        requirePriceWrite();
        pharmacyPriceService.clear(toLong(body.get("pharmacyId")), toLong(body.get("drugCatalogId")));
        return R.ok();
    }

    /** 定价维护守卫: 本机构管理员/药师/超管(牵头 requireLeadWrite 语义过严, 药房自维护覆盖价属日常业务) */
    private void requirePriceWrite() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        if (!lu.hasAnyRole(Roles.ADMIN, Roles.ORG_ADMIN, Roles.PHARMACIST, Roles.SUPER_ADMIN)) {
            throw new BizException(403, "仅本机构管理员或药师可维护药房定价");
        }
        if (lu.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法维护药房定价");
        }
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    /** 机构作用域: 牵头可取入参, 非牵头强制本机构; 入参为空回退当前登录机构 */
    private Long resolveOrgId(Long requested) {
        Long oid = guard.scopeOrgId(requested);
        if (oid != null) {
            return oid;
        }
        LoginUser lu = UserContext.get();
        return lu == null ? null : lu.getOrgId();
    }

    /** pharmacyId 非空时校验当前用户是否有权操作该药房(按授权科室); 为空(未选/全院)不拦截 */
    private void requirePharmacyIfPresent(Long pharmacyId) {
        if (pharmacyId != null) {
            access.requirePharmacyAccess(pharmacyId);
        }
    }
}
