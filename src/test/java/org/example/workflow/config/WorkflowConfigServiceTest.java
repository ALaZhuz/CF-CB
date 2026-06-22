package org.example.workflow.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WorkflowConfigService单元测试
 *
 * 测试场景：
 * 1. Tracker级配置最高优先级
 * 2. Tracker引用项目内工作流模板
 * 3. Tracker引用全局工作流模板
 * 4. Tracker批量匹配规则（按tracker-id）
 * 5. Tracker批量匹配规则（按tracker-type）
 * 6. 匹配规则按顺序执行（tracker-id优先）
 * 7. 状态是否已声明判断
 * 8. 是否需要通知判断
 * 9. trackers差异配置合并
 * 10. type-mappings查找
 * 11. extra-fields查找
 *
 * @author system
 * @since 1.0
 */
class WorkflowConfigServiceTest {

    private WorkflowProperties workflowProperties;
    private WorkflowConfigService workflowConfigService;

    /**
     * 测试数据初始化
     */
    @BeforeEach
    void setUp() {
        workflowProperties = new WorkflowProperties();
        workflowConfigService = new WorkflowConfigService(workflowProperties);
    }

    /**
     * 构建测试用的全局工作流模板
     */
    private WorkflowTemplate createGlobalWorkflow(String name) {
        WorkflowTemplate template = new WorkflowTemplate();
        template.setName(name);

        WorkflowTemplate.StateConfig state1 = new WorkflowTemplate.StateConfig();
        state1.setName("新建");
        state1.setNotify(false);

        WorkflowTemplate.StateConfig state2 = new WorkflowTemplate.StateConfig();
        state2.setName("处理中");
        state2.setNotifyField(List.of("assignedTo"));

        WorkflowTemplate.StateConfig state3 = new WorkflowTemplate.StateConfig();
        state3.setName("已关闭");
        state3.setNotify(false);

        template.setStates(Arrays.asList(state1, state2, state3));
        return template;
    }

    /**
     * 构建测试用的项目配置
     */
    private ProjectConfig createProjectConfig(Integer projectId, String projectName) {
        ProjectConfig project = new ProjectConfig();
        project.setProjectId(projectId);
        project.setProjectName(projectName);
        return project;
    }

    /**
     * 场景1: Tracker级配置最高优先级
     */
    @Test
    @DisplayName("Tracker级配置最高优先级")
    void testGetWorkflowForTracker_TrackerLevelHighestPriority() {
        // 设置全局工作流
        WorkflowTemplate globalWorkflow = createGlobalWorkflow("标准Bug工作流");
        workflowProperties.setGlobalWorkflows(Collections.singletonList(globalWorkflow));

        // 设置项目配置
        ProjectConfig project = createProjectConfig(100, "测试项目");

        // 项目内工作流
        WorkflowTemplate projectWorkflow = new WorkflowTemplate();
        projectWorkflow.setName("项目Bug流程");

        WorkflowTemplate.StateConfig projectState = new WorkflowTemplate.StateConfig();
        projectState.setName("处理中");
        projectState.setNotifyField(List.of("projectAssignedTo"));
        projectWorkflow.setStates(Collections.singletonList(projectState));

        project.setWorkflows(Collections.singletonList(projectWorkflow));

        // Tracker级精确配置
        ProjectConfig.TrackerConfig trackerConfig = new ProjectConfig.TrackerConfig();
        trackerConfig.setTrackerId(111);
        trackerConfig.setWorkflow("项目Bug流程");

        project.setTrackers(Collections.singletonList(trackerConfig));
        workflowProperties.setProjects(Collections.singletonList(project));

        // 获取配置（新签名：trackerId, trackerType, projectId）
        WorkflowTemplate result = workflowConfigService.getWorkflowForTracker(111, "Bug", 100);

        // 应返回项目内工作流（Tracker级配置引用）
        assertNotNull(result);
        assertEquals("项目Bug流程", result.getName());
    }

    /**
     * 场景2: Tracker引用全局工作流模板
     */
    @Test
    @DisplayName("Tracker引用全局工作流模板")
    void testGetWorkflowForTracker_ReferenceGlobalWorkflow() {
        WorkflowTemplate globalWorkflow = createGlobalWorkflow("标准Bug工作流");
        workflowProperties.setGlobalWorkflows(Collections.singletonList(globalWorkflow));

        ProjectConfig project = createProjectConfig(100, "测试项目");

        // Tracker配置引用全局工作流
        ProjectConfig.TrackerConfig trackerConfig = new ProjectConfig.TrackerConfig();
        trackerConfig.setTrackerId(111);
        trackerConfig.setWorkflow("标准Bug工作流");

        project.setTrackers(Collections.singletonList(trackerConfig));
        workflowProperties.setProjects(Collections.singletonList(project));

        WorkflowTemplate result = workflowConfigService.getWorkflowForTracker(111, "Bug", 100);

        assertNotNull(result);
        assertEquals("标准Bug工作流", result.getName());
    }

