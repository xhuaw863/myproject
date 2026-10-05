package com.yb.hi.service.inpatient;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.dto.inpatient.InpOrderDTO;
import com.yb.hi.dto.inpatient.OrderTemplateItemDTO;
import com.yb.hi.entity.basedata.HisChargeItem;
import com.yb.hi.entity.basedata.HisStaff;
import com.yb.hi.entity.community.HisDrugCatalog;
import com.yb.hi.entity.community.HisOrgCatalog;
import com.yb.hi.entity.doctor.HisChargeAddonRule;
import com.yb.hi.entity.inpatient.HisInpChargeDetail;
import com.yb.hi.entity.inpatient.HisInpOrder;
import com.yb.hi.entity.inpatient.HisInpOrderExec;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.entity.inpatient.HisOrderTemplate;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.basedata.HisChargeItemMapper;
import com.yb.hi.mapper.basedata.HisStaffMapper;
import com.yb.hi.mapper.community.HisDrugCatalogMapper;
import com.yb.hi.mapper.community.HisOrgCatalogMapper;
import com.yb.hi.mapper.inpatient.HisInpChargeDetailMapper;
import com.yb.hi.mapper.inpatient.HisInpOrderExecMapper;
import com.yb.hi.mapper.inpatient.HisInpOrderMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import com.yb.hi.platform.service.SystemParamResolver;
import com.yb.hi.service.doctor.HisChargeAddonRuleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 住院医嘱服务: 开嘱(服务端定价)/批量成组开嘱/停止/作废(同组联动), 临时医嘱开立即记账。
 * 状态机: 1新开 → 2已审核 → 3执行中 → 4已完成; 2/3可停止(→5), 仅1可作废(→6)。
 * 机构隔离: 医嘱归属跟随就诊机构(org_id), 非牵头机构仅可操作本机构就诊的数据。
 */
@Slf4j
@Service
public class InpOrderService extends ServiceImpl<HisInpOrderMapper, HisInpOrder> {

    private final HisInpVisitMapper visitMapper;
    private final HisInpChargeDetailMapper chargeDetailMapper;
    private final HisDrugCatalogMapper drugCatalogMapper;
    private final HisChargeItemMapper chargeItemMapper;
    private final HisOrgCatalogMapper orgCatalogMapper;
    private final HisStaffMapper staffMapper;
    private final OrgAccessGuard guard;
    private final InpAllergyService allergyService;
    private final OrderTemplateService orderTemplateService;
    private final HisInpOrderExecMapper execMapper;
    private final JdbcTemplate jdbcTemplate;
    private final HisChargeAddonRuleService chargeAddonRuleService;
    private final SystemParamResolver paramResolver;

    public InpOrderService(HisInpVisitMapper visitMapper, HisInpChargeDetailMapper chargeDetailMapper,
                           HisDrugCatalogMapper drugCatalogMapper, HisChargeItemMapper chargeItemMapper,
                           HisOrgCatalogMapper orgCatalogMapper, HisStaffMapper staffMapper, OrgAccessGuard guard,
                           InpAllergyService allergyService, OrderTemplateService orderTemplateService,
                           HisInpOrderExecMapper execMapper, JdbcTemplate jdbcTemplate,
                           HisChargeAddonRuleService chargeAddonRuleService, SystemParamResolver paramResolver) {
        this.visitMapper = visitMapper;
        this.chargeDetailMapper = chargeDetailMapper;
        this.drugCatalogMapper = drugCatalogMapper;
        this.chargeItemMapper = chargeItemMapper;
        this.orgCatalogMapper = orgCatalogMapper;
        this.staffMapper = staffMapper;
        this.guard = guard;
        this.allergyService = allergyService;
        this.orderTemplateService = orderTemplateService;
        this.execMapper = execMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.chargeAddonRuleService = chargeAddonRuleService;
        this.paramResolver = paramResolver;
    }

