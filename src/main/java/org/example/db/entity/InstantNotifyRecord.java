package org.example.db.entity;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 批量即时通知记录实体类
 *
 * 对应数据库表 instant_notify_record，用于记录当天需要批量通知的tracker-state组合。
 *
 * 存储粒度：tracker-state-通知人级别
 * 发送粒度：Tracker级别（按Project→Tracker→notify_userid分组）
 * 发送内容：tracker链接（不需要列出条目）
 *
 * UNIQUE(tracker_id, target_state, notify_userid, notify_date)
 * 同一个tracker、同一个状态、同一个通知人、同一天只通知一次。
 *
 * 批量通知只在当天有效，第二天00:00清空表。
 *
 * @author system
 * @since 1.0
 */
@Data
public class InstantNotifyRecord {

    /** 主键ID，自增 */
    private Long id;

    /** Tracker ID */
    private Integer trackerId;

    /** Tracker类型名称（如 Bug、Requirement） */
    private String trackerType;

    /** 项目ID */
    private Integer projectId;

    /** 目标状态名称 */
    private String targetState;

    /** 通知人的钉钉userid */
    private String notifyUserid;

    /** 通知日期（YYYY-MM-DD），用于当天去重和第二天清理 */
    private LocalDate notifyDate;

    /** 实际发送通知的时间（发送成功后记录） */
    private LocalDateTime notifyTime;

    /** 是否已成功发送通知 */
    private Boolean notifySuccess;
}