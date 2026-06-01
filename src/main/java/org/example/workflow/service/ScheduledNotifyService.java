package org.example.workflow.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.db.entity.ItemStateRecord;
import org.example.db.entity.NotifyLog;
import org.example.db.mapper.ItemStateRecordMapper;
import org.example.db.mapper.NotifyLogMapper;
import org.example.model.dto.response.ItemInfoResponse;
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
 * 3. 发送通知并更新记录
 *
 * @author system
 * @since 1.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
@DependsOn({"orgCacheService", "dingUserCacheService"}) // 明确依赖：确保两个缓存服务先初始化
public class ScheduledNotifyService {

    private final ConfigMetaService configMetaService;
    private final WorkflowConfigService workflowConfigService;
    private final CBSwaggerService cbSwaggerService;
    private final DingService dingService;
    private final OrgCacheService orgCacheService;
    private final ItemStateRecordMapper itemStateRecordMapper;
    private final NotifyLogMapper notifyLogMapper;
    private final DingUserCacheService dingUserCacheService; // 注入用户缓存服务

    /**
     * 服务初始化完成后检查缓存就绪状态
     */
    @PostConstruct
    public void validateCacheReadiness() {
        log.info("ScheduledNotifyService 初始化完成，检查缓存服务状态...");
        log.info("OrgCacheService 缓存记录数: {}", orgCacheService.getCacheSize());
        log.info("DingUserCacheService 有效用户数: {}", dingUserCacheService.getCacheSize());

        if (orgCacheService.getCacheSize() == 0) {
            log.warn("警告: 组织架构缓存为空，可能影响通知发送");
        }
        if (dingUserCacheService.getCacheSize() == 0) {
            log.warn("警告: 钉钉用户缓存为空，可能影响用户验证");
        }
    }

    /**
     * 检查缓存服务是否就绪
     *
     * @return true表示缓存服务已就绪
     */
    public boolean areCachesReady() {
        return orgCacheService.getCacheSize() > 0 && dingUserCacheService.getCacheSize() > 0;
    }

