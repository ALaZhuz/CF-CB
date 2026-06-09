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
import lombok.extern.slf4j.Slf4j;
import org.example.service.impl.ReviewNotificationServiceImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.annotation.PostConstruct;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReviewNotifyScheduler {
    private final ReviewNotifyAsyncTask reviewNotifyAsyncTask;

//    @Value("${dingtalk.fixed-delay}")
//    private String fixedDelay;
//
//    @Value("${dingtalk.notify-cron}")
//    private String notifyCron;
//
//    @PostConstruct
//    public void init() {
//        log.info("ReviewNotifyScheduler bean 已创建, fixedDelay={}, notifyCron={}", fixedDelay, notifyCron);
//    }
//
//    /**
//     * 测试任务：应用启动后10秒执行，验证调度器是否正常工作
//     */
//    @Scheduled(initialDelay = 10000, fixedDelay = Long.MAX_VALUE)
//    public void testScheduler() {
//        log.info("✅ 测试调度任务执行成功 - 调度器正常工作");
//    }

    /**
     * 30 分钟轮询任务
     * 1. 同步生命周期
     * 2. 超期提醒
     */
    @Scheduled(fixedDelayString = "${dingtalk.fixed-delay}")
    public void runThirtyMinuteTasks() {
//        log.info("调度任务 runThirtyMinuteTasks 开始执行");
        reviewNotifyAsyncTask.runThirtyMinuteTasks();
//        log.info("调度任务 runThirtyMinuteTasks 调用完成");
    }

    /**
     * 8 点批处理任务
     * 1. 临期提醒
     * 2. 管理层超期提醒
     */
    @Scheduled(cron = "${dingtalk.notify-cron}")
    public void runEightOClockTasks() {
//        log.info("调度任务 runEightOClockTasks 开始执行");
        reviewNotifyAsyncTask.runEightOClockTasks();
//        log.info("调度任务 runEightOClockTasks 调用完成");
    }
}
