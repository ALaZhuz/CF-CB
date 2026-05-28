package org.example.workflow.dto;

import lombok.Data;
import java.util.List;

/**
 * 校验响应DTO
 *
 * 用于beforeEvent校验接口的返回结果，
 * 告知Codebeamer Groovy脚本是否允许保存。
 *
 * @author system
 * @since 1.0
 */
@Data
public class ValidateResponse {

    /** 校验是否成功 */
    private boolean success;

    /** 错误信息，成功时为空 */
    private String errorMessage;

    /** 通知字段名称（用于日志记录） */
    private String notifyField;

    /** 需要通知的成员列表（用于日志记录） */
    private List<String> notifyMembers;

    /** 无效的userid列表（校验失败时返回） */
    private List<String> invalidUserIds;
}