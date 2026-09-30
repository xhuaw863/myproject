package com.yb.hi.service.inpatient;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.yb.hi.dto.inpatient.AllergyDTO;
import com.yb.hi.entity.community.HisDrugCatalog;
import com.yb.hi.entity.inpatient.HisInpAllergy;
import com.yb.hi.entity.inpatient.HisInpVisit;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.common.R;
import com.yb.hi.mapper.community.HisDrugCatalogMapper;
import com.yb.hi.mapper.inpatient.HisInpAllergyMapper;
import com.yb.hi.mapper.inpatient.HisInpVisitMapper;
import com.yb.hi.platform.service.OrgAccessGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 住院患者过敏记录服务: 就诊/患者维度查询、新增、失效删除, 并作为开嘱过敏拦截的数据源。
 * 说明: 为避免与 InpVisitService 形成构造器循环依赖, 就诊信息直接经 HisInpVisitMapper 读取。
 */
@Slf4j
@Service
public class InpAllergyService {

    private final HisInpAllergyMapper allergyMapper;
    private final HisInpVisitMapper visitMapper;
    private final HisDrugCatalogMapper drugCatalogMapper;
    private final OrgAccessGuard guard;

    public InpAllergyService(HisInpAllergyMapper allergyMapper, HisInpVisitMapper visitMapper,
                             HisDrugCatalogMapper drugCatalogMapper, OrgAccessGuard guard) {
        this.allergyMapper = allergyMapper;
        this.visitMapper = visitMapper;
        this.drugCatalogMapper = drugCatalogMapper;
        this.guard = guard;
    }

    /** 就诊维度过敏记录(仅有效记录, 记录时间倒序) */
    public R<List<HisInpAllergy>> listByVisit(Long visitId) {
        if (visitId == null) {
            throw new BizException(400, "visitId不能为空");
        }
        List<HisInpAllergy> list = allergyMapper.selectList(new LambdaQueryWrapper<HisInpAllergy>()
                .eq(HisInpAllergy::getInpVisitId, visitId)
                .eq(HisInpAllergy::getStatus, 1)
                .orderByDesc(HisInpAllergy::getRecordTime));
        return R.ok(list);
    }

    /** 患者维度过敏记录(跨就诊汇总, 仅有效记录) */
    public R<List<HisInpAllergy>> listByPatient(Long patientId) {
        if (patientId == null) {
            throw new BizException(400, "patientId不能为空");
        }
        List<HisInpAllergy> list = allergyMapper.selectList(new LambdaQueryWrapper<HisInpAllergy>()
                .eq(HisInpAllergy::getPatientId, patientId)
                .eq(HisInpAllergy::getStatus, 1)
                .orderByDesc(HisInpAllergy::getRecordTime));
        return R.ok(list);
    }

    /** 新增过敏记录(机构归属: 优先就诊机构, 回退当前登录机构) */
    @Transactional(rollbackFor = Exception.class)
    public R<HisInpAllergy> add(AllergyDTO dto) {
        if (dto == null || !StringUtils.hasText(dto.getAllergenName())) {
            throw new BizException(400, "过敏原名称不能为空");
        }
        HisInpAllergy a = new HisInpAllergy();

        Long orgId = null;
        Long patientId = dto.getPatientId();
        if (dto.getInpVisitId() != null) {
            HisInpVisit visit = visitMapper.selectById(dto.getInpVisitId());
            if (visit != null) {
                orgId = visit.getOrgId();
                if (patientId == null) {
                    patientId = visit.getPatientId();
                }
            }
        }
        if (patientId == null) {
            throw new BizException(400, "patientId不能为空");
        }
        a.setOrgId(orgId != null ? orgId : guard.currentOrgId());
        a.setInpVisitId(dto.getInpVisitId());
        a.setPatientId(patientId);
        a.setAllergyType(dto.getAllergyType() != null ? dto.getAllergyType() : 1);
        a.setAllergenName(dto.getAllergenName());
        a.setAllergenCode(dto.getAllergenCode());
        a.setSeverity(dto.getSeverity());
        a.setReactionDesc(dto.getReactionDesc());
        a.setRecordTime(dto.getRecordTime() != null ? dto.getRecordTime() : LocalDateTime.now());
        a.setDoctorId(dto.getDoctorId());
        a.setStatus(dto.getStatus() != null ? dto.getStatus() : 1);
        allergyMapper.insert(a);
        log.info("新增住院过敏记录: patientId={}, visitId={}, allergen={}, type={}",
                patientId, dto.getInpVisitId(), dto.getAllergenName(), a.getAllergyType());
        return R.ok(a);
    }

