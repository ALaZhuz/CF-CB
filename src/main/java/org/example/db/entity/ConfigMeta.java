package org.example.db.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 配置元数据实体类
 *
 * 对应数据库表 config_meta，用于记录初始化状态、YAML配置变更检测、配置存储。
 *
 * @author system
 * @since 1.0
 */
@Data
public class ConfigMeta {

    /** 主键ID */
    private Long id;

    /** 是否已完成初始化 */
    private Boolean initialized;

    /** YAML 文件内容（用于热更新） */
    private String yamlContent;

    /** YAML文件最后修改时间 */
    private LocalDateTime yamlModifiedTime;

    /** 配置最后加载时间 */
    private LocalDateTime lastLoadedTime;

    /** 更新人 */
    private String updatedBy;
}