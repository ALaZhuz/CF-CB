package org.example.service;

import org.example.model.dto.request.AssociationsRequest;
import org.example.model.dto.request.TrackerItemFieldRequest;
import org.example.model.dto.response.CBQueryResponse;
import org.example.model.dto.response.CBTrackerConfigurationResponse;
import org.example.model.dto.response.CBTrackerInfoResponse;
import org.example.model.dto.response.CBTrackerItemsResponse;
import org.example.model.dto.response.ItemInfoResponse;
import org.example.model.enums.RelationType;

import java.util.List;
import java.util.Map;

/**
 * Codebeamer API服务接口
 *
 * 提供与Codebeamer系统交互的API方法封装。
 *
 * @author system
 * @since 1.0
 */
public interface CBSwaggerService {

    /**
     * 使用cbql查询条目
     *
     * @param page 页码
     * @param pageSize 每页数量
     * @param queryString cbql查询语句
     * @return 查询结果
     */
    CBQueryResponse query(int page, int pageSize, String queryString);

    /**
     * 获取Tracker的所有条目
     *
     * @param page 页码
     * @param pageSize 每页数量
     * @param trackerId tracker ID
     * @return 条目列表
     */
    CBTrackerItemsResponse getAllTrackerItems(int page, int pageSize, int trackerId);

    /**
     * POST-批量获取链接关系，返回<id,outgoingAssociations id（唯一）> Map
     *
     * @param associationsRequest 关联请求
     * @param relationType 关系类型
     * @return 关系映射
     */
    Map<Integer, Integer> getSingleRelationMap(AssociationsRequest associationsRequest, RelationType relationType);

    /**
     * POST-批量获取链接关系，返回<id,upstreamReferences、incomingAssociations id列表> Map
     *
     * @param associationsRequest 关联请求
     * @param relationType 关系类型
     * @return 关系映射
     */
    Map<Integer, List<Integer>> getMultiRelationMap(AssociationsRequest associationsRequest, RelationType relationType);

    /**
     * GET-获取单个条目的链接关系
     *
     * @param id 条目ID
     * @param relationType 关系类型
     * @return 关系ID列表
     */
    List<Integer> getRelationId(Integer id, RelationType relationType);

    /**
     * 获取projectId、fieldId
     *
     * @param trackerId tracker ID
     * @return tracker配置信息
     */
    CBTrackerConfigurationResponse getTrackerConfiguration(Integer trackerId);

    /**
     * 获取Tracker的追溯字段信息
     *
     * @param trackerId tracker ID
     * @param fieldId 字段ID
     * @return 字段配置信息
     */
    CBTrackerConfigurationResponse getTrackerField(Integer trackerId, Integer fieldId);

    /**
     * 获取项目信息（id+name）
     *
     * @param trackerId tracker ID
     * @return tracker信息
     */
    CBTrackerInfoResponse getProjectInfo(Integer trackerId);

    /**
     * 获取上游TrackerItem名称
     *
     * @param trackerItemId 条目ID
     * @return 上游名称列表
     */
    List<String> getUpStreamNames(Integer trackerItemId);

    /**
     * 建立追溯
     *
     * @param req 字段请求
     * @param id 条目ID
     */
    void putTrackerItemField(TrackerItemFieldRequest req, Integer id);

    /**
     * 获取单个条目的完整详情
     *
     * 用于beforeEvent校验和afterEvent通知处理，
     * 获取条目名称、状态、tracker信息、成员字段等。
     *
     * @param itemId 条目ID
     * @return 条目详情，不存在返回null
     */
    ItemInfoResponse getItemInfo(Integer itemId);

    /**
     * 获取Codebeamer所有用户列表
     *
     * 用于userid缓存初始化，分页获取所有用户。
     *
     * @return 用户列表，每个用户包含userId和displayName
     */
    List<ItemInfoResponse.MemberInfo> getAllUsers();

    /**
     * 查询所有评审
     * state:
     * 开启--OPEN
     * 关闭--CLOSED
     */


    /**
     * 删除Tracker条目
     */


}
