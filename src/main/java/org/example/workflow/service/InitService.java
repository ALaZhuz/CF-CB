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
     * 按项目全量同步
     *
     * 实现补录 + 清理 + 状态更新：
     * 1. 查询Codebeamer项目下所有配置了定时通知状态的条目
     * 2. 查询本地数据库项目下所有记录
     * 3. 对比处理：
     *    - 条目已删除 → DELETE
     *    - 状态不一致（新状态配置了定时通知）→ UPDATE
     *    - 状态不一致（新状态没配置定时通知）→ DELETE
     *    - 状态一致 → 跳过
     * 4. 补录缺失条目
     *
     * @param projectId 项目ID
     * @return 同步结果
     */
    public InitResult supplementProject(Integer projectId) {
        log.info("开始项目全量同步: projectId={}", projectId);

        ProjectConfig projectConfig = workflowConfigService.findProjectConfig(projectId);
        if (projectConfig == null) {
            log.warn("项目配置不存在, projectId={}", projectId);
            return new InitResult();
        }

        InitResult result = new InitResult();

        try {
            // Step 1: 查询Codebeamer项目下所有配置了定时通知状态的条目
            Map<Integer, CbItemData> cbItems = collectCbItemsForProject(projectConfig);
            Set<Integer> cbItemIds = cbItems.keySet();
            log.info("Codebeamer查询完成: projectId={}, 条目数={}", projectId, cbItemIds.size());

            // Step 2: 查询本地数据库项目下所有记录
            List<ItemStateRecord> localRecords = itemStateRecordMapper.selectByProjectId(projectId);
            log.info("本地记录查询完成: projectId={}, 记录数={}", projectId, localRecords.size());

            // Step 3: 对比处理本地记录
            for (ItemStateRecord record : localRecords) {
                result.processed++;
                Integer itemId = record.getItemId();

                // 3.1 条目已删除
                if (!cbItemIds.contains(itemId)) {
                    itemStateRecordMapper.deleteByItemId(itemId);
                    result.deleted++;
                    log.info("清理已删除条目: itemId={}, trackerId={}, state={}",
                            itemId, record.getTrackerId(), record.getTargetState());
                    continue;
                }

                // 3.2 状态检查
                CbItemData cbItem = cbItems.get(itemId);
                String actualStatus = cbItem.status;
                String localState = record.getTargetState();

                if (!actualStatus.equals(localState)) {
                    // 状态不一致，检查新状态是否配置了定时通知
                    WorkflowTemplate workflow = workflowConfigService.getWorkflowForTracker(
                            cbItem.trackerId, cbItem.trackerType, projectId);

                    if (workflow == null) {
                        // 无法获取工作流配置，跳过处理
                        result.skipped++;
                        log.warn("状态不一致但无法获取工作流配置，跳过: itemId={}, 本地={}, 实际={}",
                                itemId, localState, actualStatus);
                        continue;
                    }

                    WorkflowTemplate.StateConfig stateConfig =
                            workflowConfigService.getStateConfig(workflow, actualStatus);
                    boolean newStateHasScheduledNotify = workflowConfigService.getScheduledNotify(stateConfig);

                    if (newStateHasScheduledNotify) {
                        // 新状态配置了定时通知 → UPDATE
                        try {
                            LocalDateTime enterStateTime = cbSwaggerService.getEnterStateTime(itemId, actualStatus);
                            itemStateRecordMapper.updateState(itemId, actualStatus, enterStateTime);
                            result.updated++;
                            log.info("状态不一致，更新记录: itemId={}, {} → {}, enterTime={}",
                                    itemId, localState, actualStatus, enterStateTime);

                            // 延迟避免history API限流
                            Thread.sleep(1500);
                        } catch (InterruptedException e) {
                            log.warn("延迟等待被中断");
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            log.error("更新状态失败: itemId={}, error={}", itemId, e.getMessage());
                            result.skipped++;
                        }
                    } else {
                        // 新状态没配置定时通知 → DELETE
                        itemStateRecordMapper.deleteByItemId(itemId);
                        result.deleted++;
                        log.info("状态不一致且新状态无定时通知，删除记录: itemId={}, {} → {}",
                                itemId, localState, actualStatus);
                    }
                    continue;
                }

                // 3.3 状态一致 → 跳过
                result.skipped++;
            }

            // Step 4: 补录缺失条目（本地没有的记录）
            for (Map.Entry<Integer, CbItemData> entry : cbItems.entrySet()) {
                Integer itemId = entry.getKey();
                CbItemData cbItem = entry.getValue();

                // 检查本地是否已有记录
                ItemStateRecord existingRecord = itemStateRecordMapper.selectByItemId(itemId);
                if (existingRecord != null) {
                    continue;  // 已存在，跳过
                }

                try {
                    // 获取进入状态时间
                    LocalDateTime enterStateTime = cbSwaggerService.getEnterStateTime(itemId, cbItem.status);

                    // 写入记录
                    ItemStateRecord record = new ItemStateRecord();
                    record.setItemId(itemId);
                    record.setItemName(cbItem.itemName);
                    record.setTrackerId(cbItem.trackerId);
                    record.setTrackerType(cbItem.trackerType);
                    record.setProjectId(projectId);
                    record.setTargetState(cbItem.status);
                    record.setEnterStateTime(enterStateTime);

                    itemStateRecordMapper.insert(record);
                    result.inserted++;
                    log.info("补录缺失条目: itemId={}, trackerId={}, state={}",
                            itemId, cbItem.trackerId, cbItem.status);

                    // 延迟避免history API限流
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    log.warn("延迟等待被中断");
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("补录条目失败: itemId={}, error={}", itemId, e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("项目全量同步失败: projectId={}, error={}", projectId, e.getMessage(), e);
        }

        log.info("项目全量同步完成: projectId={}, processed={}, inserted={}, updated={}, deleted={}, skipped={}",
                projectId, result.getProcessed(), result.getInserted(),
                result.getUpdated(), result.getDeleted(), result.getSkipped());

        return result;
    }

    /**
     * 收集Codebeamer项目下所有配置了定时通知状态的条目
     *
     * @param projectConfig 项目配置
     * @return 条目ID -> 条目数据的映射
     */
    private Map<Integer, CbItemData> collectCbItemsForProject(ProjectConfig projectConfig) throws InterruptedException {
        Map<Integer, CbItemData> result = new HashMap<>();
        Integer projectId = projectConfig.getProjectId();

        // 收集需要处理的 tracker 及其 workflow
        Map<Integer, String> trackerWorkflowMap = new HashMap<>();

        // 1. 从 tracker-matching 收集
        if (projectConfig.getTrackerMatching() != null) {
            for (TrackerMatchingRule rule : projectConfig.getTrackerMatching()) {
                if (rule.getWorkflow() == null) {
                    continue;
                }

                if (rule.getTrackerId() != null) {
                    trackerWorkflowMap.put(rule.getTrackerId(), rule.getWorkflow());
                } else if (rule.getTrackerType() != null && !rule.getTrackerType().isEmpty()) {
                    Map<Integer, String> typeMatchedTrackers = findTrackersByType(projectId, rule.getTrackerType());
                    for (Map.Entry<Integer, String> entry : typeMatchedTrackers.entrySet()) {
                        if (!trackerWorkflowMap.containsKey(entry.getKey())) {
                            trackerWorkflowMap.put(entry.getKey(), rule.getWorkflow());
                        }
                    }
                }
            }
        }

        // 2. 从 trackers 收集（覆盖配置）
        if (projectConfig.getTrackers() != null) {
            for (ProjectConfig.TrackerConfig trackerConfig : projectConfig.getTrackers()) {
                Integer trackerId = trackerConfig.getTrackerId();
                if (trackerId != null && trackerConfig.getWorkflow() != null) {
                    trackerWorkflowMap.put(trackerId, trackerConfig.getWorkflow());
                }
            }
        }

        // 3. 遍历每个tracker，查询配置了定时通知的状态下的条目
        for (Map.Entry<Integer, String> entry : trackerWorkflowMap.entrySet()) {
            Integer trackerId = entry.getKey();
            String workflowName = entry.getValue();

            WorkflowTemplate workflow = workflowConfigService.findWorkflowByName(workflowName,
                    workflowConfigService.findProjectConfig(projectId));

            if (workflow == null) {
                log.warn("工作流配置不存在: trackerId={}, workflow={}", trackerId, workflowName);
                continue;
            }

            // 获取 trackerType
            String trackerType = null;
            try {
                CBTrackerInfoResponse trackerInfo = getTrackerInfoWithRetry(trackerId);
                if (trackerInfo != null && trackerInfo.getType() != null) {
                    trackerType = trackerInfo.getType().getName();
                }
            } catch (Exception e) {
                log.warn("获取tracker类型失败: trackerId={}, error={}", trackerId, e.getMessage());
            }

            // 遍历配置了定时通知的状态
            for (WorkflowTemplate.StateConfig stateConfig : workflow.getStates()) {
                if (!workflowConfigService.getScheduledNotify(stateConfig)) {
                    continue;
                }

                String targetState = stateConfig.getName();
                List<TrackerItem> items = fetchItemsByTrackerAndState(trackerId, targetState);

                // 收集条目数据
                for (TrackerItem item : items) {
                    CbItemData data = new CbItemData();
                    data.itemId = item.getId();
                    data.itemName = item.getName();
                    data.trackerId = trackerId;
                    data.trackerType = trackerType;
                    data.status = targetState;

                    result.put(item.getId(), data);
                }

                // 延迟避免API限流
                Thread.sleep(1500);
            }
        }

        return result;
    }

    /**
     * Codebeamer条目数据类
     *
     * 用于存储从Codebeamer查询到的条目信息，供全量同步对比使用。
     */
    private static class CbItemData {
        Integer itemId;
        String itemName;
        Integer trackerId;
        String trackerType;
        String status;
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
     *
     * 用于全量同步的结果统计：
     * - processed: 处理的本地记录总数
     * - inserted: 新增的记录数（补录）
     * - updated: 更新的记录数（状态不一致）
     * - deleted: 删除的记录数（已删除条目或状态不一致且无定时通知）
     * - skipped: 跳过的记录数（状态一致）
     */
    public static class InitResult {
        private int processed = 0;
        private int inserted = 0;
        private int updated = 0;
        private int deleted = 0;
        private int skipped = 0;

        public int getProcessed() { return processed; }
        public int getInserted() { return inserted; }
        public int getUpdated() { return updated; }
        public int getDeleted() { return deleted; }
        public int getSkipped() { return skipped; }

        // Setter方法（供测试使用）
        public void setProcessed(int processed) { this.processed = processed; }
        public void setInserted(int inserted) { this.inserted = inserted; }
        public void setUpdated(int updated) { this.updated = updated; }
        public void setDeleted(int deleted) { this.deleted = deleted; }
        public void setSkipped(int skipped) { this.skipped = skipped; }

        public void add(InitResult other) {
            this.processed += other.processed;
            this.inserted += other.inserted;
            this.updated += other.updated;
            this.deleted += other.deleted;
            this.skipped += other.skipped;
        }
    }
}