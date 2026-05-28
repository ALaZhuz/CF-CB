package org.example.workflow.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.workflow.dto.ValidateRequest;
import org.example.workflow.dto.ValidateResponse;
import org.example.workflow.service.WorkflowValidateService;
import org.springframework.web.bind.annotation.*;

/**
 * 工作流校验控制器
 *
 * 提供beforeEvent校验接口，供Codebeamer Groovy脚本调用。
 * 在条目保存前执行校验，决定是否允许保存。
 *
 * @author system
 * @since 1.0
 */
@RestController
@Slf4j
@RequestMapping("/workflow")
@RequiredArgsConstructor
public class WorkflowValidateController {

    private final WorkflowValidateService workflowValidateService;

    /**
     * beforeEvent校验接口
     *
     * Codebeamer Groovy脚本在保存条目前调用此接口。
     * 校验失败时返回errorMessage，Groovy脚本应阻止保存并提示用户。
     * 校验成功时返回success=true，Groovy脚本放行保存。
     *
     * @param request 校验请求，包含itemId和targetState
     * @return 校验响应，包含success和errorMessage
     */
    @PostMapping("/validate")
    public ValidateResponse validate(@RequestBody ValidateRequest request) {
        log.info("收到beforeEvent校验请求: itemId={}, targetState={}",
                request.getItemId(), request.getTargetState());

        ValidateResponse response = workflowValidateService.validate(request);

        if (response.isSuccess()) {
            log.info("校验通过，放行保存");
        } else {
            log.warn("校验失败，阻止保存: {}", response.getErrorMessage());
        }

        return response;
    }
}