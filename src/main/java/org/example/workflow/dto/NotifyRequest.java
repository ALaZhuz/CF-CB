package org.example.workflow.dto;

import lombok.Data;

/**
 * 通知请求DTO
 *
 * 用于afterEvent通知接口的请求参数，
 * 由Codebeamer Groovy脚本在条目保存成功后调用。
 *
 * @author system
 * @since 1.0
 */
@Data
public class NotifyRequest {

    /** 条目ID */
    private Integer itemId;

    /** 转换前的状态名称 */
    private String previousState;

    /** 目标状态名称（转换后的状态） */
    private String targetState;

    /** Tracker ID（可选，用于加速配置查询） */
    private Integer trackerId;

    /** Tracker名称（可选） */
    private String trackerName;

    /** Tracker类型（可选） */
    private String trackerType;

    /** 项目ID（可选） */
    private Integer projectId;
}