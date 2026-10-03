package com.yb.hi.framework.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Web MVC 配置: 注册鉴权拦截器 + 上传文件静态资源映射
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final BizRoleInterceptor bizRoleInterceptor;
    private final ExportGuardInterceptor exportGuardInterceptor;

    @Value("${his.upload.path:./data/upload/}")
    private String uploadPath;

    @Value("${his.upload.url-prefix:/uploads/}")
    private String urlPrefix;

    public WebMvcConfig(AuthInterceptor authInterceptor, BizRoleInterceptor bizRoleInterceptor,
                        ExportGuardInterceptor exportGuardInterceptor) {
        this.authInterceptor = authInterceptor;
        this.bizRoleInterceptor = bizRoleInterceptor;
        this.exportGuardInterceptor = exportGuardInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login"
                );
        // 业务模块写操作角色网关(读不拦截, 数据隔离在数据层; 见 BizRoleInterceptor)
        registry.addInterceptor(bizRoleInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login"
                );
        // 导出防护闸: URL 含 export 的端点限流+并发(须在鉴权后取 UserContext; 见 ExportGuardInterceptor)
        registry.addInterceptor(exportGuardInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login"
                );
    }

    /**
     * 上传文件(头像/签名图片)静态访问映射: {url-prefix}/** -> 磁盘 upload.path。
     * 不经鉴权拦截器(仅 /api/** 被拦截), 以便 <img src> 直接展示。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path root = Paths.get(uploadPath).toAbsolutePath().normalize();
        String location = root.toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        String pattern = (urlPrefix.endsWith("/") ? urlPrefix : urlPrefix + "/") + "**";
        registry.addResourceHandler(pattern).addResourceLocations(location);
    }
}
