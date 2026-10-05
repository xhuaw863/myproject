package com.yb.hi.controller.basedata;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.yb.hi.entity.basedata.HisFeeTypeDict;
import com.yb.hi.entity.basedata.HisPayMethodDict;
import com.yb.hi.framework.common.R;
import com.yb.hi.service.basedata.FeeTypeDictService;
import com.yb.hi.service.basedata.PayMethodDictService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 费别/支付方式自定义字典接口(机构级, 门诊住院统一维护)。
 * 读: 登录即可(业务下拉消费); 写: Service 内 requireSelfOrgWrite(本机构管理员自治)。
 */
@RestController
@RequestMapping("/api/his/fee-pay-dict")
public class FeePayDictController {

    private final FeeTypeDictService feeTypeService;
    private final PayMethodDictService payMethodService;

    public FeePayDictController(FeeTypeDictService feeTypeService, PayMethodDictService payMethodService) {
        this.feeTypeService = feeTypeService;
        this.payMethodService = payMethodService;
    }

    /* ================= 患者费别 ================= */

    @GetMapping("/fee-type/page")
    public R<IPage<HisFeeTypeDict>> feeTypePage(@RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "20") long size,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String scope,
                                                @RequestParam(required = false) Integer status,
                                                @RequestParam(required = false) Long orgId,
                                                @RequestParam(defaultValue = "false") boolean withSubOrgs) {
        return R.ok(feeTypeService.listPage(page, size, keyword, scope, status, orgId, withSubOrgs));
    }

    /** 业务下拉(scene=OTP/IPT, 空=全部启用项) */
    @GetMapping("/fee-type/options")
    public R<List<HisFeeTypeDict>> feeTypeOptions(@RequestParam(required = false) String scene) {
        return R.ok(feeTypeService.options(scene));
    }

    @PostMapping("/fee-type/create")
    public R<HisFeeTypeDict> feeTypeCreate(@RequestBody HisFeeTypeDict body) {
        return R.ok(feeTypeService.create(body));
    }

    @PutMapping("/fee-type/update")
    public R<HisFeeTypeDict> feeTypeUpdate(@RequestParam Long id, @RequestBody HisFeeTypeDict body) {
        return R.ok(feeTypeService.update(id, body));
    }

    @DeleteMapping("/fee-type/delete")
    public R<Void> feeTypeDelete(@RequestParam Long id) {
        feeTypeService.delete(id);
        return R.ok(null);
    }

    /* ================= 支付方式 ================= */

    @GetMapping("/pay-method/page")
    public R<IPage<HisPayMethodDict>> payPage(@RequestParam(defaultValue = "1") long page,
                                              @RequestParam(defaultValue = "20") long size,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false) String scope,
                                              @RequestParam(required = false) Integer status,
                                              @RequestParam(required = false) Long orgId,
                                              @RequestParam(defaultValue = "false") boolean withSubOrgs) {
        return R.ok(payMethodService.listPage(page, size, keyword, scope, status, orgId, withSubOrgs));
    }

    /** 业务下拉(scene=OTP/IPT, 空=全部启用项); orgId 仅牵头维护端可指定(费别弹窗按行机构取支付候选), 非牵头后端恒锁本机构 */
    @GetMapping("/pay-method/options")
    public R<List<HisPayMethodDict>> payOptions(@RequestParam(required = false) String scene,
                                                @RequestParam(required = false) Long orgId) {
        return R.ok(payMethodService.options(scene, orgId));
    }

    @PostMapping("/pay-method/create")
    public R<HisPayMethodDict> payCreate(@RequestBody HisPayMethodDict body) {
        return R.ok(payMethodService.create(body));
    }

    @PutMapping("/pay-method/update")
    public R<HisPayMethodDict> payUpdate(@RequestParam Long id, @RequestBody HisPayMethodDict body) {
        return R.ok(payMethodService.update(id, body));
    }

    @DeleteMapping("/pay-method/delete")
    public R<Void> payDelete(@RequestParam Long id) {
        payMethodService.delete(id);
        return R.ok(null);
    }

    /* ================= 业务页一次取数 ================= */

    /**
     * 挂号/结算联动取数: 费别(按场景) + 支付方式(按场景)一次带回;
     * 费别项自带 payLimitJson, 前端选中费别后本地按白名单过滤支付方式, 无需二次请求。
     */
    @GetMapping("/reg-options")
    public R<Map<String, Object>> regOptions(@RequestParam(required = false, defaultValue = "OTP") String scene) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("feeTypes", feeTypeService.options(scene));
        m.put("payMethods", payMethodService.options(scene));
        return R.ok(m);
    }
}
