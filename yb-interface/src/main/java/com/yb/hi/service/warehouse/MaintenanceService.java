package com.yb.hi.service.warehouse;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.dto.warehouse.MaintenanceCreateReq;
import com.yb.hi.dto.warehouse.MaintenanceItemReq;
import com.yb.hi.entity.warehouse.HisDrugMaintenance;
import com.yb.hi.entity.warehouse.HisDrugMaintenanceItem;
import com.yb.hi.entity.warehouse.HisDrugStock;
import com.yb.hi.entity.warehouse.HisMaintenanceTemplate;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.warehouse.HisDrugMaintenanceItemMapper;
import com.yb.hi.mapper.warehouse.HisDrugMaintenanceMapper;
import com.yb.hi.mapper.warehouse.HisDrugStockMapper;
import com.yb.hi.mapper.warehouse.HisMaintenanceTemplateMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 药品养护服务(批次D): 养护模板 CRUD + 三种建单(手动逐行/自动按在库库存筛选/引入模板) + 明细编辑 + 完成养护。
 * 自动/模板建单从 his_drug_stock 按 (药库/剂型/关键字) 取有余量批次生行; 单号 YH+yyyyMMdd+4位, 租户内唯一。
 * tenant_id 由租户插件自动注入/过滤, 本类不显式处理租户。
 */
@Slf4j
@Service
public class MaintenanceService {

    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter DATE_ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private final HisDrugMaintenanceMapper mntMapper;
    private final HisDrugMaintenanceItemMapper mntItemMapper;
    private final HisMaintenanceTemplateMapper templateMapper;
    private final HisDrugStockMapper stockMapper;

    private String seqDate;
    private int seqNo = 0;

    public MaintenanceService(HisDrugMaintenanceMapper mntMapper, HisDrugMaintenanceItemMapper mntItemMapper,
                              HisMaintenanceTemplateMapper templateMapper, HisDrugStockMapper stockMapper) {
        this.mntMapper = mntMapper;
        this.mntItemMapper = mntItemMapper;
        this.templateMapper = templateMapper;
        this.stockMapper = stockMapper;
    }

    /* ================= 养护模板 ================= */

    public List<HisMaintenanceTemplate> templateList(Long orgId) {
        return templateMapper.selectList(new LambdaQueryWrapper<HisMaintenanceTemplate>()
                .eq(orgId != null, HisMaintenanceTemplate::getOrgId, orgId)
                .orderByDesc(HisMaintenanceTemplate::getId));
    }

    public HisMaintenanceTemplate saveTemplate(HisMaintenanceTemplate tpl) {
        if (!StringUtils.hasText(tpl.getTemplateName())) {
            throw new BizException(400, "模板名称必填");
        }
        if (tpl.getOrgId() == null) {
            throw new BizException(400, "模板需归属机构");
        }
        dupNameCheck(tpl);
        if (tpl.getId() == null) {
            if (tpl.getStatus() == null) {
                tpl.setStatus(1);
            }
            templateMapper.insert(tpl);
        } else {
            templateMapper.updateById(tpl);
        }
        return templateMapper.selectById(tpl.getId());
    }

    public void deleteTemplate(Long id) {
        templateMapper.deleteById(id);
    }

    private void dupNameCheck(HisMaintenanceTemplate tpl) {
        Long cnt = templateMapper.selectCount(new LambdaQueryWrapper<HisMaintenanceTemplate>()
                .eq(HisMaintenanceTemplate::getOrgId, tpl.getOrgId())
                .eq(HisMaintenanceTemplate::getTemplateName, tpl.getTemplateName().trim())
                .ne(tpl.getId() != null, HisMaintenanceTemplate::getId, tpl.getId()));
        if (cnt != null && cnt > 0) {
            throw new BizException("同机构下模板名称已存在: " + tpl.getTemplateName());
        }
    }

    /* ================= 养护单查询 ================= */

    public IPage<HisDrugMaintenance> page(Long orgId, Long warehouseId, Integer status, long page, long size) {
        LambdaQueryWrapper<HisDrugMaintenance> w = new LambdaQueryWrapper<>();
        w.eq(orgId != null, HisDrugMaintenance::getOrgId, orgId)
                .eq(warehouseId != null, HisDrugMaintenance::getWarehouseId, warehouseId)
                .eq(status != null, HisDrugMaintenance::getStatus, status)
                .orderByDesc(HisDrugMaintenance::getId);
        return mntMapper.selectPage(new Page<>(page, size), w);
    }

    public Map<String, Object> detail(Long id) {
        HisDrugMaintenance main = mntMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "养护单不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("main", main);
        out.put("items", mntItemMapper.selectList(new LambdaQueryWrapper<HisDrugMaintenanceItem>()
                .eq(HisDrugMaintenanceItem::getMntId, id).orderByAsc(HisDrugMaintenanceItem::getId)));
        return out;
    }

    /* ================= 建单/编辑/完成 ================= */

