package org.example.db.mapper;

import org.apache.ibatis.annotations.*;
import org.example.db.entity.OrgCache;
import java.util.List;

/**
 * 组织架构缓存Mapper接口
 *
 * 提供对 org_cache 表的读写操作，
 * 用于缓存和查询员工与科长/部长的关系。
 *
 * @author system
 * @since 1.0
 */
@Mapper
public interface OrgCacheMapper {

    /**
     * 插入或替换组织架构缓存
     *
     * @param cache 组织架构缓存实体
     */
    @Insert("INSERT OR REPLACE INTO org_cache (userid, manager_userid, director_userid, dept_id, last_sync_time) " +
            "VALUES (#{userid}, #{managerUserid}, #{directorUserid}, #{deptId}, #{lastSyncTime})")
    void insert(OrgCache cache);

    /**
     * 根据userid查询组织架构缓存
     *
     * @param userid 员工钉钉 userid
     * @return 组织架构缓存，不存在返回null
     */
    @Select("SELECT userid, manager_userid as managerUserid, director_userid as directorUserid, " +
            "dept_id as deptId, last_sync_time as lastSyncTime FROM org_cache WHERE userid = #{userid}")
    OrgCache selectByUserid(@Param("userid") String userid);

    /**
     * 查询所有组织架构缓存
     *
     * @return 所有缓存记录列表
     */
    @Select("SELECT userid, manager_userid as managerUserid, director_userid as directorUserid, " +
            "dept_id as deptId, last_sync_time as lastSyncTime FROM org_cache")
    List<OrgCache> selectAll();

    /**
     * 删除所有组织架构缓存
     *
     * 用于全量刷新前清空旧数据
     */
    @Delete("DELETE FROM org_cache")
    void deleteAll();

    /**
     * 根据userid删除缓存
     *
     * @param userid 员工钉钉 userid
     */
    @Delete("DELETE FROM org_cache WHERE userid = #{userid}")
    void deleteByUserid(@Param("userid") String userid);

    /**
     * 获取缓存记录数
     *
     * @return 缓存记录总数
     */
    @Select("SELECT COUNT(*) FROM org_cache")
    int count();
}