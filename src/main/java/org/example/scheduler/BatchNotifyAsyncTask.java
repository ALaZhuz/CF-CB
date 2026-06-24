package org.example.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.workflow.service.BatchNotifyService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 批量即时通知异步任务类
 *
 * 负责异步执行批量即时通知任务。
 * 使用@Async注解避免阻塞调度器线程。
 *
 * @author system
 * @since 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BatchNotifyAsyncTask {

    private final BatchNotifyService batchNotifyService;

    /**
     * 执行批量即时通知任务
     *
     * 调度器调用此方法，异步执行批量通知逻辑。
     */
    @Async
    public void runBatchNotifyTasks() {
        batchNotifyService.executeBatchNotify();
    }
}