package org.example.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.config.DingProperties;
import org.example.service.DingService;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DingServiceImpl implements DingService {
    private final RestTemplate restTemplate;
    private final DingProperties dingProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getAccessToken() {
        Map<String, Object> req = new HashMap<>();
        req.put("appKey", dingProperties.getAppKey());
        req.put("appSecret", dingProperties.getAppSecret());
        Map<?, ?> resp = postForObject(dingProperties.getAccessTokenUrl(), req, Map.class);
        return resp == null || resp.get("accessToken") == null ? "" : resp.get("accessToken").toString();
    }

    @Override
    public void sendMessage(String userIds, String title, String markdown, String singleTitle, String singleUrl) {
        String accessToken = getAccessToken();
        Map<String, Object> actionCard = new HashMap<>();
        actionCard.put("title", title);
        actionCard.put("markdown", markdown);
        actionCard.put("single_title", singleTitle);
        actionCard.put("single_url", singleUrl);
        Map<String, Object> msg = new HashMap<>();
        msg.put("msgtype", "action_card");
        msg.put("action_card", actionCard);
        Map<String, Object> req = new HashMap<>();
        req.put("agent_id", Long.valueOf(dingProperties.getAgentId()));
        req.put("userid_list", userIds);
        req.put("to_all_user", false);
        req.put("msg", msg);
        postForObject(dingProperties.getMessageUrl() + "?access_token=" + accessToken, req, Map.class);
    }

    @Override
    public String queryOrganizationManager(String employeeId) {
        Map<String, Object> req = new HashMap<>();
        req.put("employeeId", employeeId);
        String raw = postForString(dingProperties.getOrganizationWebhookUrl(), req);
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node.isArray() && !node.isEmpty()) {
                JsonNode first = node.get(0);
                return joinIds(first.path("sectionManager"), first.path("departmentManager"), first.path("director"));
            }
            if (node.isObject()) {
                return joinIds(node.path("sectionManager"), node.path("departmentManager"), node.path("director"));
            }
        } catch (Exception ignored) {
        }
        return "";
    }

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

    private <T> T postForObject(String url, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.POST, entity, type);
        return response.getBody();
    }

    private String postForString(String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        return response.getBody();
    }
}
