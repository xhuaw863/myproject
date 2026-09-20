package com.yb.hi.stddict;

import com.yb.hi.config.StdDictProperties;
import com.yb.hi.service.StdDictImportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 标准字典启动导入器: 仅当 std-dict.auto-import-on-startup=true 时执行全量导入。
 * 默认关闭 —— 大表全量导入耗时较长, 建议通过 /api/std-dict/import/{type} 按需触发。
 */
@Slf4j
@Component
public class StdDictImportRunner implements CommandLineRunner {

    private final StdDictProperties props;
    private final StdDictImportService importService;

    public StdDictImportRunner(StdDictProperties props, StdDictImportService importService) {
        this.props = props;
        this.importService = importService;
    }

    @Override
    public void run(String... args) {
        if (!props.isEnabled() || !props.isAutoImportOnStartup()) {
            return;
        }
        log.info("启动自动导入标准字典(全量)...");
        Object result = importService.importAll();
        log.info("标准字典自动导入完成: {}", result);
    }
}
