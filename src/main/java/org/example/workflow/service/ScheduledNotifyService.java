package org.example.workflow.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.db.entity.ItemStateRecord;
import org.example.db.entity.NotifyLog;
import org.example.db.mapper.ItemStateRecordMapper;
import org.example.db.mapper.NotifyLogMapper;
import org.example.model.dto.response.ItemInfoResponse;
import org.example.model.enums.MsgKeyConstant;
import org.example.service.CBSwaggerService;
import org.example.service.DingService;
import org.example.workflow.cache.OrgCacheService;
import org.example.workflow.cache.DingUserCacheService;
import org.example.workflow.config.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;

import javax.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 定时通知服务类
 *
 * 实现定时通知调度器核心逻辑：
 * 1. 每天指定时间扫描停留在通知状态的条目
 * 2. 计算停留天数，判断通知层级（成员/科长/部长）
 * 3. 跨条目聚合：科长/部长收到一条包含所有负责条目的消息
 * 4. 发送通知并更新记录
 *
 * 新增功能（2026-06-10）：
 * - 预清理任务：在定时通知前几小时执行数据同步，确保数据正确
 *
 * @author system
 * @since 1.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
@DependsOn({"orgCacheService", "dingUserCacheService"})
public class ScheduledNotifyService {

    private final ConfigMetaService configMetaService;
    private final WorkflowConfigService workflowConfigService;
    private final CBSwaggerService cbSwaggerService;
    private final DingService dingService;
    private final OrgCacheService orgCacheService;
    private final ItemStateRecordMapper itemStateRecordMapper;
    private final NotifyLogMapper notifyLogMapper;
    private final DingUserCacheService dingUserCacheService;
    private final InitService initService;

    @PostConstruct
    public void validateCacheReadiness() {
        if (orgCacheService.getCacheSize() == 0) {
            log.warn("警告: 组织架构缓存为空，可能影响通知发送");
        }
        if (dingUserCacheService.getCacheSize() == 0) {
            log.warn("警告: 钉钉用户缓存为空，可能影响用户验证");
        }

        // 【数据迁移】更新旧数据中缺少 trackerType 的记录
        updateMissingTrackerType();
    }

    /**
     * 更新旧数据中缺少 trackerType 的记录
     *
     * 启动时检查数据库中 trackerType 为空的记录，
     * 调用 API 获取 trackerType 并更新到数据库。
     */
    private void updateMissingTrackerType() {
        List<ItemStateRecord> allRecords = itemStateRecordMapper.selectAll();
        List<ItemStateRecord> missingTypeRecords = allRecords.stream()
                .filter(r -> r.getTrackerType() == null || r.getTrackerType().isEmpty())
                .collect(Collectors.toList());

        if (missingTypeRecords.isEmpty()) {
            log.info("所有记录已包含 trackerType，无需更新");
            return;
        }

        log.info("发现 {} 条记录缺少 trackerType，开始补充...", missingTypeRecords.size());

        int updatedCount = 0;
        int failedCount = 0;

        for (ItemStateRecord record : missingTypeRecords) {
            try {
                // 调用 API 获取完整条目信息
                ItemInfoResponse itemInfo = cbSwaggerService.getItemInfo(record.getItemId());
                if (itemInfo != null && itemInfo.getTrackerType() != null) {
                    // 更新数据库记录
                    record.setTrackerType(itemInfo.getTrackerType());
                    itemStateRecordMapper.insert(record);  // 使用 INSERT OR REPLACE 更新
                    updatedCount++;
                    log.debug("更新 trackerType: itemId={}, trackerType={}", record.getItemId(), itemInfo.getTrackerType());
                } else {
                    failedCount++;
                    log.warn("无法获取 trackerType: itemId={}", record.getItemId());
                }

                // 延迟避免 429 错误（启动时批量更新，需要更长延迟）
                Thread.sleep(1000);

            } catch (Exception e) {
                failedCount++;
                log.error("更新 trackerType 失败: itemId={}, error={}", record.getItemId(), e.getMessage());
            }
        }

        log.info("trackerType 补充完成: 成功={}, 失败={}", updatedCount, failedCount);
    }

