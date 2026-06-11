package org.example.service.impl;


import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.example.config.DingProperties;
import org.example.db.mapper.OrgCacheMapper;
import org.example.db.entity.OrgCache;
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
    private final OrgCacheMapper orgCacheMapper;
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

        // 先尝试从持久化缓存获取科长和部长
        OrgCache cached = orgCacheMapper.selectByUserid(employeeId);
        String managerId = cached != null ? cached.getManagerUserid() : null;
        String directorId = cached != null ? cached.getDirectorUserid() : null;

        // 如果科长或部长有缓存，使用缓存数据（部分缓存命中）
        // 注意：总监信息没有缓存，返回空列表
        if (managerId != null || directorId != null) {
            log.debug("组织架构缓存{}命中: employeeId={}, managerId={}, directorId={}",
                    (managerId != null && directorId != null) ? "完全" : "部分",
                    employeeId, managerId, directorId);
            return new OrganizationManagerResponse(
                    managerId != null ? List.of(managerId) : List.of(),
                    directorId != null ? List.of(directorId) : List.of(),
                    List.of()  // 总监信息需要额外处理
            );
        }

        // 缓存未完全命中，调用原始API
        String accessToken = getAccessToken();
        if (StringUtils.isBlank(accessToken)) {
            return new OrganizationManagerResponse();
        }
        Map<String, Object> req = new HashMap<>();
        req.put("employeeId", employeeId);
        String raw = post(dingProperties.getOrganizationWebhookUrl() + accessToken, req);
        try {
            JsonNode node = objectMapper.readTree(raw);
            OrganizationManagerResponse response;
            if (node.isArray() && !node.isEmpty()) {
                response = mergeOrganizationManagers(node);
            } else if (node.isObject()) {
                response = toOrganizationManagerResponse(node);
            } else {
                return new OrganizationManagerResponse();
            }

            // 将查询结果补回缓存（只取第一个科长和第一个部长）
            cacheOrganizationManagers(employeeId, response);

            return response;
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

    /**
     * 将组织架构查询结果补回持久化缓存
     */
    private void cacheOrganizationManagers(String employeeId, OrganizationManagerResponse response) {
        if (response == null || StringUtils.isBlank(employeeId)) {
            return;
        }

        try {
            // 获取第一个科长和第一个部长（如果有的话）
            String firstManager = response.getSectionManager() != null && !response.getSectionManager().isEmpty()
                    ? response.getSectionManager().get(0) : null;
            String firstDirector = response.getDepartmentManager() != null && !response.getDepartmentManager().isEmpty()
                    ? response.getDepartmentManager().get(0) : null;

            // 查询现有缓存记录
            OrgCache existingCache = orgCacheMapper.selectByUserid(employeeId);
            OrgCache cache;
            if (existingCache != null) {
                cache = existingCache;
            } else {
                cache = new OrgCache();
                cache.setUserid(employeeId);
            }

            // 更新缓存字段
            if (firstManager != null) {
                cache.setManagerUserid(firstManager);
            }
            if (firstDirector != null) {
                cache.setDirectorUserid(firstDirector);
            }
            cache.setLastSyncTime(java.time.LocalDateTime.now());

            // 保存到数据库
            orgCacheMapper.insert(cache);
            log.debug("组织架构缓存已更新: employeeId={}, manager={}, director={}",
                    employeeId, firstManager, firstDirector);
        } catch (Exception e) {
            log.warn("更新组织架构缓存失败: employeeId={}, error={}", employeeId, e.getMessage());
        }
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
//            log.warn("post 请求失败, url: {}, 原因: {}", url, e.getMessage());
            return ""; // 网络不通时返回空字符串
        }
    }

    private String null2String(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
