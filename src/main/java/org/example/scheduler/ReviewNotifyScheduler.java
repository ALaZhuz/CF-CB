/*
 * @Author: 张阳阳 1401459021@qq.com
 * @Date: 2026-05-25 19:40:21
 * @LastEditors: 张阳阳 1401459021@qq.com
 * @LastEditTime: 2026-05-29 14:20:37
 * @FilePath: \cf-cb\src\main\java\org\example\scheduler\ReviewNotifyScheduler.java
 * @Description: 这是默认设置,请设置`customMade`, 打开koroFileHeader查看配置 进行设置: https://github.com/OBKoro1/koro1FileHeader/wiki/%E9%85%8D%E7%BD%AE
 */
package org.example.scheduler;

import lombok.RequiredArgsConstructor;
import org.example.service.impl.ReviewNotificationServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReviewNotifyScheduler {
    private final ReviewNotificationServiceImpl reviewNotificationService;

    /**
     * 30 分钟轮询任务
     * 1. 同步生命周期
     * 2. 超期提醒
     */
    @Scheduled(fixedDelayString = "${dingtalk.fixed-delay}")
    public void runThirtyMinuteTasks() {
        reviewNotificationService.syncLifecycle();
        reviewNotificationService.runThirtyMinuteTasks();
    }

    /**
     * 8 点批处理任务
     * 1. 临期提醒
     * 2. 管理层超期提醒
     */
    @Scheduled(cron = "${dingtalk.notify-cron}")
    public void runEightOClockTasks() {
        reviewNotificationService.runEightOClockTasks();
    }
}
