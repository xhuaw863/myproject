package com.yb.hi.service.pharmacy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.pharmacy.HisPharmacyCrossConfig;
import com.yb.hi.entity.pharmacy.HisPharmacyDef;
import com.yb.hi.entity.pharmacy.HisPharmacyWindow;
import com.yb.hi.entity.pharmacy.HisWindowDeptRule;
import com.yb.hi.entity.pharmacy.HisWindowSignin;
import com.yb.hi.entity.pharmacy.HisWindowWorkstation;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.pharmacy.HisPharmacyCrossConfigMapper;
import com.yb.hi.mapper.pharmacy.HisPharmacyWindowMapper;
import com.yb.hi.mapper.pharmacy.HisWindowDeptRuleMapper;
import com.yb.hi.mapper.pharmacy.HisWindowSigninMapper;
import com.yb.hi.mapper.pharmacy.HisWindowWorkstationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 发药窗口维护服务(P1): 窗口 / 工作站关联 / 科室定向规则 / 跨药房配置 的增删改查与开关, 以及窗口签到。
 * 机构级配置, tenant_id 由 MyBatis-Plus 租户插件注入/过滤; 写操作守卫(牵头/角色)在 Controller 层。
 */
@Slf4j
@Service
public class PharmacyWindowService {

    private static final List<String> VALID_WINDOW_TYPES = Arrays.asList(
            "WEST", "CHINESE_PATENT", "HERB", "NARCOTIC", "TOXIC", "DECOCT", "EXPRESS");

    private final HisPharmacyWindowMapper windowMapper;
    private final HisWindowWorkstationMapper workstationMapper;
    private final HisWindowDeptRuleMapper deptRuleMapper;
    private final HisPharmacyCrossConfigMapper crossConfigMapper;
    private final HisWindowSigninMapper signinMapper;
    private final PharmacyDefService pharmacyDefService;

    public PharmacyWindowService(HisPharmacyWindowMapper windowMapper, HisWindowWorkstationMapper workstationMapper,
                                 HisWindowDeptRuleMapper deptRuleMapper, HisPharmacyCrossConfigMapper crossConfigMapper,
                                 HisWindowSigninMapper signinMapper, PharmacyDefService pharmacyDefService) {
        this.windowMapper = windowMapper;
        this.workstationMapper = workstationMapper;
        this.deptRuleMapper = deptRuleMapper;
        this.crossConfigMapper = crossConfigMapper;
        this.signinMapper = signinMapper;
        this.pharmacyDefService = pharmacyDefService;
    }

    /* ================= 窗口 ================= */

