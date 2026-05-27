package org.example.db.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 通知发送日志实体类
 *
 * 对应数据库表 notify_log，用于记录每次钉钉通知发送的结果。
 * 包括即时通知和定时通知的发送记录，便于追踪和排查问题。
 *
 * @author system
 * @since 1.0
 */
@Data
public class NotifyLog {

    /** 主键ID，自增 */
    private Long id;

    /** Codebeamer条目ID，关联的通知对象 */
    private Integer itemId;

    /** 通知发送时间 */
    private LocalDateTime sendTime;

    /** 接收者的钉钉userid */
    private String receiverUserid;

    /** 通知类型：即时 或 定时 */
    private String notifyType;

    /** 发送结果：成功 或 失败:错误原因 */
    private String sendResult;
}