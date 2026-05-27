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

            // 3. 检查转换前状态是否在表1有记录（离开目标状态）
            ItemStateRecord existingRecord = itemStateRecordMapper.selectByItemId(itemId);

            if (existingRecord != null && existingRecord.getTargetState().equals(previousState)) {
                // 离开目标状态，删除记录
                itemStateRecordMapper.deleteByItemId(itemId);
                log.info("离开目标状态，删除状态记录: itemId={}, previousState={}", itemId, previousState);
                response.setSuccess(true);
                response.setActionType("离开状态");
                return response;
            }

            // 4. 获取目标状态配置
            WorkflowTemplate.StateConfig targetStateConfig = workflowConfigService.getStateConfig(workflow, targetState);

            // 5. 判断目标状态是否需要通知
            if (!workflowConfigService.shouldNotify(targetStateConfig)) {
                log.info("目标状态不需要通知，不做处理: itemId={}, targetState={}", itemId, targetState);
                response.setSuccess(true);
                response.setActionType("无需处理");
                return response;
            }

            // 6. 进入目标状态：发送通知
            String notifyField = targetStateConfig.getNotifyField();
            List<ItemInfoResponse.MemberInfo> members = itemInfo.getMembersByField(notifyField);

            if (members == null || members.isEmpty()) {
                log.warn("通知字段无成员，跳过通知: itemId={}, notifyField={}", itemId, notifyField);
                response.setSuccess(true);
                response.setActionType("无需通知");
                return response;
            }

            // 7. 格式化消息内容（使用固定模板 + type-mappings + extra-fields）
            String messageContent = formatMessage(itemInfo, targetState, notifyField, members, trackerType, projectId);

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
     * @return 格式化后的消息内容
     */
    private String formatMessage(ItemInfoResponse itemInfo, String targetState,
                                  String notifyField, List<ItemInfoResponse.MemberInfo> members,
                                  String trackerType, Integer projectId) {
        // 1. 获取 type-mapping
        String trackerTypeDisplay = workflowConfigService.getTypeMapping(trackerType, projectId);

        // 2. 获取通知成员显示名列表
        String notifyMembersStr = members.stream()
                .map(m -> m.getDisplayName() != null ? m.getDisplayName() : m.getName())
                .collect(Collectors.joining(","));

        // 3. 获取 extra-fields 值
        List<ExtraField> extraFields = workflowConfigService.getExtraFields(projectId);
        StringBuilder extraFieldsContent = new StringBuilder();
        for (ExtraField extraField : extraFields) {
            String fieldValue = getExtraFieldValue(itemInfo, extraField.getField());
            if (fieldValue != null && !fieldValue.isEmpty()) {
                extraFieldsContent.append(extraField.getLabel())
                        .append(": ")
                        .append(fieldValue)
                        .append("\n");
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
     *
     * @param itemInfo 条目详情
     * @param fieldName 字段名称
     * @return 字段值，未找到返回null
     */
    private String getExtraFieldValue(ItemInfoResponse itemInfo, String fieldName) {
        if (itemInfo == null || fieldName == null) {
            return null;
        }

        // 查找自定义字段
        if (itemInfo.getCustomFields() != null) {
            for (ItemInfoResponse.CustomField field : itemInfo.getCustomFields()) {
                if (field.getName().equals(fieldName) ||
                        (field.getLabel() != null && field.getLabel().equals(fieldName))) {
                    if (field.getValues() != null && !field.getValues().isEmpty()) {
                        // 如果是成员类型字段，显示名称列表
                        return field.getValues().stream()
                                .map(v -> v.getDisplayName() != null ? v.getDisplayName() : v.getName())
                                .collect(Collectors.joining(","));
                    }
                    // 其他类型字段暂不支持，返回null
                    return null;
                }
            }
        }

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