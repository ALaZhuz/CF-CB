package org.example.workflow.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.workflow.cache.OrgCacheService;
import org.example.workflow.config.ConfigMetaService;
import org.example.workflow.service.InitService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 定时通知管理控制器
 *
 * 提供初始化补录、组织缓存刷新、配置重载等管理接口。
 *
 * @author system
 * @since 1.0
 */
@RestController
@Slf4j
@RequestMapping("/admin")
@RequiredArgsConstructor
public class ScheduledNotifyController {

    private final InitService initService;
    private final OrgCacheService orgCacheService;
    private final ConfigMetaService configMetaService;

    /**
     * 按项目补录存量条目
     *
     * @param projectId 项目ID
     * @return 补录结果
     */
    @PostMapping("/补录/{projectId}")
    public Map<String, Object> 补录Project(@PathVariable Integer projectId) {
        log.info("收到项目补录请求: projectId={}", projectId);

        InitService.InitResult result = initService.补录Project(projectId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("projectId", projectId);
        response.put("processed", result.getProcessed());
        response.put("inserted", result.getInserted());
        response.put("skipped", result.getSkipped());
        response.put("message", String.format("补录完成，处理%d条，新增%d条，跳过%d条",
                result.getProcessed(), result.getInserted(), result.getSkipped()));

        return response;
    }

    /**
     * 手动触发全量初始化
     *
     * @return 初始化结果
     */
    @PostMapping("/init")
    public Map<String, Object> manualInit() {
        log.info("收到手动初始化请求");

        initService.manualInit();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "全量初始化已触发");

        return response;
    }

    /**
     * 手动刷新组织架构缓存
     *
     * @return 刷新结果
     */
    @PostMapping("/org-cache/refresh")
    public Map<String, Object> refreshOrgCache() {
        log.info("收到组织缓存刷新请求");

        orgCacheService.manualRefresh();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("cacheSize", orgCacheService.getCacheSize());
        response.put("message", "组织架构缓存已刷新");

        return response;
    }

    /**
     * 手动触发配置重载（更新加载时间记录）
     *
     * @return 重载结果
     */
    @PostMapping("/config/reload")
    public Map<String, Object> reloadConfig() {
        log.info("收到配置重载请求");

        // 更新加载时间（实际配置重载需要自定义实现）
        configMetaService.updateLastLoadedTime(LocalDateTime.now());

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "配置加载时间已更新（注：Spring @ConfigurationProperties 不支持热更新，需重启生效）");

        return response;
    }

    /**
     * 查询当前状态
     *
     * @return 状态信息
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> response = new HashMap<>();
        response.put("initialized", configMetaService.checkInitialized());
        response.put("orgCacheSize", orgCacheService.getCacheSize());
        return response;
    }
}