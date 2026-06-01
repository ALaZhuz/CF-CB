package org.example.workflow.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.db.entity.ConfigMeta;
import org.example.db.mapper.ConfigMetaMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.PostConstruct;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Map;

/**
 * 配置元数据服务类
 *
 * 管理初始化状态、YAML配置变更检测、配置存储。
 * 支持配置热更新：从数据库读取配置，检测变更时自动重新加载。
 *
 * @author system
 * @since 1.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConfigMetaService {

    private final ConfigMetaMapper configMetaMapper;
    private final WorkflowProperties workflowProperties;

    @Value("${spring.config.import:classpath:workflow-config.yml}")
    private String configImport;

    /**
     * 服务启动时同步配置到数据库
     *
     * 如果数据库中没有配置，从 YAML 文件读取并存入数据库。
     */
    @PostConstruct
    public void syncConfigToDatabase() {
        try {
            ConfigMeta configMeta = configMetaMapper.select();

            if (configMeta == null || configMeta.getYamlContent() == null) {
                log.info("数据库中无配置，从 YAML 文件同步...");

                Path yamlPath = getYamlFilePath();
                if (yamlPath != null && Files.exists(yamlPath)) {
                    String yamlContent = Files.readString(yamlPath);
                    LocalDateTime fileModifiedTime = getYamlFileModifiedTime();

                    configMetaMapper.updateYamlContent(yamlContent, fileModifiedTime, LocalDateTime.now(), "system");
                    log.info("配置已同步到数据库");
                }
            } else {
                log.info("数据库已有配置，跳过同步");
            }
        } catch (Exception e) {
            log.warn("同步配置到数据库失败: {}", e.getMessage());
        }
    }

    /**
     * 检查是否已初始化
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
     */
    public boolean checkYamlModified() {
        try {
            LocalDateTime fileModifiedTime = getYamlFileModifiedTime();
            if (fileModifiedTime == null) {
                return false;
            }

            ConfigMeta configMeta = configMetaMapper.select();
            if (configMeta == null || configMeta.getYamlModifiedTime() == null) {
                return false;
            }

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
     * 重新加载YAML配置（热更新）
     *
     * 从 YAML 文件重新读取配置，存入数据库，并更新 WorkflowProperties。
     */
    public void reloadYamlConfig() {
        try {
            Path yamlPath = getYamlFilePath();
            if (yamlPath == null || !Files.exists(yamlPath)) {
                log.warn("YAML配置文件不存在，尝试从数据库加载");
                loadConfigFromDatabase();
                return;
            }

            log.info("开始重新加载YAML配置: {}", yamlPath);

            String yamlContent = Files.readString(yamlPath);
            LocalDateTime fileModifiedTime = getYamlFileModifiedTime();
            configMetaMapper.updateYamlContent(yamlContent, fileModifiedTime, LocalDateTime.now(), "auto-reload");

            // 使用 Binder 更新配置
            Yaml yaml = new Yaml();
            Map<String, Object> configMap = yaml.load(new FileInputStream(yamlPath.toFile()));
            bindConfigToProperties(configMap);

            log.info("YAML配置热更新完成");

        } catch (Exception e) {
            log.error("YAML配置热更新失败: {}", e.getMessage(), e);
            loadConfigFromDatabase();
        }
    }

    /**
     * 从数据库加载配置（热更新）
     */
    public void loadConfigFromDatabase() {
        try {
            String yamlContent = configMetaMapper.selectYamlContent();
            if (yamlContent == null || yamlContent.isEmpty()) {
                log.warn("数据库中无配置内容");
                return;
            }

            log.info("从数据库加载配置");

            Yaml yaml = new Yaml();
            Map<String, Object> configMap = yaml.load(yamlContent);
            bindConfigToProperties(configMap);

            log.info("数据库配置加载完成");

        } catch (Exception e) {
            log.error("从数据库加载配置失败: {}", e.getMessage());
        }
    }

    /**
     * 使用 Spring Binder 将配置绑定到 WorkflowProperties
     *
     * @param configMap YAML 解析后的配置 Map
     */
    private void bindConfigToProperties(Map<String, Object> configMap) {
        try {
            // 创建配置源
            MapConfigurationPropertySource source = new MapConfigurationPropertySource(configMap);
            Binder binder = new Binder(source);

            // 直接绑定到 WorkflowProperties 的各个字段
            // Binder 会自动更新对象内部的值

            // 绑定 classify-config
            binder.bind("classify-config",
                Bindable.of(WorkflowProperties.ClassifyConfigConfig.class)
                    .withExistingValue(workflowProperties.getClassifyConfig()));
            log.debug("classify-config 已更新");

            // 绑定 type-mappings
            binder.bind("type-mappings",
                Bindable.of(WorkflowProperties.TypeMappingsConfig.class)
                    .withExistingValue(workflowProperties.getTypeMappings()));
            log.debug("type-mappings 已更新");

            // 绑定 extra-fields
            binder.bind("extra-fields",
                Bindable.of(WorkflowProperties.ExtraFieldsConfig.class)
                    .withExistingValue(workflowProperties.getExtraFields()));
            log.debug("extra-fields 已更新");

            // 绑定 dingtalk
            binder.bind("dingtalk",
                Bindable.of(WorkflowProperties.DingtalkConfig.class)
                    .withExistingValue(workflowProperties.getDingtalk()));
            log.debug("dingtalk 已更新");

            // 绑定 global-workflows（列表）
            binder.bind("global-workflows",
                Bindable.ofInstance(workflowProperties.getGlobalWorkflows()));
            log.debug("global-workflows 已更新");

            // 绑定 projects（列表，支持新增/删除）
            // 先清空再绑定，确保新增/删除生效
            workflowProperties.getProjects().clear();
            binder.bind("projects",
                Bindable.ofInstance(workflowProperties.getProjects()));
            log.info("projects 配置已更新（支持新增/删除项目热更新）");

            log.info("WorkflowProperties 热更新完成");

        } catch (Exception e) {
            log.error("绑定配置到 WorkflowProperties 失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取YAML文件路径
     */
    private Path getYamlFilePath() {
        Path yamlPath = Path.of("src/main/resources/workflow-config.yml");
        if (Files.exists(yamlPath)) {
            return yamlPath;
        }

        yamlPath = Path.of("workflow-config.yml");
        if (Files.exists(yamlPath)) {
            return yamlPath;
        }

        yamlPath = Path.of("config/workflow-config.yml");
        if (Files.exists(yamlPath)) {
            return yamlPath;
        }

        return null;
    }

    /**
     * 获取YAML文件修改时间
     */
    private LocalDateTime getYamlFileModifiedTime() {
        try {
            Path yamlPath = getYamlFilePath();
            if (yamlPath != null && Files.exists(yamlPath)) {
                BasicFileAttributes attrs = Files.readAttributes(yamlPath, BasicFileAttributes.class);
                return LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault());
            }
            return null;
        } catch (IOException e) {
            log.error("获取YAML文件修改时间失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 更新YAML修改时间和加载时间
     */
    public void updateYamlLoadedTime(LocalDateTime yamlModifiedTime, LocalDateTime lastLoadedTime) {
        configMetaMapper.updateYamlTime(yamlModifiedTime, lastLoadedTime);
    }

    /**
     * 仅更新配置加载时间
     */
    public void updateLastLoadedTime(LocalDateTime lastLoadedTime) {
        configMetaMapper.updateLastLoadedTime(lastLoadedTime);
    }

    /**
     * 重置初始化标记
     */
    public void resetInitialized() {
        configMetaMapper.updateInitialized(false);
        log.info("初始化状态已重置");
    }

    /**
     * 获取数据库中存储的 YAML 内容
     */
    public String getStoredYamlContent() {
        return configMetaMapper.selectYamlContent();
    }
}