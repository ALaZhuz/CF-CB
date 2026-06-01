package org.example.workflow.service;

import org.example.db.entity.ItemStateRecord;
import org.example.db.mapper.ItemStateRecordMapper;
import org.example.model.cb.Tracker;
import org.example.model.dto.response.CBQueryResponse;
import org.example.model.dto.response.ItemInfoResponse;
import org.example.model.cb.TrackerItem;
import org.example.service.CBSwaggerService;
import org.example.workflow.config.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * InitService单元测试
 *
 * 测试场景：
 * 1. 全量初始化 - 已初始化跳过
 * 2. 全量初始化 - 未初始化执行
 * 3. 按项目补录 - 成功
 * 4. 条目已存在跳过写入
 * 5. 获取进入状态时间
 *
 * @author system
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class InitServiceTest {

    @Mock
    private ConfigMetaService configMetaService;

    @Mock(lenient = true)
    private WorkflowConfigService workflowConfigService;

    @Mock(lenient = true)
    private CBSwaggerService cbSwaggerService;

    @Mock(lenient = true)
    private ItemStateRecordMapper itemStateRecordMapper;

    @InjectMocks
    private InitService initService;

    /**
     * 场景1: 全量初始化 - 已初始化跳过
     */
    @Test
    @DisplayName("全量初始化 - 已初始化跳过")
    void testRunInitialization_AlreadyInitialized() {
        when(configMetaService.checkInitialized()).thenReturn(true);

        initService.runInitialization();

        verify(cbSwaggerService, never()).getAllUsers();
        verify(itemStateRecordMapper, never()).insert(any());
    }

    /**
     * 场景2: 按项目补录 - 项目不存在
     */
    @Test
    @DisplayName("按项目补录 - 项目不存在")
    void test补录Project_ProjectNotFound() {
        when(workflowConfigService.findProjectConfig(999)).thenReturn(null);

        InitService.InitResult result = initService.补录Project(999);

        assertEquals(0, result.getProcessed());
        assertEquals(0, result.getInserted());
        assertEquals(0, result.getSkipped());
    }

    /**
     * 场景3: 条目已存在跳过写入
     */
    @Test
    @DisplayName("条目已存在跳过写入")
    void test补录Project_ItemExists() {
        // 设置项目配置
        ProjectConfig project = new ProjectConfig();
        project.setProjectId(100);
        project.setProjectName("测试项目");

        TrackerMatchingRule rule = new TrackerMatchingRule();
        rule.setTrackerId(111);
        rule.setWorkflow("测试流程");

        project.setTrackerMatching(List.of(rule));
        when(workflowConfigService.findProjectConfig(100)).thenReturn(project);

        // 设置工作流
        WorkflowTemplate workflow = new WorkflowTemplate();
        workflow.setName("测试流程");
        WorkflowTemplate.StateConfig state = new WorkflowTemplate.StateConfig();
        state.setName("处理中");
        state.setNotifyField("assignedTo");
        state.setScheduledNotify(true);
        workflow.setStates(List.of(state));
        when(workflowConfigService.findWorkflowByName("测试流程", project)).thenReturn(workflow);
        when(workflowConfigService.getScheduledNotify(state)).thenReturn(true);

        // 模拟查询返回条目
        TrackerItem item = new TrackerItem();
        item.setId(123);
        item.setName("测试条目");
        Tracker tracker = new Tracker();
        tracker.setId(111);
        item.setTracker(tracker);

        CBQueryResponse response = new CBQueryResponse();
        response.setItems(List.of(item));
        response.setTotal(1);
        when(cbSwaggerService.query(anyInt(), anyInt(), anyString())).thenReturn(response);

        // 条目已存在
        ItemStateRecord existing = new ItemStateRecord();
        existing.setItemId(123);
        when(itemStateRecordMapper.selectByItemId(123)).thenReturn(existing);

        InitService.InitResult result = initService.补录Project(100);

        assertTrue(result.getProcessed() > 0);
        assertTrue(result.getSkipped() > 0);
        assertEquals(0, result.getInserted());
        verify(itemStateRecordMapper, never()).insert(any());
    }

    /**
     * 场景4: 手动触发初始化
     */
    @Test
    @DisplayName("手动触发初始化")
    void testManualInit() {
        when(configMetaService.checkInitialized()).thenReturn(true);
        doNothing().when(configMetaService).resetInitialized();

        // 项目配置为空列表
        WorkflowProperties properties = new WorkflowProperties();
        properties.setProjects(Collections.emptyList());
        when(workflowConfigService.getWorkflowProperties()).thenReturn(properties);

        initService.manualInit();

        verify(configMetaService).resetInitialized();
    }
}