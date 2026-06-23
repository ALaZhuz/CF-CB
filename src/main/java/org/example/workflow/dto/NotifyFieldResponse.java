package org.example.workflow.dto;

import lombok.Data;
import java.util.List;

/**
 * 查询notifyField响应DTO
 *
 * 用于返回目标状态的通知字段配置信息，
 * 供Groovy脚本在beforeEvent阶段调用。
 *
 * 多字段通知支持（2026-06-22）：
 * - notifyField: 逗号分隔字符串（向后兼容）
 * - notifyFields: 列表形式（新增，供Groovy脚本使用）
 *
 * @author system
 * @since 1.0
 */
@Data
public class NotifyFieldResponse {

    /** 目标状态是否需要通知（true表示需要校验成员字段） */
    private boolean needsNotify;

    /** 通知字段名称（逗号分隔，向后兼容单字段场景） */
    private String notifyField;

    /** 所有通知字段列表（新增，供Groovy脚本遍历多字段） */
    private List<String> notifyFields;

    /** 工作流名称（用于日志记录） */
    private String workflowName;

    /** 错误信息（状态未声明时返回） */
    private String errorMessage;
}