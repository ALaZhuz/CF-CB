package org.example.workflow.dto;

import lombok.Data;

import java.util.List;

/**
 * 校验请求DTO
 *
 * 用于beforeEvent校验接口的请求参数，
 * 由Codebeamer Groovy脚本在保存条目前调用。
 *
 * @author system
 * @since 1.0
 */
@Data
public class ValidateRequest {

    /** 条目ID（新建时为null） */
    private Integer itemId;

    /** 目标状态名称 */
    private String targetState;

    /** Tracker ID（可选，用于加速配置查询，新建时必填） */
    private Integer trackerId;

    /** Tracker名称（可选，用于匹配规则） */
    private String trackerName;

    /** Tracker类型（可选，用于匹配规则） */
    private String trackerType;

    /** 项目ID（可选，用于查找项目配置） */
    private Integer projectId;

    /** 通知字段名称（可选，新建时由Groovy脚本传递） */
    private String notifyField;

    /** 通知成员userid列表（可选，新建时由Groovy脚本传递） */
    private List<String> notifyUserIds;

    /** 通知成员名称列表（可选，用于日志记录） */
    private List<String> notifyMemberNames;
}