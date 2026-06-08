package org.example.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.config.CBProperties;
import org.example.config.CodeBeamerHttpHelper;
import org.example.model.cb.ReviewItem;
import org.example.model.cb.ReviewListResponse;
import org.example.model.dto.request.AssociationsRequest;
import org.example.model.dto.request.TrackerItemFieldRequest;
import org.example.model.dto.response.*;
import org.example.model.enums.RelationType;
import org.example.service.CBSwaggerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class CBSwaggerServiceImpl implements CBSwaggerService {

    private final CBProperties cbProperties;
    private final RestTemplate restTemplate;
    private final CodeBeamerHttpHelper httpHelper;

    private String baseUrl() {
        return cbProperties.getBaseUrl();
    }

    /**
     * Report-query cbQL语句查询
     *
     * @param page
     * @param pageSize
     * @param queryString
     * @return
     */
    public CBQueryResponse query(int page, int pageSize, String queryString) {

        String url = baseUrl() + "/v3/items/query";

        // 构建请求体（用Map，不需要单独建类）
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("page", page);
        requestBody.put("pageSize", pageSize);
        requestBody.put("queryString", queryString);

        // 构建请求头
        HttpHeaders oldHeaders = httpHelper.getAuthEntity().getHeaders();
        HttpHeaders newHeaders = new HttpHeaders();
        newHeaders.addAll(oldHeaders);
        newHeaders.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, newHeaders);

        ResponseEntity<CBQueryResponse> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                CBQueryResponse.class
        );

        return response.getBody();
    }