    @Transactional(rollbackFor = Exception.class)
    public HisDrugMaintenance create(MaintenanceCreateReq req) {
        int type = req.getMntType() == null ? 1 : req.getMntType();
        if (type < 1 || type > 3) {
            throw new BizException(400, "建单方式非法(1手动 2自动 3模板)");
        }
        Long orgId = req.getOrgId();
        List<HisDrugMaintenanceItem> items;
        Long templateId = null;
        if (type == 1) {
            if (CollectionUtils.isEmpty(req.getItems())) {
                throw new BizException(400, "手动建单需录入至少一条明细");
            }
            items = buildItems(req.getItems());
        } else if (type == 2) {
            items = autoGenerate(orgId, req.getWarehouseId(), req.getDosform(), req.getDrugKeyword(), null);
            if (items.isEmpty()) {
                throw new BizException("按当前筛选条件在库无可选药品批次");
            }
        } else {
            if (req.getTemplateId() == null) {
                throw new BizException(400, "模板建单需指定模板");
            }
            HisMaintenanceTemplate tpl = templateMapper.selectById(req.getTemplateId());
            if (tpl == null) {
                throw new BizException(400, "养护模板不存在");
            }
            templateId = tpl.getId();
            Long wh = req.getWarehouseId() != null ? req.getWarehouseId() : tpl.getWarehouseId();
            String dosform = StringUtils.hasText(req.getDosform()) ? req.getDosform() : tpl.getDosform();
            String keyword = StringUtils.hasText(req.getDrugKeyword()) ? req.getDrugKeyword() : tpl.getDrugKeyword();
            items = autoGenerate(orgId, wh, dosform, keyword, tpl.getDefaultMeasure());
            if (items.isEmpty()) {
                throw new BizException("按模板筛选条件在库无可选药品批次");
            }
            req.setWarehouseId(wh);
        }

        HisDrugMaintenance main = new HisDrugMaintenance();
        main.setOrgId(orgId);
        main.setWarehouseId(req.getWarehouseId());
        main.setMntNo(generateMntNo());
        main.setMntDate(parseDate(req.getMntDate()));
        main.setMntType(type);
        main.setTemplateId(templateId);
        main.setDrugCount(items.size());
        main.setAbnormalCount(0);
        main.setStatus(0);
        main.setRemark(StringUtils.hasText(req.getRemark()) ? req.getRemark().trim() : null);
        mntMapper.insert(main);
        for (HisDrugMaintenanceItem it : items) {
            it.setMntId(main.getId());
            mntItemMapper.insert(it);
        }
        log.info("养护单草稿: mntNo={}, type={}, orgId={}, warehouseId={}, items={}", main.getMntNo(), type, orgId, req.getWarehouseId(), items.size());
        return mntMapper.selectById(main.getId());
    }

    /** 覆盖草稿明细(仅 status=0) */
    @Transactional(rollbackFor = Exception.class)
    public HisDrugMaintenance saveItems(Long id, List<MaintenanceItemReq> items) {
        HisDrugMaintenance main = mustDraft(id);
        if (CollectionUtils.isEmpty(items)) {
            throw new BizException(400, "明细不可为空");
        }
        mntItemMapper.delete(new LambdaQueryWrapper<HisDrugMaintenanceItem>()
                .eq(HisDrugMaintenanceItem::getMntId, id));
        List<HisDrugMaintenanceItem> built = buildItems(items);
        int abnormal = 0;
        for (HisDrugMaintenanceItem it : built) {
            it.setMntId(id);
            if (it.getResult() != null && it.getResult() == 2) {
                abnormal++;
            }
            mntItemMapper.insert(it);
        }
        main.setDrugCount(built.size());
        main.setAbnormalCount(abnormal);
        mntMapper.updateById(main);
        return mntMapper.selectById(id);
    }

