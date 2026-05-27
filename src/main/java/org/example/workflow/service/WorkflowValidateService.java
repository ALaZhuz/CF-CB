package org.example.workflow.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.model.dto.response.ItemInfoResponse;
import org.example.service.CBSwaggerService;
import org.example.workflow.cache.DingUserCacheService;
import org.example.workflow.config.WorkflowConfigService;
import org.example.workflow.config.WorkflowTemplate;
import org.example.workflow.dto.ValidateRequest;
import org.example.workflow.dto.ValidateResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
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
     * 执行beforeEvent校验
     *
     * 校验流程：
     * 1. 获取条目详情（tracker信息）
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

        log.info("开始beforeEvent校验: itemId={}, targetState={}", itemId, targetState);

        try {
            // 1. 获取条目详情: itemId, targetState,trackerId,trackerName,trackerType,projectId
            ItemInfoResponse itemInfo = cbSwaggerService.getItemInfo(itemId);
            if (itemInfo == null) {
                response.setErrorMessage("条目不存在: itemId=" + itemId);
                return response;
            }

            // 补充tracker和项目信息
            Integer trackerId = itemInfo.getTracker().getId();
            String trackerType = itemInfo.getTracker().getTypeName();
            Integer projectId = itemInfo.getProject().getId();

            log.info("开始beforeEvent校验: trackerId={}, trackerType={}, projectId={}",
                    trackerId, trackerType, projectId);

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
                log.info("目标状态不需要通知，放行保存: itemId={}, targetState={}", itemId, targetState);
                return response;
            }

            // 6. 校验通知字段是否有成员
            String notifyField = stateConfig.getNotifyField();
            List<ItemInfoResponse.MemberInfo> members = itemInfo.getMembersByField(notifyField);

            if (members == null || members.isEmpty()) {
                response.setErrorMessage("通知字段[" + notifyField + "]未填写成员，请先填写后再保存");
                return response;
            }

            // 记录通知成员信息
            List<String> memberNames = members.stream()
                    .map(m -> m.getName() != null ? m.getName() : m.getUserId())
                    .collect(Collectors.toList());
            response.setNotifyField(notifyField);
            response.setNotifyMembers(memberNames);

            // 7. 校验userid在钉钉中存在
            List<String> userids = members.stream()
                    .map(ItemInfoResponse.MemberInfo::getUserId)
                    .filter(id -> id != null && !id.isEmpty())
                    .collect(Collectors.toList());

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
            log.info("beforeEvent校验通过: itemId={}, targetState={}, notifyField={}, members={}",
                    itemId, targetState, notifyField, memberNames);

        } catch (Exception e) {
            log.error("beforeEvent校验异常: itemId={}, error={}", itemId, e.getMessage(), e);
            response.setErrorMessage("校验异常: " + e.getMessage());
        }

        return response;
    }
}