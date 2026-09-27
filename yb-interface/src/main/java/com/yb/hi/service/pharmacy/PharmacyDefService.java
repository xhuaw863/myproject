package com.yb.hi.service.pharmacy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.pharmacy.HisPharmacyDef;
import com.yb.hi.entity.warehouse.HisWarehouseDef;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.mapper.pharmacy.HisPharmacyDefMapper;
import com.yb.hi.mapper.warehouse.HisWarehouseDefMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
    private final JdbcTemplate jdbcTemplate;

    public PharmacyDefService(HisPharmacyDefMapper pharmacyDefMapper, HisWarehouseDefMapper warehouseDefMapper,
                             JdbcTemplate jdbcTemplate) {
        this.pharmacyDefMapper = pharmacyDefMapper;
        this.warehouseDefMapper = warehouseDefMapper;
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
}
