package org.example.workflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.db.entity.ConfigMeta;
import org.example.db.mapper.ConfigMetaMapper;
import org.springframework.beans.factory.annotation.Value;
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

    // 使用 Jackson ObjectMapper 进行配置转换（支持 kebab-case 到 camelCase）
    private final ObjectMapper configObjectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);

    @Value("${spring.config.import:classpath:workflow-config.yml}")
    private String configImport;

    /**
     * 服务启动时同步配置到数据库并更新内存配置
     *
     * 每次启动都强制从 YAML 文件读取配置并覆盖数据库，
     * 同时更新 WorkflowProperties 内存配置，确保配置与 YAML 文件保持一致。
     */
    @PostConstruct
    public void syncConfigToDatabase() {
        try {
            Path yamlPath = getYamlFilePath();
            if (yamlPath == null || !Files.exists(yamlPath)) {
                log.warn("YAML配置文件不存在，跳过同步");
                return;
            }

            log.info("从 YAML 文件同步配置: {}", yamlPath);

            String yamlContent = Files.readString(yamlPath);
            LocalDateTime fileModifiedTime = getYamlFileModifiedTime();

            // 1. 更新数据库
            configMetaMapper.updateYamlContent(yamlContent, fileModifiedTime, LocalDateTime.now(), "system-startup");
            log.info("配置已同步到数据库");

            // 2. 更新内存中的 WorkflowProperties
            Yaml yaml = new Yaml();
            Map<String, Object> configMap = yaml.load(new FileInputStream(yamlPath.toFile()));
            bindConfigToProperties(configMap);

            log.info("启动配置同步完成");

        } catch (Exception e) {
            log.error("同步配置失败: {}", e.getMessage(), e);
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

            String yamlContent = Files.readString(yamlPath);
            LocalDateTime fileModifiedTime = getYamlFileModifiedTime();
            configMetaMapper.updateYamlContent(yamlContent, fileModifiedTime, LocalDateTime.now(), "auto-reload");

            // 使用 Binder 更新配置
            Yaml yaml = new Yaml();
            Map<String, Object> configMap = yaml.load(new FileInputStream(yamlPath.toFile()));
            bindConfigToProperties(configMap);

            log.info("YAML配置热更新完成: {}", yamlPath);

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
     * 使用 Jackson ObjectMapper 将配置绑定到 WorkflowProperties
     *
     * Jackson 的 PropertyNamingStrategies.KEBAB_CASE 支持
     * YAML 中的 kebab-case（如 classify-field）到 Java camelCase（如 classifyField）的转换
     *
     * @param configMap YAML 解析后的配置 Map
     */
    private void bindConfigToProperties(Map<String, Object> configMap) {
        try {
            // 绑定 classify-config
            if (configMap.containsKey("classify-config")) {
                Object classifyConfigMap = configMap.get("classify-config");
                WorkflowProperties.ClassifyConfigConfig classifyConfig =
                    configObjectMapper.convertValue(classifyConfigMap,
                        WorkflowProperties.ClassifyConfigConfig.class);
                workflowProperties.setClassifyConfig(classifyConfig);
            }

            // 绑定 type-mappings
            if (configMap.containsKey("type-mappings")) {
                Object typeMappingsMap = configMap.get("type-mappings");
                WorkflowProperties.TypeMappingsConfig typeMappings =
                    configObjectMapper.convertValue(typeMappingsMap,
                        WorkflowProperties.TypeMappingsConfig.class);
                workflowProperties.setTypeMappings(typeMappings);
            }

            // 绑定 extra-fields
            if (configMap.containsKey("extra-fields")) {
                Object extraFieldsMap = configMap.get("extra-fields");
                WorkflowProperties.ExtraFieldsConfig extraFields =
                    configObjectMapper.convertValue(extraFieldsMap,
                        WorkflowProperties.ExtraFieldsConfig.class);
                workflowProperties.setExtraFields(extraFields);
            }

            // 绑定根级别字段
            if (configMap.containsKey("default-cleanup-time")) {
                workflowProperties.setDefaultCleanupTime((String) configMap.get("default-cleanup-time"));
            }

            if (configMap.containsKey("default-notify-time")) {
                workflowProperties.setDefaultNotifyTime((String) configMap.get("default-notify-time"));
            }

            // 绑定 dingtalk
            if (configMap.containsKey("dingtalk")) {
                Object dingtalkMap = configMap.get("dingtalk");
                WorkflowProperties.DingtalkConfig dingtalk =
                    configObjectMapper.convertValue(dingtalkMap,
                        WorkflowProperties.DingtalkConfig.class);
                workflowProperties.setDingtalk(dingtalk);
            }

            // 绑定 global-workflows（列表）
            if (configMap.containsKey("global-workflows")) {
                Object globalWorkflowsList = configMap.get("global-workflows");
                workflowProperties.setGlobalWorkflows(
                    configObjectMapper.convertValue(globalWorkflowsList,
                        configObjectMapper.getTypeFactory().constructCollectionType(
                            java.util.ArrayList.class, WorkflowTemplate.class)));
            }

            // 绑定 projects（列表）
            if (configMap.containsKey("projects")) {
                Object projectsList = configMap.get("projects");
                workflowProperties.setProjects(
                    configObjectMapper.convertValue(projectsList,
                        configObjectMapper.getTypeFactory().constructCollectionType(
                            java.util.ArrayList.class, ProjectConfig.class)));
            }

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