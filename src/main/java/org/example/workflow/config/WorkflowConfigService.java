package org.example.workflow.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 工作流配置服务类
 *
 * 实现四层优先级配置的查询和合并逻辑：
 * 1. Tracker级差异配置（最高优先级）
 * 2. tracker-matching tracker-id 匹配
 * 3. tracker-matching tracker-type 匹配
 * 4. 无匹配（不发送通知）
 *
 * 新增功能（2026-05-27）：
 * - type-mappings：Tracker类型映射（全局 + 项目级覆盖）
 * - extra-fields：额外字段配置（全局 + 项目级完全覆盖）
 * - trackers差异配置合并：与workflow.states合并，同名覆盖，新增追加
 *
 * @author system
 * @since 1.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkflowConfigService {

    private final WorkflowProperties workflowProperties;

    /**
     * 获取Tracker类型映射名称
     *
     * 用于消息模板中的 {trackertype} 占位符。
     * 项目级优先，无配置则使用全局。
     *
     * @param trackerType tracker类型名称（如Bug、Requirement）
     * @param projectId 项目ID
     * @return 类型映射名称，未找到返回trackerType本身
     */
    public String getTypeMapping(String trackerType, Integer projectId) {
        if (trackerType == null) {
            return "";
        }

        // 先查找项目级映射
        if (projectId != null) {
            Map<String, String> projectMappings = workflowProperties.getTypeMappings()
                    .getProjects().get(String.valueOf(projectId));
            if (projectMappings != null && projectMappings.containsKey(trackerType)) {
                return projectMappings.get(trackerType);
            }
        }

        // 再查找全局映射
        Map<String, String> globalMappings = workflowProperties.getTypeMappings().getGlobal();
        if (globalMappings.containsKey(trackerType)) {
            return globalMappings.get(trackerType);
        }

        // 未找到映射，返回原类型名称
        return trackerType;
    }

    /**
     * 获取额外字段配置
     *
     * 用于消息模板中动态插入到链接行之前的额外字段。
     * 项目级完全覆盖全局，不追加。
     *
     * @param projectId 项目ID
     * @return 额外字段列表，未配置返回空列表
     */
    public List<ExtraField> getExtraFields(Integer projectId) {
        // 先查找项目级配置
        if (projectId != null) {
            List<ExtraField> projectFields = workflowProperties.getExtraFields()
                    .getProjects().get(String.valueOf(projectId));
            if (projectFields != null) {
                return projectFields;
            }
        }

        // 再使用全局配置
        List<ExtraField> globalFields = workflowProperties.getExtraFields().getGlobal();
        return globalFields != null ? globalFields : new ArrayList<>();
    }

    /**
     * 获取指定tracker的状态配置
     *
     * 按四层优先级查找：
     * 1. trackers 差异配置（最高优先级）
     *    - tracker-id 精确匹配
     *    - 直接定义 states 或引用 workflow
     * 2. tracker-matching tracker-id 匹配
     * 3. tracker-matching tracker-type 匹配
     * 4. 无匹配返回null
     *
     * @param trackerId tracker ID
     * @param trackerType tracker类型
     * @param projectId 项目ID
     * @return 找到的工作流模板，如果未找到返回null
     */
    public WorkflowTemplate getWorkflowForTracker(Integer trackerId, String trackerType,
                                                   Integer projectId) {
        // 1. 查找项目配置
        ProjectConfig projectConfig = findProjectConfig(projectId);

        if (projectConfig != null) {
            // 1.1 最高优先级：trackers 差异配置（tracker-id 精确匹配）
            WorkflowTemplate trackerDirectConfig = findTrackerDirectConfig(projectConfig, trackerId);
            if (trackerDirectConfig != null) {
                log.debug("使用trackers差异配置: projectId={}, trackerId={}", projectId, trackerId);
                return trackerDirectConfig;
            }

            // 1.2 次优先级：tracker-matching 匹配
            WorkflowTemplate matchedWorkflow = matchTrackerByRules(
                    projectConfig.getTrackerMatching(), trackerId, trackerType, projectConfig);

            if (matchedWorkflow != null) {
                // 1.3 检查是否有 trackers 差异配置需要合并
                WorkflowTemplate mergedWorkflow = mergeTrackerDifferentialConfig(
                        matchedWorkflow, projectConfig, trackerId);

                if (mergedWorkflow != matchedWorkflow) {
                    log.debug("trackers差异配置合并: projectId={}, trackerId={}", projectId, trackerId);
                }
                return mergedWorkflow;
            }
        }

        // 2. 未找到配置
        log.warn("未找到tracker配置: trackerId={}, trackerType={}, projectId={}",
                trackerId, trackerType, projectId);
        return null;
    }

    /**
     * 在项目配置中查找Tracker直接配置（最高优先级）
     *
     * trackers 配置可以是：
     * - 直接定义 states（返回临时创建的 workflow）
     * - 引用 workflow（返回对应的 workflow）
     *
     * @param projectConfig 项目配置
     * @param trackerId tracker ID
     * @return 找到的工作流模板，未找到返回null
     */
    private WorkflowTemplate findTrackerDirectConfig(ProjectConfig projectConfig, Integer trackerId) {
        if (projectConfig.getTrackers() == null) {
            return null;
        }

        Optional<ProjectConfig.TrackerConfig> trackerConfig = projectConfig.getTrackers().stream()
                .filter(t -> t.getTrackerId().equals(trackerId))
                .findFirst();

        if (trackerConfig.isEmpty()) {
            return null;
        }

        ProjectConfig.TrackerConfig config = trackerConfig.get();

        // 如果直接定义了states，创建临时工作流模板（最高优先级）
        if (config.getStates() != null && !config.getStates().isEmpty()) {
            WorkflowTemplate template = new WorkflowTemplate();
            template.setName("tracker-" + trackerId + "-direct-config");
            template.setStates(config.getStates());
            return template;
        }

        // 如果引用了workflow，查找对应的工作流模板
        if (config.getWorkflow() != null) {
            return findWorkflowByName(config.getWorkflow(), projectConfig);
        }

        return null;
    }

    /**
     * 合并tracker差异配置
     *
     * trackers中的states与workflow.states合并：
     * - 同名状态：trackers.states覆盖workflow.states
     * - 新增状态：追加到workflow.states
     *
     * @param workflow 基础workflow
     * @param projectConfig 项目配置
     * @param trackerId tracker ID
     * @return 合合后的workflow，无差异配置返回原workflow
     */
    private WorkflowTemplate mergeTrackerDifferentialConfig(WorkflowTemplate workflow,
                                                            ProjectConfig projectConfig,
                                                            Integer trackerId) {
        if (projectConfig.getTrackers() == null) {
            return workflow;
        }

        // 查找tracker差异配置
        Optional<ProjectConfig.TrackerConfig> trackerConfig = projectConfig.getTrackers().stream()
                .filter(t -> t.getTrackerId().equals(trackerId))
                .findFirst();

        if (trackerConfig.isEmpty() || trackerConfig.get().getStates() == null) {
            return workflow;
        }

        List<WorkflowTemplate.StateConfig> differentialStates = trackerConfig.get().getStates();

        // 合合逻辑：创建新workflow副本
        WorkflowTemplate mergedWorkflow = new WorkflowTemplate();
        mergedWorkflow.setName(workflow.getName());

        // 复制原workflow的states（排除被覆盖的）
        List<WorkflowTemplate.StateConfig> mergedStates = new ArrayList<>();
        Set<String> overriddenStateNames = differentialStates.stream()
                .map(WorkflowTemplate.StateConfig::getName)
                .collect(Collectors.toSet());

        for (WorkflowTemplate.StateConfig state : workflow.getStates()) {
            if (!overriddenStateNames.contains(state.getName())) {
                mergedStates.add(state);
            }
        }

        // 添加差异配置中的states
        mergedStates.addAll(differentialStates);
        mergedWorkflow.setStates(mergedStates);

        return mergedWorkflow;
    }

    /**
     * 获取指定状态的通知配置
     *
     * 从工作流模板中查找指定状态的配置。
     *
     * @param workflow 工作流模板
     * @param stateName 状态名称
     * @return 状态配置，如果状态未在工作流中声明返回null
     */
    public WorkflowTemplate.StateConfig getStateConfig(WorkflowTemplate workflow, String stateName) {
        if (workflow == null || workflow.getStates() == null) {
            return null;
        }

        return workflow.getStates().stream()
                .filter(state -> state.getName().equals(stateName))
                .findFirst()
                .orElse(null);
    }

    /**
     * 判断状态是否需要通知
     *
     * @param stateConfig 状态配置
     * @return true表示需要发送通知
     */
    public boolean shouldNotify(WorkflowTemplate.StateConfig stateConfig) {
        if (stateConfig == null) {
            // 未配置的状态，不通知
            return false;
        }

        // notify字段为false，明确不通知
        if (Boolean.FALSE.equals(stateConfig.getNotify())) {
            return false;
        }

        // 配置了notifyField，需要通知
        return stateConfig.getNotifyField() != null && !stateConfig.getNotifyField().isEmpty();
    }

    /**
     * 判断状态是否已显式声明
     *
     * 用于beforeEvent校验，未声明的状态应阻止保存。
     *
     * @param workflow 工作流模板
     * @param stateName 状态名称
     * @return true表示状态已声明（包括notify:false）
     */
    public boolean isStateDeclared(WorkflowTemplate workflow, String stateName) {
        if (workflow == null) {
            return false;
        }

        return workflow.getStates().stream()
                .anyMatch(state -> state.getName().equals(stateName));
    }

    /**
     * 查找项目配置
     *
     * @param projectId 项目ID
     * @return 项目配置，未找到返回null
     */
    private ProjectConfig findProjectConfig(Integer projectId) {
        if (projectId == null) {
            return null;
        }

        return workflowProperties.getProjects().stream()
                .filter(p -> p.getProjectId().equals(projectId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 按批量匹配规则匹配tracker
     *
     * 规则按顺序执行，使用第一条匹配成功的规则：
     * 1. tracker-id精确匹配（优先）
     * 2. tracker-type类型匹配
     *
     * @param rules 匹配规则列表
     * @param trackerId tracker ID
     * @param trackerType tracker类型
     * @param projectConfig 项目配置（用于查找项目内工作流）
     * @return 匹配到的工作流模板，未匹配返回null
     */
    private WorkflowTemplate matchTrackerByRules(List<TrackerMatchingRule> rules,
                                                  Integer trackerId, String trackerType,
                                                  ProjectConfig projectConfig) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }

        for (TrackerMatchingRule rule : rules) {
            // tracker-id精确匹配（优先）
            if (rule.matchesTrackerId(trackerId)) {
                log.debug("tracker-id匹配成功: trackerId={}, workflow={}", trackerId, rule.getWorkflow());
                return findWorkflowByName(rule.getWorkflow(), projectConfig);
            }

            // tracker-type类型匹配
            if (rule.matchesTrackerType(trackerType)) {
                log.debug("tracker-type匹配成功: trackerType={}, workflow={}", trackerType, rule.getWorkflow());
                return findWorkflowByName(rule.getWorkflow(), projectConfig);
            }
        }

        return null;
    }

    /**
     * 根据名称查找工作流模板
     *
     * 先查找项目内工作流，再查找全局工作流。
     *
     * @param workflowName 工作流名称
     * @param projectConfig 项目配置（可为null）
     * @return 工作流模板，未找到返回null
     */
    private WorkflowTemplate findWorkflowByName(String workflowName, ProjectConfig projectConfig) {
        if (workflowName == null || workflowName.isEmpty()) {
            return null;
        }

        // 先查找项目内工作流
        if (projectConfig != null && projectConfig.getWorkflows() != null) {
            Optional<WorkflowTemplate> projectWorkflow = projectConfig.getWorkflows().stream()
                    .filter(w -> w.getName().equals(workflowName))
                    .findFirst();
            if (projectWorkflow.isPresent()) {
                return projectWorkflow.get();
            }
        }

        // 再查找全局工作流
        return workflowProperties.getGlobalWorkflows().stream()
                .filter(w -> w.getName().equals(workflowName))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取钉钉通知模式
     *
     * @return 通知模式：enterprise 或 personal
     */
    public String getDingtalkMode() {
        return workflowProperties.getDingtalk().getMode();
    }

    /**
     * 获取个人钉钉Webhook URL
     *
     * @return Webhook URL
     */
    public String getPersonalWebhookUrl() {
        return workflowProperties.getDingtalk().getPersonalWebhookUrl();
    }
}