    /**
     * 场景3: Tracker批量匹配规则（按tracker-id）
     */
    @Test
    @DisplayName("Tracker批量匹配规则（按tracker-id）")
    void testGetWorkflowForTracker_MatchById() {
        WorkflowTemplate bugWorkflow = createGlobalWorkflow("标准Bug工作流");
        WorkflowTemplate requirementWorkflow = createGlobalWorkflow("标准需求工作流");
        workflowProperties.setGlobalWorkflows(Arrays.asList(bugWorkflow, requirementWorkflow));

        ProjectConfig project = createProjectConfig(100, "测试项目");

        // 批量匹配规则 - tracker-id精确匹配
        TrackerMatchingRule rule1 = new TrackerMatchingRule();
        rule1.setTrackerId(111);
        rule1.setWorkflow("标准Bug工作流");

        TrackerMatchingRule rule2 = new TrackerMatchingRule();
        rule2.setTrackerId(222);
        rule2.setWorkflow("标准需求工作流");

        project.setTrackerMatching(Arrays.asList(rule1, rule2));
        workflowProperties.setProjects(Collections.singletonList(project));

        // tracker-id匹配
        WorkflowTemplate resultBug = workflowConfigService.getWorkflowForTracker(111, "Bug", 100);
        assertNotNull(resultBug);
        assertEquals("标准Bug工作流", resultBug.getName());

        WorkflowTemplate resultReq = workflowConfigService.getWorkflowForTracker(222, "Requirement", 100);
        assertNotNull(resultReq);
        assertEquals("标准需求工作流", resultReq.getName());
    }

    /**
     * 场景4: Tracker批量匹配规则（按tracker-type）
     */
    @Test
    @DisplayName("Tracker批量匹配规则（按tracker-type）")
    void testGetWorkflowForTracker_MatchByType() {
        WorkflowTemplate bugWorkflow = createGlobalWorkflow("标准Bug工作流");
        WorkflowTemplate requirementWorkflow = createGlobalWorkflow("标准需求工作流");
        workflowProperties.setGlobalWorkflows(Arrays.asList(bugWorkflow, requirementWorkflow));

        ProjectConfig project = createProjectConfig(100, "测试项目");

        // 批量匹配规则 - tracker-type匹配
        TrackerMatchingRule rule1 = new TrackerMatchingRule();
        rule1.setTrackerType("Bug");
        rule1.setWorkflow("标准Bug工作流");

        TrackerMatchingRule rule2 = new TrackerMatchingRule();
        rule2.setTrackerType("Requirement");
        rule2.setWorkflow("标准需求工作流");

        project.setTrackerMatching(Arrays.asList(rule1, rule2));
        workflowProperties.setProjects(Collections.singletonList(project));

        // Bug类型Tracker
        WorkflowTemplate resultBug = workflowConfigService.getWorkflowForTracker(999, "Bug", 100);
        assertNotNull(resultBug);
        assertEquals("标准Bug工作流", resultBug.getName());

        // Requirement类型Tracker
        WorkflowTemplate resultReq = workflowConfigService.getWorkflowForTracker(888, "Requirement", 100);
        assertNotNull(resultReq);
        assertEquals("标准需求工作流", resultReq.getName());
    }

