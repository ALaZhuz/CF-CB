package org.example.workflow.service;

import org.example.db.entity.ItemStateRecord;
import org.example.db.mapper.ItemStateRecordMapper;
import org.example.db.mapper.NotifyLogMapper;
import org.example.model.dto.response.ItemInfoResponse;
import org.example.service.CBSwaggerService;
import org.example.service.DingService;
import org.example.workflow.config.ExtraField;
import org.example.workflow.config.WorkflowConfigService;
import org.example.workflow.config.WorkflowTemplate;
import org.example.workflow.dto.NotifyRequest;
import org.example.workflow.dto.NotifyResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * WorkflowNotifyService单元测试
 *
 * 测试场景：
 * 1. 进入目标状态 - 发送通知并记录状态
 * 2. 离开目标状态 - 删除状态记录
 * 3. 状态之间互转 - 发送通知并更新记录
 * 4. 条目不存在 - 返回错误
 * 5. 发送通知失败 - 记录失败日志
 *
 * @author system
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class WorkflowNotifyServiceTest {

    @Mock
    private WorkflowConfigService workflowConfigService;

    @Mock
    private CBSwaggerService cbSwaggerService;

    @Mock
    private DingService dingService;

    @Mock
    private ItemStateRecordMapper itemStateRecordMapper;

    @Mock
    private NotifyLogMapper notifyLogMapper;

    @InjectMocks
    private WorkflowNotifyService workflowNotifyService;

    private NotifyRequest request;
    private ItemInfoResponse itemInfo;
    private WorkflowTemplate workflow;
    private WorkflowTemplate.StateConfig stateConfig;

    /**
     * 测试数据初始化
     */
    @BeforeEach
    void setUp() {
        // 构建请求
        request = new NotifyRequest();
        request.setItemId(12345);
        request.setPreviousState("新建");
        request.setTargetState("处理中");

        // 构建条目详情
        itemInfo = new ItemInfoResponse();
        itemInfo.setId(12345);
        itemInfo.setName("测试需求A");
        itemInfo.setItemLink("http://cb-trial.hirain.com/issue/12345");

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
     * 场景1: 条目不存在 - 返回错误
     */
    @Test
    @DisplayName("条目不存在 - 返回错误")
    void testNotify_ItemNotFound() {
        when(cbSwaggerService.getItemInfo(anyInt())).thenReturn(null);

        NotifyResponse response = workflowNotifyService.notify(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("条目不存在"));
    }

    /**
     * 场景2: 未找到工作流配置 - 无需处理
     */
    @Test
    @DisplayName("未找到工作流配置 - 无需处理")
    void testNotify_NoWorkflowConfig() {
        when(cbSwaggerService.getItemInfo(anyInt())).thenReturn(itemInfo);
        when(workflowConfigService.getWorkflowForTracker(anyInt(), anyString(), anyInt()))
                .thenReturn(null);

        NotifyResponse response = workflowNotifyService.notify(request);

        assertTrue(response.isSuccess());
        assertEquals("无需处理", response.getActionType());
    }

    /**
     * 场景3: 离开目标状态 - 删除状态记录
     *
     * previousState="处理中"（需要通知），targetState="已关闭"（不需要通知）
     */
    @Test
    @DisplayName("离开目标状态 - 删除状态记录")
    void testNotify_LeaveTargetState() {
        request.setPreviousState("处理中");
        request.setTargetState("已关闭");

        // previousState配置（需要通知）
        WorkflowTemplate.StateConfig previousStateConfig = new WorkflowTemplate.StateConfig();
        previousStateConfig.setName("处理中");
        previousStateConfig.setNotifyField("assignedTo");

        when(cbSwaggerService.getItemInfo(anyInt())).thenReturn(itemInfo);
        when(workflowConfigService.getWorkflowForTracker(anyInt(), anyString(), anyInt()))
                .thenReturn(workflow);
        // previousState需要通知
        when(workflowConfigService.getStateConfig(any(WorkflowTemplate.class), eq("处理中")))
                .thenReturn(previousStateConfig);
        when(workflowConfigService.shouldNotify(previousStateConfig)).thenReturn(true);
        // targetState不需要通知
        when(workflowConfigService.getStateConfig(any(WorkflowTemplate.class), eq("已关闭")))
                .thenReturn(null);
        when(workflowConfigService.shouldNotify(null)).thenReturn(false);

        NotifyResponse response = workflowNotifyService.notify(request);

        assertTrue(response.isSuccess());
        assertEquals("离开状态", response.getActionType());
        verify(itemStateRecordMapper).deleteByItemId(12345);
//        verify(dingService, never()).sendTextMessage(anyString(), anyString());
    }

    /**
     * 场景4: 目标状态不需要通知 - 无需处理
     */
    @Test
    @DisplayName("目标状态不需要通知 - 无需处理")
    void testNotify_StateNoNotification() {
        stateConfig.setNotifyField(null);
        stateConfig.setNotify(false);
        workflow.setStates(Collections.singletonList(stateConfig));

        when(cbSwaggerService.getItemInfo(anyInt())).thenReturn(itemInfo);
        when(workflowConfigService.getWorkflowForTracker(anyInt(), anyString(), anyInt()))
                .thenReturn(workflow);
        // previousState不需要通知
        when(workflowConfigService.getStateConfig(any(WorkflowTemplate.class), eq("新建")))
                .thenReturn(null);
        when(workflowConfigService.shouldNotify(null)).thenReturn(false);
        // targetState不需要通知
        when(workflowConfigService.getStateConfig(any(WorkflowTemplate.class), eq("处理中")))
                .thenReturn(stateConfig);
        when(workflowConfigService.shouldNotify(stateConfig)).thenReturn(false);

        NotifyResponse response = workflowNotifyService.notify(request);

        assertTrue(response.isSuccess());
        assertEquals("无需处理", response.getActionType());
//        verify(dingService, never()).sendTextMessage(anyString(), anyString());
    }

    /**
     * 场景5: 进入目标状态 - 发送通知成功
     */
    @Test
    @DisplayName("进入目标状态 - 发送通知成功")
    void testNotify_EnterTargetState_Success() {
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
        // previousState不需要通知
        when(workflowConfigService.getStateConfig(any(WorkflowTemplate.class), eq("新建")))
                .thenReturn(null);
        when(workflowConfigService.shouldNotify(null)).thenReturn(false);
        // targetState需要通知
        when(workflowConfigService.getStateConfig(any(WorkflowTemplate.class), eq("处理中")))
                .thenReturn(stateConfig);
        when(workflowConfigService.shouldNotify(stateConfig)).thenReturn(true);
        // Mock type-mappings and extra-fields
        when(workflowConfigService.getTypeMapping(anyString(), anyInt())).thenReturn("缺陷");
        when(workflowConfigService.getExtraFields(anyInt(), anyInt())).thenReturn(Collections.emptyList());
//        doNothing().when(dingService).sendTextMessage(anyString(), anyString());
        doNothing().when(itemStateRecordMapper).insert(any(ItemStateRecord.class));
        doNothing().when(notifyLogMapper).insert(any());

        NotifyResponse response = workflowNotifyService.notify(request);

        assertTrue(response.isSuccess());
        assertEquals("进入状态", response.getActionType());
        assertEquals(2, response.getNotifiedUsers().size());
        assertTrue(response.getNotifiedUsers().contains("user123"));
        assertTrue(response.getNotifiedUsers().contains("user456"));
        assertTrue(response.getFailedUsers().isEmpty());

//        verify(dingService, times(2)).sendTextMessage(anyString(), anyString());
        verify(itemStateRecordMapper).insert(any(ItemStateRecord.class));
        verify(notifyLogMapper, times(2)).insert(any());
    }

    /**
     * 场景6: 发送通知失败 - 记录失败日志
     */
    @Test
    @DisplayName("发送通知部分失败 - 记录失败日志")
    void testNotify_SendPartialFailure() {
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
        // previousState不需要通知
        when(workflowConfigService.getStateConfig(any(WorkflowTemplate.class), eq("新建")))
                .thenReturn(null);
        when(workflowConfigService.shouldNotify(null)).thenReturn(false);
        // targetState需要通知
        when(workflowConfigService.getStateConfig(any(WorkflowTemplate.class), eq("处理中")))
                .thenReturn(stateConfig);
        when(workflowConfigService.shouldNotify(stateConfig)).thenReturn(true);
        when(workflowConfigService.getTypeMapping(anyString(), anyInt())).thenReturn("缺陷");
        when(workflowConfigService.getExtraFields(anyInt(), anyInt())).thenReturn(Collections.emptyList());

//        doAnswer(invocation -> {
//            String userid = invocation.getArgument(0);
//            if ("user456".equals(userid)) {
//                throw new RuntimeException("钉钉API调用失败");
//            }
//            return null;
//        }).when(dingService).sendTextMessage(anyString(), anyString());

        doNothing().when(itemStateRecordMapper).insert(any(ItemStateRecord.class));
        doNothing().when(notifyLogMapper).insert(any());

        NotifyResponse response = workflowNotifyService.notify(request);

        assertTrue(response.isSuccess());
        assertEquals("进入状态", response.getActionType());
        assertEquals(1, response.getNotifiedUsers().size());
        assertTrue(response.getNotifiedUsers().contains("user123"));
        assertEquals(1, response.getFailedUsers().size());
        assertTrue(response.getFailedUsers().contains("user456"));
    }

    /**
     * 场景7: 通知字段无成员 - 跳过通知
     */
    @Test
    @DisplayName("通知字段无成员 - 跳过通知")
    void testNotify_NoMembers() {
        itemInfo.setAssignedTo(Collections.emptyList());
        workflow.setStates(Collections.singletonList(stateConfig));

        when(cbSwaggerService.getItemInfo(anyInt())).thenReturn(itemInfo);
        when(workflowConfigService.getWorkflowForTracker(anyInt(), anyString(), anyInt()))
                .thenReturn(workflow);
        // previousState不需要通知
        when(workflowConfigService.getStateConfig(any(WorkflowTemplate.class), eq("新建")))
                .thenReturn(null);
        when(workflowConfigService.shouldNotify(null)).thenReturn(false);
        // targetState需要通知
        when(workflowConfigService.getStateConfig(any(WorkflowTemplate.class), eq("处理中")))
                .thenReturn(stateConfig);
        when(workflowConfigService.shouldNotify(stateConfig)).thenReturn(true);

        NotifyResponse response = workflowNotifyService.notify(request);

        assertTrue(response.isSuccess());
        assertEquals("无需通知", response.getActionType());
//        verify(dingService, never()).sendTextMessage(anyString(), anyString());
    }

    /**
     * 场景8: 处理异常 - 返回错误信息
     */
    @Test
    @DisplayName("处理异常 - 返回错误信息")
    void testNotify_Exception() {
        when(cbSwaggerService.getItemInfo(anyInt())).thenThrow(new RuntimeException("API调用失败"));

        NotifyResponse response = workflowNotifyService.notify(request);

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("处理异常"));
    }

    /**
     * 场景9: 使用type-mappings和extra-fields格式化消息
     */
    @Test
    @DisplayName("使用type-mappings和extra-fields格式化消息")
    void testNotify_MessageFormatting() {
        ItemInfoResponse.MemberInfo member = new ItemInfoResponse.MemberInfo();
        member.setUserId("user123");
        member.setName("张三");
        member.setDisplayName("张三");
        itemInfo.setAssignedTo(Collections.singletonList(member));
        workflow.setStates(Collections.singletonList(stateConfig));

        ExtraField extraField = new ExtraField();
        extraField.setField("priority");
        extraField.setLabel("优先级");

        ItemInfoResponse.CustomField customField = new ItemInfoResponse.CustomField();
        customField.setName("priority");
        customField.setLabel("优先级");
        ItemInfoResponse.MemberInfo priorityValue = new ItemInfoResponse.MemberInfo();
        priorityValue.setName("高");
        customField.setValues(Collections.singletonList(priorityValue));
        itemInfo.setCustomFields(Collections.singletonList(customField));

        when(cbSwaggerService.getItemInfo(anyInt())).thenReturn(itemInfo);
        when(workflowConfigService.getWorkflowForTracker(anyInt(), anyString(), anyInt()))
                .thenReturn(workflow);
        // previousState不需要通知
        when(workflowConfigService.getStateConfig(any(WorkflowTemplate.class), eq("新建")))
                .thenReturn(null);
        when(workflowConfigService.shouldNotify(null)).thenReturn(false);
        // targetState需要通知
        when(workflowConfigService.getStateConfig(any(WorkflowTemplate.class), eq("处理中")))
                .thenReturn(stateConfig);
        when(workflowConfigService.shouldNotify(stateConfig)).thenReturn(true);
        when(workflowConfigService.getTypeMapping(eq("Bug"), anyInt())).thenReturn("智驾缺陷");
        when(workflowConfigService.getExtraFields(anyInt(), anyInt())).thenReturn(Collections.singletonList(extraField));
//        doNothing().when(dingService).sendTextMessage(anyString(), anyString());
        doNothing().when(itemStateRecordMapper).insert(any(ItemStateRecord.class));
        doNothing().when(notifyLogMapper).insert(any());

        NotifyResponse response = workflowNotifyService.notify(request);

        assertTrue(response.isSuccess());
//        verify(dingService).sendTextMessage(eq("user123"), anyString());
    }
}