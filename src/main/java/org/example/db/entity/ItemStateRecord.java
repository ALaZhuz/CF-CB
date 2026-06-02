package org.example.db.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 条目状态记录实体类
 *
 * 对应数据库表 item_state_record，用于记录进入目标状态的条目信息。
 * 当条目进入配置了即时通知的状态时，系统会创建此记录，
 * 供后续的定时通知调度器查询和处理。
 *
 * @author system
 * @since 1.0
 */
@Data
public class ItemStateRecord {

    /** 主键ID，自增 */
    private Long id;

    /** Codebeamer条目ID，唯一标识一个tracker item */
    private Integer itemId;

    /** 条目名称/标题 */
    private String itemName;

    /** Tracker ID，条目所属的跟踪器 */
    private Integer trackerId;

    /** Tracker 类型名称（如 Bug、Requirement），用于定时通知分类匹配 */
    private String trackerType;

    /** 项目ID，条目所属的项目 */
    private Integer projectId;

    /** 目标状态名称，条目当前所处的需要通知的状态 */
    private String targetState;

    /** 进入目标状态的时间，用于计算临期和超期 */
    private LocalDateTime enterStateTime;

    /** 上次发送通知的时间，用于定时通知的频率控制 */
    private LocalDateTime lastNotifyTime;
}