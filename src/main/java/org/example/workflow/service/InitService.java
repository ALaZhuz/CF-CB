package org.example.workflow.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.db.entity.ItemStateRecord;
import org.example.db.mapper.ItemStateRecordMapper;
import org.example.model.dto.response.CBQueryResponse;
import org.example.model.dto.response.CBTrackerInfoResponse;
import org.example.model.dto.response.ItemInfoResponse;
import org.example.model.cb.TrackerItem;
import org.example.service.CBSwaggerService;
import org.example.workflow.config.*;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 初始化补录服务类
 *
 * 实现全量初始化和按项目补录功能。
 * 用于服务首次启动时扫描存量条目并记录进入状态时间。
 *
 * @author system
 * @since 1.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InitService {

    private final ConfigMetaService configMetaService;
    private final WorkflowConfigService workflowConfigService;
    private final CBSwaggerService cbSwaggerService;
    private final ItemStateRecordMapper itemStateRecordMapper;

    /**
     * 执行全量初始化
     *
     * 服务启动时自动执行，检查 initialized 标记，若未初始化则扫描所有存量条目。
     */
    @PostConstruct
    public void runInitialization() {
        if (configMetaService.checkInitialized()) {
            log.info("系统已初始化，跳过全量初始化");
            return;
        }

        log.info("开始执行全量初始化...");
        long startTime = System.currentTimeMillis();

        try {
            // 遍历所有项目配置
            List<ProjectConfig> projects = workflowConfigService.getWorkflowProperties().getProjects();
            int totalProcessed = 0;
            int totalInserted = 0;
            int totalSkipped = 0;

            for (ProjectConfig projectConfig : projects) {
                InitResult result = initProject(projectConfig);
                totalProcessed += result.getProcessed();
                totalInserted += result.getInserted();
                totalSkipped += result.getSkipped();
            }

            // 标记初始化完成
            configMetaService.markInitialized();

            long duration = System.currentTimeMillis() - startTime;
            log.info("全量初始化完成, 处理={}, 新增={}, 跳过={}, 耗时={}ms",
                    totalProcessed, totalInserted, totalSkipped, duration);

        } catch (Exception e) {
            log.error("全量初始化失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 初始化单个项目
     *
     * @param projectConfig 项目配置
     * @return 初始化结果统计
     */
    private InitResult initProject(ProjectConfig projectConfig) {
        InitResult result = new InitResult();
        Integer projectId = projectConfig.getProjectId();

        // 收集需要初始化的 tracker 及其 workflow
        // 使用 Map 遍免重复处理同一个 tracker
        Map<Integer, String> trackerWorkflowMap = new HashMap<>();

        // 1. 先从 tracker-matching 收集（基础配置）
        if (projectConfig.getTrackerMatching() != null) {
            for (TrackerMatchingRule rule : projectConfig.getTrackerMatching()) {
                if (rule.getWorkflow() == null) {
                    continue;
                }

                // tracker-id 精确匹配
                if (rule.getTrackerId() != null) {
                    trackerWorkflowMap.put(rule.getTrackerId(), rule.getWorkflow());
                }
                // tracker-type 类型匹配：需要查询项目下该类型的所有 tracker
                else if (rule.getTrackerType() != null && !rule.getTrackerType().isEmpty()) {
                    Map<Integer, String> typeMatchedTrackers = findTrackersByType(projectId, rule.getTrackerType());
                    for (Map.Entry<Integer, String> entry : typeMatchedTrackers.entrySet()) {
                        // 只在未配置时添加（tracker-id 配置优先级更高）
                        if (!trackerWorkflowMap.containsKey(entry.getKey())) {
                            trackerWorkflowMap.put(entry.getKey(), rule.getWorkflow());
                        }
                    }
                }
            }
        }

        // 2. 再从 trackers 收集（覆盖配置）
        if (projectConfig.getTrackers() != null) {
            for (ProjectConfig.TrackerConfig trackerConfig : projectConfig.getTrackers()) {
                Integer trackerId = trackerConfig.getTrackerId();
                if (trackerId != null) {
                    // 如果 trackers 中配置了 workflow，则覆盖 tracker-matching 的配置
                    if (trackerConfig.getWorkflow() != null) {
                        trackerWorkflowMap.put(trackerId, trackerConfig.getWorkflow());
                    }
                    // 如果没有配置 workflow，保留 tracker-matching 的配置（继承）
                }
            }
        }

        // 3. 统一初始化所有 tracker（不重复）
        for (Map.Entry<Integer, String> entry : trackerWorkflowMap.entrySet()) {
            InitResult trackerResult = initTracker(projectId, entry.getKey(), entry.getValue());
            result.add(trackerResult);
        }

        // 合并日志：项目初始化完成后输出一条汇总日志
        log.info("[初始化] 项目完成: projectId={}, trackerCount={}, 处理={}, 新增={}, 跳过={}",
                projectId, trackerWorkflowMap.size(), result.getProcessed(), result.getInserted(), result.getSkipped());

        return result;
    }

    /**
     * 根据项目ID和tracker类型查找所有tracker
     *
     * cbQL 不支持 tracker.type 语法，因此采用以下策略：
     * 1. 查询项目下所有条目，提取唯一的 trackerId 列表
     * 2. 对每个 trackerId 调用 getProjectInfo API 获取 trackerType
     * 3. 匹配 trackerType 与配置中的类型
     *
     * @param projectId 项目ID
     * @param trackerType tracker类型名称（如 Bug、Requirement）
     * @return trackerId -> trackerType 的映射
     */
    private Map<Integer, String> findTrackersByType(Integer projectId, String trackerType) {
        Map<Integer, String> result = new HashMap<>();

        // 1. 先查询项目下所有条目，获取唯一的 trackerId 列表
        Set<Integer> trackerIds = new HashSet<>();
        String queryString = String.format("project.id = %d", projectId);
        int pageSize = 500;
        int page = 1;

        while (true) {
            CBQueryResponse response = cbSwaggerService.query(page, pageSize, queryString);
            if (response == null || response.getItems() == null || response.getItems().isEmpty()) {
                break;
            }

            // 提取 trackerId
            for (TrackerItem item : response.getItems()) {
                if (item.getTracker() != null && item.getTracker().getId() != null) {
                    trackerIds.add(item.getTracker().getId());
                }
            }

            if (response.getItems().size() < pageSize) {
                break;
            }

            page++;
        }

        log.debug("项目 {} 下找到 {} 个tracker", projectId, trackerIds.size());

        // 2. 对每个 trackerId 调用 API 获取类型，匹配配置的类型
        for (Integer trackerId : trackerIds) {
            try {
                CBTrackerInfoResponse trackerInfo = getTrackerInfoWithRetry(trackerId);
                if (trackerInfo != null && trackerInfo.getType() != null) {
                    String actualType = trackerInfo.getType().getName();
                    // 匹配类型（忽略大小写）
                    if (actualType != null && actualType.equalsIgnoreCase(trackerType)) {
                        result.put(trackerId, trackerType);
                        log.debug("tracker类型匹配成功: trackerId={}, type={}", trackerId, actualType);
                    }
                }

            } catch (InterruptedException e) {
                log.warn("延迟等待被中断");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("获取tracker信息失败: trackerId={}, error={}", trackerId, e.getMessage());
            }
        }

        log.debug("查找tracker类型匹配: projectId={}, trackerType={}, 找到{}个tracker",
                projectId, trackerType, result.size());

        return result;
    }

    /**
     * 获取 tracker 信息（带重试逻辑）
     *
     * 遇到 429 限流时会等待 retryAfterSeconds 后重试，最多重试 3 次。
     *
     * @param trackerId tracker ID
     * @return tracker 信息
     */
    private CBTrackerInfoResponse getTrackerInfoWithRetry(Integer trackerId) throws InterruptedException {
        int maxRetries = 3;
        int baseDelayMs = 1500; // 基础延迟 1.5 秒

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                CBTrackerInfoResponse trackerInfo = cbSwaggerService.getProjectInfo(trackerId);
                // 成功后添加延迟避免下一次请求被限流
                Thread.sleep(baseDelayMs);
                return trackerInfo;

            } catch (org.springframework.web.client.HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429) {
                    // 429 限流，等待后重试
                    int retryAfterSeconds = extractRetryAfterSeconds(e.getMessage());
                    log.warn("API限流, trackerId={}, 等待{}秒后重试(第{}次)", trackerId, retryAfterSeconds, attempt + 1);
                    Thread.sleep(retryAfterSeconds * 1000L);
                } else {
                    // 其他错误直接抛出
                    throw e;
                }
            }
        }

        log.warn("获取tracker信息失败(重试{}次后): trackerId={}", maxRetries, trackerId);
        return null;
    }

    /**
     * 从错误消息中提取 retryAfterSeconds
     *
     * @param errorMessage 错误消息
     * @return 重试等待秒数，默认返回 2
     */
    private int extractRetryAfterSeconds(String errorMessage) {
        try {
            // 解析 {"message":"Too many requests","resourceUri":"...","retryAfterSecond":1}
            if (errorMessage != null && errorMessage.contains("retryAfterSecond")) {
                String pattern = "retryAfterSecond\":";
                int start = errorMessage.indexOf(pattern);
                if (start > 0) {
                    start += pattern.length();
                    int end = errorMessage.indexOf("}", start);
                    if (end > start) {
                        return Integer.parseInt(errorMessage.substring(start, end).trim());
                    }
                }
            }
        } catch (NumberFormatException e) {
            log.debug("解析retryAfterSeconds失败: {}", errorMessage);
        }
        return 2; // 默认等待 2 秒
    }

    /**
     * 初始化单个tracker
     *
     * @param projectId 项目ID
     * @param trackerId tracker ID
     * @param workflowName 工作流名称
     * @return 初始化结果统计
     */
    private InitResult initTracker(Integer projectId, Integer trackerId, String workflowName) {
        InitResult result = new InitResult();

        // 获取工作流配置
        WorkflowTemplate workflow = workflowConfigService.findWorkflowByName(workflowName,
                workflowConfigService.findProjectConfig(projectId));
        if (workflow == null) {
            log.warn("工作流配置不存在, trackerId={}, workflow={}", trackerId, workflowName);
            return result;
        }

        // 在开始时获取 trackerType（同一个 tracker 的所有条目类型相同）
        String trackerType = null;
        try {
            CBTrackerInfoResponse trackerInfo = getTrackerInfoWithRetry(trackerId);
            if (trackerInfo != null && trackerInfo.getType() != null) {
                trackerType = trackerInfo.getType().getName();
            }
        } catch (Exception e) {
            log.warn("获取tracker类型失败: trackerId={}, error={}", trackerId, e.getMessage());
        }

        // 遍历需要定时通知的状态
        for (WorkflowTemplate.StateConfig stateConfig : workflow.getStates()) {
            // 判断是否启用定时通知
            if (!workflowConfigService.getScheduledNotify(stateConfig)) {
                continue;
            }

            try {
                // 查询该状态的存量条目
                List<TrackerItem> items = fetchItemsByTrackerAndState(trackerId, stateConfig.getName());
                // 降低日志级别
                log.debug("查询tracker状态条目: trackerId={}, state={}, itemCount={}",
                        trackerId, stateConfig.getName(), items.size());

                // 每次状态查询后添加延迟，避免 query API 限流
                Thread.sleep(1500);

                // 批量处理条目
                for (TrackerItem item : items) {
                    result.processed++;

                    // 检查是否已存在记录
                    ItemStateRecord existingRecord = itemStateRecordMapper.selectByItemId(item.getId());
                    if (existingRecord != null) {
                        result.skipped++;
                        continue;
                    }

                    try {
                        // 获取进入状态时间
                        LocalDateTime enterStateTime = cbSwaggerService.getEnterStateTime(item.getId(), stateConfig.getName());

                        // 写入记录（包含 trackerType）
                        ItemStateRecord record = new ItemStateRecord();
                        record.setItemId(item.getId());
                        record.setItemName(item.getName());
                        record.setTrackerId(trackerId);
                        record.setTrackerType(trackerType);  // 直接设置 trackerType
                        record.setProjectId(projectId);
                        record.setTargetState(stateConfig.getName());
                        record.setEnterStateTime(enterStateTime);

                        itemStateRecordMapper.insert(record);
                        result.inserted++;

                        // 添加延迟避免 history API 限流（每次请求后等待1.5秒）
                        Thread.sleep(1500);

                    } catch (InterruptedException e) {
                        log.warn("延迟等待被中断");
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        log.error("处理条目失败, itemId={}, error={}", item.getId(), e.getMessage());
                        result.skipped++;
                    }
                }
            } catch (InterruptedException e) {
                log.warn("状态查询延迟被中断");
                Thread.currentThread().interrupt();
            }
        }

        return result;
    }

    /**
     * 查询tracker下指定状态的存量条目
     *
     * @param trackerId tracker ID
     * @param targetState 目标状态
     * @return 条目列表
     */
    private List<TrackerItem> fetchItemsByTrackerAndState(Integer trackerId, String targetState) {
        List<TrackerItem> allItems = new ArrayList<>();
        int pageSize = 500;
        int page = 1;
        int baseDelayMs = 1500; // 基础延迟 1.5 秒，避免速率限制

        // cbQL 查询：tracker.id = X AND status = 'Y'
        String queryString = String.format("tracker.id = %d AND status = '%s'", trackerId, targetState);

        while (true) {
            int retryCount = 0;
            int maxRetry = 5;
            CBQueryResponse response = null;

            // 重试逻辑：处理 429 速率限制
            while (retryCount < maxRetry) {
                try {
                    response = cbSwaggerService.query(page, pageSize, queryString);
                    // 成功后添加延迟避免下一次请求被限流
                    Thread.sleep(baseDelayMs);
                    break;
                } catch (InterruptedException e) {
                    log.warn("延迟等待被中断");
                    Thread.currentThread().interrupt();
                    break;
                } catch (org.springframework.web.client.HttpClientErrorException e) {
                    if (e.getStatusCode().value() == 429) {
                        // 解析 retryAfterSeconds
                        int retryAfterSeconds = extractRetryAfterSeconds(e.getMessage());
                        retryCount++;
                        log.warn("API限流(tracker查询), trackerId={}, state={}, 等待{}秒后重试(第{}次)",
                                trackerId, targetState, retryAfterSeconds, retryCount);
                        try {
                            Thread.sleep(retryAfterSeconds * 1000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        // 重试成功后增加基础延迟
                        baseDelayMs = Math.min(baseDelayMs + 500, 3000);
                    } else {
                        log.error("查询失败: trackerId={}, state={}, error={}", trackerId, targetState, e.getMessage());
                        break;
                    }
                } catch (Exception e) {
                    log.error("查询异常: trackerId={}, state={}, error={}", trackerId, targetState, e.getMessage());
                    break;
                }
            }

            if (response == null || response.getItems() == null || response.getItems().isEmpty()) {
                break;
            }

            allItems.addAll(response.getItems());

            if (allItems.size() >= response.getTotal()) {
                break;
            }

            page++;
        }

        return allItems;
    }

    /**
     * 按项目补录存量条目
     *
     * @param projectId 项目ID
     * @return 补录结果
     */
    public InitResult supplementProject(Integer projectId) {
        log.info("开始按项目补录: projectId={}", projectId);

        ProjectConfig projectConfig = workflowConfigService.findProjectConfig(projectId);
        if (projectConfig == null) {
            log.warn("项目配置不存在, projectId={}", projectId);
            return new InitResult();
        }

        InitResult result = initProject(projectConfig);

        log.info("项目补录完成, projectId={}, 处理={}, 新增={}, 跳过={}",
                projectId, result.getProcessed(), result.getInserted(), result.getSkipped());

        return result;
    }

    /**
     * 手动触发全量初始化（忽略initialized标记）
     */
    public void manualInit() {
        log.info("手动触发全量初始化...");
        configMetaService.resetInitialized();
        runInitialization();
    }

    /**
     * 初始化结果统计类
     */
    public static class InitResult {
        private int processed = 0;
        private int inserted = 0;
        private int skipped = 0;

        public int getProcessed() { return processed; }
        public int getInserted() { return inserted; }
        public int getSkipped() { return skipped; }

        public void add(InitResult other) {
            this.processed += other.processed;
            this.inserted += other.inserted;
            this.skipped += other.skipped;
        }
    }
}