    /**
     * 场景5: 匹配规则按顺序执行（tracker-id优先于tracker-type）
     */
    @Test
    @DisplayName("匹配规则按顺序执行（tracker-id优先）")
    void testGetWorkflowForTracker_MatchOrder() {
        WorkflowTemplate workflow1 = createGlobalWorkflow("流程1");
        WorkflowTemplate workflow2 = createGlobalWorkflow("流程2");
        workflowProperties.setGlobalWorkflows(Arrays.asList(workflow1, workflow2));

        ProjectConfig project = createProjectConfig(100, "测试项目");

        // 多条匹配规则（按顺序）
        TrackerMatchingRule rule1 = new TrackerMatchingRule();
        rule1.setTrackerId(111);
        rule1.setWorkflow("流程1");

        TrackerMatchingRule rule2 = new TrackerMatchingRule();
        rule2.setTrackerType("Bug");
        rule2.setWorkflow("流程2");

        project.setTrackerMatching(Arrays.asList(rule1, rule2));
        workflowProperties.setProjects(Collections.singletonList(project));

        // tracker-id匹配优先
        WorkflowTemplate result = workflowConfigService.getWorkflowForTracker(111, "Bug", 100);
        assertNotNull(result);
        assertEquals("流程1", result.getName());

        // tracker-id不匹配时使用tracker-type
        WorkflowTemplate result2 = workflowConfigService.getWorkflowForTracker(999, "Bug", 100);
        assertNotNull(result2);
        assertEquals("流程2", result2.getName());
    }

    /**
     * 场景6: 状态已声明判断
     */
    @Test
    @DisplayName("状态已声明判断")
    void testIsStateDeclared() {
        WorkflowTemplate workflow = createGlobalWorkflow("测试工作流");

        // 已声明的状态
        assertTrue(workflowConfigService.isStateDeclared(workflow, "新建"));
        assertTrue(workflowConfigService.isStateDeclared(workflow, "处理中"));
        assertTrue(workflowConfigService.isStateDeclared(workflow, "已关闭"));

        // 未声明的状态
        assertFalse(workflowConfigService.isStateDeclared(workflow, "待审核"));
        assertFalse(workflowConfigService.isStateDeclared(workflow, "暂停"));
    }

    /**
     * 场景7: 是否需要通知判断
     */
    @Test
    @DisplayName("是否需要通知判断")
    void testShouldNotify() {
        // 配置了notifyField
        WorkflowTemplate.StateConfig config1 = new WorkflowTemplate.StateConfig();
        config1.setName("处理中");
        config1.setNotifyField(List.of("assignedTo"));
        assertTrue(workflowConfigService.shouldNotify(config1));

        // notify=false
        WorkflowTemplate.StateConfig config2 = new WorkflowTemplate.StateConfig();
        config2.setName("新建");
        config2.setNotify(false);
        assertFalse(workflowConfigService.shouldNotify(config2));

        // notify=false 且无notifyField
        WorkflowTemplate.StateConfig config3 = new WorkflowTemplate.StateConfig();
        config3.setName("已关闭");
        config3.setNotify(false);
        config3.setNotifyField(null);
        assertFalse(workflowConfigService.shouldNotify(config3));

        // 未配置（null）
        WorkflowTemplate.StateConfig config4 = new WorkflowTemplate.StateConfig();
        config4.setName("待审核");
        // 无notify和notifyField，不通知
        assertFalse(workflowConfigService.shouldNotify(config4));
    }

    /**
     * 场景8: Tracker直接定义状态配置（最高优先级）
     */
    @Test
    @DisplayName("Tracker直接定义状态配置")
    void testGetWorkflowForTracker_DirectStateConfig() {
        WorkflowTemplate globalWorkflow = createGlobalWorkflow("全局流程");
        workflowProperties.setGlobalWorkflows(Collections.singletonList(globalWorkflow));

        ProjectConfig project = createProjectConfig(100, "测试项目");

        // Tracker直接定义状态配置
        WorkflowTemplate.StateConfig directState = new WorkflowTemplate.StateConfig();
        directState.setName("特殊状态");
        directState.setNotifyField(List.of("specialField"));

        ProjectConfig.TrackerConfig trackerConfig = new ProjectConfig.TrackerConfig();
        trackerConfig.setTrackerId(111);
        trackerConfig.setStates(Collections.singletonList(directState));

        project.setTrackers(Collections.singletonList(trackerConfig));
        workflowProperties.setProjects(Collections.singletonList(project));

        WorkflowTemplate result = workflowConfigService.getWorkflowForTracker(111, "Bug", 100);

        assertNotNull(result);
        assertEquals(1, result.getStates().size());
        assertEquals("特殊状态", result.getStates().get(0).getName());
        assertEquals(List.of("specialField"), result.getStates().get(0).getNotifyField());
    }

    /**
     * 场景9: 未找到任何配置
     */
    @Test
    @DisplayName("未找到任何配置")
    void testGetWorkflowForTracker_NoConfigFound() {
        WorkflowTemplate result = workflowConfigService.getWorkflowForTracker(999, "Unknown", 999);
        assertNull(result);
    }

