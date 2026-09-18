package com.yb.hi.service.outpatient;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yb.hi.common.DateUtil;
import com.yb.hi.entity.outpatient.HisPatient;
import com.yb.hi.framework.common.BizException;
import com.yb.hi.mapper.outpatient.HisPatientMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 患者档案服务
 */
@Service
public class HisPatientService extends ServiceImpl<HisPatientMapper, HisPatient> {

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    /** 分页查询患者(姓名/患者号/身份证/医保编号/电话 模糊检索) */
    public IPage<HisPatient> pageQuery(long page, long size, String keyword) {
        LambdaQueryChainWrapper<HisPatient> q = lambdaQuery();
        if (StringUtils.hasText(keyword)) {
            q.and(w -> w.like(HisPatient::getName, keyword)
                    .or().like(HisPatient::getPatientNo, keyword)
                    .or().like(HisPatient::getIdCard, keyword)
                    .or().like(HisPatient::getPsnNo, keyword)
                    .or().like(HisPatient::getPhone, keyword));
        }
        return q.orderByDesc(HisPatient::getId).page(new Page<>(page, size));
    }

    /** 按身份证号精确查询(读卡/建档查重) */
    public HisPatient getByIdCard(String idCard) {
        if (!StringUtils.hasText(idCard)) {
            return null;
        }
        return lambdaQuery().eq(HisPatient::getIdCard, idCard).last("limit 1").one();
    }

    /** 生成院内患者号 */
    public String generatePatientNo() {
        int s = SEQ.incrementAndGet() % 1000;
        return "P" + DateUtil.currentTimeCompact() + String.format("%03d", s);
    }

    /** 新建患者档案(自动生成患者号, 身份证查重) */
    public HisPatient createPatient(HisPatient p) {
        if (!StringUtils.hasText(p.getName())) {
            throw new BizException(400, "患者姓名不能为空");
        }
        if (StringUtils.hasText(p.getIdCard())) {
            HisPatient exist = getByIdCard(p.getIdCard());
            if (exist != null) {
                throw new BizException("该身份证已建档: " + exist.getPatientNo() + " " + exist.getName());
            }
        }
        if (!StringUtils.hasText(p.getPatientNo())) {
            p.setPatientNo(generatePatientNo());
        }
        if (p.getStatus() == null) {
            p.setStatus(1);
        }
        save(p);
        return p;
    }
}
