package org.example.workflow.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.db.entity.InstantNotifyRecord;
import org.example.db.entity.NotifyLog;
import org.example.db.mapper.InstantNotifyRecordMapper;
import org.example.db.mapper.NotifyLogMapper;
import org.example.config.CBProperties;
import org.example.model.dto.response.CBTrackerInfoResponse;
import org.example.model.dto.response.ItemInfoResponse;
import org.example.model.enums.MsgKeyConstant;
import org.example.service.CBSwaggerService;
import org.example.service.DingService;
import org.example.workflow.config.WorkflowConfigService;
import org.example.workflow.config.WorkflowTemplate;
import org.example.workflow.config.ProjectConfig;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 批量即时通知服务类
 *
 * 实现轮询批量即时通知逻辑：
 * 1. 查询当天未发送的记录
 * 2. 按 notify_userid + target_state 分组聚合
 * 3. 批量发送聚合消息（同一通知人+同一状态=一条消息，包含多个tracker链接）
 * 4. 发送成功后更新 notify_success=true
 *
 * 发送粒度：按 notify_userid + target_state 聚合
 * 发送内容：多个tracker链接列表
 *
 * 消息格式：
 * 张三，您好，
 * 以下tracker中已有条目到达【科长批准】状态，请点击链接，筛选状态为【科长批准】的条目，进行评审：
 * 1. [需求Tracker A](链接)
 * 2. [Bug Tracker B](链接)
 * 请及时处理。
 *
 * @author system
 * @since 1.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BatchNotifyService {

    private final WorkflowConfigService workflowConfigService;
    private final CBSwaggerService cbSwaggerService;
    private final DingService dingService;
    private final InstantNotifyRecordMapper instantNotifyRecordMapper;
    private final NotifyLogMapper notifyLogMapper;
    private final CBProperties cbProperties;

    /**
     * 执行批量即时通知
     *
     * 轮询任务调用此方法，查询当天未发送的记录并发送通知。
     */
    public void executeBatchNotify() {
        LocalDate today = LocalDate.now();
        log.info("========== 批量即时通知轮询启动 ========== notifyDate={}", today);

        // 1. 查询当天未发送的记录
        List<InstantNotifyRecord> pendingRecords = instantNotifyRecordMapper.selectPendingByDate(today);

        if (pendingRecords.isEmpty()) {
            log.info("当天无待发送的批量通知记录，跳过");
            return;
        }

        log.info("待发送批量通知记录数: {}", pendingRecords.size());

        // 2. 按 notify_userid + target_state 分组聚合
        Map<String, Map<String, List<InstantNotifyRecord>>> groupedRecords =
                groupByUseridAndState(pendingRecords);

        // 3. 批量发送聚合消息
        int successCount = 0;
        int failCount = 0;

        for (Map.Entry<String, Map<String, List<InstantNotifyRecord>>> userEntry :
                groupedRecords.entrySet()) {
            String notifyUserid = userEntry.getKey();

            for (Map.Entry<String, List<InstantNotifyRecord>> stateEntry :
                    userEntry.getValue().entrySet()) {
                String targetState = stateEntry.getKey();
                List<InstantNotifyRecord> records = stateEntry.getValue();

                try {
                    // 发送单条消息（同一通知人+同一状态）
                    boolean sent = sendAggregatedMessage(notifyUserid, targetState, records);

                    if (sent) {
                        // 更新 notify_success=true
                        for (InstantNotifyRecord record : records) {
                            instantNotifyRecordMapper.updateNotifySuccess(
                                    record.getTrackerId(),
                                    record.getTargetState(),
                                    record.getNotifyUserid(),
                                    record.getNotifyDate(),
                                    LocalDateTime.now()
                            );
                        }
                        successCount++;
                    } else {
                        failCount++;
                    }

                } catch (Exception e) {
                    log.error("批量通知发送失败: notifyUserid={}, targetState={}, error={}",
                            notifyUserid, targetState, e.getMessage()) ;
                    failCount++;

                    // 记录失败日志
                    for (InstantNotifyRecord record : records) {
                        saveNotifyLog(record.getTrackerId(), notifyUserid, "批量即时", "失败: " + e.getMessage());
                    }
                }
            }
        }

        log.info("========== 批量即时通知轮询完成 ========== 成功={}, 失败={}", successCount, failCount);
    }

    /**
     * 按 notify_userid + target_state 分组聚合
     *
     * @param records 待发送记录列表
     * @return 分组后的记录
     */
    private Map<String, Map<String, List<InstantNotifyRecord>>>
            groupByUseridAndState(List<InstantNotifyRecord> records) {

        Map<String, Map<String, List<InstantNotifyRecord>>> result = new LinkedHashMap<>();

        for (InstantNotifyRecord record : records) {
            String notifyUserid = record.getNotifyUserid();
            String targetState = record.getTargetState();

            result.computeIfAbsent(notifyUserid, k -> new LinkedHashMap<>())
                  .computeIfAbsent(targetState, k -> new ArrayList<>())
                  .add(record);
        }

        return result;
    }

    /**
     * 发送聚合消息（同一通知人+同一状态=一条消息）
     *
     * @param notifyUserid 通知人userid
     * @param targetState 目标状态
     * @param records 该分组下的所有tracker记录
     * @return 是否发送成功
     */
    private boolean sendAggregatedMessage(String notifyUserid, String targetState,
                                           List<InstantNotifyRecord> records) {

        // 1. 获取通知人真实姓名
        String realName = dingService.getUserInfo(notifyUserid);
        String displayName = realName != null && !realName.isEmpty() ? realName : notifyUserid;

        // 2. 构建tracker链接列表
        List<String> trackerLinks = new ArrayList<>();
        for (InstantNotifyRecord record : records) {
            Integer trackerId = record.getTrackerId();
            String trackerType = record.getTrackerType();
            Integer projectId = record.getProjectId();

            // 获取tracker信息
            CBTrackerInfoResponse trackerInfo = cbSwaggerService.getProjectInfo(trackerId);
            if (trackerInfo == null) {
                log.warn("获取tracker信息失败: trackerId={}", trackerId);
                continue;
            }

            String trackerName = trackerInfo.getName() != null ? trackerInfo.getName() : "Tracker";
            String trackerTypeDisplay = workflowConfigService.getTypeMapping(trackerType, projectId);
            String trackerLink = buildTrackerLink(trackerId);

            // 格式：[trackerType TrackerName](链接)
            trackerLinks.add(String.format("[%s Tracker：%s](%s)", trackerTypeDisplay, trackerName, trackerLink));
        }

        if (trackerLinks.isEmpty()) {
            log.warn("无可用的tracker链接: notifyUserid={}, targetState={}", notifyUserid, targetState);
            return false;
        }

        // 3. 构建消息内容
        String message = buildMessage(displayName, targetState, trackerLinks);

        // 4. 发送消息
        try {
            dingService.sendRobotMessage(notifyUserid, "条目状态转变通知", message, MsgKeyConstant.SAMPLE_MARKDOWN);
            log.info("批量通知发送成功: notifyUserid={}, targetState={}, trackerCount={}",
                    notifyUserid, targetState, trackerLinks.size());

            // 记录成功日志
            for (InstantNotifyRecord record : records) {
                saveNotifyLog(record.getTrackerId(), notifyUserid, "批量即时", "成功");
            }

            return true;

        } catch (Exception e) {
            log.error("批量通知发送异常: notifyUserid={}, targetState={}, error={}",
                    notifyUserid, targetState, e.getMessage());
            throw e;
        }
    }

    /**
     * 构建tracker链接
     *
     * @param trackerId tracker ID
     * @return tracker链接
     */
    private String buildTrackerLink(Integer trackerId) {
        // 使用Codebeamer的tracker链接格式：baseUrlPrefix + "/cb/tracker/" + trackerId
        // 例如：https://cb-trial.hirain.com/cb/tracker/2244785
        return cbProperties.getBaseUrlPrefix() + "/cb/tracker/" + trackerId;
    }

    /**
     * 构建消息内容
     *
     * @param displayName 通知人姓名
     * @param targetState 目标状态
     * @param trackerLinks tracker链接列表
     * @return 消息内容
     */
    private String buildMessage(String displayName, String targetState, List<String> trackerLinks) {

        // 1. 获取模板配置并替换占位符
        String template = workflowConfigService.getBatchNotifyTemplate();
        String templateContent = template.replace("{targetState}", targetState);

        // 2. 构建tracker链接列表
        StringBuilder linksContent = new StringBuilder();
        for (int i = 0; i < trackerLinks.size(); i++) {
            linksContent.append(i + 1).append(". ").append(trackerLinks.get(i)).append("\n");
        }

        // 3. 组合完整消息
        StringBuilder message = new StringBuilder();
        message.append(templateContent).append("\n\n");
        message.append(linksContent);

        return message.toString();
    }

    /**
     * 保存通知发送日志
     */
    private void saveNotifyLog(Integer trackerId, String userid, String notifyType, String sendResult) {
        NotifyLog logEntry = new NotifyLog();
        logEntry.setItemId(trackerId);  // 使用trackerId作为标识
        logEntry.setSendTime(LocalDateTime.now());
        logEntry.setReceiverUserid(userid);
        logEntry.setNotifyType(notifyType);
        logEntry.setSendResult(sendResult);

        notifyLogMapper.insert(logEntry);
    }

    /**
     * 补录批量即时通知记录
     *
     * 在预清理任务（凌晨4点）中调用，补录前一天进入batchNotifyField状态的条目。
     * 补录时notify_date设置为今天，确保能被轮询任务查询到。
     *
     * 使用UNIQUE约束避免重复补录。
     *
     * @param projectConfig 项目配置
     * @return 补录统计结果
     */
    public SupplementResult supplementInstantNotifyRecords(ProjectConfig projectConfig) {
        SupplementResult result = new SupplementResult();
        Integer projectId = projectConfig.getProjectId();
        LocalDate today = LocalDate.now();

        log.info("开始补录批量通知记录: projectId={}", projectId);

        // 1. 收集配置了batchNotifyField的状态及其通知字段
        Map<String, List<String>> stateBatchNotifyFields = collectBatchNotifyFields(projectConfig);

        if (stateBatchNotifyFields.isEmpty()) {
            log.info("项目未配置batchNotifyField，跳过补录: projectId={}", projectId);
            return result;
        }

        // 2. 遍历每个状态，查询处于该状态的条目
        for (Map.Entry<String, List<String>> entry : stateBatchNotifyFields.entrySet()) {
            String targetState = entry.getKey();
            List<String> batchNotifyFields = entry.getValue();

            try {
                // 查询当前处于该状态的条目（从tracker-matching获取tracker）
                supplementStateRecords(projectConfig, projectId, targetState, batchNotifyFields, today, result);

                // 延迟避免API限流
                Thread.sleep(1000);

            } catch (InterruptedException e) {
                log.warn("延迟等待被中断");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("补录状态记录失败: projectId={}, targetState={}, error={}",
                        projectId, targetState, e.getMessage());
            }
        }

        log.info("补录批量通知记录完成: projectId={}, 补录={}, 跳过={}",
                projectId, result.getSupplemented(), result.getSkipped());

        return result;
    }

    /**
     * 收集项目中配置了batchNotifyField的状态及其通知字段
     *
     * @param projectConfig 项目配置
     * @return targetState -> batchNotifyFields映射
     */
    private Map<String, List<String>> collectBatchNotifyFields(ProjectConfig projectConfig) {
        Map<String, List<String>> result = new LinkedHashMap<>();

        if (projectConfig.getWorkflows() == null) {
            return result;
        }

        for (WorkflowTemplate workflow : projectConfig.getWorkflows()) {
            if (workflow.getStates() == null) {
                continue;
            }

            for (WorkflowTemplate.StateConfig stateConfig : workflow.getStates()) {
                List<String> batchNotifyFields = workflowConfigService.getBatchNotifyFields(stateConfig);
                if (!batchNotifyFields.isEmpty()) {
                    result.put(stateConfig.getName(), batchNotifyFields);
                }
            }
        }

        return result;
    }

    /**
     * 补录指定状态的批量通知记录
     *
     * @param projectConfig 项目配置
     * @param projectId 项目ID
     * @param targetState 目标状态
     * @param batchNotifyFields 批量通知字段列表
     * @param today 今天的日期
     * @param result 补录统计结果
     */
    private void supplementStateRecords(ProjectConfig projectConfig, Integer projectId, String targetState,
                                         List<String> batchNotifyFields, LocalDate today, SupplementResult result) {

        // 从tracker-matching获取所有tracker
        if (projectConfig.getTrackerMatching() == null) {
            return;
        }

        for (var matchingRule : projectConfig.getTrackerMatching()) {
            Integer trackerId = matchingRule.getTrackerId();
            String trackerType = matchingRule.getTrackerType();

            try {
                // tracker-id或tracker-type匹配
                if (trackerId == null && trackerType != null) {
                    // 需要查找该项目下该类型的所有tracker（参考InitService的逻辑）
                    // 暂时跳过，因为实现复杂
                    log.warn("tracker-type匹配暂时不支持补录: trackerType={}", trackerType);
                    continue;
                }

                if (trackerId == null) {
                    continue;
                }

                // 获取tracker类型（用于消息显示）
                String trackerTypeName = null;
                try {
                    CBTrackerInfoResponse trackerInfo = cbSwaggerService.getProjectInfo(trackerId);
                    if (trackerInfo != null && trackerInfo.getType() != null) {
                        trackerTypeName = trackerInfo.getType().getName();
                    }
                } catch (Exception e) {
                    log.warn("获取tracker类型失败: trackerId={}, error={}", trackerId, e.getMessage());
                }

                // 查询tracker下处于targetState状态的条目
                supplementTrackerRecords(trackerId, trackerTypeName, projectId, targetState,
                        batchNotifyFields, today, result);

            } catch (Exception e) {
                log.error("补录tracker记录失败: trackerId={}, error={}", trackerId, e.getMessage());
            }
        }
    }

    /**
     * 补录指定tracker的批量通知记录
     *
     * @param trackerId tracker ID
     * @param trackerType tracker类型
     * @param projectId 项目ID
     * @param targetState 目标状态
     * @param batchNotifyFields 批量通知字段列表
     * @param today 今天的日期
     * @param result 补录统计结果
     */
    private void supplementTrackerRecords(Integer trackerId, String trackerType, Integer projectId,
                                           String targetState, List<String> batchNotifyFields, LocalDate today,
                                           SupplementResult result) {

        // 查询tracker下处于targetState状态的条目（简化：查询当前状态的条目）
        String queryString = String.format("tracker.id = %d AND status = '%s'", trackerId, targetState);
        int pageSize = 500;
        int page = 1;

        while (true) {
            try {
                var response = cbSwaggerService.query(page, pageSize, queryString);
                if (response == null || response.getItems() == null || response.getItems().isEmpty()) {
                    break;
                }

                // 遍历条目，补录批量通知记录
                for (var item : response.getItems()) {
                    result.scanned++;

                    // 获取条目详情（获取batchNotifyFields对应的成员）
                    try {
                        var itemInfo = cbSwaggerService.getItemInfo(item.getId());
                        if (itemInfo == null) {
                            result.skipped++;
                            continue;
                        }

                        // 获取通知人列表
                        List<ItemInfoResponse.MemberInfo> members = getMembersByFields(itemInfo, batchNotifyFields);

                        for (ItemInfoResponse.MemberInfo member : members) {
                            String userid = member.getUserId();
                            if (userid == null || userid.isEmpty()) {
                                continue;
                            }

                            // 检查是否已存在记录（UNIQUE约束会自动跳过）
                            InstantNotifyRecord existing = instantNotifyRecordMapper.selectByTrackerStateUseridDate(
                                    trackerId, targetState, userid, today);

                            if (existing != null) {
                                result.skipped++;
                                log.debug("已存在记录，跳过: trackerId={}, targetState={}, userid={}",
                                        trackerId, targetState, userid);
                                continue;
                            }

                            // 补录记录
                            InstantNotifyRecord record = new InstantNotifyRecord();
                            record.setTrackerId(trackerId);
                            record.setTrackerType(trackerType);
                            record.setProjectId(projectId);
                            record.setTargetState(targetState);
                            record.setNotifyUserid(userid);
                            record.setNotifyDate(today);
                            record.setNotifySuccess(false);

                            instantNotifyRecordMapper.insert(record);
                            result.supplemented++;
                            log.debug("补录批量通知记录: trackerId={}, targetState={}, userid={}, notifyDate={}",
                                    trackerId, targetState, userid, today);
                        }

                        // 延迟避免API限流
                        Thread.sleep(500);

                    } catch (InterruptedException e) {
                        log.warn("延迟等待被中断");
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        log.error("补录条目失败: itemId={}, error={}", item.getId(), e.getMessage());
                        result.skipped++;
                    }
                }

                if (response.getItems().size() < pageSize) {
                    break;
                }

                page++;

            } catch (Exception e) {
                log.error("查询条目失败: trackerId={}, targetState={}, error={}", trackerId, targetState, e.getMessage());
                break;
            }
        }
    }

    /**
     * 从条目信息获取指定字段的成员列表
     *
     * @param itemInfo 条目详情
     * @param fields 字段列表
     * @return 成员列表
     */
    private List<ItemInfoResponse.MemberInfo> getMembersByFields(ItemInfoResponse itemInfo, List<String> fields) {
        List<ItemInfoResponse.MemberInfo> members = new ArrayList<>();

        for (String field : fields) {
            List<ItemInfoResponse.MemberInfo> fieldMembers = itemInfo.getMembersByField(field);
            if (fieldMembers != null) {
                members.addAll(fieldMembers);
            }
        }

        return members;
    }

    /**
     * 补录统计结果类
     */
    public static class SupplementResult {
        private int scanned = 0;      // 扫描的条目数
        private int supplemented = 0;  // 补录的记录数
        private int skipped = 0;       // 跳过的记录数

        public int getScanned() { return scanned; }
        public int getSupplemented() { return supplemented; }
        public int getSkipped() { return skipped; }
    }
}