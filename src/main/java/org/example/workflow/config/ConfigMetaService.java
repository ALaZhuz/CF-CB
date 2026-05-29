package org.example.workflow.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.db.entity.ConfigMeta;
import org.example.db.mapper.ConfigMetaMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 配置元数据服务类
 *
 * 管理初始化状态和YAML配置变更检测。
 *
 * @author system
 * @since 1.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConfigMetaService {

    private final ConfigMetaMapper configMetaMapper;

    @Value("${spring.config.import:classpath:workflow-config.yml}")
    private String configImport;

    /**
     * 检查是否已初始化
     *
     * @return true表示已初始化
     */
    public boolean checkInitialized() {
        return configMetaMapper.isInitialized();
    }

    /**
     * 标记初始化完成
     */
    public void markInitialized() {
        configMetaMapper.updateInitialized(true);
        log.info("初始化状态已标记为完成");
    }

    /**
     * 检查YAML配置是否变更
     *
     * 对比 workflow-config.yml 文件修改时间与数据库记录。
     *
     * @return true表示配置有变更
     */
    public boolean checkYamlModified() {
        try {
            // 获取YAML文件修改时间
            LocalDateTime fileModifiedTime = getYamlFileModifiedTime();
            if (fileModifiedTime == null) {
                return false;
            }

            // 获取数据库记录
            ConfigMeta configMeta = configMetaMapper.select();
            if (configMeta == null || configMeta.getYamlModifiedTime() == null) {
                // 首次运行，记录文件时间
                updateYamlLoadedTime(fileModifiedTime, LocalDateTime.now());
                return false;
            }

            // 对比时间
            if (fileModifiedTime.isAfter(configMeta.getYamlModifiedTime())) {
                log.info("YAML配置文件已变更, fileTime={}, dbTime={}",
                        fileModifiedTime, configMeta.getYamlModifiedTime());
                return true;
            }

            return false;

        } catch (Exception e) {
            log.error("检查YAML变更失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 获取YAML文件修改时间
     *
     * @return 文件修改时间
     */
    private LocalDateTime getYamlFileModifiedTime() {
        try {
            // workflow-config.yml 文件路径
            Path yamlPath = Path.of("src/main/resources/workflow-config.yml");
            if (!Files.exists(yamlPath)) {
                // 尝试其他路径
                yamlPath = Path.of("workflow-config.yml");
            }

            if (Files.exists(yamlPath)) {
                BasicFileAttributes attrs = Files.readAttributes(yamlPath, BasicFileAttributes.class);
                return LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault());
            }

            log.warn("YAML配置文件不存在");
            return null;

        } catch (Exception e) {
            log.error("获取YAML文件修改时间失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 更新YAML修改时间和加载时间
     *
     * @param yamlModifiedTime YAML文件修改时间
     * @param lastLoadedTime 配置加载时间
     */
    public void updateYamlLoadedTime(LocalDateTime yamlModifiedTime, LocalDateTime lastLoadedTime) {
        configMetaMapper.updateYamlTime(yamlModifiedTime, lastLoadedTime);
        log.debug("更新YAML时间记录: yamlModifiedTime={}, lastLoadedTime={}",
                yamlModifiedTime, lastLoadedTime);
    }

    /**
     * 仅更新配置加载时间
     *
     * @param lastLoadedTime 配置加载时间
     */
    public void updateLastLoadedTime(LocalDateTime lastLoadedTime) {
        configMetaMapper.updateLastLoadedTime(lastLoadedTime);
    }

    /**
     * 重置初始化标记
     *
     * 用于手动触发重新初始化
     */
    public void resetInitialized() {
        configMetaMapper.updateInitialized(false);
        log.info("初始化状态已重置");
    }
}