    /** 完成养护: 汇总异常行数并置为已完成 */
    @Transactional(rollbackFor = Exception.class)
    public HisDrugMaintenance complete(Long id, String conclusion) {
        HisDrugMaintenance main = mustDraft(id);
        List<HisDrugMaintenanceItem> items = mntItemMapper.selectList(new LambdaQueryWrapper<HisDrugMaintenanceItem>()
                .eq(HisDrugMaintenanceItem::getMntId, id));
        if (items.isEmpty()) {
            throw new BizException("养护单无明细, 不可完成");
        }
        int abnormal = 0;
        for (HisDrugMaintenanceItem it : items) {
            if (it.getResult() != null && it.getResult() == 2) {
                abnormal++;
            }
        }
        main.setDrugCount(items.size());
        main.setAbnormalCount(abnormal);
        main.setConclusion(StringUtils.hasText(conclusion) ? conclusion.trim() : null);
        main.setMntBy(currentUserName());
        main.setMntTime(LocalDateTime.now());
        main.setStatus(1);
        mntMapper.updateById(main);
        log.info("养护单完成: mntNo={}, items={}, abnormal={}", main.getMntNo(), items.size(), abnormal);
        return mntMapper.selectById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteDraft(Long id) {
        mustDraft(id);
        mntMapper.deleteById(id);
        mntItemMapper.delete(new LambdaQueryWrapper<HisDrugMaintenanceItem>()
                .eq(HisDrugMaintenanceItem::getMntId, id));
    }

    /* ================= 内部实现 ================= */

    private HisDrugMaintenance mustDraft(Long id) {
        HisDrugMaintenance main = mntMapper.selectById(id);
        if (main == null) {
            throw new BizException(400, "养护单不存在");
        }
        if (main.getStatus() != null && main.getStatus() == 1) {
            throw new BizException("养护单已完成, 不可修改: " + main.getMntNo());
        }
        return main;
    }

    private List<HisDrugMaintenanceItem> buildItems(List<MaintenanceItemReq> reqs) {
        List<HisDrugMaintenanceItem> out = new ArrayList<>();
        for (MaintenanceItemReq r : reqs) {
            if (r.getDrugCatalogId() == null || !StringUtils.hasText(r.getDrugName())) {
                throw new BizException(400, "养护明细需选择药品");
            }
            HisDrugMaintenanceItem it = new HisDrugMaintenanceItem();
            it.setDrugCatalogId(r.getDrugCatalogId());
            it.setDrugCode(r.getDrugCode());
            it.setDrugName(r.getDrugName());
            it.setSpec(r.getSpec());
            it.setBatchNo(r.getBatchNo());
            it.setManufacturer(r.getManufacturer());
            it.setDosform(r.getDosform());
            it.setStorageCond(r.getStorageCond());
            it.setQty(r.getQty() == null ? BigDecimal.ZERO : r.getQty());
            it.setExpDate(parseDateOrNull(r.getExpDate()));
            it.setMeasure(r.getMeasure());
            it.setResult(r.getResult() == null ? 1 : r.getResult());
            it.setHandler(r.getHandler());
            it.setConclusion(r.getConclusion());
            it.setRemark(r.getRemark());
            out.add(it);
        }
        return out;
    }

    private List<HisDrugMaintenanceItem> autoGenerate(Long orgId, Long warehouseId, String dosform, String keyword, String defaultMeasure) {
        LambdaQueryWrapper<HisDrugStock> w = new LambdaQueryWrapper<>();
        w.eq(orgId != null, HisDrugStock::getOrgId, orgId)
                .eq(warehouseId != null, HisDrugStock::getWarehouseId, warehouseId)
                .eq(HisDrugStock::getStatus, 1)
                .gt(HisDrugStock::getQty, BigDecimal.ZERO)
                .eq(StringUtils.hasText(dosform), HisDrugStock::getDosform, dosform);
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            w.and(x -> x.like(HisDrugStock::getDrugName, kw).or().like(HisDrugStock::getDrugCode, kw));
        }
        w.orderByAsc(HisDrugStock::getDrugCode).orderByAsc(HisDrugStock::getBatchNo);
        List<HisDrugStock> stocks = stockMapper.selectList(w);
        List<HisDrugMaintenanceItem> out = new ArrayList<>();
        for (HisDrugStock s : stocks) {
            HisDrugMaintenanceItem it = new HisDrugMaintenanceItem();
            it.setDrugCatalogId(s.getDrugCatalogId());
            it.setDrugCode(s.getDrugCode());
            it.setDrugName(s.getDrugName());
            it.setSpec(s.getSpec());
            it.setBatchNo(s.getBatchNo());
            it.setManufacturer(s.getManufacturer());
            it.setDosform(s.getDosform());
            it.setQty(s.getQty());
            it.setExpDate(s.getExpDate());
            it.setMeasure(StringUtils.hasText(defaultMeasure) ? defaultMeasure.trim() : null);
            it.setResult(1);
            out.add(it);
        }
        return out;
    }

    private LocalDate parseDate(String s) {
        LocalDate d = parseDateOrNull(s);
        return d == null ? LocalDate.now() : d;
    }

    private LocalDate parseDateOrNull(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim(), DATE_ISO);
        } catch (Exception e) {
            throw new BizException(400, "日期格式应为 yyyy-MM-dd: " + s);
        }
    }

    private synchronized String generateMntNo() {
        String today = LocalDate.now().format(NO_DAY);
        if (!today.equals(seqDate)) {
            seqDate = today;
            seqNo = maxSeqFromDb(today);
        }
        seqNo++;
        String no = "YH" + today + String.format("%04d", seqNo);
        while (noExists(no)) {
            seqNo++;
            no = "YH" + today + String.format("%04d", seqNo);
        }
        return no;
    }

    private int maxSeqFromDb(String today) {
        HisDrugMaintenance one = mntMapper.selectOne(new LambdaQueryWrapper<HisDrugMaintenance>()
                .likeRight(HisDrugMaintenance::getMntNo, "YH" + today)
                .orderByDesc(HisDrugMaintenance::getMntNo)
                .last("LIMIT 1"));
        if (one == null || one.getMntNo() == null || one.getMntNo().length() < 12) {
            return 0;
        }
        try {
            return Integer.parseInt(one.getMntNo().substring(10));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean noExists(String no) {
        return mntMapper.selectCount(new LambdaQueryWrapper<HisDrugMaintenance>()
                .eq(HisDrugMaintenance::getMntNo, no)) > 0;
    }

    private String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername();
    }
}
