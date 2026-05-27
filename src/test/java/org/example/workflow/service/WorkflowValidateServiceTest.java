package org.example.workflow.service;

import org.example.model.dto.response.ItemInfoResponse;
import org.example.service.CBSwaggerService;
import org.example.workflow.cache.DingUserCacheService;
import org.example.workflow.config.WorkflowConfigService;
import org.example.workflow.config.WorkflowTemplate;
import org.example.workflow.dto.ValidateRequest;
import org.example.workflow.dto.ValidateResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * WorkflowValidateService单元测试
 *
 * 测试三项校验场景：
 * 1. 目标状态未在配置中声明 - 校验失败
 * 2. 通知字段未填写成员 - 校验失败
 * 3. userid在钉钉不存在 - 校验失败
 * 4. 状态配置notify:false - 放行保存
 * 5. 正常场景 - 校验通过
 *
 * @author system
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class WorkflowValidateServiceTest {

    @Mock
    private WorkflowConfigService workflowConfigService;

    @Mock
    private CBSwaggerService cbSwaggerService;

    @Mock
    private DingUserCacheService dingUserCacheService;

    @InjectMocks
    private WorkflowValidateService workflowValidateService;

    private ValidateRequest request;
    private ItemInfoResponse itemInfo;
    private WorkflowTemplate workflow;
    private WorkflowTemplate.StateConfig stateConfig;

    /**
     * 测试数据初始化
     */
    @BeforeEach
    void setUp() {
        // 构建基础请求
        request = new ValidateRequest();
        request.setItemId(12345);
        request.setTargetState("处理中");

        // 构建条目详情
        itemInfo = new ItemInfoResponse();
        itemInfo.setId(12345);
        itemInfo.setName("测试需求A");

        ItemInfoResponse.TrackerInfo tracker = new ItemInfoResponse.TrackerInfo();
        tracker.setId(111);
        tracker.setName("缺陷Tracker");
        tracker.setTypeName("Bug");
        itemInfo.setTracker(tracker);

        ItemInfoResponse.ProjectInfo project = new ItemInfoResponse.ProjectInfo();
        project.setId(100);
        project.setName("测试项目");
        itemInfo.setProject(project);

        // 构建工作流模板
        workflow = new WorkflowTemplate();
        workflow.setName("标准Bug工作流");

        // 构建状态配置
        stateConfig = new WorkflowTemplate.StateConfig();
        stateConfig.setName("处理中");
        stateConfig.setNotifyField("assignedTo");
    }

    /**
     * 场景1: 条目不存在 - 校验失败
     */
    @Test
    @DisplayName("条目不存在 - 校验失败")
    void testValidate_ItemNotFound() {
        when(cbSwaggerService.getItemInfo(anyInt())).thenReturn(null);

        ValidateResponse response = workflowValidateService.validate(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("条目不存在"));
    }

    /**
     * 场景2: 未找到工作流配置 - 校验失败
     */
    @Test
    @DisplayName("未找到工作流配置 - 校验失败")
    void testValidate_NoWorkflowConfig() {
        when(cbSwaggerService.getItemInfo(anyInt())).thenReturn(itemInfo);
        when(workflowConfigService.getWorkflowForTracker(anyInt(), anyString(), anyInt()))
                .thenReturn(null);

        ValidateResponse response = workflowValidateService.validate(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("未找到tracker配置"));
    }

    /**
     * 场景3: 目标状态未在配置中声明 - 校验失败
     */
    @Test
    @DisplayName("目标状态未在配置中声明 - 校验失败")
    void testValidate_StateNotDeclared() {
        request.setTargetState("未知状态");

        when(cbSwaggerService.getItemInfo(anyInt())).thenReturn(itemInfo);
        when(workflowConfigService.getWorkflowForTracker(anyInt(), anyString(), anyInt()))
                .thenReturn(workflow);
        when(workflowConfigService.isStateDeclared(any(WorkflowTemplate.class), anyString()))
                .thenReturn(false);

        ValidateResponse response = workflowValidateService.validate(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("未在工作流模板"));
        assertTrue(response.getErrorMessage().contains("中配置"));
    }

    /**
     * 场景4: 状态配置notify:false - 放行保存
     */
    @Test
    @DisplayName("状态配置notify:false - 放行保存")
    void testValidate_StateNotifyFalse() {
        stateConfig.setNotify(false);
        stateConfig.setNotifyField(null);
        workflow.setStates(Collections.singletonList(stateConfig));

        when(cbSwaggerService.getItemInfo(anyInt())).thenReturn(itemInfo);
        when(workflowConfigService.getWorkflowForTracker(anyInt(), anyString(), anyInt()))
                .thenReturn(workflow);
        when(workflowConfigService.isStateDeclared(any(WorkflowTemplate.class), anyString()))
                .thenReturn(true);
        when(workflowConfigService.getStateConfig(any(WorkflowTemplate.class), anyString()))
                .thenReturn(stateConfig);
        when(workflowConfigService.shouldNotify(any(WorkflowTemplate.StateConfig.class)))
                .thenReturn(false);

        ValidateResponse response = workflowValidateService.validate(request);

        assertTrue(response.isSuccess());
        assertNull(response.getErrorMessage());
    }

    /**
     * 场景5: 通知字段未填写成员 - 校验失败
     */
    @Test
    @DisplayName("通知字段未填写成员 - 校验失败")
    void testValidate_NotifyFieldEmpty() {
        itemInfo.setAssignedTo(Collections.emptyList());
        workflow.setStates(Collections.singletonList(stateConfig));

        when(cbSwaggerService.getItemInfo(anyInt())).thenReturn(itemInfo);
        when(workflowConfigService.getWorkflowForTracker(anyInt(), anyString(), anyInt()))
                .thenReturn(workflow);
        when(workflowConfigService.isStateDeclared(any(WorkflowTemplate.class), anyString()))
                .thenReturn(true);
        when(workflowConfigService.getStateConfig(any(WorkflowTemplate.class), anyString()))
                .thenReturn(stateConfig);
        when(workflowConfigService.shouldNotify(any(WorkflowTemplate.StateConfig.class)))
                .thenReturn(true);

        ValidateResponse response = workflowValidateService.validate(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("通知字段"));
        assertTrue(response.getErrorMessage().contains("未填写成员"));
    }

    /**
     * 场景6: userid在钉钉不存在 - 校验失败
     */
    @Test
    @DisplayName("userid在钉钉不存在 - 校验失败")
    void testValidate_UseridNotInDingTalk() {
        ItemInfoResponse.MemberInfo member1 = new ItemInfoResponse.MemberInfo();
        member1.setUserId("user123");
        member1.setName("张三");

        ItemInfoResponse.MemberInfo member2 = new ItemInfoResponse.MemberInfo();
        member2.setUserId("user456");
        member2.setName("李四");

        itemInfo.setAssignedTo(Arrays.asList(member1, member2));
        workflow.setStates(Collections.singletonList(stateConfig));

        when(cbSwaggerService.getItemInfo(anyInt())).thenReturn(itemInfo);
        when(workflowConfigService.getWorkflowForTracker(anyInt(), anyString(), anyInt()))
                .thenReturn(workflow);
        when(workflowConfigService.isStateDeclared(any(WorkflowTemplate.class), anyString()))
                .thenReturn(true);
        when(workflowConfigService.getStateConfig(any(WorkflowTemplate.class), anyString()))
                .thenReturn(stateConfig);
        when(workflowConfigService.shouldNotify(any(WorkflowTemplate.StateConfig.class)))
                .thenReturn(true);
        when(dingUserCacheService.findInvalidUserIds(any(List.class)))
                .thenReturn(new HashSet<>(Collections.singletonList("user456")));

        ValidateResponse response = workflowValidateService.validate(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("userid在钉钉中不存在"));
        assertNotNull(response.getInvalidUserIds());
        assertTrue(response.getInvalidUserIds().contains("user456"));
    }

    /**
     * 场景7: 正常场景 - 校验通过
     */
    @Test
    @DisplayName("正常场景 - 校验通过")
    void testValidate_Success() {
        ItemInfoResponse.MemberInfo member1 = new ItemInfoResponse.MemberInfo();
        member1.setUserId("user123");
        member1.setName("张三");
        member1.setDisplayName("张三");

        ItemInfoResponse.MemberInfo member2 = new ItemInfoResponse.MemberInfo();
        member2.setUserId("user456");
        member2.setName("李四");
        member2.setDisplayName("李四");

        itemInfo.setAssignedTo(Arrays.asList(member1, member2));
        workflow.setStates(Collections.singletonList(stateConfig));

        when(cbSwaggerService.getItemInfo(anyInt())).thenReturn(itemInfo);
        when(workflowConfigService.getWorkflowForTracker(anyInt(), anyString(), anyInt()))
                .thenReturn(workflow);
        when(workflowConfigService.isStateDeclared(any(WorkflowTemplate.class), anyString()))
                .thenReturn(true);
        when(workflowConfigService.getStateConfig(any(WorkflowTemplate.class), anyString()))
                .thenReturn(stateConfig);
        when(workflowConfigService.shouldNotify(any(WorkflowTemplate.StateConfig.class)))
                .thenReturn(true);
        when(dingUserCacheService.findInvalidUserIds(any(List.class)))
                .thenReturn(new HashSet<>());

        ValidateResponse response = workflowValidateService.validate(request);

        assertTrue(response.isSuccess());
        assertNull(response.getErrorMessage());
        assertEquals("assignedTo", response.getNotifyField());
        assertNotNull(response.getNotifyMembers());
        assertEquals(2, response.getNotifyMembers().size());
    }

    /**
     * 场景8: 校验异常 - 返回错误信息
     */
    @Test
    @DisplayName("校验异常 - 返回错误信息")
    void testValidate_Exception() {
        when(cbSwaggerService.getItemInfo(anyInt())).thenThrow(new RuntimeException("API调用失败"));

        ValidateResponse response = workflowValidateService.validate(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("校验异常"));
    }
}