package org.example.workflow.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WorkflowConfigService扩展测试
 *
 * 测试新增的分类配置相关方法：
 * 1. getClassifyConfig - tracker级最高优先级
 * 2. getClassifyConfig - 项目级覆盖全局
 * 3. getClassifyConfig - 全局兜底
 * 4. getScheduledNotify - 默认true
 * 5. getScheduledNotify - 显式配置false
 * 6. getNotifyTime - tracker级配置
 * 7. getNotifyTime - 全局默认
 * 8. matchClassifyRule - 成功匹配
 *
 * @author system
 * @since 1.0
 */
class WorkflowConfigServiceExtendedTest {

    private WorkflowProperties workflowProperties;
    private WorkflowConfigService workflowConfigService;

    @BeforeEach
    void setUp() {
        workflowProperties = new WorkflowProperties();
        workflowConfigService = new WorkflowConfigService(workflowProperties);
    }

    /**
     * 创建分类配置
     */
    private ClassifyConfig createClassifyConfig(String classifyField, String defaultNotifyTime) {
        ClassifyConfig config = new ClassifyConfig();
        config.setClassifyField(classifyField);
        config.setDefaultNotifyTime(defaultNotifyTime);

        ClassifyRule rule1 = new ClassifyRule();
        rule1.setCategory("严重");
        rule1.setMemberIntervalDays(1);
        rule1.setManagerEscalateDays(2);
        rule1.setDirectorEscalateDays(3);

        ClassifyRule rule2 = new ClassifyRule();
        rule2.setCategory("一般");
        rule2.setMemberIntervalDays(2);
        rule2.setManagerEscalateDays(3);
        rule2.setDirectorEscalateDays(5);

        config.setClassifyRules(List.of(rule1, rule2));
        config.setDefaultCategory("一般");
        return config;
    }

    /**
     * 创建项目配置
     */
    private ProjectConfig createProjectConfig(Integer projectId) {
        ProjectConfig project = new ProjectConfig();
        project.setProjectId(projectId);
        return project;
    }

    /**
     * 场景1: getClassifyConfig - 全局配置
     */
    @Test
    @DisplayName("getClassifyConfig - 全局配置")
    void testGetClassifyConfig_Global() {
        ClassifyConfig globalConfig = createClassifyConfig("severities", "08:00");
        workflowProperties.setClassifyConfig(new WorkflowProperties.ClassifyConfigConfig());
        workflowProperties.getClassifyConfig().setGlobal(globalConfig);

        ClassifyConfig result = workflowConfigService.getClassifyConfig(111, 100);

        assertNotNull(result);
        assertEquals("severities", result.getClassifyField());
        assertEquals("08:00", result.getDefaultNotifyTime());
    }

    /**
     * 场景2: getClassifyConfig - 项目级覆盖全局
     */
    @Test
    @DisplayName("getClassifyConfig - 项目级覆盖全局")
    void testGetClassifyConfig_ProjectOverride() {
        // 全局配置
        ClassifyConfig globalConfig = createClassifyConfig("severities", "08:00");
        workflowProperties.setClassifyConfig(new WorkflowProperties.ClassifyConfigConfig());
        workflowProperties.getClassifyConfig().setGlobal(globalConfig);

        // 项目级配置
        ClassifyConfig projectConfig = createClassifyConfig("priority", "09:00");
        workflowProperties.getClassifyConfig().getProjects().put("100", projectConfig);

        ClassifyConfig result = workflowConfigService.getClassifyConfig(111, 100);

        assertNotNull(result);
        assertEquals("priority", result.getClassifyField());
        assertEquals("09:00", result.getDefaultNotifyTime());
    }

    /**
     * 场景3: getClassifyConfig - tracker级最高优先级
     */
    @Test
    @DisplayName("getClassifyConfig - tracker级最高优先级")
    void testGetClassifyConfig_TrackerOverride() {
        // 全局配置
        ClassifyConfig globalConfig = createClassifyConfig("severities", "08:00");
        workflowProperties.setClassifyConfig(new WorkflowProperties.ClassifyConfigConfig());
        workflowProperties.getClassifyConfig().setGlobal(globalConfig);

        // 项目级配置
        ClassifyConfig projectConfig = createClassifyConfig("priority", "09:00");
        workflowProperties.getClassifyConfig().getProjects().put("100", projectConfig);

        // 项目和tracker配置
        ProjectConfig project = createProjectConfig(100);

        ProjectConfig.TrackerConfig trackerConfig = new ProjectConfig.TrackerConfig();
        trackerConfig.setTrackerId(111);
        trackerConfig.setClassifyField("客户优先级");

        ClassifyRule trackerRule = new ClassifyRule();
        trackerRule.setCategory("紧急");
        trackerRule.setMemberIntervalDays(1);
        trackerRule.setManagerEscalateDays(1);
        trackerConfig.setClassifyRules(List.of(trackerRule));

        project.setTrackers(List.of(trackerConfig));
        workflowProperties.setProjects(List.of(project));

        ClassifyConfig result = workflowConfigService.getClassifyConfig(111, 100);

        assertNotNull(result);
        assertEquals("客户优先级", result.getClassifyField());
        assertEquals(1, result.getClassifyRules().size());
        assertEquals("紧急", result.getClassifyRules().get(0).getCategory());
    }

    /**
     * 场景4: getScheduledNotify - 默认true
     */
    @Test
    @DisplayName("getScheduledNotify - 默认true")
    void testGetScheduledNotify_DefaultTrue() {
        WorkflowTemplate.StateConfig config = new WorkflowTemplate.StateConfig();
        config.setName("处理中");
        config.setNotifyField("assignedTo");
        // scheduledNotify 未配置

        assertTrue(workflowConfigService.getScheduledNotify(config));
    }

