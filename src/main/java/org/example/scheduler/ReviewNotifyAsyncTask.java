package org.example.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.service.impl.ReviewNotificationServiceImpl;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

// 异步任务类 负责执行

@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewNotifyAsyncTask {

    private final ReviewNotificationServiceImpl reviewNotificationService;

//    @Async
    public void
    runThirtyMinuteTasks() {
//        log.info("=== 进入 runThirtyMinuteTasks，线程：{}", Thread.currentThread().getName());
        reviewNotificationService.syncLifecycle();
        reviewNotificationService.runThirtyMinuteTasks();
    }
//    @Async
    public void runEightOClockTasks() {
        reviewNotificationService.runEightOClockTasks();
    }
}