package org.example.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.model.cb.TrackerItem;
import org.example.model.dto.request.AssociationsRequest;
import org.example.model.dto.request.TrackerItemFieldRequest;
import org.example.model.dto.request.TrackerItemRequest;
import org.example.model.dto.response.*;
import org.example.model.enums.RelationType;
import org.example.service.CBSwaggerService;
import org.example.service.TrackerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;


@Service
@Slf4j
public class TrackerServiceImpl implements TrackerService {
    @Autowired
    private CBSwaggerService cbSwaggerService;

    /**
     * 返回下游列表,根据项目->tracker分组
     *
     * @param trackerItemRequest
     * @return
     */
    @Override
    public List<DownstreamGroupResponse> getDownstreamReferences(TrackerItemRequest trackerItemRequest) {
        Integer curtTrackerId = trackerItemRequest.getTrackerId();
        List<TrackerItem> items = trackerItemRequest.getItems();

        try {
            // 全量返回下游id, 获取当前Tracker下的所有条目
            if (items == null || items.isEmpty()) {
                items = fetchAllTrackerItems(curtTrackerId);
            }
        } catch (Exception e) {
            log.error("当前跟踪器id：{}, {}", curtTrackerId, e.getMessage(), e);
            return Collections.emptyList();
        }
        // 1. 拼接 queryString
        String inClause = items.stream()
                .map(item -> String.valueOf(item.getId()))
                .collect(Collectors.joining(",", "(", ")"));


        String queryString = "SELECT tracker.name AS 'Tracker', COUNT(1) AS COUNT "
                + "WHERE SubjectID IN " + inClause
                + " GROUP BY 'Tracker'";

        // 2. 拉取所有下游items（处理翻页）
        List<TrackerItem> allItems = fetchAllItems(queryString);

        if (allItems == null || allItems.isEmpty()) {
            return Collections.emptyList();
        }

        // 3. 根据TrackerId分组，trackerId -> [trackerItemIds]
        Map<Integer, List<Integer>> byTrackerId = allItems.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getTracker() != null && item.getTracker().getId() != null)
                .collect(Collectors.groupingBy(
                        item -> item.getTracker().getId(),
                        Collectors.mapping(TrackerItem::getId, Collectors.toList())
                ));

        // 4. 根据projectId分组，projectId -> DownstreamGroupResponse
        Map<Integer, DownstreamGroupResponse> byProjectId = new HashMap<>();

        for (Map.Entry<Integer, List<Integer>> entry : byTrackerId.entrySet()) {
            Integer trackerId = entry.getKey();
            List<Integer> trackerItemIds = entry.getValue();
            // 获取项目名称
            CBTrackerInfoResponse trackerInfo = cbSwaggerService.getProjectInfo(trackerId);

            String trackerName = trackerInfo.getName();
            Integer projectId = trackerInfo.getProject().getId();
            String projectName = trackerInfo.getProject().getName();

            TrackerItemGroupResponse trackerGroup = new TrackerItemGroupResponse();
            trackerGroup.setTrackerName(trackerName);
            trackerGroup.setTrackerItemIds(trackerItemIds);

            DownstreamGroupResponse projectResp = byProjectId.computeIfAbsent(projectId, pid -> {
                DownstreamGroupResponse resp = new DownstreamGroupResponse();
                resp.setProjectId(pid);
                resp.setProjectName(projectName);
                resp.setTrackerItemGroupResponseList(new ArrayList<>());
                return resp;
            });

            projectResp.getTrackerItemGroupResponseList().add(trackerGroup);
        }

