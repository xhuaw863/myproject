package com.yb.hi.service.pharmacy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.pharmacy.HisPharmacyDef;
import com.yb.hi.entity.pharmacy.HisRxPharmacyRoute;
import com.yb.hi.entity.warehouse.HisWarehouseDef;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.pharmacy.HisPharmacyDefMapper;
import com.yb.hi.mapper.pharmacy.HisRxPharmacyRouteMapper;
import com.yb.hi.mapper.warehouse.HisWarehouseDefMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 药房定义服务(机构级多药房): 列表(机构无任何药房记录时自动建默认门诊药房) / 保存 / 启停。
 * 说明:
 * 1) 药房为机构级配置, tenant_id 由 MyBatis-Plus 租户插件自动注入/过滤, code 在同 tenant+org 下唯一;
 * 2) 关联药库(his_warehouse_def)存在性校验走 JdbcTemplate 原生 SQL(显式 tenant_id, 插件不作用于原生语句)。
 */
@Slf4j
@Service
public class PharmacyDefService {

    /** 药房类型: 门诊药房 */
    public static final String TYPE_OUTPATIENT = "OUTPATIENT";
    /** 药房类型: 住院药房 */
    public static final String TYPE_INPATIENT = "INPATIENT";
    /** 药房类型: 中药房 */
    public static final String TYPE_TCM = "TCM";

    /** 合法药房类型 */
    private static final List<String> VALID_TYPES = Arrays.asList(TYPE_OUTPATIENT, TYPE_INPATIENT, TYPE_TCM);
    /** 默认药房编码(机构无任何药房记录时自动创建) */
    private static final String DEFAULT_CODE = "DEFAULT";

    private final HisPharmacyDefMapper pharmacyDefMapper;
    private final HisWarehouseDefMapper warehouseDefMapper;
    private final HisRxPharmacyRouteMapper rxRouteMapper;
    private final JdbcTemplate jdbcTemplate;

    public PharmacyDefService(HisPharmacyDefMapper pharmacyDefMapper, HisWarehouseDefMapper warehouseDefMapper,
                             HisRxPharmacyRouteMapper rxRouteMapper, JdbcTemplate jdbcTemplate) {
        this.pharmacyDefMapper = pharmacyDefMapper;
        this.warehouseDefMapper = warehouseDefMapper;
        this.rxRouteMapper = rxRouteMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 查询 ================= */

    /**
     * 药房列表(该机构启用中的药房, status=1, 按 sortNo/id 升序)。
     * 机构无任何药房记录时自动创建默认门诊药房(code=DEFAULT, OUTPATIENT)后返回,
     * 保证药房工作站首次进入即有可选用药房; warehouseId 暂为 null(待药库建立后关联)。
     */
    public List<HisPharmacyDef> list(Long orgId) {
        if (orgId == null) {
            throw new BizException(400, "机构不能为空");
        }
        if (!anyExists(orgId)) {
            createDefault(orgId);
        }
        return enabledList(orgId);
    }

    /** 按ID取启用中的药房(不存在/不属于该机构/已停用抛400); orgId 非空时校验归属机构 */
    public HisPharmacyDef requireEnabled(Long id, Long orgId) {
        if (id == null) {
            throw new BizException(400, "药房ID不能为空");
        }
        HisPharmacyDef def = pharmacyDefMapper.selectById(id);
        if (def == null || (orgId != null && !orgId.equals(def.getOrgId()))) {
            throw new BizException(400, "药房不存在");
        }
        if (def.getStatus() == null || def.getStatus() != 1) {
            throw new BizException(400, "药房已停用: " + def.getName());
        }
        return def;
    }

    /** 按ID取药房(不存在返回 null, 不做启停校验, 供历史单据/退药回溯关联药库) */
    public HisPharmacyDef find(Long id) {
        return id == null ? null : pharmacyDefMapper.selectById(id);
    }

    /* ================= 科室默认发药药房(三期) ================= */

    /** 中西药渠道判定: rxType 含"中药"走中药渠道, 其余(西药/中成药)走西药渠道 */
    public static boolean isTcmChannel(String rxType) {
        return rxType != null && rxType.contains("中药");
    }

    /**
     * 解析科室默认发药药房ID(科室×中西药渠道, 未配置返回 null 供开方回落)。
     * 配置失效(药房不存在/已停用)不阻断开方, 记日志后按未配置处理(医生手工改选)。
     * his_dept 为租户表但 JdbcTemplate 不走租户插件, 显式 tenant_id 过滤。
     */
    public Long resolveDefaultPharmacyId(Long deptId, String rxType) {
        if (deptId == null) {
            return null;
        }
        String col = isTcmChannel(rxType) ? "def_pharmacy_tcm" : "def_pharmacy_west";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT " + col + " AS pid FROM his_dept WHERE id = ? AND tenant_id = ? AND deleted = 0",
                deptId, tenantId());
        if (rows.isEmpty() || rows.get(0).get("pid") == null) {
            return null;
        }
        Long pid = ((Number) rows.get(0).get("pid")).longValue();
        HisPharmacyDef def = pharmacyDefMapper.selectById(pid);
        if (def == null || def.getStatus() == null || def.getStatus() != 1) {
            log.warn("科室默认发药药房配置失效(不存在/已停用), 按未配置处理: deptId={}, pharmacyId={}", deptId, pid);
            return null;
        }
        return pid;
    }