    /**
     * 场景10: TrackerMatchingRule类型匹配忽略大小写
     */
    @Test
    @DisplayName("TrackerMatchingRule类型匹配忽略大小写")
    void testTrackerMatchingRule_TypeIgnoreCase() {
        TrackerMatchingRule rule = new TrackerMatchingRule();
        rule.setTrackerType("Bug");

        assertTrue(rule.matchesTrackerType("Bug"));
        assertTrue(rule.matchesTrackerType("bug"));
        assertTrue(rule.matchesTrackerType("BUG"));
        assertFalse(rule.matchesTrackerType("Requirement"));
    }

    /**
     * 场景11: TrackerMatchingRule tracker-id匹配
     */
    @Test
    @DisplayName("TrackerMatchingRule tracker-id匹配")
    void testTrackerMatchingRule_IdMatch() {
        TrackerMatchingRule rule = new TrackerMatchingRule();
        rule.setTrackerId(111);

        assertTrue(rule.matchesTrackerId(111));
        assertFalse(rule.matchesTrackerId(222));
        assertFalse(rule.matchesTrackerId(null));
    }

    /**
     * 场景12: type-mappings全局查找
     */
    @Test
    @DisplayName("type-mappings全局查找")
    void testGetTypeMapping_Global() {
        workflowProperties.getTypeMappings().getGlobal().put("Bug", "缺陷");
        workflowProperties.getTypeMappings().getGlobal().put("Requirement", "需求");

        assertEquals("缺陷", workflowConfigService.getTypeMapping("Bug", null));
        assertEquals("需求", workflowConfigService.getTypeMapping("Requirement", null));
        assertEquals("Task", workflowConfigService.getTypeMapping("Task", null)); // 未配置返回原值
    }

    /**
     * 场景13: type-mappings项目级覆盖
     */
    @Test
    @DisplayName("type-mappings项目级覆盖")
    void testGetTypeMapping_ProjectOverride() {
        workflowProperties.getTypeMappings().getGlobal().put("Bug", "缺陷");
        workflowProperties.getTypeMappings().getProjects().put("100", new java.util.HashMap<>());
        workflowProperties.getTypeMappings().getProjects().get("100").put("Bug", "智驾缺陷");

        // 项目级覆盖全局
        assertEquals("智驾缺陷", workflowConfigService.getTypeMapping("Bug", 100));
        // 其他项目使用全局
        assertEquals("缺陷", workflowConfigService.getTypeMapping("Bug", 200));
    }

    /**
     * 场景14: extra-fields全局查找
     */
    @Test
    @DisplayName("extra-fields全局查找")
    void testGetExtraFields_Global() {
        ExtraField field1 = new ExtraField();
        field1.setField("priority");
        field1.setLabel("优先级");

        ExtraField field2 = new ExtraField();
        field2.setField("severity");
        field2.setLabel("严重程度");

        workflowProperties.getExtraFields().setGlobal(Arrays.asList(field1, field2));

        List<ExtraField> result = workflowConfigService.getExtraFields(null, null);
        assertEquals(2, result.size());
        assertEquals("priority", result.get(0).getField());
        assertEquals("优先级", result.get(0).getLabel());
    }

    /**
     * 场景15: extra-fields项目级完全覆盖
     */
    @Test
    @DisplayName("extra-fields项目级完全覆盖")
    void testGetExtraFields_ProjectOverride() {
        ExtraField globalField = new ExtraField();
        globalField.setField("priority");
        globalField.setLabel("优先级");
        workflowProperties.getExtraFields().setGlobal(Collections.singletonList(globalField));

        ExtraField projectField = new ExtraField();
        projectField.setField("customer");
        projectField.setLabel("客户");
        workflowProperties.getExtraFields().getProjects().put("100", Collections.singletonList(projectField));

        // 项目级完全覆盖全局
        List<ExtraField> result100 = workflowConfigService.getExtraFields(100, null);
        assertEquals(1, result100.size());
        assertEquals("customer", result100.get(0).getField());

        // 其他项目使用全局
        List<ExtraField> result200 = workflowConfigService.getExtraFields(200, null);
        assertEquals(1, result200.size());
        assertEquals("priority", result200.get(0).getField());
    }