    /** 当前登录医生(his_staff.id): 无职工关联的账号不能执行医生站操作 */
    public static Long currentDoctorId() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        if (lu.getStaffId() == null) {
            throw new BizException(403, "当前账号未关联职工档案, 无法执行医生站操作");
        }
        return lu.getStaffId();
    }

    /** 医嘱分页查询(inpVisitId必传, 类型/状态/开单科室可选) */
    public IPage<HisInpOrder> listOrders(Long inpVisitId, Integer orderType, Integer orderStatus,
                                         Long orderDeptId, long page, long size) {
        if (inpVisitId == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        requireVisit(inpVisitId);
        return lambdaQuery()
                .eq(HisInpOrder::getInpVisitId, inpVisitId)
                .eq(orderType != null, HisInpOrder::getOrderType, orderType)
                .eq(orderStatus != null, HisInpOrder::getOrderStatus, orderStatus)
                .eq(orderDeptId != null, HisInpOrder::getOrderDeptId, orderDeptId)
                .orderByDesc(HisInpOrder::getStartTime)
                .orderByDesc(HisInpOrder::getId)
                .page(new Page<>(page, size));
    }

    /** 开单科室: 优先取当前登录医生所属科室, 回落就诊科室(多科会诊时区分开单科室)。 */
    private Long resolveOrderDeptId(HisInpVisit visit) {
        LoginUser lu = UserContext.get();
        if (lu != null && lu.getDeptId() != null) {
            return lu.getDeptId();
        }
        return visit == null ? null : visit.getDeptId();
    }

    /**
     * 开立医嘱(单条):
     * - 服务端定价: 药品类(orderCategory=1)按药品目录零售价(校验本机构开展), 非药品类按收费项目价格, 均不信任客户端传价;
     * - 临时医嘱(type=2)开立即生成一条费用明细并累加就诊总费用(长期医嘱由执行/记账链路逐日生成);
     * - 医嘱机构归属跟随就诊机构, 保证按机构过滤时与就诊同口径;
     * - 高警示/需皮试药品回写 high_alert_flag/double_check_flag(T36), 护士站执行端据此双人核对拦截。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpOrder createOrder(InpOrderDTO dto) {
        if (dto == null || dto.getInpVisitId() == null) {
            throw new BizException(400, "住院就诊ID不能为空");
        }
        if (dto.getOrderType() == null || (dto.getOrderType() != 1 && dto.getOrderType() != 2)) {
            throw new BizException(400, "医嘱类型必须为1长期/2临时");
        }
        if (dto.getOrderCategory() == null) {
            throw new BizException(400, "医嘱分类不能为空");
        }
        if (!StringUtils.hasText(dto.getOrderContent())) {
            throw new BizException(400, "医嘱内容不能为空");
        }
        HisInpVisit visit = requireVisit(dto.getInpVisitId());
        if (visit.getVisitStatus() == null || visit.getVisitStatus() != 2) {
            throw new BizException("患者当前不在院(就诊状态:" + visit.getVisitStatus() + "), 不能开立医嘱");
        }
        Long doctorId = currentDoctorId();
        // 手麻P1: 代开校验(权限按被代开医生口径; doctor_id 仍记实际操作人, proxy_doctor_id 记被代开)
        if (dto.getProxyDoctorId() != null && !dto.getProxyDoctorId().equals(doctorId)) {
            HisStaff target = staffMapper.selectById(dto.getProxyDoctorId());
            if (target == null || Integer.valueOf(0).equals(target.getStatus())) {
                throw new BizException(400, "被代开医生不存在或已停用");
            }
            LoginUser lu = UserContext.get();
            boolean privileged = lu != null && lu.hasAnyRole(Roles.ADMIN, Roles.ORG_ADMIN, Roles.SUPER_ADMIN);
            boolean sameDept = lu != null && lu.getDeptId() != null && lu.getDeptId().equals(target.getDeptId());
            if (!privileged && !sameDept) {
                throw new BizException(403, "无代开权限: 仅同科室医师或管理员可代开医嘱");
            }
        }
        // 手麻P1: 手术关联/阶段一致性(术前挂申请单, 术中/术后挂手术); 手术类药品医嘱需发送后才入队
        boolean surgeryLinked = dto.getSurgeryId() != null || dto.getSurgeryApplyId() != null;
        if (surgeryLinked) {
            if (dto.getOrderPhase() == null) {
                throw new BizException(400, "手术医嘱须指定阶段(1术前/2术中/3术后)");
            }
            if (dto.getOrderPhase() == 1 && dto.getSurgeryApplyId() == null) {
                throw new BizException(400, "术前医嘱须关联手术申请单");
            }
            if (dto.getOrderPhase() != 1 && dto.getSurgeryId() == null) {
                throw new BizException(400, "术中/术后医嘱须关联手术");
            }
        }

        HisInpOrder o = new HisInpOrder();
        o.setOrgId(visit.getOrgId() != null ? visit.getOrgId() : guard.currentOrgId());
        o.setInpVisitId(visit.getId());
        o.setOrderType(dto.getOrderType());
        o.setOrderCategory(dto.getOrderCategory());
        o.setOrderContent(dto.getOrderContent().trim());
        o.setChargeItemId(dto.getChargeItemId());
        o.setDrugId(dto.getDrugId());
        o.setSpec(dto.getSpec());
        o.setDosage(dto.getDosage());
        o.setDosageUnit(dto.getDosageUnit());
        o.setUsageCode(dto.getUsageCode());
        o.setFreqCode(dto.getFreqCode());
        o.setQuantity(dto.getQuantity() == null ? BigDecimal.ONE : dto.getQuantity());
        o.setStartTime(LocalDateTime.now());
        o.setOrderStatus(1);
        o.setDoctorId(doctorId);
        o.setOrderDeptId(resolveOrderDeptId(visit));
        o.setGroupNo(StringUtils.hasText(dto.getGroupNo()) ? dto.getGroupNo().trim() : null);
        // 手麻P1: 手术关联/阶段/代开落列; 手术类药品医嘱初始未发送(0), 普通医嘱保持空(自动入队)
        o.setSurgeryId(dto.getSurgeryId());
        o.setSurgeryApplyId(dto.getSurgeryApplyId());
        o.setOrderPhase(surgeryLinked ? dto.getOrderPhase() : null);
        o.setProxyDoctorId(dto.getProxyDoctorId());
        o.setProxyReason(StringUtils.hasText(dto.getProxyReason()) ? dto.getProxyReason().trim() : null);
        // 检查类医嘱(orderCategory=2)加收维度要素(与门诊 his_order_item 同口径): 落检查部位/计价部位数/造影方式, 驱动 part/contrast 加收
        if (dto.getOrderCategory() != null && dto.getOrderCategory() == 2) {
            o.setExamPart(StringUtils.hasText(dto.getExamPart()) ? dto.getExamPart().trim() : null);
            o.setSiteCount(dto.getSiteCount() == null || dto.getSiteCount() < 1 ? 1 : dto.getSiteCount());
            o.setContrastMode(StringUtils.hasText(dto.getContrastMode()) ? dto.getContrastMode().trim() : null);
        }
        if (surgeryLinked && dto.getOrderCategory() != null && dto.getOrderCategory() == 1) {
            o.setSendPharmStatus(0);
        }
        /* 药师审核(T35): 药品类医嘱(orderCategory=1, 服务端定价时强制 drugId 非空)开立即进入待审队列,
         * 护士审核前须药师先审; 非药品医嘱无需药审(0); 复制驳回医嘱重新开立时同样重置为待审 */
        o.setPharmAuditStatus(dto.getOrderCategory() != null && dto.getOrderCategory() == 1 ? 1 : 0);

        PricedItem priced = resolvePrice(dto, visit);
        o.setUnitPrice(priced.price);
        // 高警示/需皮试药品回写核对标志(T36): 皮试药品同样须双人核对, 护士站执行端按标志拦截
        o.setHighAlertFlag(priced.highAlertFlag);
        o.setDoubleCheckFlag(priced.doubleCheckFlag);
        save(o);

        if (dto.getOrderType() == 2 && o.getUnitPrice() != null) {
            HisInpChargeDetail cd = buildChargeDetail(o, priced);
            chargeDetailMapper.insert(cd);
            BigDecimal visitCharge = cd.getAmount();
            // 医保检查控费加收(住院临时检查医嘱即时加收): 命中启用加收规则且对应 inpatient 开关开启时追加多部位/增强加收费用明细
            if (dto.getOrderCategory() != null && dto.getOrderCategory() == 2) {
                for (HisInpChargeDetail addon : buildExamSurchargeCharges(o)) {
                    chargeDetailMapper.insert(addon);
                    visitCharge = visitCharge.add(addon.getAmount());
                }
            }
            accumulateTotalCost(visit.getId(), visitCharge);
        }
        log.info("开立住院医嘱: id={}, visitId={}, type={}, category={}, unitPrice={}, groupNo={}",
                o.getId(), visit.getId(), o.getOrderType(), o.getOrderCategory(), o.getUnitPrice(), o.getGroupNo());
        // 医保预审(T42, 非阻断): 依据定价环节回查的目录医保码/甲乙丙分类, 在开立回执上瞬态回填提示
        appendInsurancePreview(o, priced);
        // 手麻P4c 新生儿软守卫(非阻断): 新生儿药品医嘱且未录体重 -> 回执瞬态提示补录, 不拦截开立
        appendNewbornWeightGuard(o, visit);
        return o;
    }

    /**
     * 批量开立(成组医嘱): 同一次住院就诊的一组医嘱共享 group_no(GRP+时间戳),
     * 单条入参自带 groupNo 时保留原值; 整批一个事务, 任一条失败全部回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<HisInpOrder> batchCreate(List<InpOrderDTO> dtos) {
        if (CollectionUtils.isEmpty(dtos)) {
            throw new BizException(400, "批量医嘱不能为空");
        }
        Long firstVisitId = null;
        for (InpOrderDTO dto : dtos) {
            if (dto == null || dto.getInpVisitId() == null) {
                throw new BizException(400, "批量医嘱中存在缺少就诊ID的明细");
            }
            if (firstVisitId == null) {
                firstVisitId = dto.getInpVisitId();
            } else if (!firstVisitId.equals(dto.getInpVisitId())) {
                throw new BizException(400, "成组医嘱必须属于同一次住院就诊");
            }
        }
        String groupNo = "GRP" + System.currentTimeMillis();
        List<HisInpOrder> out = new ArrayList<>(dtos.size());
        for (InpOrderDTO dto : dtos) {
            if (!StringUtils.hasText(dto.getGroupNo())) {
                dto.setGroupNo(groupNo);
            }
            out.add(createOrder(dto));
        }
        log.info("批量开立住院医嘱: visitId={}, 条数={}, groupNo={}", firstVisitId, out.size(), groupNo);
        return out;
    }

    /**
     * 停止医嘱: 仅已审核(2)/执行中(3)可停止; 记录停嘱时间与停嘱医生;
     * 同 group_no 的开立/已审核/执行中组员联动停止(已完成/已停止/已作废不动)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpOrder stopOrder(Long id) {
        HisInpOrder order = requireOrder(id);
        Integer st = order.getOrderStatus();
        if (st == null || (st != 2 && st != 3)) {
            throw new BizException("仅已审核(2)/执行中(3)的医嘱可停止, 当前状态: " + st);
        }
        Long doctorId = currentDoctorId();
        LocalDateTime now = LocalDateTime.now();
        boolean ok = lambdaUpdate()
                .set(HisInpOrder::getOrderStatus, 5)
                .set(HisInpOrder::getStopTime, now)
                .set(HisInpOrder::getStopDoctorId, doctorId)
                .eq(HisInpOrder::getId, id)
                .eq(HisInpOrder::getOrderStatus, st)
                .update();
        if (!ok) {
            throw new BizException("医嘱状态已变化, 请刷新后重试");
        }
        if (StringUtils.hasText(order.getGroupNo())) {
            lambdaUpdate()
                    .set(HisInpOrder::getOrderStatus, 5)
                    .set(HisInpOrder::getStopTime, now)
                    .set(HisInpOrder::getStopDoctorId, doctorId)
                    .eq(HisInpOrder::getGroupNo, order.getGroupNo())
                    .ne(HisInpOrder::getId, id)
                    .in(HisInpOrder::getOrderStatus, 1, 2, 3)
                    .update();
        }
        log.info("停止住院医嘱: id={}, visitId={}, 联动组={}", id, order.getInpVisitId(), order.getGroupNo());
        return getById(id);
    }

    /**
     * 作废医嘱: 仅新开(1)未审核可作废; 同组仍为新开的组员联动作废;
     * 开立时已记账的临时医嘱费用同步冲销(明细置退费态, 就诊总费用回减)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpOrder cancelOrder(Long id) {
        HisInpOrder order = requireOrder(id);
        if (order.getOrderStatus() == null || order.getOrderStatus() != 1) {
            throw new BizException("仅新开(1)未审核的医嘱可作废, 当前状态: " + order.getOrderStatus());
        }
        /* 手麻P3a执行锁: 存在已执行留痕记录(exec_status=2)的医嘱不可作废
         * (标准住院链零影响: 病区审核后 order_status=2 本就不可作废, 此守卫仅拦截手术侧执行后回退1的场景) */
        Long execDone = execMapper.selectCount(new LambdaQueryWrapper<HisInpOrderExec>()
                .eq(HisInpOrderExec::getOrderId, id)
                .eq(HisInpOrderExec::getExecStatus, 2));
        if (execDone != null && execDone > 0) {
            throw new BizException("医嘱已执行, 不可作废");
        }
        currentDoctorId();
        // 本次作废范围: 自身 + 同组仍为新开的组员(费用冲销需覆盖全部作废对象)
        List<Long> cancelIds = new ArrayList<>();
        cancelIds.add(id);
        if (StringUtils.hasText(order.getGroupNo())) {
            List<HisInpOrder> members = lambdaQuery()
                    .eq(HisInpOrder::getGroupNo, order.getGroupNo())
                    .eq(HisInpOrder::getOrderStatus, 1)
                    .ne(HisInpOrder::getId, id)
                    .list();
            for (HisInpOrder m : members) {
                cancelIds.add(m.getId());
            }
        }
        boolean ok = lambdaUpdate()
                .set(HisInpOrder::getOrderStatus, 6)
                .eq(HisInpOrder::getId, id)
                .eq(HisInpOrder::getOrderStatus, 1)
                .update();
        if (!ok) {
            throw new BizException("医嘱状态已变化, 请刷新后重试");
        }
        if (cancelIds.size() > 1) {
            lambdaUpdate()
                    .set(HisInpOrder::getOrderStatus, 6)
                    .eq(HisInpOrder::getGroupNo, order.getGroupNo())
                    .ne(HisInpOrder::getId, id)
                    .eq(HisInpOrder::getOrderStatus, 1)
                    .update();
        }
        BigDecimal refundTotal = BigDecimal.ZERO;
        for (Long oid : cancelIds) {
            refundTotal = refundTotal.add(refundChargeOfOrder(oid));
        }
        if (refundTotal.compareTo(BigDecimal.ZERO) > 0) {
            accumulateTotalCost(order.getInpVisitId(), refundTotal.negate());
        }
        log.info("作废住院医嘱: id={}, visitId={}, 联动作废={}条, 冲销费用={}",
                id, order.getInpVisitId(), cancelIds.size() - 1, refundTotal);
        return getById(id);
    }

    /* ==================== 医嘱续开 / 医保预审 ==================== */

    /**
     * 续开医嘱(T42): 仅已停止(5)的长期医嘱(1)可续开; 复制原医嘱要素(药品/剂量/用法/频次/数量等)
     * 走标准开立链路(服务端重新定价 + 药审队列), 回写 source_order_id 溯源;
     * 新医嘱开始时间 = 原停嘱次日(无停嘱时间则当下)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisInpOrder renewOrder(Long orderId, Long doctorId) {
        if (orderId == null) {
            throw new BizException(400, "医嘱ID不能为空");
        }
        HisInpOrder original = requireOrder(orderId);
        if (original.getOrderStatus() == null || original.getOrderStatus() != 5) {
            throw new BizException("仅已停止(5)的医嘱可续开, 当前状态: " + original.getOrderStatus());
        }
        if (original.getOrderType() == null || original.getOrderType() != 1) {
            throw new BizException("仅长期医嘱(1)支持续开, 当前类型: " + original.getOrderType());
        }
        Long operator = doctorId != null ? doctorId : currentDoctorId();
        InpOrderDTO dto = new InpOrderDTO();
        dto.setInpVisitId(original.getInpVisitId());
        dto.setOrderType(1);
        dto.setOrderCategory(original.getOrderCategory());
        dto.setOrderContent(original.getOrderContent());
        dto.setChargeItemId(original.getChargeItemId());
        dto.setDrugId(original.getDrugId());
        dto.setSpec(original.getSpec());
        dto.setDosage(original.getDosage());
        dto.setDosageUnit(original.getDosageUnit());
        dto.setUsageCode(original.getUsageCode());
        dto.setFreqCode(original.getFreqCode());
        dto.setQuantity(original.getQuantity());
        HisInpOrder renewed = createOrder(dto);
        LocalDateTime start = original.getStopTime() != null ? original.getStopTime().plusDays(1) : LocalDateTime.now();
        lambdaUpdate()
                .set(HisInpOrder::getStartTime, start)
                .set(HisInpOrder::getSourceOrderId, orderId)
                .eq(HisInpOrder::getId, renewed.getId())
                .update();
        renewed.setStartTime(start);
        renewed.setSourceOrderId(orderId);
        log.info("续开住院医嘱: sourceOrderId={}, newOrderId={}, visitId={}, operator={}",
                orderId, renewed.getId(), original.getInpVisitId(), operator);
        return renewed;
    }

    /**
     * 医嘱副本数据(前端复制开立预填): 返回原医嘱核心要素(不含价格, 复制开立由服务端重新定价),
     * renewable 标记当前是否可续开(已停止的长期医嘱), 供前端按后端口径控制续开入口。
     */
    public Map<String, Object> copyOrderData(Long orderId) {
        HisInpOrder o = requireOrder(orderId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", o.getId());
        data.put("inpVisitId", o.getInpVisitId());
        data.put("orderType", o.getOrderType());
        data.put("orderCategory", o.getOrderCategory());
        data.put("orderContent", o.getOrderContent());
        data.put("drugId", o.getDrugId());
        data.put("chargeItemId", o.getChargeItemId());
        data.put("spec", o.getSpec());
        data.put("dosage", o.getDosage());
        data.put("dosageUnit", o.getDosageUnit());
        data.put("usageCode", o.getUsageCode());
        data.put("freqCode", o.getFreqCode());
        data.put("quantity", o.getQuantity());
        data.put("orderStatus", o.getOrderStatus());
        data.put("renewable", o.getOrderStatus() != null && o.getOrderStatus() == 5
                && o.getOrderType() != null && o.getOrderType() == 1);
        return data;
    }

    /* ==================== 模板开嘱 / 合理用药审查 ==================== */

    /**
     * 按模板/套餐批量开嘱: 解析模板 items(JSON) 逐条走标准开立链路(服务端定价+临时医嘱记账),
     * 同批次共享 group_no; 开立后回写来源标记(套餐→order_set_id, 单条→order_template_id)并递增使用次数。
     * 个人级(1)模板仅归属医生本人可用; 整批一个事务, 任一条失败全部回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public R<List<HisInpOrder>> createFromTemplate(Long visitId, Long templateId, Long doctorId) {
        if (visitId == null || templateId == null) {
            throw new BizException(400, "就诊ID与模板ID不能为空");
        }
        HisInpVisit visit = requireVisit(visitId);
        if (visit.getVisitStatus() == null || visit.getVisitStatus() != 2) {
            throw new BizException("患者当前不在院(就诊状态:" + visit.getVisitStatus() + "), 不能开立医嘱");
        }
        Long operator = doctorId != null ? doctorId : currentDoctorId();
        HisOrderTemplate tpl = orderTemplateService.getDetail(templateId).getData();
        if (tpl.getStatus() != null && tpl.getStatus() != 1) {
            throw new BizException(400, "模板已停用: " + tpl.getTemplateName());
        }
        if (tpl.getTemplateType() != null && tpl.getTemplateType() == 1
                && !operator.equals(tpl.getDoctorId())) {
            throw new BizException(403, "个人模板仅归属医生本人可使用");
        }
        if (!StringUtils.hasText(tpl.getItems())) {
            throw new BizException(400, "模板未配置医嘱项: " + tpl.getTemplateName());
        }
        List<OrderTemplateItemDTO> items;
        try {
            items = JSON.parseArray(tpl.getItems(), OrderTemplateItemDTO.class);
        } catch (Exception e) {
            throw new BizException(400, "模板医嘱项解析失败: " + tpl.getTemplateName());
        }
        if (CollectionUtils.isEmpty(items)) {
            throw new BizException(400, "模板未配置医嘱项: " + tpl.getTemplateName());
        }
        String groupNo = "GRP" + System.currentTimeMillis();
        List<HisInpOrder> created = new ArrayList<>(items.size());
        List<Long> createdIds = new ArrayList<>(items.size());
        for (OrderTemplateItemDTO item : items) {
            if (item == null || !StringUtils.hasText(item.getOrderContent())) {
                throw new BizException(400, "模板存在无效医嘱项(缺少医嘱内容): " + tpl.getTemplateName());
            }
            InpOrderDTO dto = new InpOrderDTO();
            dto.setInpVisitId(visitId);
            dto.setOrderType(item.getOrderType());
            dto.setOrderCategory(item.getOrderCategory());
            dto.setOrderContent(item.getOrderContent());
            dto.setChargeItemId(item.getChargeItemId());
            dto.setDrugId(item.getDrugId());
            dto.setSpec(item.getSpec());
            dto.setDosage(item.getDosage());
            dto.setDosageUnit(item.getDosageUnit());
            dto.setUsageCode(item.getUsageCode());
            dto.setFreqCode(item.getFreqCode());
            dto.setQuantity(item.getQuantity());
            dto.setUnitPrice(item.getUnitPrice());
            dto.setGroupNo(groupNo);
            HisInpOrder o = createOrder(dto);
            created.add(o);
            createdIds.add(o.getId());
        }
        // 来源标记回写: 套餐(scope_type=2)记 order_set_id, 单条(1/空)记 order_template_id
        boolean asSet = tpl.getScopeType() != null && tpl.getScopeType() == 2;
        lambdaUpdate()
                .set(asSet, HisInpOrder::getOrderSetId, templateId)
                .set(!asSet, HisInpOrder::getOrderTemplateId, templateId)
                .in(HisInpOrder::getId, createdIds)
                .update();
        for (HisInpOrder o : created) {
            if (asSet) {
                o.setOrderSetId(templateId);
            } else {
                o.setOrderTemplateId(templateId);
            }
        }
        // 模板使用次数+1(热度排序依据)
        orderTemplateService.incrementUsage(templateId);
        log.info("模板开嘱: visitId={}, templateId={}, 条数={}, 套餐={}, groupNo={}",
                visitId, templateId, created.size(), asSet, groupNo);
        return R.ok(created);
    }

    /**
     * 合理用药检查(开嘱前预检, 不落库): 过敏冲突 + 重复用药 + 单次剂量上限 + 高警示药品标识 + 皮试药品提示。
     * 返回 {visitId, drugId, drugName, passed, warnings, highAlert, doubleCheck, needSkinTest};
     * passed=无过敏冲突且无重复用药(剂量/高警示/皮试仅提示不拦截);
     * doubleCheck=高警示或需皮试药品须双人核对, needSkinTest=目录皮试标志=1(须先开具皮试医嘱)。
     */
    public R<Map<String, Object>> rationalDrugCheck(Long visitId, Long drugId, String dosage) {
        if (visitId == null || drugId == null) {
            throw new BizException(400, "就诊ID与药品ID不能为空");
        }
        HisInpVisit visit = requireVisit(visitId);
        HisDrugCatalog drug = drugCatalogMapper.selectById(drugId);
        if (drug == null) {
            throw new BizException(404, "药品目录不存在: drugId=" + drugId);
        }
        List<String> warnings = new ArrayList<>();

        // 1. 过敏史冲突(药物类过敏记录比对药品编码/名称)
        List<String> allergyHits = allergyService.checkDrugAllergy(visit.getPatientId(), drugId);
        if (!allergyHits.isEmpty()) {
            warnings.add("过敏冲突: 患者对[" + String.join("、", allergyHits) + "]过敏, 禁用或谨慎使用");
        }

        // 2. 重复用药(同就诊存在未停(新开/已审核/执行中)的同药品医嘱)
        Long dupCnt = lambdaQuery()
                .eq(HisInpOrder::getInpVisitId, visitId)
                .eq(HisInpOrder::getDrugId, drugId)
                .in(HisInpOrder::getOrderStatus, 1, 2, 3)
                .count();
        boolean duplicated = dupCnt != null && dupCnt > 0;
        if (duplicated) {
            warnings.add("重复用药: 该就诊已存在相同药品的未停医嘱");
        }

        // 3. 单次剂量上限(从剂量文本提取首个数值与 max_qty_once 比较, 仅提示不拦截)
        BigDecimal usedQty = parseDosageNumber(dosage);
        if (drug.getMaxQtyOnce() != null && usedQty != null && usedQty.signum() > 0
                && usedQty.compareTo(drug.getMaxQtyOnce()) > 0) {
            warnings.add("剂量超限: 单次剂量" + usedQty.toPlainString() + "超过上限"
                    + drug.getMaxQtyOnce().toPlainString());
        }

        // 4. 高警示药品(管理类别/类别名称关键词粗判, 命中提示双人核对)
        boolean highAlert = isHighAlertDrug(drug);
        if (highAlert) {
            warnings.add("高警示药品: " + drug.getGenericName() + "需执行双人核对并加强观察");
        }

        // 5. 皮试药品(T36): 目录皮试标志=1 时提示先开具皮试医嘱, 并联动双人核对(开嘱落 double_check_flag)
        boolean needSkinTest = drug.getSkinTestFlag() != null && drug.getSkinTestFlag() == 1;
        if (needSkinTest) {
            warnings.add("⚠ 该药品需做皮试，请先开具皮试医嘱");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("visitId", visitId);
        result.put("drugId", drugId);
        result.put("drugName", drug.getGenericName());
        result.put("passed", allergyHits.isEmpty() && !duplicated);
        result.put("warnings", warnings);
        result.put("highAlert", highAlert);
        result.put("doubleCheck", highAlert || needSkinTest);
        result.put("needSkinTest", needSkinTest);
        return R.ok(result);
    }

    /* ================= 内部实现 ================= */

    /** 就诊归属校验: 不存在报400; 非牵头机构仅可访问本机构就诊(牵头机构全医共体) */
    private HisInpVisit requireVisit(Long visitId) {
        HisInpVisit v = visitMapper.selectById(visitId);
        if (v == null) {
            throw new BizException(400, "住院就诊记录不存在");
        }
        Long scope = guard.scopeOrgId(v.getOrgId());
        if (scope == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法访问住院数据");
        }
        if (!scope.equals(v.getOrgId())) {
            throw new BizException(403, "无权访问其他机构的住院就诊数据");
        }
        return v;
    }

    /** 医嘱存在性 + 经就诊做机构归属校验 */
    private HisInpOrder requireOrder(Long id) {
        HisInpOrder o = getById(id);
        if (o == null) {
            throw new BizException(400, "医嘱不存在");
        }
        if (o.getInpVisitId() != null) {
            requireVisit(o.getInpVisitId());
        }
        return o;
    }

    /**
     * 服务端定价(不信任客户端传价):
     * - 药品类: his_drug_catalog.retail_price(最小单位), 并校验本机构开展(his_org_catalog drug 启用行; 牵头隐式全量开展);
     * - 非药品类: his_charge_item.price(启用行);
     * - 无收费载体(护理/膳食等文字医嘱): 允许携带单价或零价, 不生成费用明细。
     */
    private PricedItem resolvePrice(InpOrderDTO dto, HisInpVisit visit) {
        PricedItem p = new PricedItem();
        if (dto.getOrderCategory() != null && dto.getOrderCategory() == 1) {
            if (dto.getDrugId() == null) {
                throw new BizException(400, "药品类医嘱必须指定药品(drugId)");
            }
            HisDrugCatalog drug = drugCatalogMapper.selectById(dto.getDrugId());
            if (drug == null) {
                throw new BizException(400, "药品目录不存在: drugId=" + dto.getDrugId());
            }
            if (drug.getStatus() != null && drug.getStatus() != 1) {
                throw new BizException(400, "药品已停用: " + drug.getGenericName());
            }
            requireDrugEnabled(visit.getOrgId(), drug);
            if (drug.getRetailPrice() == null) {
                throw new BizException(400, "药品零售价未配置: " + drug.getGenericName());
            }
            p.price = drug.getRetailPrice();
            p.itemCode = drug.getDrugCode();
            p.itemName = drug.getGenericName();
            p.feeType = isTcm(drug.getMajorClass()) ? 2 : 1;
            // 医保预审数据(T42): 医保码 yb_drug_code + 甲乙丙分类(对照时同步维护)
            p.insuranceCheckable = true;
            p.ybCode = drug.getYbDrugCode();
            p.chrgitmLv = drug.getChrgitmLv();
            p.chrgitmLvName = drug.getChrgitmLvName();
            // 高警示(关键词粗判)→high_alert_flag; 高警示或需皮试(skin_test_flag=1)→double_check_flag(T36)
            p.highAlertFlag = isHighAlertDrug(drug) ? 1 : 0;
            p.doubleCheckFlag = (p.highAlertFlag == 1
                    || (drug.getSkinTestFlag() != null && drug.getSkinTestFlag() == 1)) ? 1 : 0;
            return p;
        }
        if (dto.getChargeItemId() != null) {
            HisChargeItem item = chargeItemMapper.selectById(dto.getChargeItemId());
            if (item == null) {
                throw new BizException(400, "收费项目不存在: chargeItemId=" + dto.getChargeItemId());
            }
            if (item.getStatus() != null && item.getStatus() != 1) {
                throw new BizException(400, "收费项目已停用: " + item.getItemName());
            }
            if (item.getPrice() == null) {
                throw new BizException(400, "收费项目价格未配置: " + item.getItemName());
            }
            p.price = item.getPrice();
            p.itemCode = item.getItemCode();
            p.itemName = item.getItemName();
            p.feeType = mapFeeType(dto.getOrderCategory());
            // 医保预审数据(T42): 医保码 med_list_codg + 甲乙丙等级(01甲/02乙/03丙)
            p.insuranceCheckable = true;
            p.ybCode = item.getMedListCodg();
            p.chrgitmLv = item.getChrgitmLv();
            return p;
        }
        p.price = dto.getUnitPrice();
        p.feeType = mapFeeType(dto.getOrderCategory());
        return p;
    }

    /** 药品开展校验: 非牵头机构须在机构开展目录(his_org_catalog, catalog_type=drug)中启用; 牵头机构隐式全量开展 */
    private void requireDrugEnabled(Long orgId, HisDrugCatalog drug) {
        if (orgId == null || guard.isLead()) {
            return;
        }
        Long cnt = orgCatalogMapper.selectCount(new LambdaQueryWrapper<HisOrgCatalog>()
                .eq(HisOrgCatalog::getOrgId, orgId)
                .eq(HisOrgCatalog::getCatalogType, "drug")
                .eq(HisOrgCatalog::getCatalogId, drug.getId())
                .eq(HisOrgCatalog::getEnabled, 1));
        if (cnt == null || cnt == 0) {
            throw new BizException(400, "本机构未开展该药品: " + drug.getGenericName());
        }
    }

    /** 临时医嘱开立时的记账明细(金额=单价×数量, 记账日=当日, 状态1正常) */
    private HisInpChargeDetail buildChargeDetail(HisInpOrder o, PricedItem priced) {
        HisInpChargeDetail cd = new HisInpChargeDetail();
        cd.setOrgId(o.getOrgId());
        cd.setInpVisitId(o.getInpVisitId());
        cd.setChargeItemId(o.getChargeItemId());
        cd.setItemCode(priced.itemCode);
        cd.setItemName(StringUtils.hasText(priced.itemName) ? priced.itemName : o.getOrderContent());
        cd.setQuantity(o.getQuantity());
        cd.setUnitPrice(o.getUnitPrice());
        cd.setAmount(o.getUnitPrice().multiply(o.getQuantity()).setScale(2, BigDecimal.ROUND_HALF_UP));
        cd.setChargeDate(LocalDate.now());
        cd.setOrderId(o.getId());
        cd.setFeeType(priced.feeType == null ? 9 : priced.feeType);
        cd.setStatus(1);
        cd.setOperatorId(o.getDoctorId());
        return cd;
    }

    /**
     * 读四级作用域布尔参数(登录上下文=当前机构): 仅显式 "true" 视为启用, 缺省/空/异常一律停用(false),
     * 与门诊 HisOrderService.paramEnabled 同口径(需启用的机构逐级覆盖为 true)。
     */
    private boolean paramEnabled(String paramKey) {
        try {
            String v = paramResolver.resolve(paramKey);
            return StringUtils.hasText(v) && "true".equalsIgnoreCase(v.trim());
        } catch (Exception e) {
            log.warn("住院检查控费开关参数解析失败({}), 默认停用: {}", paramKey, e.getMessage());
            return false;
        }
    }

    /**
     * 住院临时检查医嘱即时加收(仅 orderType=2 调用): 命中 his_charge_addon_rule 的 part/contrast 维度按门诊同口径追加费用明细。
     * part 维度按计价部位数(site_count)超出首部位每个按主项目单价×比例加收; contrast 维度选增强时一次性按主项目单价×比例加收。
     * 分别受 inpatient.exam_part_surcharge_enabled / inpatient.exam_contrast_surcharge_enabled 门控(默认停用); 异常仅告警不影响开立。
     */
    private List<HisInpChargeDetail> buildExamSurchargeCharges(HisInpOrder o) {
        List<HisInpChargeDetail> addons = new ArrayList<>();
        try {
            if (o.getChargeItemId() == null || o.getUnitPrice() == null) {
                return addons;
            }
            boolean partOn = paramEnabled(com.yb.hi.platform.ExamSurchargeParamSeeder.KEY_IP_PART);
            boolean contrastOn = paramEnabled(com.yb.hi.platform.ExamSurchargeParamSeeder.KEY_IP_CONTRAST);
            if (!partOn && !contrastOn) {
                return addons;
            }
            List<HisChargeAddonRule> rules = chargeAddonRuleService.listByItem(o.getChargeItemId());
            BigDecimal mainPrice = o.getUnitPrice();
            int siteCount = o.getSiteCount() == null ? 1 : o.getSiteCount();
            for (HisChargeAddonRule r : rules) {
                boolean isContrast = "contrast".equalsIgnoreCase(r.getDimType());
                boolean isPart = "part".equalsIgnoreCase(r.getDimType());
                if (isPart && !partOn) {
                    continue;
                }
                if (isContrast && !contrastOn) {
                    continue;
                }
                if (!isPart && !isContrast) {
                    continue; // 住院检查仅接 part/contrast 两维, 其余维度不适用
                }
                BigDecimal dim = isContrast
                        ? ("增强".equals(o.getContrastMode()) ? BigDecimal.ONE : BigDecimal.ZERO)
                        : new BigDecimal(siteCount);
                int threshold = r.getDimThreshold() == null ? 1 : r.getDimThreshold();
                if (dim.compareTo(new BigDecimal(threshold)) < 0) {
                    continue;
                }
                String mode = r.getCalcMode() == null ? "fixed" : r.getCalcMode().toLowerCase();
                BigDecimal amount;
                BigDecimal addonQty = isContrast ? BigDecimal.ONE : dim;
                if ("ratio".equals(mode)) {
                    BigDecimal ratio = r.getDimRatio();
                    if (ratio == null || mainPrice.compareTo(BigDecimal.ZERO) <= 0) {
                        continue;
                    }
                    if (isContrast) {
                        amount = mainPrice.multiply(ratio).setScale(2, BigDecimal.ROUND_HALF_UP);
                    } else {
                        BigDecimal extra = dim.subtract(new BigDecimal(threshold));
                        if (extra.compareTo(BigDecimal.ZERO) <= 0) {
                            continue;
                        }
                        amount = mainPrice.multiply(ratio).multiply(extra).setScale(2, BigDecimal.ROUND_HALF_UP);
                    }
                } else {
                    if (r.getUnitPrice() == null) {
                        continue;
                    }
                    amount = r.getUnitPrice().multiply(dim).setScale(2, BigDecimal.ROUND_HALF_UP);
                }
                if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                addons.add(buildSurchargeDetail(o, r, isContrast, amount, addonQty));
            }
        } catch (Exception e) {
            log.warn("住院检查加收计算失败(不影响开立): orderId={}, err={}", o.getId(), e.getMessage());
        }
        return addons;
    }

    /** 加收费用明细行(charge_item_id/item_code 置空, 与门诊加收行 itemId 为空同口径; 仅凭主项目单价与规则名称生成): 作废时按 order_id 全额冲销。 */
    private HisInpChargeDetail buildSurchargeDetail(HisInpOrder o, HisChargeAddonRule r, boolean isContrast,
                                                    BigDecimal amount, BigDecimal addonQty) {
        HisInpChargeDetail cd = new HisInpChargeDetail();
        cd.setOrgId(o.getOrgId());
        cd.setInpVisitId(o.getInpVisitId());
        cd.setChargeItemId(null);
        cd.setItemCode(null);
        cd.setItemName(StringUtils.hasText(r.getAddonItemName()) ? r.getAddonItemName()
                : (isContrast ? "增强扫描加收" : "多部位加收"));
        cd.setQuantity(addonQty);
        cd.setUnitPrice(amount.divide(addonQty, 6, BigDecimal.ROUND_HALF_UP));
        cd.setAmount(amount);
        cd.setChargeDate(LocalDate.now());
        cd.setOrderId(o.getId());
        cd.setFeeType(3); // 检查
        cd.setStatus(1);
        cd.setOperatorId(o.getDoctorId());
        return cd;
    }

    /** 冲销医嘱已生成且未退的费用明细(置退费态2), 返回冲销金额合计 */
    private BigDecimal refundChargeOfOrder(Long orderId) {
        List<HisInpChargeDetail> cds = chargeDetailMapper.selectList(new LambdaQueryWrapper<HisInpChargeDetail>()
                .eq(HisInpChargeDetail::getOrderId, orderId)
                .eq(HisInpChargeDetail::getStatus, 1));
        BigDecimal total = BigDecimal.ZERO;
        for (HisInpChargeDetail cd : cds) {
            int affected = chargeDetailMapper.update(null, new LambdaUpdateWrapper<HisInpChargeDetail>()
                    .set(HisInpChargeDetail::getStatus, 2)
                    .eq(HisInpChargeDetail::getId, cd.getId())
                    .eq(HisInpChargeDetail::getStatus, 1));
            if (affected > 0) {
                total = total.add(cd.getAmount() == null ? BigDecimal.ZERO : cd.getAmount());
            }
        }
        return total;
    }

    /** 就诊总费用原子累加(delta 可为负, 作废冲销用); 金额为纯数字拼接无注入风险 */
    private void accumulateTotalCost(Long visitId, BigDecimal delta) {
        if (visitId == null || delta == null || delta.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        visitMapper.update(null, new LambdaUpdateWrapper<HisInpVisit>()
                .setSql("total_cost = IFNULL(total_cost, 0) + " + delta.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString())
                .eq(HisInpVisit::getId, visitId));
    }

    /** 医嘱分类 → 费用类别(1药品 2检查 3检验 4治疗 5护理 6膳食 7其他 → 费用明细类别) */
    private Integer mapFeeType(Integer orderCategory) {
        if (orderCategory == null) {
            return 9;
        }
        switch (orderCategory) {
            case 1: return 1;   // 药品→西药(中药饮片/中成药在药品分支单独判为2)
            case 2: return 3;   // 检查
            case 3: return 4;   // 检验
            case 4: return 5;   // 治疗
            case 5: return 6;   // 护理
            case 6: return 9;   // 膳食→其他
            default: return 9;
        }
    }

    private boolean isTcm(String majorClass) {
        return majorClass != null && (majorClass.contains("中药") || majorClass.contains("中成"));
    }

    /** 从剂量文本提取首个数值片段(如 "0.5g"→0.5、"1次2片"→1); 无法提取返回 null */
    private static BigDecimal parseDosageNumber(String dosage) {
        if (!StringUtils.hasText(dosage)) {
            return null;
        }
        Matcher m = Pattern.compile("\\d+(\\.\\d+)?").matcher(dosage);
        if (!m.find()) {
            return null;
        }
        try {
            return new BigDecimal(m.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 文本是否命中任一关键词(null/空安全, 用于药品管理类别粗判) */
    private static boolean containsAnyKeyword(String text, String... keywords) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /** 高警示药品粗判(开嘱落标志与合理用药预检同口径): 管理类别/类别名称命中关键词 */
    private static boolean isHighAlertDrug(HisDrugCatalog drug) {
        String clsText = (drug.getDrugClass() == null ? "" : drug.getDrugClass())
                + "|" + (drug.getDrugClassName() == null ? "" : drug.getDrugClassName());
        return containsAnyKeyword(clsText, "高警示", "麻醉", "精神", "毒性", "放射");
    }

    /**
     * 手麻P4c 新生儿医嘱软守卫(非阻断): 若医嘱就诊为新生儿建档关联就诊(his_newborn.baby_inp_visit_id)
     * 且为药品类(orderCategory=1)而新生儿未记录体重(weight_g 为空), 在开立回执瞬态回填提醒补录体重。
     * 仅提示不拦截(系统无新生儿剂量字典, 不做医疗硬拦截); 任何异常仅记日志, 不影响医嘱保存。
     */
    private void appendNewbornWeightGuard(HisInpOrder o, HisInpVisit visit) {
        if (o == null || visit == null || o.getInpVisitId() == null) {
            return;
        }
        if (o.getOrderCategory() == null || o.getOrderCategory() != 1) {
            return;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT weight_g FROM his_newborn WHERE deleted = 0 AND baby_inp_visit_id = ?",
                    o.getInpVisitId());
            if (rows.isEmpty()) {
                return;
            }
            Object wg = rows.get(0).get("weight_g");
            if (wg == null) {
                o.setNewbornWeightWarning("新生儿未记录体重, 请补录后按 mg/kg 核算剂量");
            }
        } catch (Exception e) {
            log.warn("新生儿体重软守卫异常(不阻断医嘱保存): orderId={}, err={}", o.getId(), e.getMessage());
        }
    }

    /**
     * 医保预审(T42, 非阻断): 依据定价环节回查的目录医保码/甲乙丙分类, 在开立回执上瞬态回填;
     * 无码→insuranceWarning(将全额自费), 有码→insuranceCategory(甲/乙/丙);
     * 纯文字医嘱(无收费载体)不预审; 任何异常仅记日志, 不影响医嘱保存。
     */
    private void appendInsurancePreview(HisInpOrder o, PricedItem priced) {
        if (o == null || priced == null || !priced.insuranceCheckable) {
            return;
        }
        try {
            if (!StringUtils.hasText(priced.ybCode)) {
                o.setInsuranceWarning("该项目无医保编码，将全额自费");
            } else {
                o.setInsuranceCategory(resolveChrgitmLvName(priced));
            }
        } catch (Exception e) {
            log.warn("医保预审异常(不阻断医嘱保存): orderId={}, err={}", o.getId(), e.getMessage());
        }
    }

    /** 甲乙丙分类归一为名称: 药品目录 cv_code:chrgitm_lv(1甲/2乙/3丙/4可报丙类)与收费项目等级(01甲/02乙/03丙)统一转文本, 已是名称则透传 */
    private static String resolveChrgitmLvName(PricedItem p) {
        if (StringUtils.hasText(p.chrgitmLvName)) {
            return p.chrgitmLvName;
        }
        if (!StringUtils.hasText(p.chrgitmLv)) {
            return null;
        }
        switch (p.chrgitmLv.trim()) {
            case "1": case "01": return "甲类";
            case "2": case "02": return "乙类";
            case "3": case "03": return "丙类";
            case "4": return "可报丙类";
            default: return p.chrgitmLv.trim();
        }
    }

    /** 定价结果: 单价 + 费用明细回填的项目编码/名称/费用类别 + 高警示/双人核对标志(非药品默认0) + 医保预审数据 */
    private static class PricedItem {
        BigDecimal price;
        String itemCode;
        String itemName;
        Integer feeType;
        Integer highAlertFlag = 0;
        Integer doubleCheckFlag = 0;
        /* 医保预审(T42): 是否有收费载体参与预审 + 目录医保码 + 甲乙丙分类(编码/名称) */
        boolean insuranceCheckable;
        String ybCode;
        String chrgitmLv;
        String chrgitmLvName;
    }
}