    /* ================= 处方发药默认药房路由(P5) ================= */

    /** 药品大类→中西药渠道: 含中药/饮片/颗粒走中药渠道, 其余西药渠道(供路由未命中回落 his_dept 默认药房) */
    public static boolean isTcmMajorClass(String majorClass) {
        return majorClass != null && (majorClass.contains("中药") || majorClass.contains("饮片") || majorClass.contains("颗粒"));
    }

    /**
     * 处方发药默认药房解析(P5): 科室×时段×药品大类 → 药房, 优先级链 精确(科室+时段+大类) > 科室+大类 >
     * 科室+时段 > 科室 > his_dept 默认西/中药药房兑底。未配置或未命中返回 null(不阻断开方)。
     * timeSlot 为空且 nowTime 非空时经班次字典自动推算当前时段码; pharmacy_id 命中药房须启用且同机构, 失效则回落下一级。
     * his_rx_pharmacy_route 为租户表(MP 插件自动注入 tenant_id), 此处按 orgId 显式过滤。
     */
    public Long resolveRxPharmacy(Long deptId, String majorClass, LocalTime nowTime) {
        Long orgId = currentOrgId();
        String slot = currentShiftCode(nowTime);
        List<HisRxPharmacyRoute> candidates = rxRouteMapper.selectList(Wrappers.<HisRxPharmacyRoute>lambdaQuery()
                .eq(orgId != null, HisRxPharmacyRoute::getOrgId, orgId)
                .eq(HisRxPharmacyRoute::getStatus, 1));
        HisRxPharmacyRoute best = null;
        int bestScore = -1;
        for (HisRxPharmacyRoute r : candidates) {
            int score = matchScore(r, deptId, slot, majorClass);
            if (score < 0) {
                continue;
            }
            int pri = r.getPriority() == null ? 100 : r.getPriority();
            int bestPri = best == null || best.getPriority() == null ? 100 : best.getPriority();
            if (score > bestScore || (score == bestScore && best != null && pri < bestPri)) {
                best = r;
                bestScore = score;
            }
        }
        if (best != null) {
            HisPharmacyDef def = pharmacyDefMapper.selectById(best.getPharmacyId());
            if (def != null && def.getStatus() != null && def.getStatus() == 1) {
                return def.getId();
            }
            log.warn("路由命中药房失效(不存在/已停用), 回落默认: routeId={}, pharmacyId={}", best.getId(), best.getPharmacyId());
        }
        // 回落 his_dept 默认西/中药药房(兼容未配置路由的存量科室)
        return resolveDefaultPharmacyId(deptId, isTcmMajorClass(majorClass) ? "中药" : "西药");
    }

