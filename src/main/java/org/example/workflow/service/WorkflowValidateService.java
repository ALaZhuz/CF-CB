package org.example.workflow.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.model.dto.response.CBTrackerInfoResponse;
import org.example.model.dto.response.ItemInfoResponse;
import org.example.service.CBSwaggerService;
import org.example.workflow.cache.DingUserCacheService;
import org.example.workflow.config.WorkflowConfigService;
import org.example.workflow.config.WorkflowTemplate;
import org.example.workflow.dto.NotifyFieldResponse;
import org.example.workflow.dto.ValidateRequest;
import org.example.workflow.dto.ValidateResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工作流校验服务类
 *
 * 实现beforeEvent三项校验逻辑：
 * 1. 目标状态是否在工作流配置中显式声明
 * 2. 通知字段是否有成员填写
 * 3. 所有userid在钉钉缓存中存在
 *
 * @author system
 * @since 1.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WorkflowValidateService {

    private final WorkflowConfigService workflowConfigService;
    private final CBSwaggerService cbSwaggerService;
    private final DingUserCacheService dingUserCacheService;

    /**
     * 查询目标状态的notifyField
     *
     * 供Groovy脚本在beforeEvent阶段调用，获取需要校验的通知字段名称。
     * 此方法不执行校验，只返回配置信息。
     *
     * @param trackerId tracker ID
     * @param targetState 目标状态名称
     * @return notifyField响应，包含是否需要通知和字段名称
     */
    public NotifyFieldResponse getNotifyField(Integer trackerId, String targetState) {
        NotifyFieldResponse response = new NotifyFieldResponse();
        response.setNeedsNotify(false);

        try {
            // 1. 获取tracker信息（补充trackerType和projectId）
            String trackerType = null;
            Integer projectId = null;

            CBTrackerInfoResponse trackerInfo = cbSwaggerService.getProjectInfo(trackerId);
            if (trackerInfo != null) {
                if (trackerInfo.getType() != null) {
                    trackerType = trackerInfo.getType().getName();
                }
                if (trackerInfo.getProject() != null) {
                    projectId = trackerInfo.getProject().getId();
                }
            }

            // 2. 查找工作流配置
            WorkflowTemplate workflow = workflowConfigService.getWorkflowForTracker(
                    trackerId, trackerType, projectId);

            if (workflow == null) {
                response.setErrorMessage("未找到tracker配置: trackerId=" + trackerId);
                return response;
            }

            response.setWorkflowName(workflow.getName());

            // 3. 判断目标状态是否已声明
            if (!workflowConfigService.isStateDeclared(workflow, targetState)) {
                response.setErrorMessage("目标状态[" + targetState + "]未在工作流模板[" +
                        workflow.getName() + "]中配置");
                return response;
            }

            // 4. 获取状态配置
            WorkflowTemplate.StateConfig stateConfig = workflowConfigService.getStateConfig(workflow, targetState);

            // 5. 判断是否需要通知
            if (!workflowConfigService.shouldNotify(stateConfig)) {
                response.setNeedsNotify(false);
                response.setNotifyField(null);
                return response;
            }

            // 6. 返回notifyField（合并为逗号分隔字符串，向后兼容单值API契约）
            List<String> notifyFields = workflowConfigService.getNotifyFields(stateConfig);
            response.setNeedsNotify(true);
            response.setNotifyField(notifyFields.isEmpty() ? null : String.join(",", notifyFields));

        } catch (Exception e) {
            log.error("查询notifyField异常: trackerId={}, error={}", trackerId, e.getMessage(), e);
            response.setErrorMessage("查询异常: " + e.getMessage());
        }

        return response;
    }

    /**
     * 执行beforeEvent校验
     *
     * 校验流程：
     * 1. 获取条目详情（tracker信息）- 新建时使用 request 中的 tracker 信息
     * 2. 查找对应的工作流配置
     * 3. 判断目标状态是否需要通知
     * 4. 校验通知字段成员
     * 5. 校验userid钉钉存在性
     *
     * @param request 校验请求
     * @return 校验响应
     */
    public ValidateResponse validate(ValidateRequest request) {
        ValidateResponse response = new ValidateResponse();
        response.setSuccess(false);

        Integer itemId = request.getItemId();
        String targetState = request.getTargetState();

        try {
            Integer trackerId;
            String trackerType;
            Integer projectId;
            ItemInfoResponse itemInfo = null;

            // 1. 获取 tracker 和项目信息
            // 场景区分：修改条目(itemId!=null) 和 新建条目(itemId==null)
            if (itemId != null) {
                // 修改条目：通过 itemId 获取条目详情
                itemInfo = cbSwaggerService.getItemInfo(itemId);
                if (itemInfo == null) {
                    response.setErrorMessage("条目不存在: itemId=" + itemId);
                    return response;
                }
                trackerId = itemInfo.getTracker().getId();
                trackerType = itemInfo.getTracker().getTypeName();
                projectId = itemInfo.getProject().getId();
            } else {
                // 新建条目：使用 request 中的 tracker 信息
                trackerId = request.getTrackerId();
                trackerType = request.getTrackerType();
                projectId = request.getProjectId();

                // 如果 trackerId 存在但 trackerType/projectId 不存在，调用 API 补充
                if (trackerId != null) {
                    if (trackerType == null || projectId == null) {
                        var trackerInfo = cbSwaggerService.getProjectInfo(trackerId);
                        if (trackerInfo != null) {
                            if (trackerType == null && trackerInfo.getType() != null) {
                                trackerType = trackerInfo.getType().getName();
                            }
                            if (projectId == null && trackerInfo.getProject() != null) {
                                projectId = trackerInfo.getProject().getId();
                            }
                        }
                    }
                }

                if (trackerId == null) {
                    response.setErrorMessage("新建条目缺少tracker信息，无法校验");
                    return response;
                }
            }

            // 2. 查找工作流配置
            WorkflowTemplate workflow = workflowConfigService.getWorkflowForTracker(
                    trackerId, trackerType, projectId);

            if (workflow == null) {
                response.setErrorMessage("未找到tracker配置: trackerId=" + trackerId);
                return response;
            }

            // 3. 判断目标状态是否已声明
            if (!workflowConfigService.isStateDeclared(workflow, targetState)) {
                response.setErrorMessage("目标状态[" + targetState + "]未在工作流模板[" +
                        workflow.getName() + "]中配置，请联系管理员补充");
                return response;
            }

            // 4. 获取状态配置
            WorkflowTemplate.StateConfig stateConfig = workflowConfigService.getStateConfig(workflow, targetState);

            // 5. 判断是否需要通知
            if (!workflowConfigService.shouldNotify(stateConfig)) {
                // 状态配置了notify:false或无notifyField，放行保存
                response.setSuccess(true);
                response.setErrorMessage(null);
                return response;
            }

            // 6. 校验通知字段是否有成员（遍历所有通知字段）
            List<String> notifyFields = workflowConfigService.getNotifyFields(stateConfig);
            response.setNotifyField(notifyFields.isEmpty() ? null : String.join(",", notifyFields));

            List<String> userids;
            List<String> memberNames;

            // 优先使用 request 中的成员信息（Groovy脚本从subject提取的新数据）
            if (request.getNotifyUserIds() != null && !request.getNotifyUserIds().isEmpty()) {
                userids = request.getNotifyUserIds();
                memberNames = request.getNotifyMemberNames();
            } else if (itemInfo != null) {
                // 兼容旧逻辑：如果request中没有成员信息，从itemInfo获取（合并所有通知字段成员）
                List<ItemInfoResponse.MemberInfo> members = getMembersByFields(itemInfo, notifyFields);
                if (members == null || members.isEmpty()) {
                    response.setErrorMessage("通知字段[" + String.join(",", notifyFields) + "]未填写成员，请先填写后再保存");
                    return response;
                }
                userids = members.stream()
                        .map(ItemInfoResponse.MemberInfo::getUserId)
                        .filter(id -> id != null && !id.isEmpty())
                        .collect(Collectors.toList());
                memberNames = members.stream()
                        .map(m -> m.getName() != null ? m.getName() : m.getUserId())
                        .collect(Collectors.toList());
            } else {
                response.setErrorMessage("通知字段[" + String.join(",", notifyFields) + "]未填写成员，请先填写后再保存");
                return response;
            }

            if (userids == null || userids.isEmpty()) {
                response.setErrorMessage("通知字段[" + String.join(",", notifyFields) + "]未填写成员，请先填写后再保存");
                return response;
            }

            response.setNotifyMembers(memberNames);

            // 7. 校验userid在钉钉中存在
            Set<String> invalidUserIds = dingUserCacheService.findInvalidUserIds(userids);

            if (!invalidUserIds.isEmpty()) {
                response.setInvalidUserIds(new ArrayList<>(invalidUserIds));
                response.setErrorMessage("成员[" + String.join(",", invalidUserIds) +
                        "]的userid在钉钉中不存在，请检查用户配置");
                return response;
            }

            // 8. 校验全部通过
            response.setSuccess(true);
            response.setErrorMessage(null);
            // 合并日志：一次校验只输出一条成功日志
            log.info("[beforeEvent] 校验通过: itemId={}, trackerId={}, targetState={}, notifyFields={}, members={}",
                    itemId, trackerId, targetState, notifyFields, memberNames);

        } catch (Exception e) {
            log.error("[beforeEvent] 校验异常: itemId={}, error={}", itemId, e.getMessage(), e);
            response.setErrorMessage("校验异常: " + e.getMessage());
        }

        return response;
    }

    /**
     * 合并多个通知字段的成员，按 userId 去重
     *
     * @param itemInfo 条目详情
     * @param notifyFields 通知字段名称列表
     * @return 合并去重后的成员列表
     */
    private List<ItemInfoResponse.MemberInfo> getMembersByFields(ItemInfoResponse itemInfo, List<String> notifyFields) {
        if (itemInfo == null || notifyFields == null || notifyFields.isEmpty()) {
            return new ArrayList<>();
        }

        // 使用 LinkedHashMap 按 userId 去重，保留插入顺序
        Map<String, ItemInfoResponse.MemberInfo> dedup = new LinkedHashMap<>();
        for (String field : notifyFields) {
            List<ItemInfoResponse.MemberInfo> fieldMembers = itemInfo.getMembersByField(field);
            if (fieldMembers == null) {
                continue;
            }
            for (ItemInfoResponse.MemberInfo member : fieldMembers) {
                String key = member.getUserId();
                if (key == null || key.isEmpty()) {
                    dedup.put("NO_USERID_" + System.identityHashCode(member), member);
                } else if (!dedup.containsKey(key)) {
                    dedup.put(key, member);
                }
            }
        }
        return new ArrayList<>(dedup.values());
    }
}