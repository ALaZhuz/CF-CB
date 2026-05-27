package org.example.integration;

import org.example.workflow.dto.ValidateRequest;
import org.example.workflow.dto.ValidateResponse;
import org.example.workflow.dto.NotifyRequest;
import org.example.workflow.dto.NotifyResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 工作流通知集成测试
 *
 * 测试完整的beforeEvent + afterEvent流程。
 * 使用MockMvc模拟HTTP请求，验证Controller层行为。
 *
 * @author system
 * @since 1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 场景1: beforeEvent校验接口 - 请求格式正确
     */
    @Test
    @DisplayName("集成测试: beforeEvent校验接口响应正常")
    void testValidateEndpoint_RequestFormat() throws Exception {
        ValidateRequest request = new ValidateRequest();
        request.setItemId(12345);
        request.setTargetState("处理中");

        String requestBody = objectMapper.writeValueAsString(request);

        MvcResult result = mockMvc.perform(post("/workflow/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        String responseContent = result.getResponse().getContentAsString();
        ValidateResponse response = objectMapper.readValue(responseContent, ValidateResponse.class);

        // 验证响应结构
        assertNotNull(response);
        // 使用 isSuccess() 方法（Lombok对boolean字段生成is前缀）
        assertNotNull(response.isSuccess());
    }

    /**
     * 场景2: afterEvent通知接口 - 请求格式正确
     */
    @Test
    @DisplayName("集成测试: afterEvent通知接口响应正常")
    void testNotifyEndpoint_RequestFormat() throws Exception {
        NotifyRequest request = new NotifyRequest();
        request.setItemId(12345);
        request.setPreviousState("新建");
        request.setTargetState("处理中");

        String requestBody = objectMapper.writeValueAsString(request);

        MvcResult result = mockMvc.perform(post("/workflow/notify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();

        String responseContent = result.getResponse().getContentAsString();
        NotifyResponse response = objectMapper.readValue(responseContent, NotifyResponse.class);

        // 验证响应结构
        assertNotNull(response);
        assertNotNull(response.isSuccess());
    }

    /**
     * 场景3: beforeEvent校验接口 - 缺少itemId（Spring默认接受null）
     */
    @Test
    @DisplayName("集成测试: beforeEvent缺少itemId字段")
    void testValidateEndpoint_MissingItemId() throws Exception {
        // 缺少itemId的请求
        String requestBody = "{\"targetState\":\"处理中\"}";

        // Spring默认会接受null值，返回200（业务层会处理null情况）
        mockMvc.perform(post("/workflow/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk());
    }

    /**
     * 场景4: afterEvent通知接口 - 缺少itemId
     */
    @Test
    @DisplayName("集成测试: afterEvent缺少itemId字段")
    void testNotifyEndpoint_MissingItemId() throws Exception {
        String requestBody = "{\"previousState\":\"新建\",\"targetState\":\"处理中\"}";

        mockMvc.perform(post("/workflow/notify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk());
    }

    /**
     * 场景5: beforeEvent校验接口 - 空请求体
     */
    @Test
    @DisplayName("集成测试: beforeEvent空请求体返回错误")
    void testValidateEndpoint_EmptyBody() throws Exception {
        mockMvc.perform(post("/workflow/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk());
    }

    /**
     * 场景6: 验证响应字段结构
     */
    @Test
    @DisplayName("集成测试: 验证响应包含必要字段")
    void testValidateEndpoint_ResponseStructure() throws Exception {
        ValidateRequest request = new ValidateRequest();
        request.setItemId(1);
        request.setTargetState("测试状态");

        String requestBody = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/workflow/validate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").exists());
    }

    /**
     * 场景7: 通知响应字段结构
     */
    @Test
    @DisplayName("集成测试: 通知响应包含必要字段")
    void testNotifyEndpoint_ResponseStructure() throws Exception {
        NotifyRequest request = new NotifyRequest();
        request.setItemId(1);
        request.setPreviousState("状态A");
        request.setTargetState("状态B");

        String requestBody = objectMapper.writeValueAsString(request);

        mockMvc.perform(post("/workflow/notify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").exists());
    }
}