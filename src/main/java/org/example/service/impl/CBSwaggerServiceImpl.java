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
}
