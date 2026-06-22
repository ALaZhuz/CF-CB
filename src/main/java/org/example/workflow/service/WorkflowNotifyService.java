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
import org.example.workflow.config.ExtraField;
import org.example.workflow.config.WorkflowConfigService;
import org.example.workflow.config.WorkflowTemplate;
import org.example.workflow.dto.NotifyRequest;
import org.example.workflow.dto.NotifyResponse;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工作流通知服务类
 *
 * 实现afterEvent通知处理逻辑：
 * 1. 进入目标状态：发送通知并持久化状态记录
 * 2. 离开目标状态：删除状态记录
 * 3. 状态之间互转：不做处理
 *
 * 消息模板格式：
 * {trackertype}名称: {item_name}
 * {trackertype}状态: {status_name}，请您处理
 * {notify_field_display_name}: {notify_display_names}
 * {extra_fields}
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
     * 简化逻辑：即时通知与数据库记录完全分离
     *
     * 一、即时通知（只看 notify 和 notifyField）
     * - notifyField 有值 → 发送钉钉通知
     * - notify=false 或 notifyField 为空 → 不发送即时通知
     *
     * 二、数据库记录（只看 scheduledNotify）
     * - scheduledNotify=true → INSERT OR REPLACE（用于定时通知追踪）
     * - scheduledNotify=false → DELETE（不再追踪）
     *
     * enter_state_time 始终设置为进入当前状态的时间
     *
     * @param request 通知请求
     * @return 通知响应
     */
    public NotifyResponse notify(NotifyRequest request) {
        NotifyResponse response = new NotifyResponse();
        response.setSuccess(false);

        Integer itemId = request.getItemId();
        String targetState = request.getTargetState();

        try {
            // 1. 获取条目详情
            ItemInfoResponse itemInfo = cbSwaggerService.getItemInfo(itemId);
            if (itemInfo == null) {
                log.warn("[afterEvent] 条目不存在: itemId={}", itemId);
                response.setErrorMessage("条目不存在: itemId=" + itemId);
                return response;
            }

            Integer trackerId = itemInfo.getTracker().getId();
            String trackerType = itemInfo.getTracker().getTypeName();
            Integer projectId = itemInfo.getProject().getId();
            String itemName = itemInfo.getName();

            // 2. 查找工作流配置
            WorkflowTemplate workflow = workflowConfigService.getWorkflowForTracker(
                    trackerId, trackerType, projectId);

            if (workflow == null) {
                response.setSuccess(true);
                response.setActionType("无需处理");
                return response;
            }

            // 3. 获取目标状态配置
            WorkflowTemplate.StateConfig targetStateConfig = workflowConfigService.getStateConfig(workflow, targetState);

            // ========== 一、数据库记录处理（定时通知相关）==========

            boolean needsScheduledNotify = workflowConfigService.getScheduledNotify(targetStateConfig);

            if (!needsScheduledNotify) {
                // 不需要定时通知 → 删除数据库记录
                itemStateRecordMapper.deleteByItemId(itemId);
                log.info("[afterEvent] 不需要定时通知，删除数据库记录: itemId={}, targetState={}", itemId, targetState);
            } else {
                // 需要定时通知 → INSERT OR REPLACE（更新状态和进入时间）
                saveItemStateRecord(itemId, itemName, trackerId, trackerType, projectId, targetState);
                log.info("[afterEvent] 需要定时通知，更新数据库记录: itemId={}, targetState={}", itemId, targetState);
            }

            // ========== 二、即时通知处理（与数据库无关）==========

            boolean needsInstantNotify = workflowConfigService.shouldNotify(targetStateConfig);

            if (!needsInstantNotify) {
                // 不需要即时通知 → 直接返回
                log.info("[afterEvent] 不需要即时通知: itemId={}, targetState={}", itemId, targetState);
                response.setSuccess(true);
                response.setActionType(needsScheduledNotify ? "仅定时通知" : "离开状态");
                return response;
            }

            // 需要即时通知 → 发送通知
            List<String> notifyFields = workflowConfigService.getNotifyFields(targetStateConfig);
            List<ItemInfoResponse.MemberInfo> members = getMembersByFields(itemInfo, notifyFields);

            if (members == null || members.isEmpty()) {
                log.warn("[afterEvent] 通知字段无成员: itemId={}, notifyFields={}", itemId, notifyFields);
                response.setSuccess(true);
                response.setActionType("无成员");
                return response;
            }

            // 格式化消息
            String messageContent = formatMessage(itemInfo, targetState, notifyFields, members, trackerType, projectId, trackerId);

            // 发送通知
            List<String> notifiedUsers = new ArrayList<>();
            List<String> failedUsers = new ArrayList<>();

            for (ItemInfoResponse.MemberInfo member : members) {
                String userid = member.getUserId();
                if (userid == null || userid.isEmpty()) {
                    continue;
                }

                try {
                    dingService.sendRobotMessage(userid, null, messageContent, MsgKeyConstant.SAMPLE_TEXT);
                    notifiedUsers.add(userid);
                    saveNotifyLog(itemId, userid, "即时", "成功");
                } catch (Exception e) {
                    log.error("[afterEvent] 发送失败: itemId={}, userid={}, error={}", itemId, userid, e.getMessage());
                    failedUsers.add(userid);
                    saveNotifyLog(itemId, userid, "即时", "失败: " + e.getMessage());
                }
            }

            // 返回响应
            response.setSuccess(true);
            response.setNotifiedUsers(notifiedUsers);
            response.setFailedUsers(failedUsers);
            response.setActionType("即时通知");

            log.info("[afterEvent] 即时通知完成: itemId={}, targetState={}, 成功{}人, 失败{}人, notifyFields={}, message=\n{}",
                    itemId, targetState, notifiedUsers.size(), failedUsers.size(), notifyFields, messageContent);

        } catch (Exception e) {
            log.error("[afterEvent] 处理异常: itemId={}, error={}", itemId, e.getMessage(), e);
            response.setErrorMessage("处理异常: " + e.getMessage());
        }

        return response;
    }

    /**
     * 格式化消息内容
     *
     * @param itemInfo 条目详情
     * @param targetState 目标状态
     * @param notifyFields 通知字段名称列表
     * @param members 通知成员列表
     * @param trackerType tracker类型名称
     * @param projectId 项目ID
     * @param trackerId tracker ID
     * @return 格式化后的消息内容
     */
    private String formatMessage(ItemInfoResponse itemInfo, String targetState,
                                  List<String> notifyFields, List<ItemInfoResponse.MemberInfo> members,
                                  String trackerType, Integer projectId, Integer trackerId) {
        // 1. 获取 type-mapping
        String trackerTypeDisplay = workflowConfigService.getTypeMapping(trackerType, projectId);

        // 2. 获取字段名称映射（从 tracker schema API）
        Map<String, String> fieldNameMapping = cbSwaggerService.getTrackerFieldNameMapping(trackerId);

        // 3. 按字段分组显示成员（每个字段单独一行）
        StringBuilder notifyFieldsContent = new StringBuilder();
        for (String field : notifyFields) {
            String fieldDisplayName = fieldNameMapping.getOrDefault(field, field);
            List<ItemInfoResponse.MemberInfo> fieldMembers = itemInfo.getMembersByField(field);
            if (fieldMembers != null && !fieldMembers.isEmpty()) {
                String memberNames = fieldMembers.stream()
                        .map(m -> {
                            String userId = m.getUserId();
                            if (userId != null && !userId.isEmpty()) {
                                String realName = dingService.getUserInfo(userId);
                                if (realName != null && !realName.isEmpty()) {
                                    return realName;
                                }
                            }
                            return m.getDisplayName() != null ? m.getDisplayName() : m.getName();
                        })
                        .collect(Collectors.joining(","));
                notifyFieldsContent.append(fieldDisplayName).append(": ").append(memberNames).append("\n");
            }
        }

        // 4. 获取 extra-fields 值
        List<ExtraField> extraFields = workflowConfigService.getExtraFields(projectId, trackerId);
        StringBuilder extraFieldsContent = new StringBuilder();
        for (ExtraField extraField : extraFields) {
            String fieldValue = getExtraFieldValue(itemInfo, extraField.getField(), fieldNameMapping);
            if (fieldValue != null && !fieldValue.isEmpty()) {
                extraFieldsContent.append(extraField.getLabel())
                        .append(": ")
                        .append(fieldValue)
                        .append("\n");
            }
        }

        // 5. 构建消息
        StringBuilder message = new StringBuilder();
        message.append(trackerTypeDisplay).append("名称: ").append(itemInfo.getName() != null ? itemInfo.getName() : "").append("\n");
        message.append(trackerTypeDisplay).append("状态: ").append(targetState != null ? targetState : "").append("，请您处理\n");

        // 6. 插入通知字段成员（每个字段单独一行）
        if (notifyFieldsContent.length() > 0) {
            message.append(notifyFieldsContent);
        }

        // 7. 插入 extra-fields
        if (extraFieldsContent.length() > 0) {
            message.append(extraFieldsContent);
        }

        // 8. 添加链接行
        message.append(trackerTypeDisplay).append("链接: ").append(itemInfo.getItemLink() != null ? itemInfo.getItemLink() : "");

        return message.toString();
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
                    // 无 userId 的成员无法去重，直接保留（避免被丢弃）
                    dedup.put("NO_USERID_" + System.identityHashCode(member), member);
                } else if (!dedup.containsKey(key)) {
                    dedup.put(key, member);
                }
            }
        }
        return new ArrayList<>(dedup.values());
    }

    /**
     * 获取额外字段值
     *
     * @param itemInfo 条目详情
     * @param fieldName 字段名称
     * @param fieldNameMapping 字段名称映射
     * @return 字段值，未找到返回null
     */
    private String getExtraFieldValue(ItemInfoResponse itemInfo, String fieldName, Map<String, String> fieldNameMapping) {
        if (itemInfo == null || fieldName == null) {
            return null;
        }

        // 1. 先检查CodeBeamer默认字段
        if ("priority".equals(fieldName) && itemInfo.getPriority() != null) {
            return itemInfo.getPriority().getName();
        }

        if ("categories".equals(fieldName) && itemInfo.getCategories() != null && !itemInfo.getCategories().isEmpty()) {
            return itemInfo.getCategories().stream()
                    .map(ItemInfoResponse.ChoiceOption::getName)
                    .collect(Collectors.joining(","));
        }

        if ("severities".equals(fieldName) && itemInfo.getSeverities() != null && !itemInfo.getSeverities().isEmpty()) {
            return itemInfo.getSeverities().stream()
                    .map(ItemInfoResponse.ChoiceOption::getName)
                    .collect(Collectors.joining(","));
        }

        if ("teams".equals(fieldName) && itemInfo.getTeams() != null && !itemInfo.getTeams().isEmpty()) {
            return itemInfo.getTeams().stream()
                    .map(ItemInfoResponse.ChoiceOption::getName)
                    .collect(Collectors.joining(","));
        }

        if ("versions".equals(fieldName) && itemInfo.getVersions() != null && !itemInfo.getVersions().isEmpty()) {
            return itemInfo.getVersions().stream()
                    .map(ItemInfoResponse.ChoiceOption::getName)
                    .collect(Collectors.joining(","));
        }

        if ("status".equals(fieldName)) {
            return itemInfo.getStatus();
        }

        // 2. 查找自定义字段（优先使用显示名称匹配）
        if (itemInfo.getCustomFields() != null) {
            // 先用映射后的显示名称匹配
            String fieldDisplayName = fieldNameMapping.getOrDefault(fieldName, fieldName);
            for (ItemInfoResponse.CustomField field : itemInfo.getCustomFields()) {
                // 匹配 label（显示名称）
                if (field.getLabel() != null && field.getLabel().equals(fieldDisplayName)) {
                    return extractFieldValue(field);
                }
                // 匹配 name（字段名）
                if (field.getName().equals(fieldName)) {
                    return extractFieldValue(field);
                }
            }
        }

        log.debug("extra-field字段未找到或值为空: itemId={}, fieldName={}", itemInfo.getId(), fieldName);
        return null;
    }

    /**
     * 提取字段值
     */
    private String extractFieldValue(ItemInfoResponse.CustomField field) {
        if (field.getValues() != null && !field.getValues().isEmpty()) {
            return field.getValues().stream()
                    .map(v -> v.getDisplayName() != null ? v.getDisplayName() : v.getName())
                    .collect(Collectors.joining(","));
        }
        if (field.getValue() != null && !field.getValue().isEmpty()) {
            return field.getValue();
        }
        return null;
    }

    /**
     * 保存条目状态记录
     */
    private void saveItemStateRecord(Integer itemId, String itemName,
                                      Integer trackerId, String trackerType, Integer projectId, String targetState) {
        ItemStateRecord record = new ItemStateRecord();
        record.setItemId(itemId);
        record.setItemName(itemName);
        record.setTrackerId(trackerId);
        record.setTrackerType(trackerType);
        record.setProjectId(projectId);
        record.setTargetState(targetState);
        record.setEnterStateTime(LocalDateTime.now());
        record.setLastNotifyTime(LocalDateTime.now());

        itemStateRecordMapper.insert(record);
        log.debug("保存状态记录: itemId={}, trackerType={}", itemId, trackerType);
    }

    /**
     * 保存通知发送日志
     */
    private void saveNotifyLog(Integer itemId, String userid, String notifyType, String sendResult) {
        NotifyLog logEntry = new NotifyLog();
        logEntry.setItemId(itemId);
        logEntry.setSendTime(LocalDateTime.now());
        logEntry.setReceiverUserid(userid);
        logEntry.setNotifyType(notifyType);
        logEntry.setSendResult(sendResult);

        notifyLogMapper.insert(logEntry);
    }
}