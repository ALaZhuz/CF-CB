package org.example.db.mapper;

import org.apache.ibatis.annotations.*;
import org.example.db.entity.ItemStateRecord;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 条目状态记录Mapper接口
 *
 * 提供对 item_state_record 表的CRUD操作，
 * 用于即时通知和定时通知的状态记录管理。
 *
 * @author system
 * @since 1.0
 */
@Mapper
public interface ItemStateRecordMapper {

    /**
     * 插入或替换条目状态记录
     *
     * 当条目进入目标状态时调用。
     * 如果item_id已存在，则替换旧记录（SQLite UPSERT）。
     *
     * @param record 条目状态记录实体
     */
    @Insert("INSERT OR REPLACE INTO item_state_record (item_id, item_name, tracker_id, tracker_type, project_id, target_state, enter_state_time, last_notify_time) " +
            "VALUES (#{itemId}, #{itemName}, #{trackerId}, #{trackerType}, #{projectId}, #{targetState}, #{enterStateTime}, #{lastNotifyTime})")
    void insert(ItemStateRecord record);

    /**
     * 根据条目ID删除状态记录
     *
     * 当条目离开目标状态时调用，删除对应的状态记录。
     *
     * @param itemId Codebeamer条目ID
     */
    @Delete("DELETE FROM item_state_record WHERE item_id = #{itemId}")
    void deleteByItemId(@Param("itemId") Integer itemId);

    /**
     * 根据条目ID查询状态记录
     *
     * 用于判断条目是否在目标状态中。
     *
     * @param itemId Codebeamer条目ID
     * @return 条目状态记录，不存在则返回null
     */
    @Select("SELECT * FROM item_state_record WHERE item_id = #{itemId}")
    ItemStateRecord selectByItemId(@Param("itemId") Integer itemId);

    /**
     * 查询所有条目状态记录
     *
     * 用于定时通知调度器获取所有需要处理的条目。
     *
     * @return 所有条目状态记录列表
     */
    @Select("SELECT * FROM item_state_record")
    List<ItemStateRecord> selectAll();

    /**
     * 更新上次通知时间
     *
     * 发送通知成功后调用，更新last_notify_time字段。
     *
     * @param itemId Codebeamer条目ID
     * @param lastNotifyTime 上次通知时间
     */
    @Update("UPDATE item_state_record SET last_notify_time = #{lastNotifyTime} WHERE item_id = #{itemId}")
    void updateLastNotifyTime(@Param("itemId") Integer itemId, @Param("lastNotifyTime") LocalDateTime lastNotifyTime);

    /**
     * 查询项目下所有条目状态记录
     *
     * 用于全量同步时获取本地数据库中该项目的所有记录，
     * 与Codebeamer查询结果进行对比。
     *
     * @param projectId 项目ID
     * @return 项目下所有条目状态记录列表
     */
    @Select("SELECT * FROM item_state_record WHERE project_id = #{projectId}")
    List<ItemStateRecord> selectByProjectId(@Param("projectId") Integer projectId);

    /**
     * 更新条目的目标状态和进入时间
     *
     * 用于全量同步时处理状态不一致场景：
     * 当本地记录的状态与Codebeamer实际状态不一致，
     * 且新状态配置了定时通知时，更新本地记录。
     *
     * @param itemId 条目ID
     * @param targetState 新的目标状态
     * @param enterStateTime 进入新状态的时间（从history API获取）
     */
    @Update("UPDATE item_state_record SET target_state = #{targetState}, enter_state_time = #{enterStateTime} WHERE item_id = #{itemId}")
    void updateState(@Param("itemId") Integer itemId, @Param("targetState") String targetState, @Param("enterStateTime") LocalDateTime enterStateTime);

    /**
     * 更新条目名称
     *
     * 用于定时通知时同步最新的条目名称到数据库。
     * 当条目在同一个状态下名称被修改时，需要更新数据库中的记录。
     *
     * @param itemId 条目ID
     * @param itemName 新的条目名称
     */
    @Update("UPDATE item_state_record SET item_name = #{itemName} WHERE item_id = #{itemId}")
    void updateItemName(@Param("itemId") Integer itemId, @Param("itemName") String itemName);
}