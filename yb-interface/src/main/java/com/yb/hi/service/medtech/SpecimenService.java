package com.yb.hi.service.medtech;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yb.hi.entity.medtech.HisSpecimen;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.TenantContext;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.medtech.HisSpecimenMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 标本服务(检验标本采集/签收/拒收工作台)。
 * 口径:
 * 1) 标本状态: 0待采集 -> 1已采集(运送中) -> 3已签收; -1已拒收(采集或签收环节发现不合格, 跳过运送中环节简化);
 * 2) 条码: BB + yyyyMMdd + 4位序号, synchronized 内存序号 + 跨日DB回读当日最大序号兜底重启防撞号,
 *    DB回读经 Mapper(租户插件自动过滤) + 唯一键兜底循环;
 * 3) 生成: 仅检验类(order_type=检验)医嘱按明细项目名称推断标本类型与采血管颜色分组, 同类型同管色合管一份;
 * 4) 租户级表 his_order/his_order_item/his_patient/his_staff 跨表查询走 JdbcTemplate 显式租户过滤
 *    (MyBatis-Plus 租户插件仅作用于 Mapper 语句, 原生 SQL 需显式 tenant_id)。
 */
@Slf4j
@Service
public class SpecimenService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 标本条码前缀 */
    private static final String BARCODE_PREFIX = "BB";

    private final HisSpecimenMapper specimenMapper;
    private final JdbcTemplate jdbcTemplate;

    /** 条码内存序号(synchronized 唯一; 跨日重置时从DB回读当日最大序号) */
    private String seqDate;
    private int seqNo = 0;

    public SpecimenService(HisSpecimenMapper specimenMapper, JdbcTemplate jdbcTemplate) {
        this.specimenMapper = specimenMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    /* ================= 标本生成 ================= */

    /**
     * 从检验类医嘱生成标本记录:
     * 校验医嘱存在且 order_type=检验 -> 幂等(已生成直接返回已有) -> 按明细项目推断 (标本类型,采血管颜色) 去重分组
     * -> 每组一份标本(条码+状态0待采集), 血液类如"血常规+生化"因管色不同仍分两管, 同为管色则合管。
     */
    public List<HisSpecimen> generateSpecimens(Long orderId) {
        if (orderId == null) {
            throw new BizException(400, "医嘱ID不能为空");
        }
        Long orgId = requireOrgId();
        long tid = tenantId();
        List<Map<String, Object>> orders = jdbcTemplate.queryForList(
                "SELECT id, patient_id, order_type, status FROM his_order"
                        + " WHERE id = ? AND tenant_id = ? AND deleted = 0", orderId, tid);
        if (orders.isEmpty()) {
            throw new BizException(400, "医嘱单不存在");
        }
        Map<String, Object> order = orders.get(0);
        Number status = (Number) order.get("status");
        if (status != null && status.intValue() < 0) {
            throw new BizException("该医嘱单已作废, 无法生成标本");
        }
        String orderType = str(order.get("order_type"));
        if (!"检验".equals(orderType)) {
            throw new BizException("仅检验类医嘱可生成标本(当前单据类型: " + (orderType == null ? "-" : orderType) + ")");
        }

        // 幂等: 该医嘱已有"未被拒收"的标本则直接返回; 全部拒收时继续往下重生成补采标本(拒收后无法重采是断链)
        List<HisSpecimen> existed = specimenMapper.selectList(Wrappers.<HisSpecimen>lambdaQuery()
                .eq(HisSpecimen::getOrderId, orderId).orderByAsc(HisSpecimen::getId));
        List<HisSpecimen> existedValid = new ArrayList<>();
        for (HisSpecimen s : existed) {
            if (s.getStatus() == null || s.getStatus() >= 0) {
                existedValid.add(s);
            }
        }
        if (!existedValid.isEmpty()) {
            return existedValid;
        }

        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT item_name FROM his_order_item WHERE order_id = ? AND tenant_id = ? AND deleted = 0",
                orderId, tid);
        if (items.isEmpty()) {
            throw new BizException("该医嘱无明细项目, 无法生成标本");
        }

        // 按 (标本类型+采血管颜色) 分组去重: LinkedHashMap 保持明细先后顺序
        Map<String, String[]> groups = new LinkedHashMap<>();
        for (Map<String, Object> it : items) {
            String[] spec = inferSpecimen(str(it.get("item_name")));
            groups.putIfAbsent(spec[0] + "|" + (spec[1] == null ? "" : spec[1]), spec);
        }

        Long patientId = toLong(order.get("patient_id"));
        List<HisSpecimen> created = new ArrayList<>();
        for (String[] spec : groups.values()) {
            HisSpecimen s = new HisSpecimen();
            s.setOrgId(orgId);
            s.setBarcode(nextBarcode());
            s.setOrderId(orderId);
            s.setPatientId(patientId);
            s.setSpecimenType(spec[0]);
            s.setTubeColor(spec[1]);
            s.setStatus(0);
            specimenMapper.insert(s);
            created.add(s);
        }
        log.info("标本生成: orderId={}, orgId={}, 明细{}项, 生成标本{}份, 条码={}",
                orderId, orgId, items.size(), created.size(),
                created.isEmpty() ? "-" : created.get(0).getBarcode());
        return created;
    }

    /**
     * 项目名称推断标本类型与采血管颜色(本地受控规则, 非国标字典):
     * 尿/便/痰优先(名称含"常规"易误判血类) -> 凝血蓝管/血沉黑管/血常规紫管/免疫黄管/生化红管 -> 其余按血液红管 -> 兜底其他。
     * 返回 [标本类型, 采血管颜色(可空=无采血管)]。
     */
    private static String[] inferSpecimen(String itemName) {
        String n = itemName == null ? "" : itemName;
        if (n.contains("尿")) {
            return new String[]{"urine", null};
        }
        if (n.contains("便") || n.contains("粪") || n.contains("隐血")) {
            return new String[]{"stool", null};
        }
        if (n.contains("痰")) {
            return new String[]{"sputum", null};
        }
        if (n.contains("凝血") || n.contains("PT") || n.contains("D-二聚体") || n.contains("纤维蛋白")) {
            return new String[]{"blood", "蓝"};
        }
        if (n.contains("血沉")) {
            return new String[]{"blood", "黑"};
        }
        if (n.contains("血常规") || n.contains("血细胞") || n.contains("网织") || n.contains("血型") || n.contains("交叉配血")) {
            return new String[]{"blood", "紫"};
        }
        if (n.contains("免疫") || n.contains("激素") || n.contains("肿瘤") || n.contains("甲功")
                || n.contains("抗体") || n.contains("标志物") || n.contains("抗原")) {
            return new String[]{"blood", "黄"};
        }
        if (n.contains("生化") || n.contains("肝功") || n.contains("肾功") || n.contains("血糖")
                || n.contains("血脂") || n.contains("电解质") || n.contains("心肌") || n.contains("淀粉酶")
                || n.contains("肌钙") || n.contains("尿酸") || n.contains("蛋白") || n.contains("钙")
                || n.contains("钾") || n.contains("钠") || n.contains("氯")) {
            return new String[]{"blood", "红"};
        }
        if (n.contains("血") || n.contains("红细胞") || n.contains("白细胞") || n.contains("血小板")
                || n.contains("血红蛋白") || n.contains("淋巴")) {
            return new String[]{"blood", "红"};
        }
        return new String[]{"other", null};
    }

    private String nextBarcode() {
        return generateNo();
    }

    /** 条码生成: BB+yyyyMMdd+4位序号, synchronized 唯一, 跨日重置时DB回读当日最大序号兜底重启, 占用冲突再自增 */
    private synchronized String generateNo() {
        String today = LocalDate.now().format(DAY);
        if (!today.equals(seqDate)) {
            seqDate = today;
            seqNo = maxSeqFromDb(today);
        }
        seqNo++;
        String no = BARCODE_PREFIX + today + String.format("%04d", seqNo);
        while (barcodeExists(no)) {
            seqNo++;
            no = BARCODE_PREFIX + today + String.format("%04d", seqNo);
        }
        return no;
    }

    /** 查当日已有条码最大序号(重启后防撞号; Mapper 查询经租户插件自动按当前租户过滤) */
    private int maxSeqFromDb(String today) {
        HisSpecimen one = specimenMapper.selectOne(Wrappers.<HisSpecimen>lambdaQuery()
                .likeRight(HisSpecimen::getBarcode, BARCODE_PREFIX + today)
                .orderByDesc(HisSpecimen::getBarcode)
                .last("LIMIT 1"));
        if (one == null || one.getBarcode() == null || one.getBarcode().length() < 10) {
            return 0;
        }
        try {
            return Integer.parseInt(one.getBarcode().substring(one.getBarcode().length() - 4));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean barcodeExists(String barcode) {
        return specimenMapper.selectCount(Wrappers.<HisSpecimen>lambdaQuery()
                .eq(HisSpecimen::getBarcode, barcode)) > 0;
    }

    /* ================= 标本列表 ================= */

    /**
     * 标本分页(按机构; statuses 支持多值: 待采集[0]/运送中[1,2]/已签收[3])。
     * JOIN his_patient 取患者姓名/就诊卡号/性别/年龄, JOIN his_staff 取采集护士与签收技师姓名。
     */
    public IPage<Map<String, Object>> listSpecimens(Long orgId, List<Integer> statuses, long page, long size) {
        if (orgId == null) {
            throw new BizException(400, "机构ID不能为空");
        }
        long p = safePage(page);
        long s = safeSize(size);
        StringBuilder where = new StringBuilder(" WHERE s.deleted = 0 AND s.tenant_id = ? AND s.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId());
        args.add(orgId);
        if (!CollectionUtils.isEmpty(statuses)) {
            where.append(" AND s.status IN (");
            for (int i = 0; i < statuses.size(); i++) {
                where.append(i == 0 ? "?" : ",?");
                args.add(statuses.get(i));
            }
            where.append(")");
        }
        String joins = " FROM his_specimen s"
                + " LEFT JOIN his_patient p ON p.id = s.patient_id AND p.deleted = 0"
                + " LEFT JOIN his_staff n ON n.id = s.collect_nurse_id AND n.deleted = 0"
                + " LEFT JOIN his_staff t ON t.id = s.receive_tech_id AND t.deleted = 0"
                + " LEFT JOIN his_order o ON o.id = s.order_id AND o.deleted = 0";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + joins + where, Long.class, args.toArray());

        String dataSql = "SELECT s.id, s.barcode, s.order_id AS orderId, s.patient_id AS patientId,"
                + " s.specimen_type AS specimenType, s.tube_color AS tubeColor,"
                + " s.collect_nurse_id AS collectNurseId,"
                + " DATE_FORMAT(s.collect_time, '%Y-%m-%d %H:%i:%s') AS collectTime,"
                + " s.receive_tech_id AS receiveTechId,"
                + " DATE_FORMAT(s.receive_time, '%Y-%m-%d %H:%i:%s') AS receiveTime,"
                + " s.status, s.reject_reason AS rejectReason,"
                + " p.name AS patientName, p.patient_no AS patientNo, p.gender_name AS genderName, p.age,"
                + " n.staff_name AS collectNurseName, t.staff_name AS receiveTechName,"
                + " o.order_no AS orderNo, o.order_type AS orderType"
                + joins + where + " ORDER BY s.id DESC LIMIT ?, ?";
        List<Object> dataArgs = new ArrayList<>(args);
        dataArgs.add((p - 1) * s);
        dataArgs.add(s);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(dataSql, dataArgs.toArray());

        Page<Map<String, Object>> result = new Page<>(p, s);
        result.setTotal(total == null ? 0L : total);
        result.setRecords(rows);
        return result;
    }

    /* ================= 采集/签收/拒收(乐观锁) ================= */

    /** 采集标本: 乐观锁 status 0->1, 记录采集护士与采集时间 */
    public HisSpecimen collectSpecimen(String barcode, Long nurseId) {
        int affected = jdbcTemplate.update(
                "UPDATE his_specimen SET status = 1, collect_nurse_id = ?, collect_time = NOW(),"
                        + " update_by = ?, update_time = NOW()"
                        + " WHERE barcode = ? AND status = 0 AND tenant_id = ? AND deleted = 0",
                nurseId, currentUserName(), barcode, tenantId());
        if (affected == 0) {
            throw new BizException("该标本不存在或状态已变更(仅待采集标本可采集)");
        }
        log.info("标本采集: barcode={}, nurseId={}", barcode, nurseId);
        return requireByBarcode(barcode);
    }

    /** 批量采集: 逐条乐观锁更新, 成功计数, 失败条码回列(部分成功不回滚, 可对失败项重试) */
    public Map<String, Object> batchCollect(List<String> barcodes, Long nurseId) {
        if (CollectionUtils.isEmpty(barcodes)) {
            throw new BizException(400, "请先勾选待采集标本");
        }
        int success = 0;
        List<String> failed = new ArrayList<>();
        for (String barcode : barcodes) {
            if (!StringUtils.hasText(barcode)) {
                continue;
            }
            int affected = jdbcTemplate.update(
                    "UPDATE his_specimen SET status = 1, collect_nurse_id = ?, collect_time = NOW(),"
                            + " update_by = ?, update_time = NOW()"
                            + " WHERE barcode = ? AND status = 0 AND tenant_id = ? AND deleted = 0",
                    nurseId, currentUserName(), barcode.trim(), tenantId());
            if (affected > 0) {
                success++;
            } else {
                failed.add(barcode.trim());
            }
        }
        log.info("标本批量采集: 成功{}条, 失败{}条, nurseId={}", success, failed.size(), nurseId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("failed", failed);
        return result;
    }

    /** 签收标本: 乐观锁 status 1->3(跳过运送中简化), 记录签收技师与签收时间 */
    public HisSpecimen receiveSpecimen(String barcode, Long techId) {
        int affected = jdbcTemplate.update(
                "UPDATE his_specimen SET status = 3, receive_tech_id = ?, receive_time = NOW(),"
                        + " update_by = ?, update_time = NOW()"
                        + " WHERE barcode = ? AND status = 1 AND tenant_id = ? AND deleted = 0",
                techId, currentUserName(), barcode, tenantId());
        if (affected == 0) {
            throw new BizException("该标本不存在或状态已变更(仅已采集运送中的标本可签收)");
        }
        log.info("标本签收: barcode={}, techId={}", barcode, techId);
        return requireByBarcode(barcode);
    }

    /** 拒收标本: 乐观锁 status IN (0,1) -> -1, 记录拒收原因(采集前不合格或签收时不合格) */
    public HisSpecimen rejectSpecimen(String barcode, Long techId, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BizException(400, "拒收原因不能为空");
        }
        int affected = jdbcTemplate.update(
                "UPDATE his_specimen SET status = -1, reject_reason = ?,"
                        + " update_by = ?, update_time = NOW()"
                        + " WHERE barcode = ? AND status IN (0, 1) AND tenant_id = ? AND deleted = 0",
                reason.trim(), currentUserName(), barcode, tenantId());
        if (affected == 0) {
            throw new BizException("该标本不存在或状态已变更(仅待采集/运送中的标本可拒收)");
        }
        log.info("标本拒收: barcode={}, techId={}, reason={}", barcode, techId, reason);
        return requireByBarcode(barcode);
    }

    /** 按条码回读标本(Mapper 查询经租户插件自动过滤) */
    public HisSpecimen requireByBarcode(String barcode) {
        HisSpecimen s = specimenMapper.selectOne(Wrappers.<HisSpecimen>lambdaQuery()
                .eq(HisSpecimen::getBarcode, barcode));
        if (s == null) {
            throw new BizException(400, "标本不存在");
        }
        return s;
    }

    /* ================= 辅助 ================= */

    private static long tenantId() {
        Long t = TenantContext.get();
        return t == null ? 0L : t;
    }

    private Long requireOrgId() {
        LoginUser u = UserContext.get();
        if (u == null || u.getOrgId() == null) {
            throw new BizException(403, "当前账号未归属任何机构, 无法操作医技业务");
        }
        return u.getOrgId();
    }

    private String currentUserName() {
        LoginUser lu = UserContext.get();
        if (lu == null) {
            return null;
        }
        return StringUtils.hasText(lu.getRealName()) ? lu.getRealName() : lu.getUsername();
    }

    private static long safePage(long page) {
        return page < 1 ? 1 : page;
    }

    private static long safeSize(long size) {
        if (size < 1) {
            return 20;
        }
        return size > 200 ? 200 : size;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Long toLong(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.valueOf(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