    /**
     * 定时通知主调度方法
     *
     * 默认每天08:00执行，时间可通过配置覆盖。
     * 实际执行时间由配置文件中的 default-notify-time 决定
     */
    @Scheduled(cron = "0 0/1 * * * *") // 每分钟检查一次
    public void executeScheduledNotify() {
        // 获取配置的默认通知时间
        String notifyTime = workflowConfigService.getNotifyTime(null, 5); // 使用项目5的配置

        // 解析配置的时间（格式：HH:mm）
        String[] timeParts = notifyTime.split(":");
        if (timeParts.length != 2) {
            log.warn("配置的notifyTime格式错误: {}, 使用默认8点", notifyTime);
            timeParts = new String[]{"08", "00"};
        }

        int configuredHour = Integer.parseInt(timeParts[0]);
        int configuredMinute = Integer.parseInt(timeParts[1]);

        // 获取当前时间
        LocalDateTime now = LocalDateTime.now();
        int currentHour = now.getHour();
        int currentMinute = now.getMinute();

        // 检查是否是配置的执行时间（允许±1分钟误差）
        boolean isExecutionTime = false;
        if (currentHour == configuredHour) {
            if (currentMinute == configuredMinute) {
                isExecutionTime = true; // 正好在配置时间
                log.info("⏰ 到达配置的执行时间 {}:{}，开始执行定时通知", configuredHour, configuredMinute);
            } else if (currentMinute == configuredMinute - 1 || currentMinute == configuredMinute + 1) {
                // 前后1分钟内也执行，避免错过
                log.info("⏰ 接近配置的执行时间 {}:{}（当前{}:{}），执行定时通知",
                        configuredHour, configuredMinute, currentHour, currentMinute);
                isExecutionTime = true;
            }
        }

        if (!isExecutionTime) {
            log.debug("当前时间 {}:{} 不在配置的执行时间 {}:{} 附近，跳过执行",
                    currentHour, currentMinute, configuredHour, configuredMinute);
            return;
        }
        log.info("========== 定时通知调度器启动 ==========");

        // 检查缓存服务就绪状态
        if (!areCachesReady()) {
            log.warn("缓存服务未就绪(OrgCache:{}, DingUserCache:{}), 跳过本次执行",
                    orgCacheService.getCacheSize(), dingUserCacheService.getCacheSize());
            return;
        }

        long startTime = System.currentTimeMillis();

        try {
            // 1. 检测YAML变更并重载配置
            checkAndReloadConfig();

            // 2. 查询需要通知的条目
            List<ItemStateRecord> itemsToNotify = queryItemsToNotify();
            log.info("待处理条目数: {}", itemsToNotify.size());

            if (itemsToNotify.isEmpty()) {
                log.info("无待处理条目，定时通知完成");
                return;
            }

            // 3. 统计变量
            int memberNotifyCount = 0;
            int managerNotifyCount = 0;
            int directorNotifyCount = 0;
            int skipCount = 0;

            // 4. 按条目处理
            for (ItemStateRecord record : itemsToNotify) {
                try {
                    NotifyResult result = processItem(record);
                    memberNotifyCount += result.memberNotified;
                    managerNotifyCount += result.managerNotified;
                    directorNotifyCount += result.directorNotified;
                    if (result.skipped) {
                        skipCount++;
                    }
                } catch (Exception e) {
                    log.error("处理条目失败: itemId={}, error={}", record.getItemId(), e.getMessage());
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("========== 定时通知调度完成 ========== 成员通知={}, 科长通知={}, 部长通知={}, 跳过={}, 耗时={}ms",
                    memberNotifyCount, managerNotifyCount, directorNotifyCount, skipCount, duration);

        } catch (Exception e) {
            log.error("定时通知调度执行失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 检测YAML变更并重载配置
     */
    private void checkAndReloadConfig() {
        if (configMetaService.checkYamlModified()) {
            log.info("检测到YAML配置变更，触发热更新");
            configMetaService.reloadYamlConfig();
        }
    }

    /**
     * 查询需要通知的条目
     *
     * 过滤今日已发送通知的条目。
     */
    private List<ItemStateRecord> queryItemsToNotify() {
        List<ItemStateRecord> allRecords = itemStateRecordMapper.selectAll();
        LocalDate today = LocalDate.now();

        return allRecords.stream()
                .filter(record -> {
                    // 过滤今日已通知
                    if (record.getLastNotifyTime() != null) {
                        LocalDate lastNotifyDate = record.getLastNotifyTime().toLocalDate();
                        return !lastNotifyDate.equals(today);
                    }
                    return true;
                })
                .collect(Collectors.toList());
    }

    /**
     * 处理单个条目
     *
     * 计算停留天数、获取分类规则、判断通知层级、发送通知。
     */
    private NotifyResult processItem(ItemStateRecord record) {
        NotifyResult result = new NotifyResult();

        Integer itemId = record.getItemId();
        Integer trackerId = record.getTrackerId();
        Integer projectId = record.getProjectId();
        String targetState = record.getTargetState();

        // 1. 获取条目详情
        ItemInfoResponse itemInfo = cbSwaggerService.getItemInfo(itemId);
        if (itemInfo == null) {
            log.warn("条目不存在, itemId={}", itemId);
            result.skipped = true;
            return result;
        }

        // 2. 获取工作流配置
        WorkflowTemplate workflow = workflowConfigService.getWorkflowForTracker(
                trackerId, itemInfo.getTracker().getTypeName(), projectId);
        if (workflow == null) {
            log.warn("未找到工作流配置, itemId={}, trackerId={}", itemId, trackerId);
            result.skipped = true;
            return result;
        }

        // 3. 获取状态配置
        WorkflowTemplate.StateConfig stateConfig = workflowConfigService.getStateConfig(workflow, targetState);
        if (stateConfig == null || !workflowConfigService.getScheduledNotify(stateConfig)) {
            result.skipped = true;
            return result;
        }

        // 4. 计算停留天数
        int stayDays = calculateStayDays(record.getEnterStateTime());
        log.debug("条目停留天数: itemId={}, stayDays={}", itemId, stayDays);

        // 5. 获取分类配置和规则（传入 trackerType）
        String trackerType = itemInfo.getTracker().getTypeName();
        ClassifyConfig classifyConfig = workflowConfigService.getClassifyConfig(trackerId, trackerType, projectId);

        // 如果没有分类配置，说明该 tracker/tracker-type 不启用分类通知，跳过
        if (classifyConfig == null) {
            log.debug("该tracker/tracker-type未配置分类通知, itemId={}, trackerId={}, trackerType={}", itemId, trackerId, trackerType);
            result.skipped = true;
            return result;
        }

        String classifyValue = getClassifyValue(itemInfo, classifyConfig.getClassifyField());
        ClassifyRule classifyRule = workflowConfigService.matchClassifyRule(classifyValue, classifyConfig);

        if (classifyRule == null) {
            log.warn("未匹配分类规则, itemId={}, classifyValue={}", itemId, classifyValue);
            result.skipped = true;
            return result;
        }

        // 6. 获取通知成员
        String notifyField = stateConfig.getNotifyField();
        List<ItemInfoResponse.MemberInfo> members = itemInfo.getMembersByField(notifyField);
        if (members == null || members.isEmpty()) {
            log.warn("通知字段无成员, itemId={}, notifyField={}", itemId, notifyField);
            result.skipped = true;
            return result;
        }

        // 验证用户有效性（新增检查）
        List<String> userIds = members.stream()
                .map(m -> m.getUserId())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Set<String> invalidUserIds = dingUserCacheService.findInvalidUserIds(userIds);
        if (!invalidUserIds.isEmpty()) {
            log.warn("发现无效用户ID: itemId={}, invalidUserIds={}", itemId, invalidUserIds);
            // 可以选择过滤掉无效用户继续处理，或者跳过整个条目
        }

        // 7. 发送成员通知
        if (shouldSendMemberNotification(stayDays, classifyRule)) {
            sendMemberNotifications(itemId, itemInfo, stayDays, members);
            result.memberNotified = members.size();
        }

        // 8. 发送科长通知
        if (shouldSendManagerNotification(stayDays, classifyRule)) {
            sendManagerNotifications(itemId, itemInfo, stayDays, members);
            result.managerNotified = countUniqueManagers(members);
        }

        // 9. 发送部长通知
        if (shouldSendDirectorNotification(stayDays, classifyRule)) {
            sendDirectorNotifications(itemId, itemInfo, stayDays, members);
            result.directorNotified = countUniqueDirectors(members);
        }

        // 10. 更新last_notify_time
        itemStateRecordMapper.updateLastNotifyTime(itemId, LocalDateTime.now());

        return result;
    }

    /**
     * 计算停留天数
     */
    private int calculateStayDays(LocalDateTime enterStateTime) {
        if (enterStateTime == null) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(enterStateTime.toLocalDate(), LocalDate.now());
    }

    /**
     * 提取分类字段值
     */
    private String getClassifyValue(ItemInfoResponse itemInfo, String classifyField) {
        if (classifyField == null || itemInfo == null) {
            return null;
        }

        // 复用 WorkflowNotifyService.getExtraFieldValue 的逻辑
        // 内置字段
        if ("priority".equals(classifyField) && itemInfo.getPriority() != null) {
            return itemInfo.getPriority().getName();
        }
        if ("severities".equals(classifyField) && itemInfo.getSeverities() != null && !itemInfo.getSeverities().isEmpty()) {
            return itemInfo.getSeverities().get(0).getName();
        }
        if ("categories".equals(classifyField) && itemInfo.getCategories() != null && !itemInfo.getCategories().isEmpty()) {
            return itemInfo.getCategories().get(0).getName();
        }

        // 自定义字段
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

        log.warn("分类字段未找到或值为空: classifyField={}", classifyField);
        return null;
    }

    /**
     * 判断是否发送成员通知
     */
    private boolean shouldSendMemberNotification(int stayDays, ClassifyRule classifyRule) {
        if (classifyRule.getMemberIntervalDays() == null || classifyRule.getMemberIntervalDays() <= 0) {
            return false;
        }
        return stayDays >= classifyRule.getMemberIntervalDays() &&
                stayDays % classifyRule.getMemberIntervalDays() == 0;
    }

    /**
     * 判断是否发送科长通知
     */
    private boolean shouldSendManagerNotification(int stayDays, ClassifyRule classifyRule) {
        if (classifyRule.getManagerEscalateDays() == null) {
            return false;
        }
        return stayDays >= classifyRule.getManagerEscalateDays();
    }

    /**
     * 判断是否发送部长通知
     */
    private boolean shouldSendDirectorNotification(int stayDays, ClassifyRule classifyRule) {
        if (classifyRule.getDirectorEscalateDays() == null) {
            return false;
        }
        return stayDays >= classifyRule.getDirectorEscalateDays();
    }

    /**
     * 发送成员通知（逐人）
     */
    private void sendMemberNotifications(Integer itemId, ItemInfoResponse itemInfo, int stayDays,
                                          List<ItemInfoResponse.MemberInfo> members) {
        String trackerTypeDisplay = workflowConfigService.getTypeMapping(
                itemInfo.getTracker().getTypeName(), itemInfo.getProject().getId());

        for (ItemInfoResponse.MemberInfo member : members) {
            String userid = member.getUserId();
            if (userid == null || userid.isEmpty()) {
                continue;
            }

            // 检查用户有效性
            if (!dingUserCacheService.isValidUserId(userid)) {
                log.warn("跳过无效用户: itemId={}, userid={}", itemId, userid);
                continue;
            }

            String message = formatMemberMessage(itemInfo, trackerTypeDisplay, stayDays, member);

            try {
                dingService.sendTextMessage(userid, message);
                log.info("成员通知发送成功: itemId={}, userid={}", itemId, userid);
                saveNotifyLog(itemId, userid, "定时成员", "成功");
            } catch (Exception e) {
                log.error("成员通知发送失败: itemId={}, userid={}, error={}", itemId, userid, e.getMessage());
                saveNotifyLog(itemId, userid, "定时成员", "失败: " + e.getMessage());
            }
        }
    }

    /**
     * 发送科长通知（按科长聚合）
     */
    private void sendManagerNotifications(Integer itemId, ItemInfoResponse itemInfo, int stayDays,
                                           List<ItemInfoResponse.MemberInfo> members) {
        // 按科长分组
        Map<String, List<ItemInfoResponse.MemberInfo>> managerGroup = new HashMap<>();

        for (ItemInfoResponse.MemberInfo member : members) {
            String userid = member.getUserId();
            if (userid == null || userid.isEmpty()) {
                continue;
            }

            // 检查用户有效性
            if (!dingUserCacheService.isValidUserId(userid)) {
                continue;
            }

            String managerId = orgCacheService.getManager(userid);
            if (managerId != null) {
                managerGroup.computeIfAbsent(managerId, k -> new ArrayList<>()).add(member);
            }
        }

        String trackerTypeDisplay = workflowConfigService.getTypeMapping(
                itemInfo.getTracker().getTypeName(), itemInfo.getProject().getId());

        // 逐科长发送聚合消息
        for (Map.Entry<String, List<ItemInfoResponse.MemberInfo>> entry : managerGroup.entrySet()) {
            String managerId = entry.getKey();
            List<ItemInfoResponse.MemberInfo> responsibleMembers = entry.getValue();

            // 检查科长用户有效性
            if (!dingUserCacheService.isValidUserId(managerId)) {
                log.warn("跳过无效科长: itemId={}, managerId={}", itemId, managerId);
                continue;
            }

            String memberNames = responsibleMembers.stream()
                    .map(m -> m.getDisplayName() != null ? m.getDisplayName() : m.getName())
                    .collect(Collectors.joining(","));

            String message = formatAggregatedMessage(itemInfo, trackerTypeDisplay, stayDays, memberNames);

            try {
                dingService.sendTextMessage(managerId, message);
                log.info("科长通知发送成功: itemId={}, managerId={}, responsibleMembers={}",
                        itemId, managerId, memberNames);
                saveNotifyLog(itemId, managerId, "定时科长", "成功");
            } catch (Exception e) {
                log.error("科长通知发送失败: itemId={}, managerId={}, error={}", itemId, managerId, e.getMessage());
                saveNotifyLog(itemId, managerId, "定时科长", "失败: " + e.getMessage());
            }
        }
    }

    /**
     * 发送部长通知（按部长聚合）
     */
    private void sendDirectorNotifications(Integer itemId, ItemInfoResponse itemInfo, int stayDays,
                                            List<ItemInfoResponse.MemberInfo> members) {
        // 按部长分组
        Map<String, List<ItemInfoResponse.MemberInfo>> directorGroup = new HashMap<>();

        for (ItemInfoResponse.MemberInfo member : members) {
            String userid = member.getUserId();
            if (userid == null || userid.isEmpty()) {
                continue;
            }

            // 检查用户有效性
            if (!dingUserCacheService.isValidUserId(userid)) {
                continue;
            }

            String directorId = orgCacheService.getDirector(userid);
            if (directorId != null) {
                directorGroup.computeIfAbsent(directorId, k -> new ArrayList<>()).add(member);
            }
        }

        String trackerTypeDisplay = workflowConfigService.getTypeMapping(
                itemInfo.getTracker().getTypeName(), itemInfo.getProject().getId());

        // 逐部长发送聚合消息
        for (Map.Entry<String, List<ItemInfoResponse.MemberInfo>> entry : directorGroup.entrySet()) {
            String directorId = entry.getKey();
            List<ItemInfoResponse.MemberInfo> responsibleMembers = entry.getValue();

            // 检查部长用户有效性
            if (!dingUserCacheService.isValidUserId(directorId)) {
                log.warn("跳过无效部长: itemId={}, directorId={}", itemId, directorId);
                continue;
            }

            String memberNames = responsibleMembers.stream()
                    .map(m -> m.getDisplayName() != null ? m.getDisplayName() : m.getName())
                    .collect(Collectors.joining(","));

            String message = formatAggregatedMessage(itemInfo, trackerTypeDisplay, stayDays, memberNames);

            try {
                dingService.sendTextMessage(directorId, message);
                log.info("部长通知发送成功: itemId={}, directorId={}, responsibleMembers={}",
                        itemId, directorId, memberNames);
                saveNotifyLog(itemId, directorId, "定时部长", "成功");
            } catch (Exception e) {
                log.error("部长通知发送失败: itemId={}, directorId={}, error={}", itemId, directorId, e.getMessage());
                saveNotifyLog(itemId, directorId, "定时部长", "失败: " + e.getMessage());
            }
        }
    }

    /**
     * 格式化成员消息
     */
    private String formatMemberMessage(ItemInfoResponse itemInfo, String trackerTypeDisplay,
                                        int stayDays, ItemInfoResponse.MemberInfo member) {
        String memberName = member.getDisplayName() != null ? member.getDisplayName() : member.getName();
        return String.format("【%s】，在【%s】状态下已【%d】天，负责人【%s】",
                itemInfo.getName(), itemInfo.getStatus(), stayDays, memberName);
    }

    /**
     * 格式化聚合消息（科长/部长）
     */
    private String formatAggregatedMessage(ItemInfoResponse itemInfo, String trackerTypeDisplay,
                                            int stayDays, String memberNames) {
        return String.format("您好，以下问题未及时处理，请知悉！\n【%s】，在【%s】状态下已【%d】天，负责人【%s】",
                itemInfo.getName(), itemInfo.getStatus(), stayDays, memberNames);
    }

    /**
     * 统计唯一科长数
     */
    private int countUniqueManagers(List<ItemInfoResponse.MemberInfo> members) {
        Set<String> managerIds = new HashSet<>();
        for (ItemInfoResponse.MemberInfo member : members) {
            String userid = member.getUserId();
            if (userid != null) {
                String managerId = orgCacheService.getManager(userid);
                if (managerId != null) {
                    managerIds.add(managerId);
                }
            }
        }
        return managerIds.size();
    }

    /**
     * 统计唯一部长数
     */
    private int countUniqueDirectors(List<ItemInfoResponse.MemberInfo> members) {
        Set<String> directorIds = new HashSet<>();
        for (ItemInfoResponse.MemberInfo member : members) {
            String userid = member.getUserId();
            if (userid != null) {
                String directorId = orgCacheService.getDirector(userid);
                if (directorId != null) {
                    directorIds.add(directorId);
                }
            }
        }
        return directorIds.size();
    }

    /**
     * 保存通知日志
     */
    private void saveNotifyLog(Integer itemId, String userid, String notifyType, String sendResult) {
        NotifyLog log = new NotifyLog();
        log.setItemId(itemId);
        log.setSendTime(LocalDateTime.now());
        log.setReceiverUserid(userid);
        log.setNotifyType(notifyType);
        log.setSendResult(sendResult);
        notifyLogMapper.insert(log);
    }

    /**
     * 通知结果统计类
     */
    private static class NotifyResult {
        int memberNotified = 0;
        int managerNotified = 0;
        int directorNotified = 0;
        boolean skipped = false;
    }
}