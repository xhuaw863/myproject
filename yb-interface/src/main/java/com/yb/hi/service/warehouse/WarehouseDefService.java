package com.yb.hi.service.warehouse;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yb.hi.entity.warehouse.HisDrugStock;
import com.yb.hi.entity.warehouse.HisWarehouseDef;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.warehouse.HisDrugStockMapper;
import com.yb.hi.mapper.warehouse.HisWarehouseDefMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 药库定义服务(机构级多药库: 西药库/中药库/混合库):
 * - 首次访问自动创建"默认药库"(code=DEFAULT, MIXED), 保证老机构无药库数据也能出入库;
 * - code 在同 tenant+机构下唯一(排除自身), tenant 过滤由租户插件注入;
 * - 停用守卫: 该药库下存在 qty>0 的库存记录时禁止停用, 防止库存"悬空"。
 */
@Slf4j
@Service
public class WarehouseDefService {

    /** 药库类型合法值 */
    private static final List<String> TYPES = Arrays.asList("WESTERN", "TCM", "MIXED");

    private final HisWarehouseDefMapper defMapper;
    private final HisDrugStockMapper stockMapper;

    public WarehouseDefService(HisWarehouseDefMapper defMapper, HisDrugStockMapper stockMapper) {
        this.defMapper = defMapper;
        this.stockMapper = stockMapper;
    }

    /**
     * 启用药库列表(入库/出库/盘点下拉用): status=1 按 sortNo,id 排序。
     * 指定机构首次调用时无任何药库记录 → 自动创建默认药库(幂等, 并发撞唯一键忽略)。
     * orgId 为 null 时(牵头管理员跨机构查看)返回全部机构启用列表且不做默认创建。
     */
    public List<HisWarehouseDef> list(Long orgId) {
        ensureDefault(orgId);
        LambdaQueryWrapper<HisWarehouseDef> w = new LambdaQueryWrapper<>();
        w.eq(orgId != null, HisWarehouseDef::getOrgId, orgId)
                .eq(HisWarehouseDef::getStatus, 1)
                .orderByAsc(HisWarehouseDef::getSortNo)
                .orderByAsc(HisWarehouseDef::getId);
        return defMapper.selectList(w);
    }

    /** 全量药库列表(维护页含停用, 便于重新启用): 按 sortNo,id 排序 */
    public List<HisWarehouseDef> listAll(Long orgId) {
        ensureDefault(orgId);
        LambdaQueryWrapper<HisWarehouseDef> w = new LambdaQueryWrapper<>();
        w.eq(orgId != null, HisWarehouseDef::getOrgId, orgId)
                .orderByAsc(HisWarehouseDef::getSortNo)
                .orderByAsc(HisWarehouseDef::getId);
        return defMapper.selectList(w);
    }

    /**
     * 新增或编辑药库(id==null 为新增)。
     * 校验: code 同机构下唯一(排除自身), 类型取值合法; 编辑不允许变更机构归属。
     */
    @Transactional(rollbackFor = Exception.class)
    public HisWarehouseDef save(HisWarehouseDef def) {
        if (def == null) {
            throw new BizException(400, "药库信息不能为空");
        }
        if (def.getOrgId() == null) {
            throw new BizException(400, "机构不能为空");
        }
        if (!StringUtils.hasText(def.getCode())) {
            throw new BizException(400, "药库编码不能为空");
        }
        if (!StringUtils.hasText(def.getName())) {
            throw new BizException(400, "药库名称不能为空");
        }
        String type = StringUtils.hasText(def.getWarehouseType()) ? def.getWarehouseType().trim() : "MIXED";
        if (!TYPES.contains(type)) {
            throw new BizException(400, "药库类型不合法, 应为 WESTERN/TCM/MIXED: " + type);
        }
        def.setCode(def.getCode().trim());
        def.setWarehouseType(type);

        Long dup = defMapper.selectCount(new LambdaQueryWrapper<HisWarehouseDef>()
                .eq(HisWarehouseDef::getOrgId, def.getOrgId())
                .eq(HisWarehouseDef::getCode, def.getCode())
                .ne(def.getId() != null, HisWarehouseDef::getId, def.getId()));
        if (dup != null && dup > 0) {
            throw new BizException(400, "药库编码已存在: " + def.getCode());
        }

        if (def.getId() == null) {
            def.setStatus(def.getStatus() == null ? 1 : def.getStatus());
            def.setSortNo(def.getSortNo() == null ? 0 : def.getSortNo());
            defMapper.insert(def);
            log.info("新增药库: id={}, orgId={}, code={}, name={}, type={}",
                    def.getId(), def.getOrgId(), def.getCode(), def.getName(), type);
            return def;
        }
        // 编辑: 以库中记录为准锁定机构归属, 防止跨机构篡改
        HisWarehouseDef exist = defMapper.selectById(def.getId());
        if (exist == null) {
            throw new BizException(400, "药库不存在: id=" + def.getId());
        }
        if (!def.getOrgId().equals(exist.getOrgId())) {
            throw new BizException(400, "不允许变更药库所属机构");
        }
        HisWarehouseDef upd = new HisWarehouseDef();
        upd.setId(def.getId());
        upd.setCode(def.getCode());
        upd.setName(def.getName());
        upd.setWarehouseType(type);
        upd.setLocation(def.getLocation());
        upd.setManager(def.getManager());
        upd.setSortNo(def.getSortNo() == null ? exist.getSortNo() : def.getSortNo());
        defMapper.updateById(upd);
        log.info("编辑药库: id={}, code={}, name={}", def.getId(), def.getCode(), def.getName());
        return defMapper.selectById(def.getId());
    }

