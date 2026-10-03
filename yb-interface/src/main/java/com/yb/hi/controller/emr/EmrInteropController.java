package com.yb.hi.controller.emr;

import com.yb.hi.entity.emr.HisEmrWebhookSubscription;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.service.emr.EmrInteropService;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 病历互操作接口(P7b-1): RESTful 批量病历查询 + 病历事件 Webhook 订阅管理。
 *
 * 查询(读, 机构范围由登录身份收口): /api/emr/interop/
 * - GET /by-patient?patientId=&format=json|fhir|cda   按患者查全部住院病历;
 * - GET /by-visit?visitId=&scope=1|2&format=          按就诊查(1住院/2门诊);
 * - GET /by-category?deptId=&recordType=&startDate=&endDate=&format=   多条件类别查询;
 * - GET /flat?visitId=                                病历扁平化查询(v_emr_element_flat 视图)。
 *
 * Webhook 订阅(维护, Controller 显式守卫档位 = 机构管理员):
 * - GET /webhook/list        订阅列表(机构范围收口);
 * - POST /webhook            新建订阅;
 * - PUT /webhook/{id}        更新订阅;
 * - DELETE /webhook/{id}     删除订阅(逻辑删除);
 * - POST /webhook/{id}/test  同步连通性测试(HMAC 签名真实投递);
 * - GET /webhook/event-types 可订阅事件类型清单(前端下拉)。
 *
 * 事件推送不设入口: 由 EmrEvent 领域事件驱动(归档/签署/召回/封存/解封), 服务层异步投递。
 */
@RestController
@RequestMapping("/api/emr/interop")
public class EmrInteropController {

    private final EmrInteropService interopService;

    public EmrInteropController(EmrInteropService interopService) {
        this.interopService = interopService;
    }

    /* ==================== RESTful 批量查询 ==================== */

    /** 按患者查询其全部住院病历(format=json|fhir|cda, 默认 json)。 */
    @GetMapping("/by-patient")
    public R<Map<String, Object>> byPatient(@RequestParam Long patientId,
                                            @RequestParam(defaultValue = "json") String format) {
        return R.ok(interopService.queryByPatient(patientId, format));
    }

    /** 按就诊查询(scope=1 住院默认 / 2 门诊)。 */
    @GetMapping("/by-visit")
    public R<Map<String, Object>> byVisit(@RequestParam Long visitId,
                                          @RequestParam(defaultValue = "1") Integer scope,
                                          @RequestParam(defaultValue = "json") String format) {
        return R.ok(interopService.queryByVisit(visitId, scope, format));
    }

    /** 按类别查询(科室/病历类型/记录时间区间均可选, 多条件 AND 组合)。 */
    @GetMapping("/by-category")
    public R<Map<String, Object>> byCategory(@RequestParam(required = false) Long deptId,
                                             @RequestParam(required = false) Integer recordType,
                                             @RequestParam(required = false) String startDate,
                                             @RequestParam(required = false) String endDate,
                                             @RequestParam(defaultValue = "json") String format) {
        return R.ok(interopService.queryByCategory(deptId, recordType, startDate, endDate, format));
    }

    /** 扁平化查询(病历×就诊×患者×科室×医生联查视图)。 */
    @GetMapping("/flat")
    public R<List<Map<String, Object>>> flat(@RequestParam Long visitId) {
        return R.ok(interopService.flatElementQuery(visitId));
    }

    /* ==================== Webhook 订阅管理 ==================== */

    /** 订阅列表(机构范围收口: 牵头/超管全量, 非牵头仅本机构)。 */
    @GetMapping("/webhook/list")
    public R<List<HisEmrWebhookSubscription>> webhookList() {
        return R.ok(interopService.listWebhooks());
    }

    /** 新建订阅(机构管理员档位; 密钥缺省自动生成)。 */
    @PostMapping("/webhook")
    public R<HisEmrWebhookSubscription> createWebhook(@RequestBody HisEmrWebhookSubscription sub) {
        requireWebhookMaintainer();
        return R.ok(interopService.createWebhook(sub));
    }

    /** 更新订阅(机构管理员档位 + 机构归属校验)。 */
    @PutMapping("/webhook/{id}")
    public R<HisEmrWebhookSubscription> updateWebhook(@PathVariable Long id,
                                                      @RequestBody HisEmrWebhookSubscription sub) {
        requireWebhookMaintainer();
        return R.ok(interopService.updateWebhook(id, sub));
    }

    /** 删除订阅(逻辑删除, 机构管理员档位 + 机构归属校验)。 */
    @DeleteMapping("/webhook/{id}")
    public R<Void> deleteWebhook(@PathVariable Long id) {
        requireWebhookMaintainer();
        interopService.deleteWebhook(id);
        return R.ok();
    }

    /** 连通性测试(同步真实投递 TEST 事件, 返回成功/失败与订阅健康状态)。 */
    @PostMapping("/webhook/{id}/test")
    public R<Map<String, Object>> testWebhook(@PathVariable Long id) {
        requireWebhookMaintainer();
        return R.ok(interopService.testWebhook(id));
    }

    /** 可订阅事件类型清单(供前端多选下拉: [{name, displayName}])。 */
    @GetMapping("/webhook/event-types")
    public R<List<Map<String, Object>>> eventTypes() {
        return R.ok(interopService.eventTypes());
    }

    /** Webhook 维护守卫: 平台超管放行, 其余须 ADMIN/ORG_ADMIN(机构自治型配置, 参考 requireSelfOrgWrite 档位)。 */
    private void requireWebhookMaintainer() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        if (lu.hasRole(Roles.SUPER_ADMIN)) {
            return;
        }
        if (!lu.hasAnyRole(Roles.ADMIN, Roles.ORG_ADMIN)) {
            throw new BizException(403, "仅机构管理员可维护病历 Webhook 订阅");
        }
    }
}
