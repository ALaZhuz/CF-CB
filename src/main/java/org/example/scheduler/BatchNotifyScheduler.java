package org.example.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.workflow.config.WorkflowConfigService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 批量即时通知调度器
 *
 * 负责定时触发批量即时通知任务。
 * 使用@Scheduled注解配置轮询间隔。
 *
 * 轮询间隔从workflow-config.yml中的default-batch-notify-delay读取（默认180000ms=3分钟）。
 *
 * @author system
 * @since 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BatchNotifyScheduler {

    private final BatchNotifyAsyncTask batchNotifyAsyncTask;
    private final WorkflowConfigService workflowConfigService;

    /**
     * 批量即时通知轮询任务
     *
     * 每隔default-batch-notify-delay毫秒执行一次。
     * 异步执行避免阻塞调度器线程。
     */
    @Scheduled(fixedDelayString = "${default-batch-notify-delay:180000}")
    public void runBatchNotifyTasks() {
        log.debug("批量即时通知轮询任务触发，轮询间隔={}ms", workflowConfigService.getBatchNotifyDelay());
        batchNotifyAsyncTask.runBatchNotifyTasks();
    }
}