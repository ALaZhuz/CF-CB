package org.example.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.config.DingProperties;
import org.example.service.DingService;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 钉钉服务实现类
 *
 * 实现钉钉消息发送、用户校验等功能。
 * 支持企业钉钉和个人钉钉两种通知模式。
 *
 * @author system
 * @since 1.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DingServiceImpl implements DingService {

    private final RestTemplate restTemplate;
    private final DingProperties dingProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取企业钉钉AccessToken
     *
     * @return AccessToken字符串，失败返回空字符串
     */

    public String getAccessToken() {
        Map<String, Object> req = new HashMap<>();
        req.put("appKey", dingProperties.getAppKey());
        req.put("appSecret", dingProperties.getAppSecret());
        Map<?, ?> resp = postForObject(dingProperties.getAccessTokenUrl(), req, Map.class);
        return resp == null || resp.get("accessToken") == null ? "" : resp.get("accessToken").toString();
    }

    @Override
    public void sendMessage(String userIds, String title, String markdown, String singleTitle, String singleUrl) {

    }

    /**
     * 发送ActionCard消息（企业钉钉）
     *
     * @param userIds 接收用户ID列表，逗号分隔
     * @param title 消息标题
     * @param markdown Markdown内容
     */

    public void sendMessage(String userIds, String title, String markdown) {
        String accessToken = getAccessToken();
        Map<String, Object> actionCard = new HashMap<>();
        actionCard.put("title", title);
        actionCard.put("text", markdown);
        Map<String, Object> msg = new HashMap<>();
        msg.put("msgtype", "markdown");
        msg.put("markdown", actionCard);
        Map<String, Object> req = new HashMap<>();
        req.put("agent_id", Long.valueOf(dingProperties.getAgentId()));
        req.put("userid_list", userIds);
        req.put("to_all_user", false);
        req.put("msg", msg);
        postForObject(dingProperties.getMessageUrl() + "?access_token=" + accessToken, req, Map.class);
    }

    /**
     * 发送纯文本消息（企业钉钉）
     *
     * @param userIds 接收用户ID列表，逗号分隔
     * @param content 文本内容
     */
    @Override
    public void sendTextMessage(String userIds, String content) {
        sendEnterpriseTextMessage(userIds, content);
    }

    @Override
    public String queryOrganizationManager(String employeeId) {
        return null;
    }

    /**
     * 企业钉钉发送纯文本消息
     *
     * @param userIds 接收用户ID列表，逗号分隔
     * @param content 文本内容
     */
    private void sendEnterpriseTextMessage(String userIds, String content) {
        String accessToken = getAccessToken();

        Map<String, Object> text = new HashMap<>();
        text.put("content", content);

        Map<String, Object> msg = new HashMap<>();
        msg.put("msgtype", "text");
        msg.put("text", text);

        Map<String, Object> req = new HashMap<>();
        req.put("agent_id", Long.valueOf(dingProperties.getAgentId()));
        req.put("userid_list", userIds);
        req.put("to_all_user", false);
        req.put("msg", msg);

        postForObject(dingProperties.getMessageUrl() + "?access_token=" + accessToken, req, Map.class);
        log.info("企业钉钉发送文本消息成功, userIds={}", userIds);
    }

    /**
     * 查询组织管理者
     *
     * @param userId 员工ID
     * @return 管理者ID列表，逗号分隔
     */
    @Override
    public String getUserInfo(String userId) {
        String accessToken = getAccessToken();
        String url = dingProperties.getUserInfoUrl() + "?access_token=" + accessToken;
        Map<String, Object> req = new HashMap<>();
        req.put("userid", userId);
        req.put("language", "zh_CN");
        Map<?, ?> resp = postForObject(url, req, Map.class);
        Integer errcode = (Integer) resp.get("errcode");
        if (errcode == null || errcode != 0) {
            return null;
        }
        Map<?, ?> result = (Map<?, ?>) resp.get("result");
        if (result == null) {
            return null;
        }
        String name = (String) result.get("name");
        return name;
    }

    /**
     * 校验用户userid在钉钉中是否存在
     *
     * 调用钉钉用户查询API确认userid有效性。
     * 不在此方法中打印日志，由调用方统一处理日志输出。
     *
     * @param userid 用户钉钉userid
     * @return true表示用户存在
     */
    @Override
    public boolean checkUserExists(String userid) {
        if (userid == null || userid.isEmpty()) {
            return false;
        }

        try {
            String accessToken = getAccessToken();
            String url = dingProperties.getUserQueryUrl() + "?access_token=" + accessToken;

            Map<String, Object> req = new HashMap<>();
            req.put("userid", userid);

            Map<?, ?> resp = postForObject(url, req, Map.class);

            if (resp == null) {
                return false;
            }

            // 检查返回结果中的errcode，0表示成功
            Object errcode = resp.get("errcode");
            if (errcode != null && ((Number) errcode).intValue() == 0) {
                return true;
            }

            return false;
        } catch (Exception e) {
            log.error("钉钉用户校验异常, userid={}, error={}", userid, e.getMessage());
            return false;
        }
    }

    /**
     * 合并多个管理者ID
     *
     * @param nodes JSON节点数组
     * @return 合并后的ID字符串，逗号分隔
     */
    private String joinIds(JsonNode... nodes) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode node : nodes) {
            String value = node.isMissingNode() || node.isNull() ? "" : node.asText("");
            if (!value.isBlank()) {
                if (sb.length() > 0) sb.append(',');
                sb.append(value);
            }
        }
        return sb.toString();
    }

    /**
     * POST请求并返回对象
     *
     * @param url 请求URL
     * @param body 请求体
     * @param type 返回类型
     * @return 响应对象
     */
    private <T> T postForObject(String url, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.POST, entity, type);
        return response.getBody();
    }

    /**
     * POST请求并返回字符串
     *
     * @param url 请求URL
     * @param body 请求体
     * @return 响应字符串
     */
    private String postForString(String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        return response.getBody();
    }
}
