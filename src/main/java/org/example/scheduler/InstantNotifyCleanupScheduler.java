package org.example.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.db.entity.InstantNotifyRecord;
import org.example.db.mapper.InstantNotifyRecordMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 批量即时通知记录清理调度器
 *
 * 负责每天00:00清空instant_notify_record表。
 * 因为批量通知只在当天有效，第二天失效，需要清空前一天的所有记录。
 *
 * @author system
 * @since 1.0
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InstantNotifyCleanupScheduler {

    private final InstantNotifyRecordMapper instantNotifyRecordMapper;

    /**
     * 清理批量通知记录任务
     *
     * 每天00:00执行，清空instant_notify_record表的所有记录。
     * 批量通知只在状态切换当天有效，第二天转为定时通知范畴。
     *
     * 清空后会验证表中所有数据是否为0，确保清空成功。
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void cleanupInstantNotifyRecords() {
        log.info("========== 批量通知记录清理任务启动 ==========");

        try {
            // 1. 清空表
            instantNotifyRecordMapper.deleteAll();
            log.info("批量通知记录清理完成：已执行DELETE操作");

            // 2. 验证表中所有数据是否为0
            List<InstantNotifyRecord> allRecords = instantNotifyRecordMapper.selectAll();

            if (allRecords.isEmpty()) {
                log.info("批量通知记录清理验证成功：表中数据为0");
            } else {
                log.warn("批量通知记录清理验证失败：表中仍有{}条记录，请检查", allRecords.size());
            }

        } catch (Exception e) {
            log.error("批量通知记录清理失败: {}", e.getMessage(), e);
        }

        log.info("========== 批量通知记录清理任务完成 ==========");
    }
}