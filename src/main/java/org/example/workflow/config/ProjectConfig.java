package org.example.workflow.config;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目级配置类
 *
 * 定义某个项目的专属工作流配置。
 * 优先级高于全局配置，可定义项目内工作流模板、批量匹配规则和tracker精确配置。
 *
 * @author system
 * @since 1.0
 */
@Data
public class ProjectConfig {

    /** 项目ID，与Codebeamer中的项目ID对应 */
    private Integer projectId;

    /** 项目名称，用于日志和调试 */
    private String projectName;

    /** 项目内工作流模板列表，仅供本项目内的tracker引用 */
    private List<WorkflowTemplate> workflows = new ArrayList<>();

    /** Tracker批量匹配规则列表，按顺序执行，使用第一条匹配成功的规则 */
    private List<TrackerMatchingRule> trackerMatching = new ArrayList<>();

    /** Tracker级配置列表，最高优先级 */
    private List<TrackerConfig> trackers = new ArrayList<>();

    /**
     * Tracker级配置类
     *
     * 定义某个tracker的专属配置，优先级最高。
     * 可直接引用工作流模板，或直接定义状态配置。
     */
    @Data
    public static class TrackerConfig {

        /** Tracker ID，与Codebeamer中的tracker ID对应 */
        private Integer trackerId;

        /** 引用的工作流模板名称（项目内或全局） */
        private String workflow;

        /** 直接定义的状态配置列表（优先级高于引用的workflow） */
        private List<WorkflowTemplate.StateConfig> states;
    }
}