        return new ArrayList<>(byProjectId.values());
    }


    /**
     * 翻页拉取所有下游数据
     **/
    private List<TrackerItem> fetchAllItems(String queryString) {
        List<TrackerItem> allItems = new ArrayList<>();
        int pageSize = 500;
        int page = 1;

        while (true) {
            CBQueryResponse response = cbSwaggerService.query(page, pageSize, queryString);

            if (response == null || response.getItems() == null || response.getItems().isEmpty()) break;

            allItems.addAll(response.getItems());

            // total <= 已拉取数量 说明没有下一页
            if (allItems.size() >= response.getTotal()) break;

            page++;
        }

        return allItems;
    }

    /**
     * 翻页拉取指定 trackerId 的所有 items
     */
    private List<TrackerItem> fetchAllTrackerItems(Integer trackerId) {
        List<TrackerItem> allItems = new ArrayList<>();
        int pageSize = 500;
        int page = 1;

        while (true) {
            CBTrackerItemsResponse response = cbSwaggerService.getAllTrackerItems(page, pageSize, trackerId);

            if (response == null || response.getItemRefs() == null || response.getItemRefs().isEmpty()) {
                break;
            }

            allItems.addAll(response.getItemRefs());

            if (allItems.size() >= response.getTotal()) {
                break;
            }

            page++;
        }
        if (allItems == null || allItems.isEmpty()) {
            throw new RuntimeException("该跟踪器下无条目，请先创建需求条目！");
        }
        return allItems;
    }

    /**
     * 建立追溯，输入的projectId和TrackerId是唯一的
     * * 1. 无输入：全量
     * * 2. 有：批量
     * <p>
     * * v2修改：
     * * 1.根据outgoingassociation获得的id列表（需求池的下游），去查链接关系，收集需求池上游upstreamReferences
     * * 2.根据upstreamReferences，去查链接关系，收集incomingAssociations
     * * 3.query接口获取本项目的id
     * *
     * * V2.1修改：
     * * 改批量查询relations为单条分页查询
     *
     * @param trackerItemRequest
     * @return
     */
    @Override
    public BuildUpstreamResponse updateField(TrackerItemRequest trackerItemRequest) {

        // 收集更新追溯字段失败的项目条目id
        List<Integer> errUpdateIdList = new ArrayList<>();
        // 收集找不到需求池outgoingAssociations的项目条目id
        List<Integer> errOutIdList = new ArrayList<>();
        // 收集找不到需求池上游追溯的项目条目id
        List<Integer> errUpstreamIdList = new ArrayList<>();
        // 需求池上游未被复制到项目
        List<Integer> errInIdList = new ArrayList<>();

        // 当前Tracker Id，唯一
        Integer trackerId = trackerItemRequest.getTrackerId();
        // 当前项目id，唯一
        Integer projectId = null;
        // 当前Tracker追溯字段id
        Integer fieldId;
        // 拆解valueModel得到
        String fieldType;
        String trackerItemType;

        BuildUpstreamResponse resp = new BuildUpstreamResponse();

        List<TrackerItem> items = trackerItemRequest.getItems();

        try {
            // 全量建立追溯, 获取当前Tracker下的所有条目
            if (items == null || items.isEmpty()) {
                items = fetchAllTrackerItems(trackerId);
            }

            // 1. 查Tracker配置，拿到projectId和tracker追溯字段id
            CBTrackerConfigurationResponse trackerConfiguration = cbSwaggerService.getTrackerConfiguration(trackerId);
            projectId = trackerConfiguration.getProjectId();
            fieldId = trackerConfiguration.getFieldId();

            // 2. 用Tracker的自定义追溯字段id，拿到Tracker追溯字段的valueModel
            CBTrackerConfigurationResponse trackerField = cbSwaggerService.getTrackerField(trackerId, fieldId);
            fieldType = trackerField.getFieldType();
            trackerItemType = trackerField.getTrackerItemType();

            /** 3. 根据项目trackeritem id查找唯一outgoingAssociations,构建Map<项目, 池子>item id映射
             *  -- 一对一：
             *  -- Map<项目id, 池子id>
             *  -- Map<项目id, null>
             */
//            AssociationsRequest associationsReq = new AssociationsRequest(items);
//            Map<Integer, Integer> itemsRelationMap = cbSwaggerService.getSingleRelationMap(
//                    associationsReq, RelationType.OUTGOING_ASSOCIATIONS
//            );
            Map<Integer, Integer> itemsRelationMap = new HashMap<>();
            for (TrackerItem item : items) {
                Integer id = item.getId();
                List<Integer> outgoingId = cbSwaggerService.getRelationId(id, RelationType.OUTGOING_ASSOCIATIONS);
                if (outgoingId == null || outgoingId.isEmpty()) {
                    itemsRelationMap.put(id, null);
                    continue;
                }
                itemsRelationMap.put(id, outgoingId.get(0));
            }

            /** 4. 根据池子item id查upstreamReferences，构建Map<池子itemId, 池子上游id列表>
             *  -- 一对多
             *  -- Map<池子itemId, 池子上游id列表>
             *  -- Map<池子itemId, null>
             */
            // 如果车型项目里选择的所有条目都没有和需求池的outcoming关系，直接中断链路
            if (itemsRelationMap.values().stream().allMatch(v -> v == null)) {
                throw new RuntimeException("当前选择条目均无复制关联，请检查！");
            }
//            AssociationsRequest associationsGetUpStream = new AssociationsRequest(itemsRelationMap.values());
//            Map<Integer, List<Integer>> copyToUpstreamMap = cbSwaggerService.getMultiRelationMap(
//                    associationsGetUpStream, RelationType.UPSTREAM_REFERENCES
//            );
            Map<Integer, List<Integer>> copyToUpstreamMap = new HashMap<>();
            for (Integer id : itemsRelationMap.values()) {
                // 车型项目中有些条目可能不是从池子复制的
                if (id == null) {
                    continue;
                }
                List<Integer> upstreamId = cbSwaggerService.getRelationId(id, RelationType.UPSTREAM_REFERENCES);
                copyToUpstreamMap.put(id, upstreamId.isEmpty() ? null : upstreamId);
            }

            /** 5. 根据所有池子上游id查incomingAssociations，构建Map<池子上游id, incoming id列表>
             *  -- 一对多
             *  -- Map<池子上游id, 被复制到项目中的id列表>
             *  -- Map<池子上游id, null>
             */
            Set<Integer> allUpstreamIds = copyToUpstreamMap.values().stream()
                    .filter(list -> list != null && !list.isEmpty())
                    .flatMap(List::stream)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            // 如果车型项目里选择条目所对应的需求池outcoming条目，都没有上游追溯，直接中断链路
            if (allUpstreamIds == null || allUpstreamIds.isEmpty()) {
                throw new RuntimeException("当前选择条目的复制关联条目均无上游追溯，请检查！");
            }
//            Map<Integer, List<Integer>> upstreamToIncomingMap = cbSwaggerService.getMultiRelationMap(
//                    new AssociationsRequest(allUpstreamIds), RelationType.INCOMING_ASSOCIATIONS
//            );
            Map<Integer, List<Integer>> upstreamToIncomingMap = new HashMap<>();
            for (Integer upstreamId : allUpstreamIds) {
                List<Integer> incomingId = cbSwaggerService.getRelationId(upstreamId, RelationType.INCOMING_ASSOCIATIONS);
                upstreamToIncomingMap.put(upstreamId, incomingId.isEmpty() ? null : incomingId);
            }

            // v2-6. 用projectId+incomingAssociations筛选本项目的条目
            Set<Integer> allIncomingIds = upstreamToIncomingMap.values().stream()
                    .filter(list -> list != null && !list.isEmpty())
                    .flatMap(List::stream)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            // 如果车型项目里选择条目所对应的需求池outcoming条目，其对应的上游追溯都没有incoming条目，直接中断链路
            if (allIncomingIds == null || allIncomingIds.isEmpty()) {
                throw new RuntimeException("当前选择条目的复制关联条目,所对应的上游追溯条目均未被复制，请检查！");
            }
            List<Integer> projectIncomingList = queryProjectItemsByIncomingIds(allIncomingIds, projectId);
            // 如果车型项目里选择条目所对应的需求池outcoming条目，其对应的上游追溯的incoming条目都不是本项目，直接中断链路
            if (projectIncomingList == null || projectIncomingList.isEmpty()) {
                throw new RuntimeException("当前选择条目的复制关联条目,所对应的上游追溯条目均未被复制到当前项目，请检查！");
            }
            //对每个Trackeritem逐一建立追溯
            for (Map.Entry<Integer, Integer> entry : itemsRelationMap.entrySet()) {
                //项目里的TrackeritemId
                Integer id = entry.getKey();
                //从池子里复制过来的Trackeritemid，一对一
                Integer copyId = entry.getValue();
                if (copyId == null) {
                    errOutIdList.add(id);
                    continue;
                }

                //try {

                // 4. 根据池子item id，得到池子上游item name
//                    List<String> upStreamNames = cbSwaggerService.getUpStreamNames(copyId);
//                    if (upStreamNames == null || upStreamNames.isEmpty()) {
//                        throw new RuntimeException("需求池条目追溯不完整:" + copyId);
//                    }


                // 5. query-拿池子上游item name（多个上游）去和项目里（要拿到project id）items做匹配，拿到项目里的item id
//                    String upStreamNamesStr = upStreamNames.stream()
//                            .map(this::toSummaryEqualsCondition)
//                            .collect(Collectors.joining(" OR ", "(", ")"));
//
//                    String queryString = upStreamNamesStr + " AND project.id IN (" + projectId + ")";
//                    List<TrackerItem> projectUptreamItem = fetchAllItems(queryString);
//                    if(projectUptreamItem.isEmpty()){
//                        throw new RuntimeException("项目上游Tracker条目匹配失败！");
//                    }
                // copyId得到池子里的上游条目，一对多
                List<Integer> currentUpstreamIds = copyToUpstreamMap.getOrDefault(copyId, Collections.emptyList());
                if (currentUpstreamIds == null || currentUpstreamIds.isEmpty()) {
                    errUpstreamIdList.add(id);
                    continue;
                }
                // 池子上游条目得到所有incoming
                Set<Integer> currentIncomingIds = new LinkedHashSet<>();
                for (Integer upstreamId : currentUpstreamIds) {
                    currentIncomingIds.addAll(
                            Optional.ofNullable(upstreamToIncomingMap.getOrDefault(upstreamId, Collections.emptyList()))
                                    .orElse(Collections.emptyList())
                    );
                }
                if (currentIncomingIds == null || currentIncomingIds.isEmpty()) {
                    errInIdList.add(id);
                    continue;
                }
                // 筛选得到本项目的上游条目
                List<Integer> projectUptreamItem = currentIncomingIds.stream()
                        .filter(projectIncomingList::contains)
                        .collect(Collectors.toList());
                if (projectUptreamItem.isEmpty()) {
                    errInIdList.add(id);
                    continue;
                }

                // 6. 更新项目item自定义追溯字段
                TrackerItemFieldRequest trackerItemFieldRequest = new TrackerItemFieldRequest();
                TrackerItemFieldRequest.FieldValue fieldValue = new TrackerItemFieldRequest.FieldValue();
                List<TrackerItemFieldRequest.ValueItem> values = new ArrayList<>();
                // 构造批量追溯入参
                for (Integer t : projectUptreamItem) {
                    TrackerItemFieldRequest.ValueItem valueItem = new TrackerItemFieldRequest.ValueItem();
                    valueItem.setId(t);
                    valueItem.setType(trackerItemType);
                    values.add(valueItem);
                }
                fieldValue.setValues(values);
                fieldValue.setFieldId(fieldId);
                fieldValue.setType(fieldType);
                trackerItemFieldRequest.setFieldValues(List.of(fieldValue));
                try {
                    cbSwaggerService.putTrackerItemField(trackerItemFieldRequest, id);
                } catch (Exception e) {
                    log.error("项目id:{}, 跟踪器id：{}, {}", projectId, trackerId, e.getMessage(), e);
                    errUpdateIdList.add(id);
                }
            }


        } catch (Exception e) {
            log.error("建立追溯流程中断, 项目id: {}, Tracker id：{}, {}", projectId, trackerId, e.getMessage(), e);
            resp.setSuccess(false);
            resp.setMessage("建立追溯流程中断！" + e.getMessage());
            return resp;
        }
        if (errInIdList.isEmpty() && errUpstreamIdList.isEmpty() && errOutIdList.isEmpty() && errUpdateIdList.isEmpty()) {
            resp.setSuccess(true);
            resp.setMessage("成功建立追溯!");
            return resp;
        } else {
            resp.setSuccess(false);
            resp.setMessage("以下条目未建立追溯：");
            if (!errInIdList.isEmpty()) {
                resp.setErrInIdList(errInIdList);
            }
            if (!errUpstreamIdList.isEmpty()) {
                resp.setErrUpstreamIdList(errUpstreamIdList);
            }
            if (!errOutIdList.isEmpty()) {
                resp.setErrOutIdList(errOutIdList);
            }
            if (!errUpdateIdList.isEmpty()) {
                resp.setErrUpdateIdList(errUpdateIdList);
            }
            return resp;
        }

    }

    /**
     * 构造 summary = 'xxx' 条件。
     * 这里使用字符串字面量转义（单引号 -> 两个单引号），避免特殊字符破坏语法。
     */
    @SuppressWarnings("unused")
    private String toSummaryEqualsCondition(String summary) {
        String safeSummary = summary == null ? "" : summary.replace("'", "''");
        return "summary = '" + safeSummary + "'";
    }

    /**
     * queryString拼接规则：
     * project.id IN (904) AND item.id IN (1968298,1968328,...)
     */
    private List<Integer> queryProjectItemsByIncomingIds(Set<Integer> incomingIds, Integer projectId) {
        String incomingClause = incomingIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "(", ")"));
        String queryString = "project.id IN (" + projectId + ") AND item.id IN " + incomingClause;
        List<TrackerItem> projectItems = fetchAllItems(queryString);
        return projectItems.stream()
                .filter(item -> item.getId() != null)
                .map(TrackerItem::getId)
                .collect(Collectors.toList());
    }
}