    /**
     * 启停药库: 停用前校验该药库下无 qty>0 的库存记录, 有则拒绝(避免在途库存被隐藏)。
     */
    @Transactional(rollbackFor = Exception.class)
    public void toggle(Long id, boolean enabled) {
        HisWarehouseDef exist = defMapper.selectById(id);
        if (exist == null) {
            throw new BizException(400, "药库不存在: id=" + id);
        }
        if (!enabled) {
            Long holding = stockMapper.selectCount(new LambdaQueryWrapper<HisDrugStock>()
                    .eq(HisDrugStock::getOrgId, exist.getOrgId())
                    .eq(HisDrugStock::getWarehouseId, id)
                    .gt(HisDrugStock::getQty, BigDecimal.ZERO));
            if (holding != null && holding > 0) {
                throw new BizException(400, "药库[" + exist.getName() + "]下存在 "
                        + holding + " 条有量库存, 不可停用; 请先清空或盘点归零后再停用");
            }
        }
        HisWarehouseDef upd = new HisWarehouseDef();
        upd.setId(id);
        upd.setStatus(enabled ? 1 : 0);
        defMapper.updateById(upd);
        log.info("启停药库: id={}, name={}, enabled={}", id, exist.getName(), enabled);
    }

    /**
     * 默认药库自动创建: 指定机构无任何药库记录时补一条
     * (code=DEFAULT, name=默认药库, type=MIXED, status=1); 已有记录/并发撞唯一键均幂等跳过。
     * 创建成功后顺带把该机构存量未归属(warehouse_id IS NULL)的库存行回填到默认库,
     * 回填条件自带幂等(仅命中未归属行), 并发撞键分支也补跑一次, 避免另一会话先建库导致回填丢失。
     */
    private void ensureDefault(Long orgId) {
        if (orgId == null) {
            return;
        }
        Long cnt = defMapper.selectCount(new LambdaQueryWrapper<HisWarehouseDef>()
                .eq(HisWarehouseDef::getOrgId, orgId));
        if (cnt != null && cnt > 0) {
            return;
        }
        HisWarehouseDef def = new HisWarehouseDef();
        def.setOrgId(orgId);
        def.setCode("DEFAULT");
        def.setName("默认药库");
        def.setWarehouseType("MIXED");
        def.setStatus(1);
        def.setSortNo(0);
        try {
            defMapper.insert(def);
            backfillStockWarehouse(orgId, def.getId());
            log.info("自动创建默认药库: id={}, orgId={}", def.getId(), orgId);
        } catch (DuplicateKeyException e) {
            // 并发首次访问撞唯一键: 另一会话已创建(其事务可能尚未提交, 重试一次回填兼容已提交场景)
            HisWarehouseDef other = defMapper.selectOne(new LambdaQueryWrapper<HisWarehouseDef>()
                    .eq(HisWarehouseDef::getOrgId, orgId)
                    .orderByAsc(HisWarehouseDef::getId)
                    .last("LIMIT 1"));
            if (other != null) {
                backfillStockWarehouse(orgId, other.getId());
            }
        }
    }

    /** 存量未归属库存回填到指定药库(仅命中 warehouse_id IS NULL 行, 可重复执行) */
    private void backfillStockWarehouse(Long orgId, Long warehouseId) {
        int migrated = stockMapper.update(null, new LambdaUpdateWrapper<HisDrugStock>()
                .eq(HisDrugStock::getOrgId, orgId)
                .isNull(HisDrugStock::getWarehouseId)
                .set(HisDrugStock::getWarehouseId, warehouseId));
        if (migrated > 0) {
            log.info("存量库存归属回填默认药库: orgId={}, warehouseId={}, rows={}", orgId, warehouseId, migrated);
        }
    }
}
