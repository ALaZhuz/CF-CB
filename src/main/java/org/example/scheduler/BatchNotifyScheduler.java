package org.example.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.workflow.config.WorkflowConfigService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.util.concurrent.ScheduledFuture;

/**
 * 批量即时通知调度器
 *
 * 负责定时触发批量即时通知任务。
 * 使用编程式TaskScheduler动态读取workflow-config.yml中的轮询间隔配置。
 *
 * 配置项：default-batch-notify-delay（单位：毫秒，默认180000ms=3分钟）
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
    private final ThreadPoolTaskScheduler taskScheduler;

    private ScheduledFuture<?> scheduledTask;

    /**
     * 应用启动完成后启动定时任务
     *
     * 动态读取workflow-config.yml中的轮询间隔配置
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startBatchNotifyTask() {
        Long delay = workflowConfigService.getBatchNotifyDelay();
        log.info("启动批量即时通知定时任务，轮询间隔={}ms（{}分钟）", delay, delay / 60000);

        // 创建周期性执行的定时任务
        scheduledTask = taskScheduler.scheduleWithFixedDelay(
            () -> {
                log.debug("批量即时通知轮询任务触发");
                batchNotifyAsyncTask.runBatchNotifyTasks();
            },
            delay
        );
    }
}