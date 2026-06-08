package org.example.workflow.dto;

import lombok.Data;

/**
 * 查询notifyField响应DTO
 *
 * 用于返回目标状态的通知字段配置信息，
 * 供Groovy脚本在beforeEvent阶段调用。
 *
 * @author system
 * @since 1.0
 */
@Data
public class NotifyFieldResponse {

    /** 目标状态是否需要通知（true表示需要校验成员字段） */
    private boolean needsNotify;

    /** 通知字段名称（如 assignedTo、submitter、自定义字段名） */
    private String notifyField;

    /** 工作流名称（用于日志记录） */
    private String workflowName;

    /** 错误信息（状态未声明时返回） */
    private String errorMessage;
}