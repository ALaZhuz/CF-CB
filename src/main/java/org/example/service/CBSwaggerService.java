package org.example.service;

import org.example.model.cb.ReviewItem;
import org.example.model.dto.request.AssociationsRequest;
import org.example.model.dto.request.TrackerItemFieldRequest;
import org.example.model.dto.response.*;
import org.example.model.enums.RelationType;

import java.util.List;
import java.util.Map;

/**
 * cb swagger
 */
public interface CBSwaggerService {
    /**
     * 使用cbql查询
     * @param page
     * @param pageSize
     * @param queryString
     * @return
     */
    CBQueryResponse query(int page, int pageSize, String queryString);

    /**
     * 获取Tracker的所有条目
     * @param page
     * @param pageSize
     * @param trackerId
     * @return
     */
    CBTrackerItemsResponse getAllTrackerItems(int page, int pageSize, int trackerId);

    /**
     * 查询所有评审
     */
    List<ReviewItem> fetchAllReviews();

    /**
     * 获取单个评审的统计信息
     */
    ReviewStatisticsResponse getReviewStatistics(String reviewId);

    /**
     * POST-批量获取链接关系，返回<id,outgoingAssociations id（唯一）> Map
     * @param associationsRequest
     * @param relationType
     * @return
     */
    Map<Integer, Integer> getSingleRelationMap(AssociationsRequest associationsRequest, RelationType relationType);

    /**
     * POST-批量获取链接关系，返回<id,upstreamReferences、incomingAssociations id列表> Map
     * @param associationsRequest
     * @param relationType
     * @return
     */
    Map<Integer, List<Integer>> getMultiRelationMap(AssociationsRequest associationsRequest, RelationType relationType);

    /**
     * GET-获取单个条目的链接关系
     * @param id
     * @return
     */
    List<Integer> getRelationId(Integer id, RelationType relationType);

    /**
     * 获取projectId、fieldId
     * @param trackerId
     * @return
     */
    CBTrackerConfigurationResponse getTrackerConfiguration(Integer trackerId);

    /**
     * 获取Tracker的追溯字段信息
     * @param trackerId
     * @param fieldId
     * @return
     */
    CBTrackerConfigurationResponse getTrackerField(Integer trackerId, Integer fieldId);

    /**
     * 获取项目信息（id+name）
     * @param trackerId
     * @return
     */
    CBTrackerInfoResponse getProjectInfo(Integer trackerId);

    /**
     * 获取上游TrackerItem名称
     * @param trackerItemId
     * @return
     */
    List<String> getUpStreamNames(Integer trackerItemId);

    /**
     * 建立追溯
     * @param req
     * @return
     */
    void putTrackerItemField(TrackerItemFieldRequest req, Integer id);
}
