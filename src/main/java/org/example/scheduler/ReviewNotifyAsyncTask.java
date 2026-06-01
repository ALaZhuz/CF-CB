package org.example.scheduler;

import lombok.RequiredArgsConstructor;
import org.example.service.impl.ReviewNotificationServiceImpl;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

// 异步任务类 负责执行
@Async
@Component
@RequiredArgsConstructor
public class ReviewNotifyAsyncTask {

    private final ReviewNotificationServiceImpl reviewNotificationService;

    public void runThirtyMinuteTasks() {
        reviewNotificationService.syncLifecycle();
        reviewNotificationService.runThirtyMinuteTasks();
    }

    public void runEightOClockTasks() {
        reviewNotificationService.runEightOClockTasks();
    }
}