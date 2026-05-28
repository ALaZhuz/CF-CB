package org.example.workflow.dto;

import lombok.Data;
import java.util.List;

/**
 * 通知响应DTO
 *
 * 用于afterEvent通知接口的返回结果，
 * 告知Codebeamer Groovy脚本通知发送情况。
 *
 * @author system
 * @since 1.0
 */
@Data
public class NotifyResponse {

    /** 处理是否成功 */
    private boolean success;

    /** 错误信息，成功时为空 */
    private String errorMessage;

    /** 已通知的用户列表 */
    private List<String> notifiedUsers;

    /** 发送失败的用户列表 */
    private List<String> failedUsers;

    /** 处理类型：进入状态/离开状态/无需处理 */
    private String actionType;
}