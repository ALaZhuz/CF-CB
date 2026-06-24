package org.example.db.mapper;

import org.apache.ibatis.annotations.*;
import org.example.db.entity.InstantNotifyRecord;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量即时通知记录Mapper接口
 *
 * 提供对 instant_notify_record 表的CRUD操作，
 * 用于批量即时通知的记录管理和去重。
 *
 * @author system
 * @since 1.0
 */
@Mapper
public interface InstantNotifyRecordMapper {

    /**
     * 插入或忽略批量通知记录
     *
     * 当条目进入配置了batchNotifyField的状态时调用。
     * 如果已存在相同tracker-state-userid-date的记录，则忽略（SQLite INSERT OR IGNORE）。
     *
     * @param record 批量通知记录实体
     */
    @Insert("INSERT OR IGNORE INTO instant_notify_record (tracker_id, tracker_type, project_id, target_state, notify_userid, notify_date, notify_success) " +
            "VALUES (#{trackerId}, #{trackerType}, #{projectId}, #{targetState}, #{notifyUserid}, #{notifyDate}, #{notifySuccess})")
    void insert(InstantNotifyRecord record);

    /**
     * 查询指定tracker-state-userid-date的记录
     *
     * 用于判断是否已存在记录，避免重复插入。
     *
     * @param trackerId tracker ID
     * @param targetState 目标状态
     * @param notifyUserid 通知人userid
     * @param notifyDate 通知日期
     * @return 批量通知记录，不存在则返回null
     */
    @Select("SELECT * FROM instant_notify_record WHERE tracker_id = #{trackerId} AND target_state = #{targetState} AND notify_userid = #{notifyUserid} AND notify_date = #{notifyDate}")
    InstantNotifyRecord selectByTrackerStateUseridDate(@Param("trackerId") Integer trackerId,
                                                        @Param("targetState") String targetState,
                                                        @Param("notifyUserid") String notifyUserid,
                                                        @Param("notifyDate") LocalDate notifyDate);

    /**
     * 查询当天未发送的记录
     *
     * 用于轮询批量通知时获取待发送的记录。
     *
     * @param notifyDate 通知日期
     * @return 未发送的记录列表
     */
    @Select("SELECT * FROM instant_notify_record WHERE notify_date = #{notifyDate} AND notify_success = FALSE")
    List<InstantNotifyRecord> selectPendingByDate(@Param("notifyDate") LocalDate notifyDate);

    /**
     * 更新通知发送状态
     *
     * 发送成功后调用，更新notify_time和notify_success。
     *
     * @param trackerId tracker ID
     * @param targetState 目标状态
     * @param notifyUserid 通知人userid
     * @param notifyDate 通知日期
     * @param notifyTime 实际发送时间
     */
    @Update("UPDATE instant_notify_record SET notify_time = #{notifyTime}, notify_success = TRUE " +
            "WHERE tracker_id = #{trackerId} AND target_state = #{targetState} AND notify_userid = #{notifyUserid} AND notify_date = #{notifyDate}")
    void updateNotifySuccess(@Param("trackerId") Integer trackerId,
                             @Param("targetState") String targetState,
                             @Param("notifyUserid") String notifyUserid,
                             @Param("notifyDate") LocalDate notifyDate,
                             @Param("notifyTime") LocalDateTime notifyTime);

    /**
     * 清空所有记录
     *
     * 每天00:00调用，清空前一天的所有记录。
     * 批量通知只在当天有效，第二天失效。
     */
    @Delete("DELETE FROM instant_notify_record")
    void deleteAll();

    /**
     * 查询所有记录
     *
     * 用于验证清空后表中数据是否为0。
     *
     * @return 所有记录列表
     */
    @Select("SELECT * FROM instant_notify_record")
    List<InstantNotifyRecord> selectAll();
}