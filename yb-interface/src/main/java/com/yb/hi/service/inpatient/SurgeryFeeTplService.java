package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yb.hi.entity.inpatient.HisSurgery;
import com.yb.hi.entity.inpatient.HisSurgeryFeeTpl;
import com.yb.hi.entity.inpatient.HisSurgeryFeeTplItem;
import com.yb.hi.dto.inpatient.SurgeryFeeDTO;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.inpatient.HisSurgeryFeeTplItemMapper;
import com.yb.hi.mapper.inpatient.HisSurgeryFeeTplMapper;
import com.yb.hi.mapper.inpatient.HisSurgeryMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手术费用模板服务(规范2.2.2.3.7.21): 个人/科室/全院三级模板维护 + 术后费用一键导入记账。
 * 可见性: 个人=仅本人; 科室=同科室; 全院=全部; apply 复用 SurgeryFeeService 双写链路(住院记费/门诊 his_order_item)。
 */
@Slf4j
@Service
public class SurgeryFeeTplService {

    private final HisSurgeryFeeTplMapper tplMapper;
    private final HisSurgeryFeeTplItemMapper itemMapper;
    private final HisSurgeryMapper surgeryMapper;
    private final SurgeryFeeService feeService;
    private final OrgAccessGuard guard;

    public SurgeryFeeTplService(HisSurgeryFeeTplMapper tplMapper, HisSurgeryFeeTplItemMapper itemMapper,
                                HisSurgeryMapper surgeryMapper, SurgeryFeeService feeService,
                                OrgAccessGuard guard) {
        this.tplMapper = tplMapper;
        this.itemMapper = itemMapper;
        this.surgeryMapper = surgeryMapper;
        this.feeService = feeService;
        this.guard = guard;
    }

    /* ==================== 模板查询(按可见性过滤) ==================== */

