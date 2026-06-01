package org.example.workflow.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.db.entity.ItemStateRecord;
import org.example.db.mapper.ItemStateRecordMapper;
import org.example.model.dto.response.CBQueryResponse;
import org.example.model.dto.response.ItemInfoResponse;
import org.example.model.cb.TrackerItem;
import org.example.service.CBSwaggerService;
import org.example.workflow.config.*;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
     * 检查 initialized 标记，若未初始化则扫描所有存量条目。
     */
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

        log.info("初始化项目: projectId={}, projectName={}", projectId, projectConfig.getProjectName());

        // 遍历 tracker-matching 配置
        if (projectConfig.getTrackerMatching() != null) {
            for (TrackerMatchingRule rule : projectConfig.getTrackerMatching()) {
                if (rule.getTrackerId() != null) {
                    InitResult trackerResult = initTracker(projectId, rule.getTrackerId(), rule.getWorkflow());
                    result.add(trackerResult);
                }
            }
        }

        // 遍历 trackers 差异配置
        if (projectConfig.getTrackers() != null) {
            for (ProjectConfig.TrackerConfig trackerConfig : projectConfig.getTrackers()) {
                InitResult trackerResult = initTracker(projectId, trackerConfig.getTrackerId(), trackerConfig.getWorkflow());
                result.add(trackerResult);
            }
        }

        return result;
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

        // 遍历需要定时通知的状态
        for (WorkflowTemplate.StateConfig stateConfig : workflow.getStates()) {
            // 判断是否启用定时通知
            if (!workflowConfigService.getScheduledNotify(stateConfig)) {
                continue;
            }

            // 查询该状态的存量条目
            List<TrackerItem> items = fetchItemsByTrackerAndState(trackerId, stateConfig.getName());
            log.info("查询tracker状态条目: trackerId={}, state={}, itemCount={}",
                    trackerId, stateConfig.getName(), items.size());

            // 批量处理条目
            for (TrackerItem item : items) {
                result.processed++;

                // 检查是否已存在记录
                ItemStateRecord existingRecord = itemStateRecordMapper.selectByItemId(item.getId());
                if (existingRecord != null) {
                    result.skipped++;
                    continue;
                }

                // 获取进入状态时间
                LocalDateTime enterStateTime = cbSwaggerService.getEnterStateTime(item.getId(), stateConfig.getName());

                // 写入记录
                ItemStateRecord record = new ItemStateRecord();
                record.setItemId(item.getId());
                record.setItemName(item.getName());
                record.setTrackerId(trackerId);
                record.setProjectId(projectId);
                record.setTargetState(stateConfig.getName());
                record.setEnterStateTime(enterStateTime);

                itemStateRecordMapper.insert(record);
                result.inserted++;
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

        // cbQL 查询：tracker.id = X AND status = 'Y'
        String queryString = String.format("tracker.id = %d AND status = '%s'", trackerId, targetState);

        while (true) {
            CBQueryResponse response = cbSwaggerService.query(page, pageSize, queryString);
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
    public InitResult 补录Project(Integer projectId) {
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