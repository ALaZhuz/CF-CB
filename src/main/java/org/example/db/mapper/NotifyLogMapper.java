package org.example.db.mapper;

import org.apache.ibatis.annotations.*;
import org.example.db.entity.NotifyLog;
import java.util.List;

/**
 * 通知发送日志Mapper接口
 *
 * 提供对 notify_log 表的写入和查询操作，
 * 用于记录和查询钉钉通知发送的历史记录。
 *
 * @author system
 * @since 1.0
 */
@Mapper
public interface NotifyLogMapper {

    /**
     * 插入通知发送日志
     *
     * 每次发送通知后调用，记录发送结果。
     *
     * @param log 通知日志实体
     */
    @Insert("INSERT INTO notify_log (item_id, send_time, receiver_userid, notify_type, send_result) " +
            "VALUES (#{itemId}, #{sendTime}, #{receiverUserid}, #{notifyType}, #{sendResult})")
    void insert(NotifyLog log);

    /**
     * 根据条目ID查询所有通知日志
     *
     * 查询某个条目的所有通知发送记录，按发送时间倒序排列。
     *
     * @param itemId Codebeamer条目ID
     * @return 通知日志列表
     */
    @Select("SELECT * FROM notify_log WHERE item_id = #{itemId} ORDER BY send_time DESC")
    List<NotifyLog> selectByItemId(@Param("itemId") Integer itemId);

    /**
     * 根据条目ID和用户ID查询最近一条通知日志
     *
     * 用于判断某个用户是否已经收到过该条目的通知。
     *
     * @param itemId Codebeamer条目ID
     * @param userid 接收者的钉钉userid
     * @return 最近一条通知日志，不存在则返回null
     */
    @Select("SELECT * FROM notify_log WHERE item_id = #{itemId} AND receiver_userid = #{userid} ORDER BY send_time DESC LIMIT 1")
    NotifyLog selectLatestByItemIdAndUserid(@Param("itemId") Integer itemId, @Param("userid") String userid);
}