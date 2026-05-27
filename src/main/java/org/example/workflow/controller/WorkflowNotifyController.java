package org.example.workflow.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.workflow.dto.NotifyRequest;
import org.example.workflow.dto.NotifyResponse;
import org.example.workflow.service.WorkflowNotifyService;
import org.springframework.web.bind.annotation.*;

/**
 * 工作流通知控制器
 *
 * 提供afterEvent通知接口，供Codebeamer Groovy脚本调用。
 * 在条目保存成功后处理通知逻辑。
 *
 * @author system
 * @since 1.0
 */
@RestController
@Slf4j
@RequestMapping("/workflow")
@RequiredArgsConstructor
public class WorkflowNotifyController {

    private final WorkflowNotifyService workflowNotifyService;

    /**
     * afterEvent通知接口
     *
     * Codebeamer Groovy脚本在条目保存成功后调用此接口。
     * 处理进入/离开目标状态的通知逻辑。
     *
     * @param request 通知请求，包含itemId、previousState、targetState
     * @return 通知响应，包含success和notifiedUsers
     */
    @PostMapping("/notify")
    public NotifyResponse notify(@RequestBody NotifyRequest request) {
        log.info("收到afterEvent通知请求: itemId={}, previousState={}, targetState={}",
                request.getItemId(), request.getPreviousState(), request.getTargetState());

        NotifyResponse response = workflowNotifyService.notify(request);

        log.info("afterEvent处理完成: actionType={}, success={}",
                response.getActionType(), response.isSuccess());

        return response;
    }
}