    /**
     * 场景16: extra-fields tracker级覆盖项目级
     */
    @Test
    @DisplayName("extra-fields tracker级覆盖项目级")
    void testGetExtraFields_TrackerOverride() {
        // 全局配置
        ExtraField globalField = new ExtraField();
        globalField.setField("priority");
        globalField.setLabel("优先级");
        workflowProperties.getExtraFields().setGlobal(Collections.singletonList(globalField));

        // 项目级配置
        ExtraField projectField = new ExtraField();
        projectField.setField("customer");
        projectField.setLabel("客户");
        workflowProperties.getExtraFields().getProjects().put("100", Collections.singletonList(projectField));

        // 项目配置
        ProjectConfig project = createProjectConfig(100, "测试项目");

        // tracker级配置
        ExtraField trackerField = new ExtraField();
        trackerField.setField("dueDate");
        trackerField.setLabel("截止日期");

        ProjectConfig.TrackerConfig trackerConfig = new ProjectConfig.TrackerConfig();
        trackerConfig.setTrackerId(111);
        trackerConfig.setExtraFields(Collections.singletonList(trackerField));

        project.setTrackers(Collections.singletonList(trackerConfig));
        workflowProperties.setProjects(Collections.singletonList(project));

        // tracker级完全覆盖项目级
        List<ExtraField> resultTracker = workflowConfigService.getExtraFields(100, 111);
        assertEquals(1, resultTracker.size());
        assertEquals("dueDate", resultTracker.get(0).getField());

        // 其他tracker使用项目级
        List<ExtraField> resultOtherTracker = workflowConfigService.getExtraFields(100, 222);
        assertEquals(1, resultOtherTracker.size());
        assertEquals("customer", resultOtherTracker.get(0).getField());

        // 无trackerId参数使用项目级
        List<ExtraField> resultNoTracker = workflowConfigService.getExtraFields(100, null);
        assertEquals(1, resultNoTracker.size());
        assertEquals("customer", resultNoTracker.get(0).getField());
    }

    /**
     * 场景17: extra-fields tracker级空列表（不显示任何额外字段）
     */
    @Test
    @DisplayName("extra-fields tracker级空列表")
    void testGetExtraFields_TrackerEmptyList() {
        // 全局配置
        ExtraField globalField = new ExtraField();
        globalField.setField("priority");
        globalField.setLabel("优先级");
        workflowProperties.getExtraFields().setGlobal(Collections.singletonList(globalField));

        // 项目配置
        ProjectConfig project = createProjectConfig(100, "测试项目");

        // tracker级配置为空列表（显式设置为无额外字段）
        ProjectConfig.TrackerConfig trackerConfig = new ProjectConfig.TrackerConfig();
        trackerConfig.setTrackerId(111);
        trackerConfig.setExtraFields(Collections.emptyList());

        project.setTrackers(Collections.singletonList(trackerConfig));
        workflowProperties.setProjects(Collections.singletonList(project));

        // tracker级空列表，返回空列表
        List<ExtraField> result = workflowConfigService.getExtraFields(100, 111);
        assertEquals(0, result.size());
    }

    @Test
    void testGetNotifyFields_SingleField() {
        WorkflowTemplate.StateConfig stateConfig = new WorkflowTemplate.StateConfig();
        stateConfig.setNotifyField(List.of("assignedTo"));

        List<String> result = workflowConfigService.getNotifyFields(stateConfig);

        assertEquals(1, result.size());
        assertEquals("assignedTo", result.get(0));
    }

    @Test
    void testGetNotifyFields_MultipleFields() {
        WorkflowTemplate.StateConfig stateConfig = new WorkflowTemplate.StateConfig();
        stateConfig.setNotifyField(List.of("assignedTo", "supervisors"));

        List<String> result = workflowConfigService.getNotifyFields(stateConfig);

        assertEquals(2, result.size());
        assertEquals("assignedTo", result.get(0));
        assertEquals("supervisors", result.get(1));
    }

    @Test
    void testGetNotifyFields_EmptyField() {
        WorkflowTemplate.StateConfig stateConfig = new WorkflowTemplate.StateConfig();
        stateConfig.setNotifyField(new ArrayList<>());

        List<String> result = workflowConfigService.getNotifyFields(stateConfig);

        assertEquals(0, result.size());
    }

    @Test
    void testGetNotifyFields_NullField() {
        WorkflowTemplate.StateConfig stateConfig = new WorkflowTemplate.StateConfig();
        stateConfig.setNotifyField(null);

        List<String> result = workflowConfigService.getNotifyFields(stateConfig);

        assertEquals(0, result.size());
    }

    @Test
    void testGetNotifyFields_NullStateConfig() {
        List<String> result = workflowConfigService.getNotifyFields(null);

        assertEquals(0, result.size());
    }
}