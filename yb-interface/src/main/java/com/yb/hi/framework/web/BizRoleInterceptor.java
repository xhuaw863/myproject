package com.yb.hi.framework.web;

import com.alibaba.fastjson2.JSON;
import com.yb.hi.framework.common.R;
import com.yb.hi.framework.common.Roles;
import com.yb.hi.framework.tenant.LoginUser;
import com.yb.hi.framework.tenant.UserContext;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务端点角色网关(B5): 对写操作(POST/PUT/DELETE)按 URL 前缀做模块级角色校验。
 * - 读操作(GET/HEAD/OPTIONS)不拦截: 数据隔离由租户/机构/科室维度在数据层保证(见各 Service);
 * - 管理角色(ADMIN/ORG_ADMIN/SUPER_ADMIN)直通(与药房 requirePriceWrite 等既有口径一致);
 * - 多角色用户(R1 改造)按并集判权, 任一匹配即放行;
 * - 未映射前缀的写操作不拦截(平台管理/字典等已有各自守卫)。
 */
@Component
public class BizRoleInterceptor implements HandlerInterceptor {

    /** 前缀 -> 允许角色(写操作); 顺序即匹配优先级 */
    private static final Map<String, String[]> WRITE_ROLES = new LinkedHashMap<>();

    static {
        // 收费工作站
        WRITE_ROLES.put("/api/his/cashier/", new String[]{Roles.CASHIER});
        // 药房工作站(发药/退药/改派/定价) + 药库(出入库/调拨/价格调整/追溯)
        WRITE_ROLES.put("/api/his/pharmacy/", new String[]{Roles.PHARMACIST});
        WRITE_ROLES.put("/api/his/stock/", new String[]{Roles.PHARMACIST});
        WRITE_ROLES.put("/api/his/price-adjust/", new String[]{Roles.PHARMACIST});
        WRITE_ROLES.put("/api/his/trace/", new String[]{Roles.PHARMACIST});
        WRITE_ROLES.put("/api/his/transfer/", new String[]{Roles.PHARMACIST});
        // 挂号站(挂号/退号/换号) + 患者建档(挂号员/医生)
        WRITE_ROLES.put("/api/his/registration/", new String[]{Roles.REGISTRAR});
        WRITE_ROLES.put("/api/his/patient/", new String[]{Roles.REGISTRAR, Roles.DOCTOR});
        // 医生站(接诊/完成接诊/开单/开方/病历模板/会诊/证明)
        WRITE_ROLES.put("/api/his/visit/", new String[]{Roles.DOCTOR});
        WRITE_ROLES.put("/api/his/order/", new String[]{Roles.DOCTOR});
        WRITE_ROLES.put("/api/his/prescription/", new String[]{Roles.DOCTOR});
        WRITE_ROLES.put("/api/his/template/", new String[]{Roles.DOCTOR});
        WRITE_ROLES.put("/api/his/consult/", new String[]{Roles.DOCTOR});
        WRITE_ROLES.put("/api/his/medical-cert/", new String[]{Roles.DOCTOR});
        WRITE_ROLES.put("/api/his/admission-cert/", new String[]{Roles.DOCTOR});
        // 护士站 / 治疗 / 医技
        WRITE_ROLES.put("/api/nurse/", new String[]{Roles.NURSE});
        WRITE_ROLES.put("/api/treatment/", new String[]{Roles.THERAPIST});
        WRITE_ROLES.put("/api/medtech/", new String[]{Roles.TECHNICIAN});
        // 医保对账台(3201/3202 触发与差异处置; ADMIN/ORG_ADMIN 全局放行)
        WRITE_ROLES.put("/api/yb/recon/", new String[]{Roles.CASHIER});
        // 医保目录对照上报管理端(3301/3302 队列触发与重传; 对照管理为管理端职责, 仅管理员)
        WRITE_ROLES.put("/api/yb/catalog-upload/", new String[]{Roles.ADMIN});
        // 医保上报中心(M5: 上传状态手动重传; 管理端职责, 仅管理员)
        WRITE_ROLES.put("/api/yb/upload-status/", new String[]{Roles.ADMIN});
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        LoginUser lu = UserContext.get();
        if (lu == null) {
            // 鉴权拦截器兜底(未登录在前序拦截器已拒, 此处防御)
            return true;
        }
        if (lu.hasAnyRole(Roles.ADMIN, Roles.ORG_ADMIN, Roles.SUPER_ADMIN)) {
            return true;
        }
        String uri = request.getRequestURI();
        for (Map.Entry<String, String[]> e : WRITE_ROLES.entrySet()) {
            if (uri.startsWith(e.getKey())) {
                if (lu.hasAnyRole(e.getValue())) {
                    return true;
                }
                writeForbidden(response, lu);
                return false;
            }
        }
        return true;
    }

    private void writeForbidden(HttpServletResponse response, LoginUser lu) throws Exception {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JSON.toJSONString(R.fail(403,
                "当前账号角色(" + (lu.getRoles() == null ? "" : String.join("/", lu.getRoles()))
                        + ")无该模块操作权限, 请联系管理员授权")));
    }
}
