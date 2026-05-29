package org.example.db.mapper;

import org.apache.ibatis.annotations.*;
import org.example.db.entity.ConfigMeta;
import java.time.LocalDateTime;

/**
 * 配置元数据Mapper接口
 *
 * 提供对 config_meta 表的读写操作，
 * 用于管理初始化状态和YAML配置变更检测。
 *
 * @author system
 * @since 1.0
 */
@Mapper
public interface ConfigMetaMapper {

    /**
     * 查询配置元数据
     *
     * config_meta 表只有一条记录（id=1）
     *
     * @return 配置元数据实体
     */
    @Select("SELECT id, initialized, yaml_modified_time as yamlModifiedTime, " +
            "last_loaded_time as lastLoadedTime FROM config_meta WHERE id = 1")
    ConfigMeta select();

    /**
     * 更新初始化状态
     *
     * @param initialized 是否已初始化
     */
    @Update("UPDATE config_meta SET initialized = #{initialized} WHERE id = 1")
    void updateInitialized(@Param("initialized") boolean initialized);

    /**
     * 更新YAML修改时间和加载时间
     *
     * @param yamlModifiedTime YAML文件修改时间
     * @param lastLoadedTime 配置加载时间
     */
    @Update("UPDATE config_meta SET yaml_modified_time = #{yamlModifiedTime}, " +
            "last_loaded_time = #{lastLoadedTime} WHERE id = 1")
    void updateYamlTime(@Param("yamlModifiedTime") LocalDateTime yamlModifiedTime,
                         @Param("lastLoadedTime") LocalDateTime lastLoadedTime);

    /**
     * 仅更新配置加载时间
     *
     * @param lastLoadedTime 配置加载时间
     */
    @Update("UPDATE config_meta SET last_loaded_time = #{lastLoadedTime} WHERE id = 1")
    void updateLastLoadedTime(@Param("lastLoadedTime") LocalDateTime lastLoadedTime);

    /**
     * 检查是否已初始化
     *
     * @return true表示已初始化
     */
    @Select("SELECT initialized FROM config_meta WHERE id = 1")
    boolean isInitialized();
}