    public List<HisPharmacyWindow> listWindows(Long orgId, Long pharmacyId) {
        return windowMapper.selectList(Wrappers.<HisPharmacyWindow>lambdaQuery()
                .eq(orgId != null, HisPharmacyWindow::getOrgId, orgId)
                .eq(pharmacyId != null, HisPharmacyWindow::getPharmacyId, pharmacyId)
                .orderByAsc(HisPharmacyWindow::getSortNo)
                .orderByAsc(HisPharmacyWindow::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public HisPharmacyWindow saveWindow(HisPharmacyWindow w) {
        if (w == null) {
            throw new BizException(400, "窗口信息不能为空");
        }
        HisPharmacyWindow exist = w.getId() == null ? null : windowMapper.selectById(w.getId());
        if (w.getId() != null && exist == null) {
            throw new BizException(400, "窗口不存在");
        }
        if (exist != null) {
            w.setOrgId(exist.getOrgId());
        }
        if (w.getOrgId() == null) {
            throw new BizException(400, "机构不能为空");
        }
        if (w.getPharmacyId() == null) {
            throw new BizException(400, "所属药房不能为空");
        }
        HisPharmacyDef def = pharmacyDefService.find(w.getPharmacyId());
        if (def == null || !w.getOrgId().equals(def.getOrgId())) {
            throw new BizException(400, "所属药房不存在或不属于该机构");
        }
        if (!StringUtils.hasText(w.getCode())) {
            throw new BizException(400, "窗口编码不能为空");
        }
        if (!StringUtils.hasText(w.getName())) {
            throw new BizException(400, "窗口名称不能为空");
        }
        w.setCode(w.getCode().trim());
        w.setName(w.getName().trim());
        if (StringUtils.hasText(w.getWindowType())) {
            String wt = w.getWindowType().trim().toUpperCase();
            if (!VALID_WINDOW_TYPES.contains(wt)) {
                throw new BizException(400, "窗口类型不正确(WEST/CHINESE_PATENT/HERB/NARCOTIC/TOXIC/DECOCT/EXPRESS): " + w.getWindowType());
            }
            w.setWindowType(wt);
        }
        // 编码同租户+药房唯一(编辑排除自身)
        Long dup = windowMapper.selectCount(Wrappers.<HisPharmacyWindow>lambdaQuery()
                .eq(HisPharmacyWindow::getPharmacyId, w.getPharmacyId())
                .eq(HisPharmacyWindow::getCode, w.getCode())
                .ne(w.getId() != null, HisPharmacyWindow::getId, w.getId()));
        if (dup != null && dup > 0) {
            throw new BizException("窗口编码已存在: " + w.getCode());
        }
        if (w.getAssignStrategy() == null) {
            w.setAssignStrategy(1);
        }
        if (w.getIsDefault() == null) {
            w.setIsDefault(0);
        }
        if (w.getOpenStatus() == null) {
            w.setOpenStatus(1);
        }
        if (w.getSigninRequired() == null) {
            w.setSigninRequired(0);
        }
        if (w.getTraceRequired() == null) {
            w.setTraceRequired(0);
        }
        if (w.getSortNo() == null) {
            w.setSortNo(0);
        }
        if (w.getStatus() == null) {
            w.setStatus(1);
        }
        // 兜底唯一: 本药房只允许一个 is_default=1, 置1时清除同药房其它默认
        if (w.getIsDefault() != null && w.getIsDefault() == 1) {
            clearOtherDefault(w.getPharmacyId(), w.getId());
        }
        if (w.getId() == null) {
            windowMapper.insert(w);
            log.info("新增发药窗口: id={}, pharmacyId={}, code={}", w.getId(), w.getPharmacyId(), w.getCode());
        } else {
            windowMapper.updateById(w);
            log.info("编辑发药窗口: id={}, code={}", w.getId(), w.getCode());
        }
        return windowMapper.selectById(w.getId());
    }

    /** 开窗/关窗 */
    @Transactional(rollbackFor = Exception.class)
    public HisPharmacyWindow toggleOpen(Long id, boolean open) {
        HisPharmacyWindow exist = requireWindow(id);
        HisPharmacyWindow upd = new HisPharmacyWindow();
        upd.setId(id);
        upd.setOpenStatus(open ? 1 : 0);
        windowMapper.updateById(upd);
        log.info("窗口开关窗: id={}, name={}, open={}", id, exist.getName(), open);
        return windowMapper.selectById(id);
    }

    /** 启停窗口(停用同时清除兜底) */
    @Transactional(rollbackFor = Exception.class)
    public HisPharmacyWindow toggleStatus(Long id, boolean enabled) {
        requireWindow(id);
        HisPharmacyWindow upd = new HisPharmacyWindow();
        upd.setId(id);
        upd.setStatus(enabled ? 1 : 0);
        if (!enabled) {
            upd.setIsDefault(0);
        }
        windowMapper.updateById(upd);
        return windowMapper.selectById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteWindow(Long id) {
        requireWindow(id);
        Long ws = workstationMapper.selectCount(Wrappers.<HisWindowWorkstation>lambdaQuery()
                .eq(HisWindowWorkstation::getWindowId, id));
        if (ws != null && ws > 0) {
            throw new BizException("该窗口已绑定工作站, 请先解绑后再删除");
        }
        Long rule = deptRuleMapper.selectCount(Wrappers.<HisWindowDeptRule>lambdaQuery()
                .eq(HisWindowDeptRule::getWindowId, id));
        if (rule != null && rule > 0) {
            throw new BizException("该窗口被科室定向规则引用, 请先删除规则");
        }
        windowMapper.deleteById(id);
        log.info("删除发药窗口: id={}", id);
    }

    private void clearOtherDefault(Long pharmacyId, Long keepId) {
        List<HisPharmacyWindow> defaults = windowMapper.selectList(Wrappers.<HisPharmacyWindow>lambdaQuery()
                .eq(HisPharmacyWindow::getPharmacyId, pharmacyId)
                .eq(HisPharmacyWindow::getIsDefault, 1)
                .ne(keepId != null, HisPharmacyWindow::getId, keepId));
        for (HisPharmacyWindow d : defaults) {
            HisPharmacyWindow upd = new HisPharmacyWindow();
            upd.setId(d.getId());
            upd.setIsDefault(0);
            windowMapper.updateById(upd);
        }
    }

    /* ================= 工作站↔窗口 ================= */

    public List<HisWindowWorkstation> listWorkstations(Long windowId) {
        return workstationMapper.selectList(Wrappers.<HisWindowWorkstation>lambdaQuery()
                .eq(windowId != null, HisWindowWorkstation::getWindowId, windowId)
                .orderByDesc(HisWindowWorkstation::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public HisWindowWorkstation saveWorkstation(HisWindowWorkstation ws) {
        if (ws == null || ws.getWindowId() == null) {
            throw new BizException(400, "窗口不能为空");
        }
        requireWindow(ws.getWindowId());
        if (ws.getUserId() == null && ws.getStaffId() == null) {
            throw new BizException(400, "用户或职工至少填写一项");
        }
        if (ws.getOrgId() == null) {
            ws.setOrgId(windowMapper.selectById(ws.getWindowId()).getOrgId());
        }
        Long dup = workstationMapper.selectCount(Wrappers.<HisWindowWorkstation>lambdaQuery()
                .eq(HisWindowWorkstation::getWindowId, ws.getWindowId())
                .eq(ws.getUserId() != null, HisWindowWorkstation::getUserId, ws.getUserId())
                .ne(ws.getId() != null, HisWindowWorkstation::getId, ws.getId()));
        if (ws.getUserId() != null && dup != null && dup > 0) {
            throw new BizException("该用户已绑定此窗口");
        }
        if (ws.getId() == null) {
            workstationMapper.insert(ws);
        } else {
            workstationMapper.updateById(ws);
        }
        return workstationMapper.selectById(ws.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteWorkstation(Long id) {
        workstationMapper.deleteById(id);
    }

    /* ================= 科室→窗口规则 ================= */

    public List<HisWindowDeptRule> listDeptRules() {
        return deptRuleMapper.selectList(Wrappers.<HisWindowDeptRule>lambdaQuery()
                .orderByDesc(HisWindowDeptRule::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public HisWindowDeptRule saveDeptRule(HisWindowDeptRule rule) {
        if (rule == null || rule.getDeptId() == null || rule.getWindowId() == null) {
            throw new BizException(400, "科室与窗口均不能为空");
        }
        HisPharmacyWindow win = requireWindow(rule.getWindowId());
        if (rule.getOrgId() == null) {
            rule.setOrgId(win.getOrgId());
        }
        Long dup = deptRuleMapper.selectCount(Wrappers.<HisWindowDeptRule>lambdaQuery()
                .eq(HisWindowDeptRule::getDeptId, rule.getDeptId())
                .eq(HisWindowDeptRule::getWindowId, rule.getWindowId())
                .ne(rule.getId() != null, HisWindowDeptRule::getId, rule.getId()));
        if (dup != null && dup > 0) {
            throw new BizException("该科室到此窗口的定向规则已存在");
        }
        if (rule.getId() == null) {
            deptRuleMapper.insert(rule);
        } else {
            deptRuleMapper.updateById(rule);
        }
        return deptRuleMapper.selectById(rule.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteDeptRule(Long id) {
        deptRuleMapper.deleteById(id);
    }

    /* ================= 跨药房配置 ================= */

    public List<HisPharmacyCrossConfig> listCrossConfigs(Long orgId) {
        return crossConfigMapper.selectList(Wrappers.<HisPharmacyCrossConfig>lambdaQuery()
                .eq(orgId != null, HisPharmacyCrossConfig::getOrgId, orgId)
                .orderByDesc(HisPharmacyCrossConfig::getId));
    }

    @Transactional(rollbackFor = Exception.class)
    public HisPharmacyCrossConfig saveCrossConfig(HisPharmacyCrossConfig cfg) {
        if (cfg == null || cfg.getSourcePharmacyId() == null || cfg.getTargetPharmacyId() == null) {
            throw new BizException(400, "源药房与目标药房均不能为空");
        }
        if (cfg.getSourcePharmacyId().equals(cfg.getTargetPharmacyId())) {
            throw new BizException(400, "源药房与目标药房不能相同");
        }
        HisPharmacyDef src = pharmacyDefService.find(cfg.getSourcePharmacyId());
        HisPharmacyDef tgt = pharmacyDefService.find(cfg.getTargetPharmacyId());
        if (src == null || tgt == null) {
            throw new BizException(400, "源或目标药房不存在");
        }
        if (cfg.getOrgId() == null) {
            cfg.setOrgId(src.getOrgId());
        }
        if (cfg.getAllowCrossStatus() == null) {
            cfg.setAllowCrossStatus(0);
        }
        if (cfg.getEnabled() == null) {
            cfg.setEnabled(1);
        }
        Long dup = crossConfigMapper.selectCount(Wrappers.<HisPharmacyCrossConfig>lambdaQuery()
                .eq(HisPharmacyCrossConfig::getSourcePharmacyId, cfg.getSourcePharmacyId())
                .eq(HisPharmacyCrossConfig::getTargetPharmacyId, cfg.getTargetPharmacyId())
                .ne(cfg.getId() != null, HisPharmacyCrossConfig::getId, cfg.getId()));
        if (dup != null && dup > 0) {
            throw new BizException("该药房对的跨药房配置已存在");
        }
        if (cfg.getId() == null) {
            crossConfigMapper.insert(cfg);
        } else {
            crossConfigMapper.updateById(cfg);
        }
        return crossConfigMapper.selectById(cfg.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public HisPharmacyCrossConfig toggleCrossConfig(Long id, boolean enabled) {
        HisPharmacyCrossConfig exist = crossConfigMapper.selectById(id);
        if (exist == null) {
            throw new BizException(400, "跨药房配置不存在");
        }
        HisPharmacyCrossConfig upd = new HisPharmacyCrossConfig();
        upd.setId(id);
        upd.setEnabled(enabled ? 1 : 0);
        crossConfigMapper.updateById(upd);
        return crossConfigMapper.selectById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteCrossConfig(Long id) {
        crossConfigMapper.deleteById(id);
    }

    /** 判定源→目标是否允许跨药房发药(存在启用配置) */
    public boolean isCrossAllowed(Long sourcePharmacyId, Long targetPharmacyId) {
        if (sourcePharmacyId == null || targetPharmacyId == null) {
            return false;
        }
        Long cnt = crossConfigMapper.selectCount(Wrappers.<HisPharmacyCrossConfig>lambdaQuery()
                .eq(HisPharmacyCrossConfig::getSourcePharmacyId, sourcePharmacyId)
                .eq(HisPharmacyCrossConfig::getTargetPharmacyId, targetPharmacyId)
                .eq(HisPharmacyCrossConfig::getEnabled, 1));
        return cnt != null && cnt > 0;
    }

    /** 源药房是否已启用跨药房白名单(存在任意配置行即视为受控, 未配置则保持旧改派行为不受限) */
    public boolean isCrossControlled(Long sourcePharmacyId) {
        if (sourcePharmacyId == null) {
            return false;
        }
        Long cnt = crossConfigMapper.selectCount(Wrappers.<HisPharmacyCrossConfig>lambdaQuery()
                .eq(HisPharmacyCrossConfig::getSourcePharmacyId, sourcePharmacyId));
        return cnt != null && cnt > 0;
    }

    /* ================= 窗口签到 ================= */

    public List<HisWindowSignin> listSignins(Long windowId) {
        return signinMapper.selectList(Wrappers.<HisWindowSignin>lambdaQuery()
                .eq(windowId != null, HisWindowSignin::getWindowId, windowId)
                .eq(HisWindowSignin::getSigninStatus, 1)
                .orderByDesc(HisWindowSignin::getSigninTime));
    }

    /** 患者到窗口签到(条码/刷卡/发票号定位); 已签到则刷新时间, 幂等 */
    @Transactional(rollbackFor = Exception.class)
    public HisWindowSignin signIn(Long windowId, Long patientId, Long visitId, Long prescriptionId, String signinNo, String by) {
        HisPharmacyWindow win = requireWindow(windowId);
        if (patientId == null && !StringUtils.hasText(signinNo)) {
            throw new BizException(400, "患者ID或签到凭证号至少填写一项");
        }
        HisWindowSignin exist = signinMapper.selectOne(Wrappers.<HisWindowSignin>lambdaQuery()
                .eq(HisWindowSignin::getWindowId, windowId)
                .eq(patientId != null, HisWindowSignin::getPatientId, patientId)
                .eq(patientId == null, HisWindowSignin::getSigninNo, StringUtils.hasText(signinNo) ? signinNo.trim() : null)
                .eq(HisWindowSignin::getSigninStatus, 1)
                .last("LIMIT 1"));
        if (exist != null) {
            HisWindowSignin upd = new HisWindowSignin();
            upd.setId(exist.getId());
            upd.setSigninTime(LocalDateTime.now());
            upd.setSigninBy(by);
            signinMapper.updateById(upd);
            return signinMapper.selectById(exist.getId());
        }
        HisWindowSignin rec = new HisWindowSignin();
        rec.setOrgId(win.getOrgId());
        rec.setWindowId(windowId);
        rec.setPharmacyId(win.getPharmacyId());
        rec.setPatientId(patientId);
        rec.setVisitId(visitId);
        rec.setPrescriptionId(prescriptionId);
        rec.setSigninNo(StringUtils.hasText(signinNo) ? signinNo.trim() : null);
        rec.setSigninStatus(1);
        rec.setSigninBy(by);
        rec.setSigninTime(LocalDateTime.now());
        signinMapper.insert(rec);
        log.info("窗口签到: windowId={}, patientId={}, by={}", windowId, patientId, by);
        return signinMapper.selectById(rec.getId());
    }

    /** 取消签到 */
    @Transactional(rollbackFor = Exception.class)
    public void cancelSignin(Long id) {
        HisWindowSignin exist = signinMapper.selectById(id);
        if (exist == null) {
            throw new BizException(400, "签到记录不存在");
        }
        HisWindowSignin upd = new HisWindowSignin();
        upd.setId(id);
        upd.setSigninStatus(0);
        signinMapper.updateById(upd);
    }

    /** 判定患者在该窗口是否已签到 */
    public boolean isSignedIn(Long windowId, Long patientId) {
        if (windowId == null || patientId == null) {
            return false;
        }
        Long cnt = signinMapper.selectCount(Wrappers.<HisWindowSignin>lambdaQuery()
                .eq(HisWindowSignin::getWindowId, windowId)
                .eq(HisWindowSignin::getPatientId, patientId)
                .eq(HisWindowSignin::getSigninStatus, 1));
        return cnt != null && cnt > 0;
    }

    private HisPharmacyWindow requireWindow(Long id) {
        if (id == null) {
            throw new BizException(400, "窗口ID不能为空");
        }
        HisPharmacyWindow w = windowMapper.selectById(id);
        if (w == null) {
            throw new BizException(400, "窗口不存在");
        }
        return w;
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }
}