    /**
     * 规则匹配打分: 返回 -1 表示不匹配(某非空维度与请求不符); 否则返回精确命中维度数(0~3, 越大越优先)。
     * 维度为 null 表示通配(不限), 不扣分也不加分。
     */
    private int matchScore(HisRxPharmacyRoute r, Long deptId, String slot, String majorClass) {
        int score = 0;
        if (r.getDeptId() != null) {
            if (deptId == null || !deptId.equals(r.getDeptId())) {
                return -1;
            }
            score++;
        }
        if (StringUtils.hasText(r.getTimeSlot())) {
            if (!r.getTimeSlot().equals(slot)) {
                return -1;
            }
            score++;
        }
        if (StringUtils.hasText(r.getDrugMajorClass())) {
            if (!r.getDrugMajorClass().equals(majorClass)) {
                return -1;
            }
            score++;
        }
        return score;
    }

    /**
     * 由当前时刻经班次字典(his_shift_dict)推算时段码: 命中 start_time~end_time 区间的启用班次;
     * nowTime 为空或未命中返回 null(不参与时段维度匹配)。his_shift_dict 租户表但 JdbcTemplate 不走插件, 显式 tenant_id。
     */
    private String currentShiftCode(LocalTime nowTime) {
        if (nowTime == null) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT code, start_time, end_time FROM his_shift_dict WHERE tenant_id = ? AND status = 1 AND deleted = 0"
                        + " AND start_time IS NOT NULL AND end_time IS NOT NULL",
                tenantId());
        for (Map<String, Object> row : rows) {
            LocalTime st = parseHm(str(row.get("start_time")));
            LocalTime et = parseHm(str(row.get("end_time")));
            if (st == null || et == null) {
                continue;
            }
            boolean hit = et.isBefore(st) ? (!nowTime.isBefore(st) || !nowTime.isAfter(et))
                    : (!nowTime.isBefore(st) && !nowTime.isAfter(et));
            if (hit) {
                return str(row.get("code"));
            }
        }
        return null;
    }

    private static LocalTime parseHm(String hm) {
        if (!StringUtils.hasText(hm)) {
            return null;
        }
        try {
            return LocalTime.parse(hm.trim(), DateTimeFormatter.ofPattern("H:mm"));
        } catch (Exception e) {
            try {
                return LocalTime.parse(hm.trim());
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /* ================= 路由规则 CRUD(P5) ================= */

    /** 路由规则列表(按机构, 含停用; 优先级/精确度排序供展示) */
    public List<HisRxPharmacyRoute> listRoutes(Long orgId) {
        return rxRouteMapper.selectList(Wrappers.<HisRxPharmacyRoute>lambdaQuery()
                .eq(orgId != null, HisRxPharmacyRoute::getOrgId, orgId)
                .orderByAsc(HisRxPharmacyRoute::getPriority)
                .orderByDesc(HisRxPharmacyRoute::getId));
    }

    /** 新增/编辑路由规则; 校验 pharmacyId 启用同机构, 同(科室+时段+大类)组合判重(排除自身) */
    @Transactional(rollbackFor = Exception.class)
    public HisRxPharmacyRoute saveRoute(HisRxPharmacyRoute route) {
        if (route == null || route.getPharmacyId() == null) {
            throw new BizException(400, "发药药房不能为空");
        }
        HisRxPharmacyRoute exist = route.getId() == null ? null : rxRouteMapper.selectById(route.getId());
        if (route.getId() != null && exist == null) {
            throw new BizException(400, "路由规则不存在");
        }
        Long orgId = exist != null ? exist.getOrgId() : (route.getOrgId() != null ? route.getOrgId() : currentOrgId());
        if (orgId == null) {
            throw new BizException(400, "机构不能为空");
        }
        route.setOrgId(orgId);
        // 发药药房必须存在且启用同机构
        requireEnabled(route.getPharmacyId(), orgId);
        if (!StringUtils.hasText(route.getTimeSlot())) {
            route.setTimeSlot(null);
        }
        if (!StringUtils.hasText(route.getDrugMajorClass())) {
            route.setDrugMajorClass(null);
        }
        if (route.getPriority() == null) {
            route.setPriority(100);
        }
        // 同(科室+时段+大类)组合判重(null 代表通配, 归一为空串参与比较)
        Long dup = rxRouteMapper.selectCount(Wrappers.<HisRxPharmacyRoute>lambdaQuery()
                .eq(HisRxPharmacyRoute::getOrgId, orgId)
                .eq(route.getDeptId() != null, HisRxPharmacyRoute::getDeptId, route.getDeptId())
                .isNull(route.getDeptId() == null, HisRxPharmacyRoute::getDeptId)
                .eq(StringUtils.hasText(route.getTimeSlot()), HisRxPharmacyRoute::getTimeSlot, route.getTimeSlot())
                .isNull(!StringUtils.hasText(route.getTimeSlot()), HisRxPharmacyRoute::getTimeSlot)
                .eq(StringUtils.hasText(route.getDrugMajorClass()), HisRxPharmacyRoute::getDrugMajorClass, route.getDrugMajorClass())
                .isNull(!StringUtils.hasText(route.getDrugMajorClass()), HisRxPharmacyRoute::getDrugMajorClass)
                .ne(route.getId() != null, HisRxPharmacyRoute::getId, route.getId()));
        if (dup != null && dup > 0) {
            throw new BizException(400, "相同科室+时段+药品大类的路由规则已存在, 不允许重复配置");
        }
        if (route.getId() == null) {
            if (route.getStatus() == null) {
                route.setStatus(1);
            }
            rxRouteMapper.insert(route);
            log.info("新增发药路由: id={}, orgId={}, deptId={}, slot={}, majorClass={}, pharmacyId={}",
                    route.getId(), orgId, route.getDeptId(), route.getTimeSlot(), route.getDrugMajorClass(), route.getPharmacyId());
        } else {
            rxRouteMapper.updateById(route);
            log.info("编辑发药路由: id={}, pharmacyId={}, status={}", route.getId(), route.getPharmacyId(), route.getStatus());
        }
        return rxRouteMapper.selectById(route.getId());
    }

    /** 启停路由规则 */
    @Transactional(rollbackFor = Exception.class)
    public HisRxPharmacyRoute toggleRoute(Long id, boolean enabled) {
        if (id == null) {
            throw new BizException(400, "路由规则ID不能为空");
        }
        if (rxRouteMapper.selectById(id) == null) {
            throw new BizException(400, "路由规则不存在");
        }
        HisRxPharmacyRoute upd = new HisRxPharmacyRoute();
        upd.setId(id);
        upd.setStatus(enabled ? 1 : 0);
        rxRouteMapper.updateById(upd);
        return rxRouteMapper.selectById(id);
    }

    /** 删除路由规则(逻辑删除) */
    @Transactional(rollbackFor = Exception.class)
    public void deleteRoute(Long id) {
        if (id == null) {
            throw new BizException(400, "路由规则ID不能为空");
        }
        rxRouteMapper.deleteById(id);
    }

    /* ================= 保存 / 启停 ================= */

    /**
     * 新增或编辑药房。
     * 校验: code 同租户+机构唯一(编辑排除自身); warehouseId 非空须对应 his_warehouse_def 存在且启用(同机构)。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisPharmacyDef save(HisPharmacyDef def) {
        if (def == null) {
            throw new BizException(400, "药房信息不能为空");
        }
        // 编辑: 先回读原记录(不存在拒绝), 归属机构不允许迁移
        HisPharmacyDef exist = def.getId() == null ? null : pharmacyDefMapper.selectById(def.getId());
        if (def.getId() != null && exist == null) {
            throw new BizException(400, "药房不存在");
        }
        if (exist != null) {
            def.setOrgId(exist.getOrgId());
        }
        if (def.getOrgId() == null) {
            throw new BizException(400, "机构不能为空");
        }
        if (!StringUtils.hasText(def.getCode())) {
            throw new BizException(400, "药房编码不能为空");
        }
        if (!StringUtils.hasText(def.getName())) {
            throw new BizException(400, "药房名称不能为空");
        }
        def.setCode(def.getCode().trim());
        def.setName(def.getName().trim());
        String type = StringUtils.hasText(def.getPharmacyType())
                ? def.getPharmacyType().trim().toUpperCase() : TYPE_OUTPATIENT;
        if (!VALID_TYPES.contains(type)) {
            throw new BizException(400, "药房类型不正确(OUTPATIENT/INPATIENT/TCM): " + def.getPharmacyType());
        }
        def.setPharmacyType(type);

        // 编码同租户+机构唯一(编辑排除自身)
        Long dup = pharmacyDefMapper.selectCount(Wrappers.<HisPharmacyDef>lambdaQuery()
                .eq(HisPharmacyDef::getOrgId, def.getOrgId())
                .eq(HisPharmacyDef::getCode, def.getCode())
                .ne(def.getId() != null, HisPharmacyDef::getId, def.getId()));
        if (dup != null && dup > 0) {
            throw new BizException("药房编码已存在: " + def.getCode());
        }
        // 归属科室一一对应校验: 非空时该科室不得被其它药房占用(排除自身; 与 uk_dept 同口径, 不分启用/停用)
        if (def.getDeptId() != null) {
            Long deptDup = pharmacyDefMapper.selectCount(Wrappers.<HisPharmacyDef>lambdaQuery()
                    .eq(HisPharmacyDef::getDeptId, def.getDeptId())
                    .ne(def.getId() != null, HisPharmacyDef::getId, def.getId()));
            if (deptDup != null && deptDup > 0) {
                throw new BizException(400, "该科室已绑定其它药房, 药房与科室一一对应不允许重复绑定");
            }
        }
        // 关联药库校验: 非空须存在且启用(同机构)
        if (def.getWarehouseId() != null && !warehouseEnabled(def.getWarehouseId(), def.getOrgId())) {
            throw new BizException("关联药库不存在或已停用: warehouseId=" + def.getWarehouseId());
        }

        if (def.getId() == null) {
            if (def.getStatus() == null) {
                def.setStatus(1);
            }
            if (def.getSortNo() == null) {
                def.setSortNo(0);
            }
            pharmacyDefMapper.insert(def);
            log.info("新增药房: id={}, orgId={}, code={}, name={}, type={}",
                    def.getId(), def.getOrgId(), def.getCode(), def.getName(), def.getPharmacyType());
        } else {
            def.setStockLocationId(exist.getStockLocationId());
            pharmacyDefMapper.updateById(def);
            log.info("编辑药房: id={}, code={}, name={}, status={}, warehouseId={}",
                    def.getId(), def.getCode(), def.getName(), def.getStatus(), def.getWarehouseId());
        }
        // 两级库存基座: 无论文新建/编辑, 确保本药房拥有 PHARMACY 库存位并回填 stock_location_id
        ensureStockLocation(pharmacyDefMapper.selectById(def.getId()));
        return pharmacyDefMapper.selectById(def.getId());
    }

    /** 启停药房: enabled=true 置1启用 / false 置0停用 */
    @Transactional(rollbackFor = Exception.class)
    public HisPharmacyDef toggle(Long id, boolean enabled) {
        if (id == null) {
            throw new BizException(400, "药房ID不能为空");
        }
        HisPharmacyDef exist = pharmacyDefMapper.selectById(id);
        if (exist == null) {
            throw new BizException(400, "药房不存在");
        }
        HisPharmacyDef upd = new HisPharmacyDef();
        upd.setId(id);
        upd.setStatus(enabled ? 1 : 0);
        pharmacyDefMapper.updateById(upd);
        log.info("药房启停: id={}, name={}, enabled={}", id, exist.getName(), enabled);
        return pharmacyDefMapper.selectById(id);
    }

    /* ================= 内部实现 ================= */

    /** 该机构是否存在任何药房记录(含停用; 有记录但全停用时不自动重建默认药房) */
    private boolean anyExists(Long orgId) {
        return pharmacyDefMapper.selectCount(Wrappers.<HisPharmacyDef>lambdaQuery()
                .eq(HisPharmacyDef::getOrgId, orgId)) > 0;
    }

    /** 机构启用中的药房列表(sortNo/id 升序) */
    private List<HisPharmacyDef> enabledList(Long orgId) {
        return pharmacyDefMapper.selectList(Wrappers.<HisPharmacyDef>lambdaQuery()
                .eq(HisPharmacyDef::getOrgId, orgId)
                .eq(HisPharmacyDef::getStatus, 1)
                .orderByAsc(HisPharmacyDef::getSortNo)
                .orderByAsc(HisPharmacyDef::getId));
    }

    /** 自动创建默认门诊药房(并发首访撞唯一键则忽略, 复用已创建记录) */
    private void createDefault(Long orgId) {
        HisPharmacyDef def = new HisPharmacyDef();
        def.setOrgId(orgId);
        def.setCode(DEFAULT_CODE);
        def.setName("默认门诊药房");
        def.setPharmacyType(TYPE_OUTPATIENT);
        def.setStatus(1);
        def.setSortNo(0);
        try {
            pharmacyDefMapper.insert(def);
            ensureStockLocation(pharmacyDefMapper.selectById(def.getId()));
            log.info("自动创建默认药房: orgId={}, id={}", orgId, def.getId());
        } catch (DuplicateKeyException e) {
            log.info("默认药房已存在(并发首访), 复用: orgId={}", orgId);
        }
    }

    /**
     * 两级库存基座: 为药房确保一个 PHARMACY 型库存位并回填 stock_location_id(幂等)。
     * 优先按 ref_pharmacy_id 命中(可抵御药房编码变更), 其次按 PHLOC-编码 命中并回填 ref,
     * 均无则新建; 撞唯一键回查复用。调用前提: def.getId() 非空(已落库)。
     */
    private void ensureStockLocation(HisPharmacyDef def) {
        if (def == null || def.getId() == null || def.getOrgId() == null) {
            return;
        }
        String locCode = "PHLOC-" + def.getCode();
        String whType = TYPE_TCM.equals(def.getPharmacyType()) ? "TCM" : "MIXED";
        HisWarehouseDef loc = warehouseDefMapper.selectOne(Wrappers.<HisWarehouseDef>lambdaQuery()
                .eq(HisWarehouseDef::getOrgId, def.getOrgId())
                .eq(HisWarehouseDef::getKind, "PHARMACY")
                .eq(HisWarehouseDef::getRefPharmacyId, def.getId())
                .last("LIMIT 1"));
        if (loc == null) {
            loc = warehouseDefMapper.selectOne(Wrappers.<HisWarehouseDef>lambdaQuery()
                    .eq(HisWarehouseDef::getOrgId, def.getOrgId())
                    .eq(HisWarehouseDef::getCode, locCode)
                    .last("LIMIT 1"));
            if (loc == null) {
                loc = new HisWarehouseDef();
                loc.setOrgId(def.getOrgId());
                loc.setCode(locCode);
                loc.setName((def.getName() == null ? def.getCode() : def.getName()) + "-库存位");
                loc.setWarehouseType(whType);
                loc.setKind("PHARMACY");
                loc.setRefPharmacyId(def.getId());
                loc.setStatus(1);
                loc.setSortNo(0);
                try {
                    warehouseDefMapper.insert(loc);
                } catch (DuplicateKeyException e) {
                    loc = warehouseDefMapper.selectOne(Wrappers.<HisWarehouseDef>lambdaQuery()
                            .eq(HisWarehouseDef::getOrgId, def.getOrgId())
                            .eq(HisWarehouseDef::getCode, locCode)
                            .last("LIMIT 1"));
                }
            } else if (loc.getKind() == null || !"PHARMACY".equals(loc.getKind())
                    || !def.getId().equals(loc.getRefPharmacyId())) {
                HisWarehouseDef locUpd = new HisWarehouseDef();
                locUpd.setId(loc.getId());
                locUpd.setKind("PHARMACY");
                locUpd.setRefPharmacyId(def.getId());
                warehouseDefMapper.updateById(locUpd);
            }
        }
        if (loc != null && loc.getId() != null && !loc.getId().equals(def.getStockLocationId())) {
            HisPharmacyDef upd = new HisPharmacyDef();
            upd.setId(def.getId());
            upd.setStockLocationId(loc.getId());
            pharmacyDefMapper.updateById(upd);
            log.info("药房库存位回填: pharmacyId={}, stockLocationId={}", def.getId(), loc.getId());
        }
    }

    /** 关联药库是否存在于该机构且启用(原生SQL显式租户过滤) */
    private boolean warehouseEnabled(Long warehouseId, Long orgId) {
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM his_warehouse_def"
                        + " WHERE id = ? AND tenant_id = ? AND org_id = ? AND status = 1 AND deleted = 0",
                Long.class, warehouseId, tenantId(), orgId);
        return cnt != null && cnt > 0;
    }

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    /** 当前登录用户归属机构(路由解析/新增未显式传 orgId 时的默认作用域) */
    private static Long currentOrgId() {
        LoginUser lu = UserContext.get();
        return lu == null ? null : lu.getOrgId();
    }
}
