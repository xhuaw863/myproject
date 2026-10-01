package com.yb.hi.service.doctor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yb.hi.entity.doctor.HisFeverRegister;
import com.yb.hi.entity.doctor.HisPreConsult;
import com.yb.hi.entity.doctor.HisUserDisplayPref;
import com.yb.hi.entity.doctor.HisVitalSign;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import com.yb.hi.mapper.doctor.HisFeverRegisterMapper;
import com.yb.hi.mapper.doctor.HisPreConsultMapper;
import com.yb.hi.mapper.doctor.HisUserDisplayPrefMapper;
import com.yb.hi.mapper.doctor.HisVitalSignMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 门诊医生站就诊增强服务(OP-A): 显示偏好 / 生命体征(含血糖血酮趋势与发热联动) / 诊前预问诊 / 发热登记。
 */
@Service
public class OutpEncounterService {

    /** 发热阈值(℃), 达此体温自动触发发热病人登记 */
    private static final BigDecimal FEVER_THRESHOLD = new BigDecimal("37.3");

    private final HisUserDisplayPrefMapper prefMapper;
    private final HisVitalSignMapper vitalMapper;
    private final HisPreConsultMapper preConsultMapper;
    private final HisFeverRegisterMapper feverMapper;

    public OutpEncounterService(HisUserDisplayPrefMapper prefMapper, HisVitalSignMapper vitalMapper,
                                HisPreConsultMapper preConsultMapper, HisFeverRegisterMapper feverMapper) {
        this.prefMapper = prefMapper;
        this.vitalMapper = vitalMapper;
        this.preConsultMapper = preConsultMapper;
        this.feverMapper = feverMapper;
    }

    /* ---------- 患者信息栏/布局显示偏好(个人级) ---------- */

    public HisUserDisplayPref getPref(String scene) {
        Long userId = currentUserId();
        return prefMapper.selectOne(Wrappers.<HisUserDisplayPref>lambdaQuery()
                .eq(HisUserDisplayPref::getUserId, userId)
                .eq(HisUserDisplayPref::getScene, scene)
                .last("LIMIT 1"));
    }

    public HisUserDisplayPref savePref(String scene, String configJson) {
        if (!StringUtils.hasText(scene)) {
            throw new BizException(400, "场景不能为空");
        }
        Long userId = currentUserId();
        HisUserDisplayPref existing = prefMapper.selectOne(Wrappers.<HisUserDisplayPref>lambdaQuery()
                .eq(HisUserDisplayPref::getUserId, userId)
                .eq(HisUserDisplayPref::getScene, scene)
                .last("LIMIT 1"));
        if (existing == null) {
            HisUserDisplayPref pref = new HisUserDisplayPref();
            pref.setUserId(userId);
            pref.setScene(scene);
            pref.setConfigJson(configJson);
            prefMapper.insert(pref);
            return pref;
        }
        existing.setConfigJson(configJson);
        prefMapper.updateById(existing);
        return existing;
    }

    /* ---------- 生命体征 ---------- */

    public List<HisVitalSign> listVitalByVisit(Long visitId) {
        return vitalMapper.selectList(Wrappers.<HisVitalSign>lambdaQuery()
                .eq(HisVitalSign::getVisitId, visitId)
                .orderByDesc(HisVitalSign::getMeasTime));
    }

    /** 血糖/血酮趋势: 取患者最近 N 条含血糖或血酮的体征, 按测量时间正序供前端画趋势图 */
    public List<HisVitalSign> trend(Long patientId, int limit) {
        List<HisVitalSign> list = vitalMapper.selectList(Wrappers.<HisVitalSign>lambdaQuery()
                .eq(HisVitalSign::getPatientId, patientId)
                .and(w -> w.isNotNull(HisVitalSign::getBloodGlucose).or().isNotNull(HisVitalSign::getBloodKetone))
                .orderByDesc(HisVitalSign::getMeasTime)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200))));
        java.util.Collections.reverse(list);
        return list;
    }

    /**
     * 保存体征; 若携带体温且达发热阈值, 自动为本次就诊登记发热病人(同就诊仅一条有效)。
     * @return 是否触发发热登记
     */
    public boolean saveVital(HisVitalSign vital) {
        if (vital.getMeasTime() == null) {
            vital.setMeasTime(LocalDateTime.now());
        }
        if (!StringUtils.hasText(vital.getSource())) {
            vital.setSource("手工");
        }
        LoginUser user = UserContext.get();
        if (user != null) {
            vital.setRecorderId(user.getStaffId());
        }
        vitalMapper.insert(vital);
        boolean fever = false;
        if (vital.getVisitId() != null && vital.getTemperature() != null
                && vital.getTemperature().compareTo(FEVER_THRESHOLD) >= 0) {
            fever = autoRegisterFever(vital.getVisitId(), vital.getPatientId(), vital.getTemperature());
        }
        return fever;
    }

    /* ---------- 诊前预问诊 ---------- */

    public HisPreConsult getPreConsult(Long visitId) {
        return preConsultMapper.selectOne(Wrappers.<HisPreConsult>lambdaQuery()
                .eq(HisPreConsult::getVisitId, visitId)
                .orderByDesc(HisPreConsult::getId)
                .last("LIMIT 1"));
    }

    public HisPreConsult savePreConsult(Long visitId, String contentJson) {
        if (visitId == null) {
            throw new BizException(400, "就诊ID不能为空");
        }
        HisPreConsult existing = getPreConsult(visitId);
        LoginUser user = UserContext.get();
        if (existing == null) {
            HisPreConsult rec = new HisPreConsult();
            rec.setVisitId(visitId);
            rec.setContentJson(contentJson);
            if (user != null) {
                rec.setRecorder(user.getRealName());
                rec.setRecorderId(user.getUserId());
            }
            preConsultMapper.insert(rec);
            return rec;
        }
        existing.setContentJson(contentJson);
        preConsultMapper.updateById(existing);
        return existing;
    }

    /* ---------- 发热登记 ---------- */

    public List<HisFeverRegister> listFeverByVisit(Long visitId) {
        return feverMapper.selectList(Wrappers.<HisFeverRegister>lambdaQuery()
                .eq(HisFeverRegister::getVisitId, visitId)
                .orderByDesc(HisFeverRegister::getRegisterTime));
    }

    /** 自动登记发热病人: 同就诊已存在登记则跳过, 返回是否新建 */
    public boolean autoRegisterFever(Long visitId, Long patientId, BigDecimal temperature) {
        Long exists = feverMapper.selectCount(Wrappers.<HisFeverRegister>lambdaQuery()
                .eq(HisFeverRegister::getVisitId, visitId));
        if (exists != null && exists > 0) {
            return false;
        }
        HisFeverRegister reg = new HisFeverRegister();
        reg.setVisitId(visitId);
        reg.setPatientId(patientId);
        reg.setTemperature(temperature);
        reg.setRegisterTime(LocalDateTime.now());
        feverMapper.insert(reg);
        return true;
    }

    private Long currentUserId() {
        LoginUser user = UserContext.get();
        if (user == null || user.getUserId() == null) {
            throw new BizException(401, "未登录");
        }
        return user.getUserId();
    }
}
