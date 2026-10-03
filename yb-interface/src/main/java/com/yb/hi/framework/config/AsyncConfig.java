package com.yb.hi.framework.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 异步执行开启(@Async): 供病历模板母板传播等大批量后台任务使用。
 * 线程池复用 Spring Boot 默认 applicationTaskExecutor(ThreadPoolTaskExecutor, 由 TaskExecutionAutoConfiguration 装配)。
 * 注意: TenantContext/UserContext 为 ThreadLocal, 不随 @Async 跨线程传递, 异步方法内须手动捕获并恢复。
 * proxyTargetClass=true: 对齐 Spring Boot 全局 CGLIB 默认, 使实现接口(如 ApplicationRunner)的 @Async Bean
 *   仍以类代理, 保证按具体类型注入(EmrTemplateController -> EmrTemplateService)不因 JDK 动态代理失败。
 */
@Configuration
@EnableAsync(proxyTargetClass = true)
public class AsyncConfig {
}
