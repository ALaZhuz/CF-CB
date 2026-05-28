package org.example.workflow.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流通知配置属性绑定类
 *
 * 用于将 workflow-config.yml 中的配置绑定到Java对象。
 * 支持四层优先级配置：Tracker级差异配置 > tracker-matching > 全局工作流 > 无匹配。
 *
 * 配置结构（2026-05-27更新）：
 * - type-mappings: Tracker类型映射（全局 + 项目级覆盖）
 * - extra-fields: 额外字段配置（全局 + 项目级完全覆盖）
 * - global-workflows: 全局工作流模板
 * - projects: 项目级配置
 *
 * @author system
 * @since 1.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "")
public class WorkflowProperties {

    /** 钉钉通知模式配置 */
    private DingtalkConfig dingtalk = new DingtalkConfig();

    /** Tracker类型映射配置 */
    private TypeMappingsConfig typeMappings = new TypeMappingsConfig();

    /** 额外字段配置 */
    private ExtraFieldsConfig extraFields = new ExtraFieldsConfig();

    /** 全局工作流模板列表 */
    private List<WorkflowTemplate> globalWorkflows = new ArrayList<>();

    /** 项目级配置列表 */
    private List<ProjectConfig> projects = new ArrayList<>();

    /**
     * 钉钉通知模式配置类
     */
    @Data
    public static class DingtalkConfig {
        /** 通知模式：enterprise（企业钉钉）或 personal（个人钉钉Webhook） */
        private String mode = "enterprise";

        /** 个人钉钉Webhook URL（当mode为personal时使用） */
        private String personalWebhookUrl;
    }

    /**
     * Tracker类型映射配置类
     *
     * 决定消息中 {trackertype} 显示的内容。
     * 项目级完全覆盖全局，不追加。
     */
    @Data
    public static class TypeMappingsConfig {
        /** 全局类型映射：tracker type -> 显示名称 */
        private Map<String, String> global = new HashMap<>();

        /** 项目级类型映射：projectId -> (tracker type -> 显示名称) */
        private Map<String, Map<String, String>> projects = new HashMap<>();
    }

    /**
     * 额外字段配置类
     *
     * 定义消息中额外显示的字段，动态插入到链接行之前。
     * 项目级完全覆盖全局，不追加。
     */
    @Data
    public static class ExtraFieldsConfig {
        /** 全局额外字段列表 */
        private List<ExtraField> global = new ArrayList<>();

        /** 项目级额外字段列表：projectId -> 额外字段列表 */
        private Map<String, List<ExtraField>> projects = new HashMap<>();
    }
}