    /**
     * 场景5: getScheduledNotify - 显式配置false
     */
    @Test
    @DisplayName("getScheduledNotify - 显式配置false")
    void testGetScheduledNotify_ExplicitFalse() {
        WorkflowTemplate.StateConfig config = new WorkflowTemplate.StateConfig();
        config.setName("处理中");
        config.setNotifyField("assignedTo");
        config.setScheduledNotify(false);

        assertFalse(workflowConfigService.getScheduledNotify(config));
    }

    /**
     * 场景6: getScheduledNotify - 显式配置true
     */
    @Test
    @DisplayName("getScheduledNotify - 显式配置true")
    void testGetScheduledNotify_ExplicitTrue() {
        WorkflowTemplate.StateConfig config = new WorkflowTemplate.StateConfig();
        config.setName("处理中");
        config.setNotifyField("assignedTo");
        config.setScheduledNotify(true);

        assertTrue(workflowConfigService.getScheduledNotify(config));
    }

    /**
     * 场景7: getScheduledNotify - 无notifyField返回false
     */
    @Test
    @DisplayName("getScheduledNotify - 无notifyField返回false")
    void testGetScheduledNotify_NoNotifyField() {
        WorkflowTemplate.StateConfig config = new WorkflowTemplate.StateConfig();
        config.setName("新建");
        config.setNotify(false);
        // 无 notifyField

        assertFalse(workflowConfigService.getScheduledNotify(config));
    }

    /**
     * 场景8: getNotifyTime - 全局默认
     */
    @Test
    @DisplayName("getNotifyTime - 全局默认")
    void testGetNotifyTime_GlobalDefault() {
        ClassifyConfig globalConfig = new ClassifyConfig();
        globalConfig.setDefaultNotifyTime("08:00");
        workflowProperties.setClassifyConfig(new WorkflowProperties.ClassifyConfigConfig());
        workflowProperties.getClassifyConfig().setGlobal(globalConfig);

        String notifyTime = workflowConfigService.getNotifyTime(111, 100);

        assertEquals("08:00", notifyTime);
    }

    /**
     * 场景9: getNotifyTime - tracker级配置
     */
    @Test
    @DisplayName("getNotifyTime - tracker级配置")
    void testGetNotifyTime_TrackerLevel() {
        ClassifyConfig globalConfig = new ClassifyConfig();
        globalConfig.setDefaultNotifyTime("08:00");
        workflowProperties.setClassifyConfig(new WorkflowProperties.ClassifyConfigConfig());
        workflowProperties.getClassifyConfig().setGlobal(globalConfig);

        ProjectConfig project = createProjectConfig(100);

        ProjectConfig.TrackerConfig trackerConfig = new ProjectConfig.TrackerConfig();
        trackerConfig.setTrackerId(111);
        trackerConfig.setNotifyTime("09:30");

        project.setTrackers(List.of(trackerConfig));
        workflowProperties.setProjects(List.of(project));

        String notifyTime = workflowConfigService.getNotifyTime(111, 100);

        assertEquals("09:30", notifyTime);
    }

    /**
     * 场景10: getNotifyTime - 无配置使用默认08:00
     */
    @Test
    @DisplayName("getNotifyTime - 无配置使用默认08:00")
    void testGetNotifyTime_NoConfig() {
        workflowProperties.setClassifyConfig(new WorkflowProperties.ClassifyConfigConfig());
        workflowProperties.getClassifyConfig().setGlobal(new ClassifyConfig());

        String notifyTime = workflowConfigService.getNotifyTime(111, 100);

        assertEquals("08:00", notifyTime);
    }

    /**
     * 场景11: matchClassifyRule - 成功匹配
     */
    @Test
    @DisplayName("matchClassifyRule - 成功匹配")
    void testMatchClassifyRule_Success() {
        ClassifyConfig config = createClassifyConfig("severities", "08:00");

        ClassifyRule result = workflowConfigService.matchClassifyRule("严重", config);

        assertNotNull(result);
        assertEquals("严重", result.getCategory());
        assertEquals(1, result.getMemberIntervalDays());
        assertEquals(2, result.getManagerEscalateDays());
        assertEquals(3, result.getDirectorEscalateDays());
    }

    /**
     * 场景12: matchClassifyRule - 使用默认分类
     */
    @Test
    @DisplayName("matchClassifyRule - 使用默认分类")
    void testMatchClassifyRule_DefaultCategory() {
        ClassifyConfig config = createClassifyConfig("severities", "08:00");

        // "轻微" 不匹配，使用默认分类 "一般"
        ClassifyRule result = workflowConfigService.matchClassifyRule("轻微", config);

        assertNotNull(result);
        assertEquals("一般", result.getCategory());
        assertEquals(2, result.getMemberIntervalDays());
    }

    /**
     * 场景13: matchClassifyRule - 无匹配无默认
     */
    @Test
    @DisplayName("matchClassifyRule - 无匹配无默认")
    void testMatchClassifyRule_NoMatch() {
        ClassifyConfig config = new ClassifyConfig();
        ClassifyRule rule = new ClassifyRule();
        rule.setCategory("严重");
        config.setClassifyRules(List.of(rule));
        // 无 defaultCategory

        ClassifyRule result = workflowConfigService.matchClassifyRule("一般", config);

        assertNull(result);
    }
}