//    @Override
//    public CBQueryResponse query(int page, int pageSize, String queryString) {
//        String encodedQueryString = UriUtils.encodeQueryParam(queryString, StandardCharsets.UTF_8);
//        URI uri = UriComponentsBuilder
//                .fromHttpUrl(baseUrl + "/v3/items/query")
//                .queryParam("page", page)
//                .queryParam("pageSize", pageSize)
//                .queryParam("queryString", encodedQueryString)
//                .build(true)
//                .toUri();
//
//        ResponseEntity<CBQueryResponse> response = restTemplate.exchange(
//                uri,
//                HttpMethod.GET,
//                httpHelper.getAuthEntity(),
//                CBQueryResponse.class
//        );
//
//        return response.getBody();
//    }

    /**
     * 获取当前Tracker的所有条目
     *
     * @param page
     * @param pageSize
     * @param trackerId
     * @return
     */
    @Override
    public CBTrackerItemsResponse getAllTrackerItems(int page, int pageSize, int trackerId) {
        String url = baseUrl() + "/v3/trackers/" + trackerId + "/items?page=" + page + "&pageSize=" + pageSize;

        ResponseEntity<CBTrackerItemsResponse> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                httpHelper.getAuthEntity(),
                CBTrackerItemsResponse.class
        );

        return response.getBody();
    }

    /**
     * 查询一对一的链接关系（需求池-outcoming-车型项目）
     *
     * @param associationsRequest
     * @param relationType
     * @return
     */
    @Override
    public Map<Integer, Integer> getSingleRelationMap(AssociationsRequest associationsRequest, RelationType relationType) {
        Map<Integer, List<Integer>> multiMap = getMultiRelationMap(associationsRequest, relationType);
        Map<Integer, Integer> result = new LinkedHashMap<>();

        for (Map.Entry<Integer, List<Integer>> entry : multiMap.entrySet()) {
            List<Integer> ids = entry.getValue();
            if (ids == null || ids.isEmpty()) {
                // 如果车型项目找不到和需求池的关联关系，就放一个空值
                result.put(entry.getKey(), null);
                continue;
            }
            result.put(entry.getKey(), ids.get(0));
        }

        return result;
    }

    /**
     * 查询一对多的链接关系：
     * 1. 需求池-upstream-需求池
     * 2. 需求池-incoming-车型项目
     *
     * @param associationsRequest
     * @param relationType
     * @return
     */
    @Override
    public Map<Integer, List<Integer>> getMultiRelationMap(AssociationsRequest associationsRequest, RelationType relationType) {
        String url = baseUrl() + "/v3/items/relations";
        HttpHeaders headers = httpHelper.getAuthHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<AssociationsRequest> entity = new HttpEntity<>(associationsRequest, headers);

        ResponseEntity<List<CBRelationsResponse>> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<List<CBRelationsResponse>>() {
                }
        );

        // 就算无链接关系，body不为空
        List<CBRelationsResponse> body = response.getBody();

        Map<Integer, List<Integer>> result = new LinkedHashMap<>();
        List<Integer> missingIds = new ArrayList<>();
        for (CBRelationsResponse relation : body) {
            Integer itemId = relation.getItemId().getId();
            List<Integer> relationIds = extractRelationIds(relation, relationType);
            if (relationIds == null || relationIds.isEmpty()) {
                missingIds.add(itemId);
                // 如果找不到链接关系，就放一个空值
                result.put(itemId, null);
                continue;
            }
            result.put(itemId, relationIds);
        }
        if (!missingIds.isEmpty()) {
            log.warn("链接关系缺失, relationType={}, failedIds={}", relationType, missingIds);
        }
        return result;
    }

    /**
     * GET-获取单个条目的链接关系（分页拉全量）
     *
     * @param id
     * @param relationType
     * @return
     */
    @Override
    public List<Integer> getRelationId(Integer id, RelationType relationType) {
        if (id == null) {
            return Collections.emptyList();
        }

        final int pageSize = 500; // 接口最大值
        int page = 1;
        List<Integer> result = new ArrayList<>();

        while (true) {
            String url = String.format("%s/v3/items/%d/relations?page=%d&pageSize=%d",
                    baseUrl(), id, page, pageSize);

            ResponseEntity<CBRelationsResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    httpHelper.getAuthEntity(),
                    CBRelationsResponse.class
            );

            CBRelationsResponse body = response.getBody();

            List<Integer> pageIds = extractRelationIds(body, relationType);
            if (pageIds != null && !pageIds.isEmpty()) {
                result.addAll(pageIds);
            }

            // 分页结束
            if (body.getLastPage()) {
                break;
            }

            page++;
        }

        if (result.isEmpty()) {
            log.warn("链接关系缺失, relationType={}, itemId={}", relationType, id);
        }

        return result;
    }


    /**
     * 区分链接关系
     *
     * @param relation
     * @param relationType
     * @return
     */
    private List<Integer> extractRelationIds(CBRelationsResponse relation, RelationType relationType) {
        List<CBRelationsResponse.Association> associations;
        switch (relationType) {
            case OUTGOING_ASSOCIATIONS:
                associations = relation.getOutgoingAssociations();
                break;
            case UPSTREAM_REFERENCES:
                associations = relation.getUpstreamReferences();
                break;
            case INCOMING_ASSOCIATIONS:
                associations = relation.getIncomingAssociations();
                break;
            default:
                associations = Collections.emptyList();
                break;
        }
        if (associations == null || associations.isEmpty()) {
            return Collections.emptyList();
        }

        return associations.stream()
                .map(CBRelationsResponse.Association::getItemRevision)
                .filter(Objects::nonNull)
                .map(CBRelationsResponse.ItemRevision::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 根据TrackerId查询Tracker配置
     *
     * @param trackerId
     * @return
     */
    @Override
    public CBTrackerConfigurationResponse getTrackerConfiguration(Integer trackerId) {
        String url = baseUrl() + "/v3/tracker/" + trackerId + "/configuration";

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                httpHelper.getAuthEntity(),
                JsonNode.class
        );

        JsonNode body = response.getBody();
        if (body == null) {
            throw new RuntimeException("该跟踪器可能被误删,找不到Tracker配置信息, trackerId=" + trackerId);
        }

        CBTrackerConfigurationResponse result = new CBTrackerConfigurationResponse();

        // 1) 取 basicInformation.projectId
        Integer projectId = null;
        JsonNode basicInfo = body.path("basicInformation");
        if (!basicInfo.isMissingNode() && basicInfo.hasNonNull("projectId")) {
            projectId = basicInfo.get("projectId").asInt();
        }
        result.setProjectId(projectId);

        // 2) 在 fields 中模糊匹配 label 包含“追溯”的对象，取 referenceId
        Integer fieldId = null;
        JsonNode fields = body.path("fields");
        if (fields.isArray()) {
            for (JsonNode field : fields) {
                String label = field.path("label").asText("");
                if (label.contains("追溯")) {
                    if (field.hasNonNull("referenceId")) {
                        fieldId = field.get("referenceId").asInt();
                        break;
                    }
                }
            }
        }

        if (fieldId == null) {
            throw new RuntimeException("该跟踪器未配置追溯字段, 跟踪器Id=" + trackerId);
        }
        result.setFieldId(fieldId);

        return result;
    }

    /**
     * 获取Tracker的自定义追溯字段配置
     *
     * @param trackerId
     * @param fieldId
     * @return
     */
    @Override
    public CBTrackerConfigurationResponse getTrackerField(Integer trackerId, Integer fieldId) {
        String url = baseUrl() + "/v3/trackers/" + trackerId + "/fields/" + fieldId;

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                httpHelper.getAuthEntity(),
                JsonNode.class
        );

        JsonNode body = response.getBody();
        if (body == null) {
            throw new RuntimeException("跟踪器自定义追溯字段可能被误删，响应为空, Id=" + trackerId);
        }

        CBTrackerConfigurationResponse result = new CBTrackerConfigurationResponse();

        // 例: ChoiceFieldValue<TrackerItemReference>
        String valueModel = body.path("valueModel").asText(null);
        if (valueModel == null || valueModel.isBlank()) {
            throw new RuntimeException("跟踪器自定义追溯字段类型可能不准确，valueModel为空, Id=" + trackerId + ", fieldId=" + fieldId);
        }

        // 默认整串先作为 fieldType（兜底）
        String parsedFieldType = valueModel;
        String parsedTrackerItemType = null;

        int lt = valueModel.indexOf('<');
        int gt = valueModel.lastIndexOf('>');

        if (lt > 0 && gt > lt) {
            parsedFieldType = valueModel.substring(0, lt).trim();
            parsedTrackerItemType = valueModel.substring(lt + 1, gt).trim();
        }

        result.setFieldType(parsedFieldType);               // ChoiceFieldValue
        result.setTrackerItemType(parsedTrackerItemType);   // TrackerItemReference

        return result;
    }

    /**
     * 获取项目信息（id+name）
     *
     * @param trackerId
     * @return
     */
    @Override
    public CBTrackerInfoResponse getProjectInfo(Integer trackerId) {
        String url = baseUrl() + "/v3/trackers/" + trackerId;

        ResponseEntity<CBTrackerInfoResponse> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                httpHelper.getAuthEntity(),
                CBTrackerInfoResponse.class
        );

        CBTrackerInfoResponse res = response.getBody();

        return res;
    }

    @Override
    public List<String> getUpStreamNames(Integer trackerItemId) {
        String url = baseUrl() + "/v3/items/" + trackerItemId;

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                httpHelper.getAuthEntity(),
                JsonNode.class
        );

        JsonNode body = response.getBody();
        // subjects: [{..., "name": "..."}]
        JsonNode subjects = body.path("subjects");
        if (subjects.isEmpty()) {
            throw new RuntimeException("查询池子条目的上游为空, 条目Id=" + trackerItemId);
        }

        List<String> names = new ArrayList<>();


        for (JsonNode subject : subjects) {
            String name = subject.path("name").asText(null);
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }


        return names;
    }

    @Override
    public void putTrackerItemField(TrackerItemFieldRequest req, Integer id) {
        String url = baseUrl() + "/v3/items/" + id + "/fields?quietMode=false";
        HttpHeaders headers = httpHelper.getAuthHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<TrackerItemFieldRequest> entity = new HttpEntity<>(req, headers);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url,
                HttpMethod.PUT,
                entity,
                JsonNode.class
        );

        if (response.getBody() == null) {
            throw new RuntimeException("更新项目跟踪器自定义追溯字段失败, 条目Id：" + id);
        }
    }

    /**
     * 获取单个条目的完整详情
     *
     * 调用Codebeamer API获取条目的详细信息，包括名称、状态、成员字段等。
     * 包含 trackerType 和 projectId（通过调用 tracker API 获取）。
     *
     * @param itemId 条目ID
     * @return 条目详情，不存在返回null
     */
    @Override
    public ItemInfoResponse getItemInfo(Integer itemId) {
        JsonNode body = fetchItemJson(itemId);
        if (body == null) {
            return null;
        }

        // 解析基本信息（复用）
        ItemInfoResponse itemInfo = parseItemInfoCommon(itemId, body);

        // 获取 tracker 信息（调用 API）
        Integer trackerId = itemInfo.getTracker().getId();
        if (trackerId != null && trackerId > 0) {
            fillTrackerInfo(itemInfo, trackerId);
        }

        log.info("条目详情解析完成: itemId={}, trackerId={}, trackerName={}, trackerType={}, projectId={}, projectName={}",
                itemId, trackerId, itemInfo.getTracker().getName(), itemInfo.getTrackerType(),
                itemInfo.getProject() != null ? itemInfo.getProject().getId() : null,
                itemInfo.getProject() != null ? itemInfo.getProject().getName() : null);

        return itemInfo;
    }

    /**
     * 获取单个条目的基本信息（不调用 tracker API）
     *
     * 用于定时通知，避免 429 速率限制。
     * trackerType 和 projectId 不从 API 获取，需要从外部传入或数据库读取。
     *
     * @param itemId 条目ID
     * @return 条目基本信息，不存在返回null
     */
    @Override
    public ItemInfoResponse getItemInfoBasic(Integer itemId) {
        JsonNode body = fetchItemJson(itemId);
        if (body == null) {
            return null;
        }

        // 解析基本信息（复用）
        ItemInfoResponse itemInfo = parseItemInfoCommon(itemId, body);

        // 不调用 tracker API，trackerType 和 projectId 保持为 null
        log.debug("条目基本信息解析完成: itemId={}, trackerId={}", itemId, itemInfo.getTracker().getId());

        return itemInfo;
    }

    /**
     * 获取条目 JSON 数据（带429重试）
     *
     * @param itemId 条目ID
     * @return JSON 数据，不存在返回null
     */
    private JsonNode fetchItemJson(Integer itemId) {
        if (itemId == null) {
            return null;
        }

        String url = baseUrl() + "/v3/items/" + itemId;

        // 带429重试的API调用
        return executeWithRetry(() -> {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    httpHelper.getAuthEntity(),
                    JsonNode.class
            );

            JsonNode body = response.getBody();
            if (body == null || body.isMissingNode()) {
                log.warn("条目不存在, itemId={}", itemId);
                return null;
            }
            return body;
        }, "fetchItemJson", itemId);
    }

    /**
     * 带429重试的API调用执行器
     *
     * @param action 要执行的API调用
     * @param operationName 操作名称（用于日志）
     * @param itemId 条目ID（用于日志）
     * @return API调用结果
     */
    private JsonNode executeWithRetry(java.util.function.Supplier<JsonNode> action, String operationName, Integer itemId) {
        int maxRetries = 3;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return action.get();

            } catch (org.springframework.web.client.HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429) {
                    // 429限流，解析retryAfterSeconds并等待重试
                    int retryAfterSeconds = extractRetryAfterSeconds(e.getMessage());
                    log.warn("API限流, operation={}, itemId={}, 等待{}秒后重试(第{}次)",
                            operationName, itemId, retryAfterSeconds, attempt + 1);

                    try {
                        Thread.sleep(retryAfterSeconds * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("重试等待被中断", ie);
                    }
                } else {
                    // 其他HTTP错误直接抛出
                    throw e;
                }
            }
        }

        log.warn("API调用失败(重试{}次后): operation={}, itemId={}", maxRetries, operationName, itemId);
        return null;
    }

    /**
     * 从错误消息中提取retryAfterSeconds
     *
     * @param errorMessage 错误消息
     * @return 重试等待秒数，默认返回2
     */
    private int extractRetryAfterSeconds(String errorMessage) {
        try {
            if (errorMessage != null && errorMessage.contains("retryAfterSecond")) {
                String pattern = "retryAfterSecond\":";
                int start = errorMessage.indexOf(pattern);
                if (start > 0) {
                    start += pattern.length();
                    int end = errorMessage.indexOf("}", start);
                    if (end > start) {
                        return Integer.parseInt(errorMessage.substring(start, end).trim());
                    }
                }
            }
        } catch (NumberFormatException e) {
            log.debug("解析retryAfterSeconds失败: {}", errorMessage);
        }
        return 2; // 默认等待2秒
    }

    /**
     * 解析条目基本信息（复用方法）
     *
     * 解析条目的通用字段，不包括 trackerType 和 projectId。
     *
     * @param itemId 条目ID
     * @param body JSON 数据
     * @return 条目信息对象
     */
    private ItemInfoResponse parseItemInfoCommon(Integer itemId, JsonNode body) {
        ItemInfoResponse itemInfo = new ItemInfoResponse();

        // 解析基本字段
        itemInfo.setId(itemId);
        itemInfo.setName(body.path("name").asText(null));

        // 解析状态
        JsonNode statusNode = body.path("status");
        itemInfo.setStatus(statusNode.path("name").asText(null));

        // 解析tracker信息
        JsonNode trackerNode = body.path("tracker");
        ItemInfoResponse.TrackerInfo trackerInfo = new ItemInfoResponse.TrackerInfo();
        trackerInfo.setId(trackerNode.path("id").asInt());
        trackerInfo.setName(trackerNode.path("name").asText(null));
        trackerInfo.setTypeName(null);  // 不在这里设置，由 fillTrackerInfo 设置
        itemInfo.setTracker(trackerInfo);

        // project 信息不在这里设置
        itemInfo.setProject(null);

        // 解析assignedTo成员列表
        JsonNode assignedToNode = body.path("assignedTo");
        if (assignedToNode.isArray()) {
            List<ItemInfoResponse.MemberInfo> assignedToList = new ArrayList<>();
            for (JsonNode member : assignedToNode) {
                ItemInfoResponse.MemberInfo memberInfo = parseMember(member);
                if (memberInfo != null) {
                    assignedToList.add(memberInfo);
                }
            }
            itemInfo.setAssignedTo(assignedToList);
        }

        // 解析submitter
        JsonNode submitterNode = body.path("submitter");
        if (!submitterNode.isMissingNode()) {
            itemInfo.setSubmitter(parseMember(submitterNode));
        }

        // 解析createdBy
        JsonNode createdByNode = body.path("createdBy");
        if (!createdByNode.isMissingNode()) {
            itemInfo.setCreatedBy(parseMember(createdByNode));
        }

        // 解析modifiedBy
        JsonNode modifiedByNode = body.path("modifiedBy");
        if (!modifiedByNode.isMissingNode()) {
            itemInfo.setModifiedBy(parseMember(modifiedByNode));
        }

        // 解析priority
        JsonNode priorityNode = body.path("priority");
        if (!priorityNode.isMissingNode()) {
            itemInfo.setPriority(parseChoiceOption(priorityNode));
        }

        // 解析categories
        JsonNode categoriesNode = body.path("categories");
        if (categoriesNode.isArray()) {
            List<ItemInfoResponse.ChoiceOption> categories = new ArrayList<>();
            for (JsonNode category : categoriesNode) {
                categories.add(parseChoiceOption(category));
            }
            itemInfo.setCategories(categories);
        }

        // 解析severities
        JsonNode severitiesNode = body.path("severities");
        if (severitiesNode.isArray()) {
            List<ItemInfoResponse.ChoiceOption> severities = new ArrayList<>();
            for (JsonNode severity : severitiesNode) {
                severities.add(parseChoiceOption(severity));
            }
            itemInfo.setSeverities(severities);
        }

        // 解析teams
        JsonNode teamsNode = body.path("teams");
        if (teamsNode.isArray()) {
            List<ItemInfoResponse.ChoiceOption> teams = new ArrayList<>();
            for (JsonNode team : teamsNode) {
                teams.add(parseChoiceOption(team));
            }
            itemInfo.setTeams(teams);
        }

        // 解析versions
        JsonNode versionsNode = body.path("versions");
        if (versionsNode.isArray()) {
            List<ItemInfoResponse.ChoiceOption> versions = new ArrayList<>();
            for (JsonNode version : versionsNode) {
                versions.add(parseChoiceOption(version));
            }
            itemInfo.setVersions(versions);
        }

        // 解析自定义字段
        JsonNode customFieldsNode = body.path("customFields");
        if (customFieldsNode.isArray()) {
            List<ItemInfoResponse.CustomField> customFields = new ArrayList<>();
            for (JsonNode field : customFieldsNode) {
                ItemInfoResponse.CustomField customField = new ItemInfoResponse.CustomField();
                customField.setName(field.path("name").asText(null));
                customField.setLabel(field.path("label").asText(null));
                customField.setType(field.path("type").asText(null));

                JsonNode valuesNode = field.path("values");
                if (valuesNode.isArray()) {
                    List<ItemInfoResponse.MemberInfo> values = new ArrayList<>();
                    for (JsonNode value : valuesNode) {
                        ItemInfoResponse.MemberInfo memberInfo = parseMember(value);
                        if (memberInfo != null) {
                            values.add(memberInfo);
                        }
                    }
                    customField.setValues(values);
                }

                JsonNode valueNode = field.path("value");
                if (!valueNode.isMissingNode() && !valueNode.isNull()) {
                    customField.setValue(valueNode.asText(null));
                }

                customFields.add(customField);
            }
            itemInfo.setCustomFields(customFields);
        }

        // 构建条目链接
        String itemLink = baseUrl().replace("/api", "") + "/issue/" + itemId;
        itemInfo.setItemLink(itemLink);

        return itemInfo;
    }

    /**
     * 填充 tracker 信息（调用 tracker API）
     *
     * @param itemInfo 条目信息对象
     * @param trackerId tracker ID
     */
    private void fillTrackerInfo(ItemInfoResponse itemInfo, Integer trackerId) {
        Integer projectId = null;
        String projectName = null;
        String typeName = null;

        try {
            CBTrackerInfoResponse trackerInfoResp = getProjectInfo(trackerId);
            if (trackerInfoResp != null) {
                if (trackerInfoResp.getProject() != null) {
                    projectId = trackerInfoResp.getProject().getId();
                    projectName = trackerInfoResp.getProject().getName();
                }
                if (trackerInfoResp.getType() != null) {
                    typeName = trackerInfoResp.getType().getName();
                }
            }
        } catch (Exception e) {
            log.warn("获取tracker项目信息失败: trackerId={}, error={}", trackerId, e.getMessage());
        }

        // 设置tracker类型
        itemInfo.getTracker().setTypeName(typeName);
        itemInfo.setTrackerType(typeName);

        // 设置项目信息
        ItemInfoResponse.ProjectInfo projectInfo = new ItemInfoResponse.ProjectInfo();
        projectInfo.setId(projectId);
        projectInfo.setName(projectName);
        itemInfo.setProject(projectInfo);
    }

    /**
     * 解析成员节点
     *
     * @param memberNode JSON节点
     * @return 成员信息，解析失败返回null
     */
    private ItemInfoResponse.MemberInfo parseMember(JsonNode memberNode) {
        if (memberNode == null || memberNode.isMissingNode()) {
            return null;
        }

        ItemInfoResponse.MemberInfo memberInfo = new ItemInfoResponse.MemberInfo();
        memberInfo.setUserId(memberNode.path("name").asText(null));
        memberInfo.setName(memberNode.path("name").asText(null));
        memberInfo.setDisplayName(memberNode.path("displayName").asText(null));
        memberInfo.setEmail(memberNode.path("email").asText(null));
        return memberInfo;
    }

    /**
     * 解析选择选项节点
     *
     * @param optionNode JSON节点
     * @return 选择选项，解析失败返回null
     */
    private ItemInfoResponse.ChoiceOption parseChoiceOption(JsonNode optionNode) {
        if (optionNode == null || optionNode.isMissingNode()) {
            return null;
        }

        ItemInfoResponse.ChoiceOption option = new ItemInfoResponse.ChoiceOption();
        option.setId(optionNode.path("id").asInt());
        option.setName(optionNode.path("name").asText(null));
        option.setType(optionNode.path("type").asText(null));
        return option;
    }

    /**
     * 获取Codebeamer所有用户列表
     *
     * 分页获取所有用户，用于userid缓存初始化。
     *
     * @return 用户列表
     */
    @Override
    public List<ItemInfoResponse.MemberInfo> getAllUsers() {
        List<ItemInfoResponse.MemberInfo> allUsers = new ArrayList<>();
        int pageSize = 500;
        int page = 1;

        while (true) {
            String url = baseUrl() + "/v3/users?page=" + page + "&pageSize=" + pageSize;

            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    httpHelper.getAuthEntity(),
                    JsonNode.class
            );

            JsonNode body = response.getBody();
            if (body == null) {
                break;
            }

            JsonNode usersNode = body.path("users");
            if (!usersNode.isArray() || usersNode.isEmpty()) {
                break;
            }

            for (JsonNode user : usersNode) {
                ItemInfoResponse.MemberInfo memberInfo = new ItemInfoResponse.MemberInfo();
                memberInfo.setUserId(user.path("name").asText(null));
                memberInfo.setName(user.path("name").asText(null));
                memberInfo.setDisplayName(user.path("displayName").asText(null));
                if (memberInfo.getUserId() != null && !memberInfo.getUserId().isEmpty()) {
                    allUsers.add(memberInfo);
                }
            }

            // 检查是否还有下一页
            int total = body.path("total").asInt(0);
            if (allUsers.size() >= total) {
                break;
            }

            page++;
        }

        log.info("获取Codebeamer用户列表完成, 共{}个用户", allUsers.size());
        return allUsers;
    }

    /**
     * 获取所有评审
     */
    @Override
    public List<ReviewItem> fetchAllReviews() {
        List<ReviewItem> all = new ArrayList<>();
        int pageNo = 1;
        int pageSize = 100;
        while (true) {
            ReviewListResponse response = fetchReviewPage(pageNo, pageSize);
            if (response == null || response.getGroupedReviews() == null || response.getGroupedReviews().isEmpty()) {
                break;
            }
            for (ReviewListResponse.GroupedReview groupedReview : response.getGroupedReviews()) {
                if (groupedReview.getListOfReviewItems() == null) continue;
                for (ReviewListResponse.ReviewItemWrap wrap : groupedReview.getListOfReviewItems()) {
                    if (wrap != null && wrap.getReview() != null) {
                        ReviewItem item = new ReviewItem();
                        item.setReview(wrap.getReview());
                        item.setReviewer(wrap.getReviewer());
                        item.setModerator(wrap.getModerator());
                        all.add(item);
                    }
                }
            }
            if (all.size() >= response.getTotalCount()) {
                break;
            }
            pageNo++;
        }
        return all;
    }

    private ReviewListResponse fetchReviewPage(int pageNo, int pageSize) {
        String url = baseUrl() + "/reviews/list";
        Map<String, Object> req = Map.of(
                "grouping", "None",
                "pageNo", pageNo,
                "pageSize", pageSize
        );
        HttpHeaders headers = httpHelper.getAuthHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<ReviewListResponse> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                new HttpEntity<>(req, headers),
                ReviewListResponse.class
        );
        return response.getBody();
    }

    @Override
    public ReviewStatisticsResponse getReviewStatistics(String reviewId) {
        String url = baseUrl() + "/reviews/" + reviewId + "/reviewStatistics";
        ResponseEntity<ReviewStatisticsResponse> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                httpHelper.getAuthEntity(),
                ReviewStatisticsResponse.class
        );
        return response.getBody();
    }

    /**
     * 获取条目状态变更历史
     *
     * 调用 Codebeamer History API 获取条目的所有修改历史。
     *
     * @param itemId 条目ID
     * @return 历史记录响应
     */
    @Override
    public CBHistoryResponse getItemHistory(Integer itemId) {
        if (itemId == null) {
            return null;
        }

        String url = baseUrl() + "/v3/items/" + itemId + "/history";

        ResponseEntity<CBHistoryResponse> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                httpHelper.getAuthEntity(),
                CBHistoryResponse.class
        );

        CBHistoryResponse historyResponse = response.getBody();
        if (historyResponse == null || historyResponse.getVersions() == null) {
            log.warn("条目历史记录为空, itemId={}", itemId);
            return null;
        }

        log.debug("获取条目历史记录成功, itemId={}, versionCount={}", itemId, historyResponse.getVersions().size());
        return historyResponse;
    }

    /**
     * 获取条目进入目标状态的时间
     *
     * 从历史记录中查找最后一次状态切换到目标状态的时间。
     * 遍历 versions（从最新到最旧），找 changes 中 field.name = "Status" 且 newValue = targetState 的版本。
     * 如果找不到状态变更记录，说明该状态是条目的初始状态（新建时的默认状态），使用 version=1 的 modifiedAt（创建时间）。
     *
     * @param itemId 条目ID
     * @param targetState 目标状态名称
     * @return 进入目标状态的时间，未找到返回创建时间（兜底）
     */
    @Override
    public LocalDateTime getEnterStateTime(Integer itemId, String targetState) {
        if (itemId == null || targetState == null) {
            return LocalDateTime.now();
        }

        CBHistoryResponse historyResponse = getItemHistory(itemId);
        if (historyResponse == null || historyResponse.getVersions() == null) {
            log.warn("无法获取条目历史记录, 使用当前时间作为enter_state_time, itemId={}", itemId);
            return LocalDateTime.now();
        }

        List<CBHistoryVersion> versions = historyResponse.getVersions();
        if (versions.isEmpty()) {
            log.warn("条目历史记录版本列表为空, itemId={}", itemId);
            return LocalDateTime.now();
        }

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

        // 遍历 versions（从最新到最旧）
        List<CBHistoryVersion> reversedVersions = new ArrayList<>(versions);
        Collections.reverse(reversedVersions);

        for (CBHistoryVersion version : reversedVersions) {
            if (version.getChanges() == null || version.getChanges().isEmpty()) {
                continue;
            }

            // 检查本次变更是否包含状态切换
            for (CBHistoryChange change : version.getChanges()) {
                // 检查是否是状态字段变更
                if (change.getField() != null && "Status".equals(change.getField().getName())) {
                    // 检查新状态是否为目标状态
                    if (change.getNewValue() != null && change.getNewValue().getValues() != null) {
                        for (CBHistoryChange.ValueItem valueItem : change.getNewValue().getValues()) {
                            if (targetState.equals(valueItem.getName())) {
                                // 找到了！解析时间
                                String modifiedAt = version.getModifiedAt();
                                if (modifiedAt != null) {
                                    try {
                                        return LocalDateTime.parse(modifiedAt, formatter);
                                    } catch (Exception e) {
                                        log.warn("解析时间失败, modifiedAt={}, itemId={}", modifiedAt, itemId);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 未找到状态变更记录，说明该状态是条目的初始状态
        // 使用 version=1（第一个版本，即创建时间）的 modifiedAt 作为 enter_state_time
        // 版本列表通常是按版本号顺序排列的，所以第一个元素就是 version=1
        CBHistoryVersion firstVersion = versions.get(0);
        if (firstVersion.getItemRevision() != null && firstVersion.getItemRevision().getVersion() == 1) {
            String createdAt = firstVersion.getModifiedAt();
            if (createdAt != null) {
                try {
                    log.info("未找到状态变更记录, 使用创建时间作为初始状态的enter_state_time, itemId={}, targetState={}, createdAt={}",
                            itemId, targetState, createdAt);
                    return LocalDateTime.parse(createdAt, formatter);
                } catch (Exception e) {
                    log.warn("解析创建时间失败, createdAt={}, itemId={}", createdAt, itemId);
                }
            }
        }

        // 兜底：使用当前时间
        log.warn("无法确定enter_state_time, 使用当前时间, itemId={}, targetState={}", itemId, targetState);
        return LocalDateTime.now();
    }
}