    /** 可见模板列表(手术记费抽屉"导入模板"下拉 + 模板管理页) */
    public List<Map<String, Object>> listVisible(Long orgId) {
        LoginUser lu = requireLogin();
        LambdaQueryWrapper<HisSurgeryFeeTpl> q = new LambdaQueryWrapper<HisSurgeryFeeTpl>()
                .eq(HisSurgeryFeeTpl::getStatus, 1)
                .and(w -> w
                        .and(w1 -> w1.eq(HisSurgeryFeeTpl::getTplLevel, 3))
                        .or(w2 -> w2.eq(HisSurgeryFeeTpl::getTplLevel, 2)
                                .eq(lu.getDeptId() != null, HisSurgeryFeeTpl::getDeptId, lu.getDeptId()))
                        .or(w3 -> w3.eq(HisSurgeryFeeTpl::getTplLevel, 1)
                                .eq(lu.getStaffId() != null, HisSurgeryFeeTpl::getOwnerStaffId, lu.getStaffId())))
                .orderByAsc(HisSurgeryFeeTpl::getTplLevel).orderByDesc(HisSurgeryFeeTpl::getId);
        Long scope = guard.scopeOrgId(orgId);
        if (scope != null) {
            q.eq(HisSurgeryFeeTpl::getOrgId, scope);
        }
        List<HisSurgeryFeeTpl> tpls = tplMapper.selectList(q);
        List<Map<String, Object>> out = new ArrayList<>();
        for (HisSurgeryFeeTpl t : tpls) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tpl", t);
            m.put("items", itemMapper.selectList(new LambdaQueryWrapper<HisSurgeryFeeTplItem>()
                    .eq(HisSurgeryFeeTplItem::getTplId, t.getId())
                    .orderByAsc(HisSurgeryFeeTplItem::getId)));
            out.add(m);
        }
        return out;
    }

    /** 模板明细 */
    public Map<String, Object> detail(Long id) {
        HisSurgeryFeeTpl tpl = requireTpl(id, false);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tpl", tpl);
        m.put("items", itemMapper.selectList(new LambdaQueryWrapper<HisSurgeryFeeTplItem>()
                .eq(HisSurgeryFeeTplItem::getTplId, id)
                .orderByAsc(HisSurgeryFeeTplItem::getId)));
        return m;
    }

    /* ==================== 模板维护 ==================== */

    /** 创建模板(items 随主表一起保存) */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgeryFeeTpl create(HisSurgeryFeeTpl tpl, List<HisSurgeryFeeTplItem> items) {
        validate(tpl, items);
        LoginUser lu = requireLogin();
        tpl.setId(null);
        tpl.setOrgId(guard.currentOrgId());
        if (tpl.getStatus() == null) {
            tpl.setStatus(1);
        }
        if (tpl.getTplLevel() != null && tpl.getTplLevel() == 1) {
            tpl.setOwnerStaffId(lu.getStaffId());
        }
        if (tpl.getTplLevel() != null && tpl.getTplLevel() == 2 && tpl.getDeptId() == null) {
            tpl.setDeptId(lu.getDeptId());
        }
        tplMapper.insert(tpl);
        saveItems(tpl.getId(), items);
        log.info("手术费用模板创建: id={}, name={}, level={}, items={}",
                tpl.getId(), tpl.getTplName(), tpl.getTplLevel(), items.size());
        return tpl;
    }

    /** 更新模板(级别不允许升级变更, 防个人模板升全院; items 全删重插) */
    @Transactional(rollbackFor = Exception.class)
    public HisSurgeryFeeTpl update(Long id, HisSurgeryFeeTpl body, List<HisSurgeryFeeTplItem> items) {
        HisSurgeryFeeTpl exist = requireTpl(id, true);
        validate(body, items);
        if (body.getTplLevel() != null && !body.getTplLevel().equals(exist.getTplLevel())) {
            throw new BizException("模板级别不允许变更, 请新建模板");
        }
        exist.setTplName(body.getTplName());
        exist.setSurgeryCode(body.getSurgeryCode());
        exist.setSurgeryName(body.getSurgeryName());
        exist.setRemark(body.getRemark());
        if (body.getStatus() != null) {
            exist.setStatus(body.getStatus());
        }
        if (body.getDeptId() != null) {
            exist.setDeptId(body.getDeptId());
        }
        tplMapper.updateById(exist);
        itemMapper.delete(new LambdaQueryWrapper<HisSurgeryFeeTplItem>()
                .eq(HisSurgeryFeeTplItem::getTplId, id));
        saveItems(id, items);
        return tplMapper.selectById(id);
    }

    /** 删除模板(级联逻辑删明细) */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireTpl(id, true);
        itemMapper.delete(new LambdaQueryWrapper<HisSurgeryFeeTplItem>()
                .eq(HisSurgeryFeeTplItem::getTplId, id));
        tplMapper.deleteById(id);
    }

    /* ==================== 一键导入记账 ==================== */

    /** 模板应用到手术: 逐行转 SurgeryFeeDTO 走标准双写链路, 返回记账条数与总额 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> applyToSurgery(Long surgeryId, Long tplId) {
        HisSurgery surgery = surgeryMapper.selectById(surgeryId);
        if (surgery == null) {
            throw new BizException(404, "手术记录不存在");
        }
        Long scope = guard.scopeOrgId(surgery.getOrgId());
        if (scope == null || !scope.equals(surgery.getOrgId())) {
            throw new BizException(403, "无权操作其他机构的手术");
        }
        HisSurgeryFeeTpl tpl = requireTpl(tplId, false);
        List<HisSurgeryFeeTplItem> items = itemMapper.selectList(new LambdaQueryWrapper<HisSurgeryFeeTplItem>()
                .eq(HisSurgeryFeeTplItem::getTplId, tplId)
                .orderByAsc(HisSurgeryFeeTplItem::getId));
        if (items.isEmpty()) {
            throw new BizException("模板明细为空, 无法导入");
        }
        List<SurgeryFeeDTO> dtos = new ArrayList<>(items.size());
        BigDecimal total = BigDecimal.ZERO;
        for (HisSurgeryFeeTplItem it : items) {
            SurgeryFeeDTO dto = new SurgeryFeeDTO();
            dto.setSurgeryId(surgeryId);
            dto.setChargeItemId(it.getChargeItemId());
            dto.setItemName(it.getItemName());
            dto.setItemCode(it.getItemCode());
            dto.setFeeCategory(it.getFeeCategory());
            dto.setQuantity(it.getQuantity() == null ? BigDecimal.ONE : it.getQuantity());
            dto.setUnitPrice(it.getUnitPrice());
            dto.setAmount(it.getAmount());
            dtos.add(dto);
            BigDecimal amt = it.getAmount() != null ? it.getAmount()
                    : (it.getUnitPrice() == null ? BigDecimal.ZERO
                    : it.getUnitPrice().multiply(it.getQuantity() == null ? BigDecimal.ONE : it.getQuantity()));
            total = total.add(amt);
        }
        feeService.batchAddFee(dtos, surgery.getOrgId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", dtos.size());
        out.put("totalAmount", total);
        out.put("tplName", tpl.getTplName());
        log.info("费用模板一键导入: surgeryId={}, tplId={}, count={}", surgeryId, tplId, dtos.size());
        return out;
    }

    /* ==================== 校验 / 工具 ==================== */

    private HisSurgeryFeeTpl requireTpl(Long id, boolean requireOwner) {
        if (id == null) {
            throw new BizException(400, "模板ID不能为空");
        }
        HisSurgeryFeeTpl tpl = tplMapper.selectById(id);
        if (tpl == null) {
            throw new BizException(404, "费用模板不存在");
        }
        Long scope = guard.scopeOrgId(tpl.getOrgId());
        if (scope == null || !scope.equals(tpl.getOrgId())) {
            throw new BizException(403, "无权操作其他机构的费用模板");
        }
        if (requireOwner) {
            LoginUser lu = requireLogin();
            if (tpl.getTplLevel() != null && tpl.getTplLevel() == 1
                    && lu.getStaffId() != null && !lu.getStaffId().equals(tpl.getOwnerStaffId())) {
                throw new BizException(403, "个人模板仅创建人可维护");
            }
            if (tpl.getTplLevel() != null && tpl.getTplLevel() == 3
                    && !lu.hasRole("ADMIN") && !lu.hasRole("SUPER_ADMIN")) {
                throw new BizException(403, "全院模板仅管理员可维护");
            }
        }
        return tpl;
    }

    private void validate(HisSurgeryFeeTpl tpl, List<HisSurgeryFeeTplItem> items) {
        if (tpl == null || !StringUtils.hasText(tpl.getTplName())) {
            throw new BizException(400, "模板名称不能为空");
        }
        if (tpl.getTplLevel() == null || tpl.getTplLevel() < 1 || tpl.getTplLevel() > 3) {
            throw new BizException(400, "模板级别必填: 1个人 2科室 3全院");
        }
        if (CollectionUtils.isEmpty(items)) {
            throw new BizException(400, "模板明细不能为空");
        }
        for (HisSurgeryFeeTplItem it : items) {
            if (!StringUtils.hasText(it.getItemName())) {
                throw new BizException(400, "模板明细项目名称不能为空");
            }
            if (it.getUnitPrice() == null) {
                throw new BizException(400, "模板明细[" + it.getItemName() + "]单价不能为空");
            }
        }
    }

    private void saveItems(Long tplId, List<HisSurgeryFeeTplItem> items) {
        for (HisSurgeryFeeTplItem it : items) {
            it.setId(null);
            it.setTplId(tplId);
            if (it.getQuantity() == null) {
                it.setQuantity(BigDecimal.ONE);
            }
            if (it.getFeeCategory() == null) {
                it.setFeeCategory(1);
            }
            if (it.getAmount() == null && it.getUnitPrice() != null) {
                it.setAmount(it.getUnitPrice().multiply(it.getQuantity()));
            }
            itemMapper.insert(it);
        }
    }

    private static LoginUser requireLogin() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            throw new BizException(401, "未登录");
        }
        return lu;
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
