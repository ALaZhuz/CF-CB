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
import org.example.workflow.config.ExtraField;
import org.example.workflow.config.WorkflowConfigService;
import org.example.workflow.config.WorkflowTemplate;
import org.example.workflow.dto.NotifyRequest;
import org.example.workflow.dto.NotifyResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 工作流通知服务类
 *
 * 实现afterEvent通知处理逻辑：
 * 1. 进入目标状态：发送通知并持久化状态记录
 * 2. 离开目标状态：删除状态记录
 * 3. 状态之间互转：不做处理
 *
 * 消息模板格式（固定，2026-05-27更新）：
 * 【{trackertype}】
 * {trackertype}名称: {item_name}
 * {trackertype}状态: {status_name}，请您处理
 * {notify_field_name}: {notify_display_names}
 * {extra_fields}                  ← 动态插入（如果配置了）
 * {trackertype}链接: {item_url}
 *
 * @author system
 * @since 1.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkflowNotifyService {

    private final WorkflowConfigService workflowConfigService;
    private final CBSwaggerService cbSwaggerService;
    private final DingService dingService;
    private final ItemStateRecordMapper itemStateRecordMapper;
    private final NotifyLogMapper notifyLogMapper;

    /**
     * 执行afterEvent通知处理
     *
     * 处理流程：
     * 1. 判断是进入状态还是离开状态
     * 2. 进入目标状态：发送通知并记录状态
     * 3. 离开目标状态：删除状态记录
     *
     * @param request 通知请求
     * @return 通知响应
     */
    public NotifyResponse notify(NotifyRequest request) {
        NotifyResponse response = new NotifyResponse();
        response.setSuccess(false);

        Integer itemId = request.getItemId();
        String previousState = request.getPreviousState();
        String targetState = request.getTargetState();

        log.info("开始afterEvent处理: itemId={}, previousState={}, targetState={}",
                itemId, previousState, targetState);

        try {
            // 1. 获取条目详情
            ItemInfoResponse itemInfo = cbSwaggerService.getItemInfo(itemId);
            if (itemInfo == null) {
                response.setErrorMessage("条目不存在: itemId=" + itemId);
                return response;
            }

            // 补充tracker和项目信息
            Integer trackerId = itemInfo.getTracker().getId();
            String trackerType = itemInfo.getTracker().getTypeName();
            Integer projectId = itemInfo.getProject().getId();

            // 2. 查找工作流配置
            WorkflowTemplate workflow = workflowConfigService.getWorkflowForTracker(
                    trackerId, trackerType, projectId);

            if (workflow == null) {
                log.info("未找到工作流配置，不做处理: itemId={}", itemId);
                response.setSuccess(true);
                response.setActionType("无需处理");
                return response;
            }

            // 3. 判断 previousState 是否是需要通知的状态
            WorkflowTemplate.StateConfig previousStateConfig = workflowConfigService.getStateConfig(workflow, previousState);
            boolean previousStateNeedsNotify = workflowConfigService.shouldNotify(previousStateConfig);

            // 4. 判断 targetState 是否是需要通知的状态
            WorkflowTemplate.StateConfig targetStateConfig = workflowConfigService.getStateConfig(workflow, targetState);
            boolean targetStateNeedsNotify = workflowConfigService.shouldNotify(targetStateConfig);

            // 5. 根据状态转换情况决定操作
            if (previousStateNeedsNotify && !targetStateNeedsNotify) {
                // 从通知状态进入非通知状态 → 离开状态，删除记录
                itemStateRecordMapper.deleteByItemId(itemId);
                log.info("离开通知状态，删除状态记录: itemId={}, previousState={}, targetState={}",
                        itemId, previousState, targetState);
                response.setSuccess(true);
                response.setActionType("离开状态");
                return response;
            }

            // 如果 targetState 不需要通知，不做处理（可能是非通知状态之间的转换）
            if (!targetStateNeedsNotify) {
                log.info("目标状态不需要通知，不做处理: itemId={}, targetState={}", itemId, targetState);
                response.setSuccess(true);
                response.setActionType("无需处理");
                return response;
            }

            // 6. 进入通知状态（包括状态转换）：发送通知
            String notifyField = targetStateConfig.getNotifyField();
            List<ItemInfoResponse.MemberInfo> members = itemInfo.getMembersByField(notifyField);

            if (members == null || members.isEmpty()) {
                log.warn("通知字段无成员，跳过通知: itemId={}, notifyField={}", itemId, notifyField);
                response.setSuccess(true);
                response.setActionType("无需通知");
                return response;
            }

            // 7. 格式化消息内容（使用固定模板 + type-mappings + extra-fields）
            String messageContent = formatMessage(itemInfo, targetState, notifyField, members, trackerType, projectId, trackerId);

            // 8. 发送通知给每个成员
            List<String> notifiedUsers = new ArrayList<>();
            List<String> failedUsers = new ArrayList<>();

            for (ItemInfoResponse.MemberInfo member : members) {
                String userid = member.getUserId();
                if (userid == null || userid.isEmpty()) {
                    continue;
                }

                try {
                    dingService.sendTextMessage(userid, messageContent);
                    notifiedUsers.add(userid);

                    // 发送成功，打印消息内容
                    log.info("钉钉通知发送成功: itemId={}, userid={}, message=\n{}", itemId, userid, messageContent);

                    // 记录发送日志（成功）
                    saveNotifyLog(itemId, userid, "即时", "成功");

                } catch (Exception e) {
                    log.error("发送通知失败: itemId={}, userid={}, error={}", itemId, userid, e.getMessage());
                    failedUsers.add(userid);

                    // 记录发送日志（失败）
                    saveNotifyLog(itemId, userid, "即时", "失败: " + e.getMessage());
                }
            }

            // 9. 持久化状态记录
            saveItemStateRecord(itemId, itemInfo.getName(), trackerId, projectId, targetState);

            // 10. 返回响应
            response.setSuccess(true);
            response.setNotifiedUsers(notifiedUsers);
            response.setFailedUsers(failedUsers);
            response.setActionType("进入状态");
            log.info("afterEvent处理完成: itemId={}, notified={}, failed={}",
                    itemId, notifiedUsers.size(), failedUsers.size());

        } catch (Exception e) {
            log.error("afterEvent处理异常: itemId={}, error={}", itemId, e.getMessage(), e);
            response.setErrorMessage("处理异常: " + e.getMessage());
        }

        return response;
    }

    /**
     * 格式化消息内容
     *
     * 使用固定模板格式：
     * 【{trackertype}】
     * {trackertype}名称: {item_name}
     * {trackertype}状态: {status_name}，请您处理
     * {notify_field_name}: {notify_display_names}
     * {extra_fields}                  ← 动态插入（如果配置了）
     * {trackertype}链接: {item_url}
     *
     * @param itemInfo 条目详情
     * @param targetState 目标状态
     * @param notifyField 通知字段名称
     * @param members 通知成员列表
     * @param trackerType tracker类型名称
     * @param projectId 项目ID
     * @param trackerId tracker ID（用于查找tracker级extra-fields）
     * @return 格式化后的消息内容
     */
    private String formatMessage(ItemInfoResponse itemInfo, String targetState,
                                  String notifyField, List<ItemInfoResponse.MemberInfo> members,
                                  String trackerType, Integer projectId, Integer trackerId) {
        // 1. 获取 type-mapping
        String trackerTypeDisplay = workflowConfigService.getTypeMapping(trackerType, projectId);

        // 2. 获取通知成员显示名列表
        String notifyMembersStr = members.stream()
                .map(m -> m.getDisplayName() != null ? m.getDisplayName() : m.getName())
                .collect(Collectors.joining(","));

        // 3. 获取 extra-fields 值（支持 tracker 级配置）
        List<ExtraField> extraFields = workflowConfigService.getExtraFields(projectId, trackerId);
        StringBuilder extraFieldsContent = new StringBuilder();
        for (ExtraField extraField : extraFields) {
            String fieldValue = getExtraFieldValue(itemInfo, extraField.getField());
            if (fieldValue != null && !fieldValue.isEmpty()) {
                extraFieldsContent.append(extraField.getLabel())
                        .append(": ")
                        .append(fieldValue)
                        .append("\n");
            } else {
                // 找不到字段值，警告提示
                log.warn("extra-field字段未找到或值为空: itemId={}, fieldName={}, 请检查Codebeamer中是否存在该字段或字段名是否正确",
                        itemInfo.getId(), extraField.getField());
            }
        }

        // 4. 构建固定模板消息
        StringBuilder message = new StringBuilder();
        message.append("【").append(trackerTypeDisplay).append("】\n");
        message.append(trackerTypeDisplay).append("名称: ").append(itemInfo.getName() != null ? itemInfo.getName() : "").append("\n");
        message.append(trackerTypeDisplay).append("状态: ").append(targetState != null ? targetState : "").append("，请您处理\n");
        message.append(notifyField).append(": ").append(notifyMembersStr).append("\n");

        // 5. 插入 extra-fields（在链接行之前）
        if (extraFieldsContent.length() > 0) {
            message.append(extraFieldsContent);
        }

        // 6. 添加链接行
        message.append(trackerTypeDisplay).append("链接: ").append(itemInfo.getItemLink() != null ? itemInfo.getItemLink() : "");

        return message.toString();
    }

    /**
     * 获取额外字段值
     *
     * 从条目详情中获取指定字段的值。
     * 支持：
     * - CodeBeamer默认字段（priority、categories、severities等）
     * - 自定义字段中的成员类型字段（values）：显示名称列表
     * - 自定义字段中的文本/日期/选择类型字段（value）：直接显示值
     *
     * @param itemInfo 条目详情
     * @param fieldName 字段名称
     * @return 字段值，未找到返回null
     */
    private String getExtraFieldValue(ItemInfoResponse itemInfo, String fieldName) {
        if (itemInfo == null || fieldName == null) {
            return null;
        }

        // 1. 先检查CodeBeamer默认字段
        // priority（单个选项）
        if ("priority".equals(fieldName) && itemInfo.getPriority() != null) {
            return itemInfo.getPriority().getName();
        }

        // categories（列表）
        if ("categories".equals(fieldName) && itemInfo.getCategories() != null && !itemInfo.getCategories().isEmpty()) {
            return itemInfo.getCategories().stream()
                    .map(ItemInfoResponse.ChoiceOption::getName)
                    .collect(Collectors.joining(","));
        }

        // severities（列表）
        if ("severities".equals(fieldName) && itemInfo.getSeverities() != null && !itemInfo.getSeverities().isEmpty()) {
            return itemInfo.getSeverities().stream()
                    .map(ItemInfoResponse.ChoiceOption::getName)
                    .collect(Collectors.joining(","));
        }

        // teams（列表）
        if ("teams".equals(fieldName) && itemInfo.getTeams() != null && !itemInfo.getTeams().isEmpty()) {
            return itemInfo.getTeams().stream()
                    .map(ItemInfoResponse.ChoiceOption::getName)
                    .collect(Collectors.joining(","));
        }

        // versions（列表）
        if ("versions".equals(fieldName) && itemInfo.getVersions() != null && !itemInfo.getVersions().isEmpty()) {
            return itemInfo.getVersions().stream()
                    .map(ItemInfoResponse.ChoiceOption::getName)
                    .collect(Collectors.joining(","));
        }

        // status
        if ("status".equals(fieldName)) {
            return itemInfo.getStatus();
        }

        // 2. 查找自定义字段
        if (itemInfo.getCustomFields() != null) {
            for (ItemInfoResponse.CustomField field : itemInfo.getCustomFields()) {
                if (field.getName().equals(fieldName) ||
                        (field.getLabel() != null && field.getLabel().equals(fieldName))) {
                    // 成员类型字段（values）
                    if (field.getValues() != null && !field.getValues().isEmpty()) {
                        return field.getValues().stream()
                                .map(v -> v.getDisplayName() != null ? v.getDisplayName() : v.getName())
                                .collect(Collectors.joining(","));
                    }
                    // 文本/日期/选择类型字段（value）
                    if (field.getValue() != null && !field.getValue().isEmpty()) {
                        return field.getValue();
                    }
                    return null;
                }
            }
        }

        log.warn("extra-field字段未找到或值为空: itemId={}, fieldName={}, 请检查Codebeamer中是否存在该字段或字段名是否正确",
                itemInfo.getId(), fieldName);
        return null;
    }

    /**
     * 保存条目状态记录
     *
     * @param itemId 条目ID
     * @param itemName 条目名称
     * @param trackerId tracker ID
     * @param projectId 项目ID
     * @param targetState 目标状态
     */
    private void saveItemStateRecord(Integer itemId, String itemName,
                                      Integer trackerId, Integer projectId, String targetState) {
        ItemStateRecord record = new ItemStateRecord();
        record.setItemId(itemId);
        record.setItemName(itemName);
        record.setTrackerId(trackerId);
        record.setProjectId(projectId);
        record.setTargetState(targetState);
        record.setEnterStateTime(LocalDateTime.now());
        record.setLastNotifyTime(LocalDateTime.now());

        itemStateRecordMapper.insert(record);
        log.debug("保存状态记录: itemId={}", itemId);
    }

    /**
     * 保存通知发送日志
     *
     * @param itemId 条目ID
     * @param userid 接收者userid
     * @param notifyType 通知类型
     * @param sendResult 发送结果
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
}