    /** 失效过敏记录(逻辑标记 status=0 保留留痕, 不物理删除) */
    @Transactional(rollbackFor = Exception.class)
    public R<Void> remove(Long id) {
        if (id == null) {
            throw new BizException(400, "id不能为空");
        }
        HisInpAllergy exist = allergyMapper.selectById(id);
        if (exist == null) {
            throw new BizException(404, "过敏记录不存在");
        }
        int affected = allergyMapper.update(null, new LambdaUpdateWrapper<HisInpAllergy>()
                .eq(HisInpAllergy::getId, id)
                .eq(HisInpAllergy::getStatus, 1)
                .set(HisInpAllergy::getStatus, 0));
        if (affected == 0) {
            throw new BizException("该过敏记录已失效, 请刷新后重试");
        }
        log.info("失效住院过敏记录: id={}, patientId={}", id, exist.getPatientId());
        return R.ok();
    }

    /** 患者是否存在指定过敏原(编码精确匹配, 供外部联动判断) */
    public boolean hasAllergen(Long patientId, String allergenCode) {
        if (patientId == null || !StringUtils.hasText(allergenCode)) {
            return false;
        }
        Long count = allergyMapper.selectCount(new LambdaQueryWrapper<HisInpAllergy>()
                .eq(HisInpAllergy::getPatientId, patientId)
                .eq(HisInpAllergy::getStatus, 1)
                .eq(HisInpAllergy::getAllergenCode, allergenCode));
        return count != null && count > 0;
    }

    /**
     * 开嘱药品过敏比对: 按药品编码(院内码/医保码, 忽略大小写)与药品名(通用名/商品名, 双向包含)匹配,
     * 返回命中的过敏原描述列表(空列表=无过敏冲突)。仅比对药物类过敏(allergyType=1)且有效记录。
     */
    public List<String> checkDrugAllergy(Long patientId, Long drugId) {
        List<String> hits = new ArrayList<>();
        if (patientId == null || drugId == null) {
            return hits;
        }
        HisDrugCatalog drug = drugCatalogMapper.selectById(drugId);
        if (drug == null) {
            return hits;
        }
        List<HisInpAllergy> allergies = allergyMapper.selectList(new LambdaQueryWrapper<HisInpAllergy>()
                .eq(HisInpAllergy::getPatientId, patientId)
                .eq(HisInpAllergy::getAllergyType, 1)
                .eq(HisInpAllergy::getStatus, 1));
        for (HisInpAllergy al : allergies) {
            if (matchDrug(al, drug)) {
                hits.add(al.getAllergenName() + "(严重程度:" + severityName(al.getSeverity()) + ")");
            }
        }
        if (!hits.isEmpty()) {
            log.warn("开嘱过敏冲突: patientId={}, drugId={}, drug={}, hits={}",
                    patientId, drugId, drug.getGenericName(), hits);
        }
        return hits;
    }

    /** 过敏原与药品匹配: 编码精确(忽略大小写) 或 名称双向包含 */
    private boolean matchDrug(HisInpAllergy al, HisDrugCatalog drug) {
        String allergenCode = trim(al.getAllergenCode());
        if (allergenCode != null) {
            if (allergenCode.equalsIgnoreCase(trim(drug.getDrugCode()))
                    || allergenCode.equalsIgnoreCase(trim(drug.getYbDrugCode()))) {
                return true;
            }
        }
        String allergen = trim(al.getAllergenName());
        if (allergen == null) {
            return false;
        }
        String generic = trim(drug.getGenericName());
        String trade = trim(drug.getTradeName());
        return contains(generic, allergen) || contains(allergen, generic) || contains(trade, allergen);
    }

    /** a 非空且 b 非空时判断 a 是否包含 b(忽略大小写) */
    private boolean contains(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.toLowerCase().contains(b.toLowerCase());
    }

    private String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private String severityName(Integer severity) {
        if (severity == null) {
            return "未知";
        }
        switch (severity) {
            case 1:
                return "轻";
            case 2:
                return "中";
            case 3:
                return "重";
            default:
                return "未知";
        }
    }
}
