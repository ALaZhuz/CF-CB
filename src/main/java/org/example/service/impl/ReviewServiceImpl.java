package org.example.service.impl;


import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.example.config.DingProperties;
import org.example.model.dto.response.OrganizationManagerResponse;
import org.example.service.ReviewService;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewServiceImpl implements ReviewService {

    private final RestTemplate restTemplate;
    private final DingProperties dingProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();


    public String getAuthorizeCode() {
        String url = dingProperties.getClientAuthorizeCodeUrl();
        Map<String, String> reqParam = new HashMap<>();
        reqParam.put("corpid", dingProperties.getCorpId());
        reqParam.put("response_type", "code");
        reqParam.put("state", "");
        try {
            String authorizeCode = post(url, reqParam);
            if (StringUtils.isNotBlank(authorizeCode)) {
                JSONObject authorizeCodeObj = JSONObject.parseObject(authorizeCode);
                return null2String(authorizeCodeObj.get("code"));
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    public String getAccessToken() {
        String url = dingProperties.getClientAccessTokenUrl();
        String authorizeCode = getAuthorizeCode();
        if (StringUtils.isBlank(authorizeCode)) {
            return "";
        }
        Map<String, String> reqParam = new HashMap<>();
        reqParam.put("app_key", dingProperties.getAppKey());
        reqParam.put("app_secret", dingProperties.getAppSecret());
        reqParam.put("grant_type", "authorization_code");
        reqParam.put("code", authorizeCode);
        try {
            String accessToken = post(url, reqParam);
            if (StringUtils.isNotBlank(accessToken)) {
                JSONObject accessTokenObj = JSONObject.parseObject(accessToken);
                return null2String(accessTokenObj.get("accessToken"));
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    @Override
    public OrganizationManagerResponse queryOrganizationManager(String employeeId) {
        if (StringUtils.isBlank(employeeId)) {
            return new OrganizationManagerResponse();
        }

        String accessToken = getAccessToken();
        if (StringUtils.isBlank(accessToken)) {
            return new OrganizationManagerResponse();
        }
        Map<String, Object> req = new HashMap<>();
        req.put("employeeId", employeeId);
        String raw = post(dingProperties.getOrganizationWebhookUrl() + accessToken, req);
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node.isArray() && !node.isEmpty()) {
                return mergeOrganizationManagers(node);
            }
            if (node.isObject()) {
                return toOrganizationManagerResponse(node);
            }
        } catch (Exception ignored) {
        }
        return new OrganizationManagerResponse();
    }

    private OrganizationManagerResponse mergeOrganizationManagers(JsonNode arrayNode) {
        List<String> sectionManagers = new ArrayList<>();
        List<String> departmentManagers = new ArrayList<>();
        List<String> directors = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            OrganizationManagerResponse one = toOrganizationManagerResponse(node);
            sectionManagers.addAll(one.getSectionManager());
            departmentManagers.addAll(one.getDepartmentManager());
            directors.addAll(one.getDirector());
        }
        return new OrganizationManagerResponse(
                sectionManagers.stream().filter(StringUtils::isNotBlank).distinct().toList(),
                departmentManagers.stream().filter(StringUtils::isNotBlank).distinct().toList(),
                directors.stream().filter(StringUtils::isNotBlank).distinct().toList()
        );
    }

    private OrganizationManagerResponse toOrganizationManagerResponse(JsonNode node) {
        List<String> sectionManagers = readIds(node.path("sectionManager"));
        List<String> departmentManagers = readIds(node.path("departmentManager"));
        List<String> directors = readIds(node.path("director"));
        return new OrganizationManagerResponse(sectionManagers, departmentManagers, directors);
    }

    private List<String> readIds(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new ArrayList<>();
        }
        List<String> ids = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = item == null || item.isNull() ? "" : item.asText("");
                if (StringUtils.isNotBlank(value)) {
                    ids.add(value);
                }
            }
        } else {
            String value = node.asText("");
            if (StringUtils.isNotBlank(value)) {
                ids.add(value);
            }
        }
        return ids.stream().filter(StringUtils::isNotBlank).distinct().collect(java.util.stream.Collectors.toList());
    }

    private String post(String url, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Object> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);
            return response.getBody() == null ? "" : response.getBody();
        } catch (Exception e) {
            log.warn("post 请求失败, url: {}, 原因: {}", url, e.getMessage());
            return ""; // 网络不通时返回空字符串
        }
    }

    private String null2String(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