    public boolean areCachesReady() {
        return orgCacheService.getCacheSize() > 0 && dingUserCacheService.getCacheSize() > 0;
    }

    /**
     * 预清理任务调度方法
     *
     * 在定时通知前几小时执行数据同步，确保：
     * 1. 清理已删除条目的残留记录
     * 2. 更新状态不一致的记录
     * 3. 补录缺失的条目记录
     *
     * 执行时间通过 default-cleanup-time 配置（如 "04:00"）
     */
    @Scheduled(cron = "0 0/1 * * * *")
    public void executeCleanup() {
        String cleanupTime = workflowConfigService.getCleanupTime();

        String[] timeParts = cleanupTime.split(":");
        if (timeParts.length != 2) {
            log.warn("配置的cleanupTime格式错误: {}, 使用默认4点", cleanupTime);
            timeParts = new String[]{"04", "00"};
        }

        int configuredHour = Integer.parseInt(timeParts[0]);
        int configuredMinute = Integer.parseInt(timeParts[1]);

        LocalDateTime now = LocalDateTime.now();
        int currentHour = now.getHour();
        int currentMinute = now.getMinute();

        if (!(currentHour == configuredHour && currentMinute == configuredMinute)) {
            log.debug("当前时间 {}:{} 不在配置的预清理时间 {}:{}，跳过执行",
                    currentHour, currentMinute, configuredHour, configuredMinute);
            return;
        }

        log.info("到达配置的预清理时间 {}:{}，开始执行数据同步", configuredHour, configuredMinute);
        log.info("========== 预清理任务启动 ==========");

        if (!areCachesReady()) {
            log.warn("缓存服务未就绪，跳过本次预清理");
            return;
        }

        long startTime = System.currentTimeMillis();

        try {
            checkAndReloadConfig();

            // 遍历所有配置的项目，执行数据同步
            List<ProjectConfig> projects = workflowConfigService.getWorkflowProperties().getProjects();
            int totalProcessed = 0;
            int totalInserted = 0;
            int totalUpdated = 0;
            int totalDeleted = 0;
            int totalSkipped = 0;

            for (ProjectConfig project : projects) {
                try {
                    InitService.InitResult result = initService.supplementProject(project.getProjectId());
                    totalProcessed += result.getProcessed();
                    totalInserted += result.getInserted();
                    totalUpdated += result.getUpdated();
                    totalDeleted += result.getDeleted();
                    totalSkipped += result.getSkipped();

                    // 项目之间添加延迟，避免API限流
                    Thread.sleep(2000);

                } catch (InterruptedException e) {
                    log.warn("延迟等待被中断");
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("项目数据同步失败: projectId={}, error={}", project.getProjectId(), e.getMessage());
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("========== 预清理任务完成 ========== 处理={}, 新增={}, 更新={}, 删除={}, 跳过={}, 耗时={}ms",
                    totalProcessed, totalInserted, totalUpdated, totalDeleted, totalSkipped, duration);

        } catch (Exception e) {
            log.error("预清理任务执行失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 定时通知主调度方法
     */
    @Scheduled(cron = "0 0/1 * * * *")
    public void executeScheduledNotify() {
        String notifyTime = workflowConfigService.getNotifyTime();

        String[] timeParts = notifyTime.split(":");
        if (timeParts.length != 2) {
            log.warn("配置的notifyTime格式错误: {}, 使用默认8点", notifyTime);
            timeParts = new String[]{"08", "00"};
        }

        int configuredHour = Integer.parseInt(timeParts[0]);
        int configuredMinute = Integer.parseInt(timeParts[1]);

        LocalDateTime now = LocalDateTime.now();
        int currentHour = now.getHour();
        int currentMinute = now.getMinute();

        if (!(currentHour == configuredHour && currentMinute == configuredMinute)) {
            log.debug("当前时间 {}:{} 不在配置的执行时间 {}:{}，跳过执行",
                    currentHour, currentMinute, configuredHour, configuredMinute);
            return;
        }

        log.info("到达配置的执行时间 {}:{}，开始执行定时通知", configuredHour, configuredMinute);
        log.info("========== 定时通知调度器启动 ==========");

        if (!areCachesReady()) {
            log.warn("缓存服务未就绪，跳过本次执行");
            return;
        }

        long startTime = System.currentTimeMillis();

        try {
            checkAndReloadConfig();

            List<ItemStateRecord> itemsToNotify = queryItemsToNotify();
            log.info("待处理条目数: {}", itemsToNotify.size());

            if (itemsToNotify.isEmpty()) {
                log.info("无待处理条目，定时通知完成");
                return;
            }

            // === 第一阶段：收集所有条目通知数据 ===
            List<ItemNotifyData> notifyDataList = collectNotifyData(itemsToNotify);
            log.info("有效条目数: {}", notifyDataList.size());

            if (notifyDataList.isEmpty()) {
                log.info("无有效条目需要通知，定时通知完成");
                return;
            }

            // 用于追踪发送成功的条目ID
            Set<Integer> successItemIds = new HashSet<>();

            int memberNotifyCount = 0;
            int managerNotifyCount = 0;
            int directorNotifyCount = 0;

            // === 第二阶段：发送成员通知（逐人） ===
            memberNotifyCount = sendAllMemberNotifications(notifyDataList, successItemIds);

            // === 第三阶段：按科长跨条目聚合发送 ===
            managerNotifyCount = sendAllManagerNotificationsAggregated(notifyDataList, successItemIds);

            // === 第四阶段：按部长跨条目聚合发送 ===
            directorNotifyCount = sendAllDirectorNotificationsAggregated(notifyDataList, successItemIds);

            // === 第五阶段：只更新发送成功的条目的 last_notify_time ===
            for (Integer itemId : successItemIds) {
                itemStateRecordMapper.updateLastNotifyTime(itemId, LocalDateTime.now());
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("========== 定时通知调度完成 ========== 成员通知={}, 科长通知={}, 部长通知={}, 耗时={}ms",
                    memberNotifyCount, managerNotifyCount, directorNotifyCount, duration);

        } catch (Exception e) {
            log.error("定时通知调度执行失败: {}", e.getMessage(), e);
        }
    }

    private void checkAndReloadConfig() {
        if (configMetaService.checkYamlModified()) {
            log.info("检测到YAML配置变更，触发热更新");
            configMetaService.reloadYamlConfig();
        }
    }

    private List<ItemStateRecord> queryItemsToNotify() {
        List<ItemStateRecord> allRecords = itemStateRecordMapper.selectAll();
        LocalDate today = LocalDate.now();

        return allRecords.stream()
                .filter(record -> {
                    if (record.getLastNotifyTime() != null) {
                        LocalDate lastNotifyDate = record.getLastNotifyTime().toLocalDate();
                        return !lastNotifyDate.equals(today);
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    /**
     * 收集所有条目的通知数据
     */
    private List<ItemNotifyData> collectNotifyData(List<ItemStateRecord> itemsToNotify) {
        List<ItemNotifyData> result = new ArrayList<>();

        // 收集分类字段未找到或值为空的所有 itemId（按 classifyField 分组）
        Map<String, List<Integer>> missingClassifyFieldItems = new HashMap<>();

        for (ItemStateRecord record : itemsToNotify) {
            int retryCount = 0;
            int maxRetry = 3;

            while (retryCount < maxRetry) {
                try {
                    ItemNotifyData data = buildNotifyData(record, missingClassifyFieldItems);
                    if (data != null) {
                        result.add(data);
                    }
                    // 成功后延迟避免API速率限制
                    Thread.sleep(500);
                    break;
                } catch (InterruptedException e) {
                    log.warn("延迟等待被中断");
                    break;
                } catch (Exception e) {
                    String errorMsg = e.getMessage();
                    if (errorMsg != null && errorMsg.contains("429")) {
                        retryCount++;
                        log.warn("遇到429速率限制，等待1秒后重试: itemId={}, retryCount={}", record.getItemId(), retryCount);
                        try {
                            Thread.sleep(1000); // 等待1秒后重试
                        } catch (InterruptedException ie) {
                            break;
                        }
                    } else {
                        log.error("收集条目数据失败: itemId={}, error={}", record.getItemId(), errorMsg);
                        break;
                    }
                }
            }
        }

        // 打印汇总日志：分类字段未找到或值为空的所有 itemId
        for (Map.Entry<String, List<Integer>> entry : missingClassifyFieldItems.entrySet()) {
            String classifyField = entry.getKey();
            List<Integer> itemIds = entry.getValue();
            if (!itemIds.isEmpty()) {
                log.warn("分类字段未找到或值为空: classifyField={}, affectedItemIds={}", classifyField, itemIds);
            }
        }

        return result;
    }

    /**
     * 构建单个条目的通知数据
     *
     * 直接使用 ItemStateRecord 中存储的 trackerType 和 projectId，避免调用 tracker API
     *
     * @param record 条目状态记录
     * @param missingClassifyFieldItems 收集分类字段未找到的 itemId（按 classifyField 分组）
     */
    private ItemNotifyData buildNotifyData(ItemStateRecord record, Map<String, List<Integer>> missingClassifyFieldItems) {
        Integer itemId = record.getItemId();
        Integer trackerId = record.getTrackerId();
        Integer projectId = record.getProjectId();
        String targetState = record.getTargetState();
        // 直接从记录中读取 trackerType，避免调用 tracker API
        String trackerType = record.getTrackerType();

        // 如果记录中没有 trackerType（旧数据），跳过该条目
        // 需要用户手动触发一次状态变化来更新记录
        if (trackerType == null || trackerType.isEmpty()) {
            log.warn("记录中缺少 trackerType，跳过（请触发状态变化来更新）: itemId={}", itemId);
            return null;
        }

        // 使用 getItemInfoBasic 避免调用 tracker API（减少 429 错误）
        ItemInfoResponse itemInfo = cbSwaggerService.getItemInfoBasic(itemId);
        if (itemInfo == null) {
            log.warn("条目不存在, itemId={}", itemId);
            return null;
        }

        WorkflowTemplate workflow = workflowConfigService.getWorkflowForTracker(
                trackerId, trackerType, projectId);
        if (workflow == null) {
            log.debug("未找到工作流配置, itemId={}, trackerId={}, trackerType={}", itemId, trackerId, trackerType);
            return null;
        }

        WorkflowTemplate.StateConfig stateConfig = workflowConfigService.getStateConfig(workflow, targetState);
        if (stateConfig == null || !workflowConfigService.getScheduledNotify(stateConfig)) {
            return null;
        }

        int stayDays = calculateStayDays(record.getEnterStateTime());

        ClassifyConfig classifyConfig = workflowConfigService.getClassifyConfig(trackerId, trackerType, projectId);
        if (classifyConfig == null) {
            log.debug("该tracker未配置分类通知, itemId={}, trackerType={}", itemId, trackerType);
            return null;
        }

        String classifyValue = getClassifyValue(itemInfo, classifyConfig.getClassifyField(), itemId, missingClassifyFieldItems);
        ClassifyRule classifyRule = workflowConfigService.matchClassifyRule(classifyValue, classifyConfig);
        if (classifyRule == null) {
            log.debug("未匹配分类规则, itemId={}, classifyValue={}", itemId, classifyValue);
            return null;
        }

        List<String> notifyFields = workflowConfigService.getScheduledNotifyFields(stateConfig);
        List<ItemInfoResponse.MemberInfo> members = getMembersByFields(itemInfo, notifyFields);
        if (members == null || members.isEmpty()) {
            log.debug("通知字段无成员, itemId={}, notifyFields={}", itemId, notifyFields);
            return null;
        }

        // 过滤有效成员
        List<ItemInfoResponse.MemberInfo> validMembers = members.stream()
                .filter(m -> m.getUserId() != null && !m.getUserId().isEmpty())
                .filter(m -> dingUserCacheService.isValidUserId(m.getUserId()))
                .collect(Collectors.toList());

        if (validMembers.isEmpty()) {
            log.debug("无有效成员, itemId={}", itemId);
            return null;
        }

        String trackerTypeDisplay = workflowConfigService.getTypeMapping(trackerType, projectId);

        ItemNotifyData data = new ItemNotifyData();
        data.itemId = itemId;
        data.itemInfo = itemInfo;
        data.stayDays = stayDays;
        data.trackerTypeDisplay = trackerTypeDisplay;
        data.members = validMembers;
        data.classifyRule = classifyRule;

        return data;
    }

    /**
     * 发送所有成员通知（逐人）
     *
     * @param notifyDataList 条目通知数据列表
     * @param successItemIds 发送成功的条目ID集合（用于追踪）
     */
    private int sendAllMemberNotifications(List<ItemNotifyData> notifyDataList, Set<Integer> successItemIds) {
        int count = 0;

        for (ItemNotifyData data : notifyDataList) {
            if (!shouldSendMemberNotification(data.stayDays, data.classifyRule)) {
                continue;
            }

            boolean anySuccess = false;
            for (ItemInfoResponse.MemberInfo member : data.members) {
                String userid = member.getUserId();
                String message = formatMemberMessage(data.itemInfo, data.trackerTypeDisplay, data.stayDays, member);

                try {
                    // 使用 markdown 消息类型，支持链接点击
                    dingService.sendRobotMessage(userid, "定时通知提醒", message, MsgKeyConstant.SAMPLE_MARKDOWN);
                    log.info("成员通知发送成功: itemId={}, userid={}, 消息内容=\n{}", data.itemId, userid, message);
                    saveNotifyLog(data.itemId, userid, "定时成员", "成功");
                    count++;
                    anySuccess = true;
                } catch (Exception e) {
                    log.error("成员通知发送失败: itemId={}, userid={}, error={}", data.itemId, userid, e.getMessage());
                    saveNotifyLog(data.itemId, userid, "定时成员", "失败: " + e.getMessage());
                }
            }

            // 只要有一个成员通知成功，就记录该条目为成功
            if (anySuccess) {
                successItemIds.add(data.itemId);
            }
        }

        return count;
    }

    /**
     * 按科长跨条目聚合发送通知
     *
     * @param notifyDataList 条目通知数据列表
     * @param successItemIds 发送成功的条目ID集合（用于追踪）
     */
    private int sendAllManagerNotificationsAggregated(List<ItemNotifyData> notifyDataList, Set<Integer> successItemIds) {
        // 按 科长 -> 条目列表 聚合（使用 Set 去重）
        Map<String, Set<ItemNotifyData>> managerGroup = new HashMap<>();

        for (ItemNotifyData data : notifyDataList) {
            if (!shouldSendManagerNotification(data.stayDays, data.classifyRule)) {
                continue;
            }

            for (ItemInfoResponse.MemberInfo member : data.members) {
                String userid = member.getUserId();
                String managerId = orgCacheService.getManager(userid);
                if (managerId != null && dingUserCacheService.isValidUserId(managerId)) {
                    // 使用 computeIfAbsent 创建 Set，并添加条目（自动去重）
                    managerGroup.computeIfAbsent(managerId, k -> new LinkedHashSet<>()).add(data);
                }
            }
        }

        int count = 0;

        // 逐科长发送聚合消息
        for (Map.Entry<String, Set<ItemNotifyData>> entry : managerGroup.entrySet()) {
            String managerId = entry.getKey();
            List<ItemNotifyData> items = new ArrayList<>(entry.getValue());  // Set 转 List

            String message = formatCrossItemAggregatedMessage(items, "科长");
            try {
                // 使用 markdown 消息类型，支持链接点击
                dingService.sendRobotMessage(managerId, "定时通知提醒", message, MsgKeyConstant.SAMPLE_MARKDOWN);
                log.info("科长聚合通知发送成功: managerId={}, 条目数={}, 消息内容=\n{}", managerId, items.size(), message);
                for (ItemNotifyData data : items) {
                    saveNotifyLog(data.itemId, managerId, "定时科长", "成功");
                    successItemIds.add(data.itemId);  // 记录成功的条目
                }
                count++;
            } catch (Exception e) {
                log.error("科长聚合通知发送失败: managerId={}, error={}", managerId, e.getMessage());
                for (ItemNotifyData data : items) {
                    saveNotifyLog(data.itemId, managerId, "定时科长", "失败: " + e.getMessage());
                }
            }
        }

        return count;
    }

    /**
     * 按部长跨条目聚合发送通知
     *
     * @param notifyDataList 条目通知数据列表
     * @param successItemIds 发送成功的条目ID集合（用于追踪）
     */
    private int sendAllDirectorNotificationsAggregated(List<ItemNotifyData> notifyDataList, Set<Integer> successItemIds) {
        // 按 部长 -> 条目列表 聚合（使用 Set 去重）
        Map<String, Set<ItemNotifyData>> directorGroup = new HashMap<>();

        for (ItemNotifyData data : notifyDataList) {
            if (!shouldSendDirectorNotification(data.stayDays, data.classifyRule)) {
                continue;
            }

            for (ItemInfoResponse.MemberInfo member : data.members) {
                String userid = member.getUserId();
                String directorId = orgCacheService.getDirector(userid);
                if (directorId != null && dingUserCacheService.isValidUserId(directorId)) {
                    // 使用 computeIfAbsent 创建 Set，并添加条目（自动去重）
                    directorGroup.computeIfAbsent(directorId, k -> new LinkedHashSet<>()).add(data);
                }
            }
        }

        int count = 0;

        // 逐部长发送聚合消息
        for (Map.Entry<String, Set<ItemNotifyData>> entry : directorGroup.entrySet()) {
            String directorId = entry.getKey();
            List<ItemNotifyData> items = new ArrayList<>(entry.getValue());  // Set 转 List

            String message = formatCrossItemAggregatedMessage(items, "部长");
            try {
                // 使用 markdown 消息类型，支持链接点击
                dingService.sendRobotMessage(directorId, "定时通知提醒", message, MsgKeyConstant.SAMPLE_MARKDOWN);
                log.info("部长聚合通知发送成功: directorId={}, 条目数={}, 消息内容=\n{}", directorId, items.size(), message);
                for (ItemNotifyData data : items) {
                    saveNotifyLog(data.itemId, directorId, "定时部长", "成功");
                    successItemIds.add(data.itemId);  // 记录成功的条目
                }
                count++;
            } catch (Exception e) {
                log.error("部长聚合通知发送失败: directorId={}, error={}", directorId, e.getMessage());
                for (ItemNotifyData data : items) {
                    saveNotifyLog(data.itemId, directorId, "定时部长", "失败: " + e.getMessage());
                }
            }
        }

        return count;
    }

    /**
     * 格式化跨条目聚合消息
     *
     * 使用 trackerTypeDisplay（如"问题"、"需求"）作为条目前缀
     * 条目名称支持点击跳转链接
     */
    private String formatCrossItemAggregatedMessage(List<ItemNotifyData> items, String notifyLevel) {
        StringBuilder sb = new StringBuilder();
        // 使用第一个条目的 trackerTypeDisplay 作为消息开头
        String typeDisplay = items.isEmpty() ? "问题" : items.get(0).trackerTypeDisplay;
        sb.append("您好，以下").append(typeDisplay).append("未及时处理，请知悉！\n\n");

        for (int i = 0; i < items.size(); i++) {
            ItemNotifyData data = items.get(i);
            // 调用钉钉API获取用户真实姓名
            String memberNames = data.members.stream()
                    .map(m -> getRealName(m))
                    .collect(Collectors.joining(","));

            // 构建条目链接（markdown格式：[名称](链接)）
            String itemName = data.itemInfo.getName();
            String itemLink = data.itemInfo.getItemLink();
            String itemNameWithLink = itemLink != null && !itemLink.isEmpty()
                    ? "[" + itemName + "](" + itemLink + ")"
                    : itemName;

            // 使用 trackerTypeDisplay 作为前缀，如 "问题【条目名称（可点击）】"
            sb.append(i + 1).append(". ")
              .append(data.trackerTypeDisplay).append("【").append(itemNameWithLink).append("】")
              .append("，在【").append(data.itemInfo.getStatus()).append("】状态下已【").append(data.stayDays).append("】天")
              .append("，负责人【").append(memberNames).append("】\n");
        }

        return sb.toString();
    }

    private int calculateStayDays(LocalDateTime enterStateTime) {
        if (enterStateTime == null) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(enterStateTime.toLocalDate(), LocalDate.now());
    }

    private String getClassifyValue(ItemInfoResponse itemInfo, String classifyField, Integer itemId, Map<String, List<Integer>> missingClassifyFieldItems) {
        if (classifyField == null || itemInfo == null) {
            return null;
        }

        if ("priority".equals(classifyField) && itemInfo.getPriority() != null) {
            return itemInfo.getPriority().getName();
        }
        if ("severities".equals(classifyField) && itemInfo.getSeverities() != null && !itemInfo.getSeverities().isEmpty()) {
            return itemInfo.getSeverities().get(0).getName();
        }
        if ("categories".equals(classifyField) && itemInfo.getCategories() != null && !itemInfo.getCategories().isEmpty()) {
            return itemInfo.getCategories().get(0).getName();
        }

        if (itemInfo.getCustomFields() != null) {
            for (ItemInfoResponse.CustomField field : itemInfo.getCustomFields()) {
                if (field.getName().equals(classifyField) ||
                        (field.getLabel() != null && field.getLabel().equals(classifyField))) {
                    if (field.getValues() != null && !field.getValues().isEmpty()) {
                        return field.getValues().get(0).getName();
                    }
                    if (field.getValue() != null) {
                        return field.getValue();
                    }
                }
            }
        }

        // 收集分类字段缺失的条目ID，后续统一打印汇总日志
        missingClassifyFieldItems.computeIfAbsent(classifyField, k -> new ArrayList<>()).add(itemId);
        return null;
    }

    private boolean shouldSendMemberNotification(int stayDays, ClassifyRule classifyRule) {
        if (classifyRule.getMemberIntervalDays() == null || classifyRule.getMemberIntervalDays() <= 0) {
            return false;
        }
        return stayDays >= classifyRule.getMemberIntervalDays() &&
                stayDays % classifyRule.getMemberIntervalDays() == 0;
    }

    private boolean shouldSendManagerNotification(int stayDays, ClassifyRule classifyRule) {
        if (classifyRule.getManagerEscalateDays() == null) {
            return false;
        }
        return stayDays >= classifyRule.getManagerEscalateDays();
    }

    private boolean shouldSendDirectorNotification(int stayDays, ClassifyRule classifyRule) {
        if (classifyRule.getDirectorEscalateDays() == null) {
            return false;
        }
        return stayDays >= classifyRule.getDirectorEscalateDays();
    }

    /**
     * 格式化成员消息
     *
     * 使用 trackerTypeDisplay（如"问题"、"需求"）作为条目前缀
     * 条目名称支持点击跳转链接
     */
    private String formatMemberMessage(ItemInfoResponse itemInfo, String trackerTypeDisplay,
                                        int stayDays, ItemInfoResponse.MemberInfo member) {
        // 调用钉钉API获取用户真实姓名
        String memberName = getRealName(member);

        // 构建条目链接（markdown格式：[名称](链接)）
        String itemName = itemInfo.getName();
        String itemLink = itemInfo.getItemLink();
        String itemNameWithLink = itemLink != null && !itemLink.isEmpty()
                ? "[" + itemName + "](" + itemLink + ")"
                : itemName;

        // 使用 trackerTypeDisplay 作为前缀，如 "问题【条目名称（可点击）】"
        return String.format("您好，以下%s未及时处理，请知悉！\n\n%s【%s】，在【%s】状态下已【%d】天，负责人【%s】",
                trackerTypeDisplay, trackerTypeDisplay, itemNameWithLink, itemInfo.getStatus(), stayDays, memberName);
    }

    /**
     * 获取成员真实姓名（调用钉钉API转换工号）
     */
    private String getRealName(ItemInfoResponse.MemberInfo member) {
        String userId = member.getUserId();
        if (userId != null && !userId.isEmpty()) {
            String realName = dingService.getUserInfo(userId);
            if (realName != null && !realName.isEmpty()) {
                return realName;
            }
        }
        // 兜底：使用原有显示名
        return member.getDisplayName() != null ? member.getDisplayName() : member.getName();
    }

    /**
     * 合并多个通知字段的成员，按 userId 去重
     *
     * @param itemInfo 条目详情
     * @param notifyFields 通知字段名称列表
     * @return 合并去重后的成员列表
     */
    private List<ItemInfoResponse.MemberInfo> getMembersByFields(ItemInfoResponse itemInfo, List<String> notifyFields) {
        if (itemInfo == null || notifyFields == null || notifyFields.isEmpty()) {
            return new ArrayList<>();
        }

        // 使用 LinkedHashMap 按 userId 去重，保留插入顺序
        Map<String, ItemInfoResponse.MemberInfo> dedup = new LinkedHashMap<>();
        for (String field : notifyFields) {
            List<ItemInfoResponse.MemberInfo> fieldMembers = itemInfo.getMembersByField(field);
            if (fieldMembers == null) {
                continue;
            }
            for (ItemInfoResponse.MemberInfo member : fieldMembers) {
                String key = member.getUserId();
                if (key == null || key.isEmpty()) {
                    dedup.put("NO_USERID_" + System.identityHashCode(member), member);
                } else if (!dedup.containsKey(key)) {
                    dedup.put(key, member);
                }
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private void saveNotifyLog(Integer itemId, String userid, String notifyType, String sendResult) {
        NotifyLog logEntry = new NotifyLog();
        logEntry.setItemId(itemId);
        logEntry.setSendTime(LocalDateTime.now());
        logEntry.setReceiverUserid(userid);
        logEntry.setNotifyType(notifyType);
        logEntry.setSendResult(sendResult);
        notifyLogMapper.insert(logEntry);
    }

    /**
     * 条目通知数据类
     *
     * 基于 itemId 实现 equals 和 hashCode，用于 Set 去重
     */
    private static class ItemNotifyData {
        Integer itemId;
        ItemInfoResponse itemInfo;
        int stayDays;
        String trackerTypeDisplay;
        List<ItemInfoResponse.MemberInfo> members;
        ClassifyRule classifyRule;

        Integer getItemId() {
            return itemId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ItemNotifyData that = (ItemNotifyData) o;
            return Objects.equals(itemId, that.itemId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(itemId);
        }
    }
}