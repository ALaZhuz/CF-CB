package org.example.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.config.DingProperties;
import org.example.config.ReviewProperties;
import org.example.model.cb.ReviewItem;
import org.example.model.dto.response.DingMessageResponse;
import org.example.model.dto.response.OrganizationManagerResponse;
import org.example.model.dto.response.ReviewStatisticsResponse;
import org.example.repository.ReviewNotifyRepository;
import org.example.repository.ReviewNotifyRepository.ReviewRecord;
import org.example.service.CBSwaggerService;
import org.example.service.DingService;
import org.example.service.ReviewService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewNotificationServiceImpl {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ReviewService reviewService;
    private final DingService dingService;
    private final CBSwaggerService cbSwaggerService;
    private final ReviewNotifyRepository repository;
    private final ReviewProperties reviewProperties;
    @Value("${dingtalk.overdue-weekly}")
    private int overdueWeekly;
    @Value("${dingtalk.overdue-minister-default}")
    private int overdueMinisterDefault;
    @Value("${dingtalk.overdue-director-default}")
    private int overdueDirectorDefault;
    @Value("${dingtalk.near-expired-default}")
    private int nearExpiredDefault;
    @Value("${dingtalk.overdue-weekly-day}")
    private int overdueWeeklyDay;

    public void runEightOClockTasks() {
        log.info("每日定时任务启动");
        List<ReviewRecord> records = repository.findOpenRecords();
        if (records == null || records.isEmpty()) {
            log.info("没有开启的评审单！");
            return;
        }

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        // 临期记录的分组映射：评审人 -> (临期天数 -> 评审记录列表)
        Map<String, Map<Long, List<ReviewRecord>>> nearExpiredByRecipient = new LinkedHashMap<>();
        // 超期记录的分组映射：评审人 -> (超期天数 -> 评审记录列表)
        Map<String, Map<Long, List<ReviewRecord>>> overdueByRecipient = new LinkedHashMap<>();
        // 判断是周几
        Map<Integer, java.time.DayOfWeek> dayOfWeekMap = Map.of(
                1, java.time.DayOfWeek.MONDAY,
                2, java.time.DayOfWeek.TUESDAY,
                3, java.time.DayOfWeek.WEDNESDAY,
                4, java.time.DayOfWeek.THURSDAY,
                5, java.time.DayOfWeek.FRIDAY,
                6, java.time.DayOfWeek.SATURDAY,
                7, java.time.DayOfWeek.SUNDAY);

        // 8 点任务按“先确认接收人，再按这个人批量发送”的方式组织数据。
        // 同一个人如果命中多条 review，会在同一个事件分组里合并成一条消息。
        for (ReviewRecord record : records) {
            if (record == null || record.deadline == null || record.deadline.isBlank()) {
                continue;
            }

            // 5. 计算逾期天数：正数表示超期，负数表示未到期，overdueDays==0表示dealine当天
            long overdueDays = calcOverdueDays(record.deadline, today);
            // 还未临期，跳过，dealine当日为临期最后一天
            if (overdueDays < 0 && Math.abs(overdueDays) >= nearExpiredDefault) {
                continue;
            }

            // 是否临期：逾期天数 <= 0 且绝对值小于临期阈值
            boolean isNearExpired = overdueDays <= 0 && Math.abs(overdueDays) < nearExpiredDefault;
            // 是否超期：逾期天数 > 0
            boolean isOverdue = overdueDays > 0;
            // 超期后判断是周几，如果是设定的逾期每周提醒日，则满足条件
            boolean weeklyOverdueNotice = isOverdue && overdueDays > overdueWeekly
                    && today.getDayOfWeek() == dayOfWeekMap.getOrDefault(overdueWeeklyDay, java.time.DayOfWeek.MONDAY);

            List<String> recipients = resolveEightClockRecipients(record, overdueDays, weeklyOverdueNotice);
            if (recipients.isEmpty()) {
                continue;
            }

            // 9. 根据记录状态选择目标分组（临期或超期）
            // 临期消息和超期消息分开统计，避免不同语义混在一起。
            Map<String, Map<Long, List<ReviewRecord>>> targetBuckets = isNearExpired ? nearExpiredByRecipient
                    : overdueByRecipient;
            // 使用逾期天数的绝对值作为分桶键（对于临期记录，表示还有多少天截止）
            long bucketDays = Math.abs(overdueDays);
            for (String recipient : recipients) {
                targetBuckets
                        .computeIfAbsent(recipient, k -> new TreeMap<>())
                        .computeIfAbsent(bucketDays, k -> new ArrayList<>())
                        .add(record);
            }
        }

        sendEightClockBuckets(nearExpiredByRecipient, "以下评审单即将截止，请您尽快处理！", "near_expired_last_sent", today, now, false,
                true);
        // 春风组织架构接口不可用
        sendEightClockBuckets(overdueByRecipient, "以下评审单已超期，请您尽快处理！", "overdue_last_sent", today, now, true,
                today.getDayOfWeek() == dayOfWeekMap.getOrDefault(overdueWeeklyDay, java.time.DayOfWeek.MONDAY));

    }

    public void runThirtyMinuteTasks() {
        log.info("查询评审超期轮询任务开启");
        List<ReviewRecord> records = repository.findOpenRecords();
        LocalDate today = LocalDate.now();
        // 超期记录的分组映射：收件人 -> (超期天数 -> 评审记录列表)
        Map<String, Map<Long, List<ReviewRecord>>> overdueByRecipient = new LinkedHashMap<>();

        // 先按收件人分组，再按超期天数分桶，确保“谁负责谁收到”，且消息内容仍然保持分组展示。
        for (ReviewRecord record : records) {
            if (record == null || record.deadline == null || record.deadline.isBlank()) {
                continue;
            }

            long overdueDays = calcOverdueDays(record.deadline, today);

            // 只处理 1~8 天的超期提醒，超出范围的记录交给其他机制处理。
            if (overdueDays <= 0 || overdueDays > overdueWeekly) {
                continue;
            }

            // 如果今天已经提醒过，就跳过，避免 30 分钟轮询时重复刷屏。
            if (alreadySentToday(record.overdueLastSent, today)) {
                continue;
            }

            // 负责人和评审人
            List<String> recipients = new ArrayList<>();
            recipients.addAll(record.moderatorIds);
            recipients.addAll(record.reviewerIds);
            recipients = recipients.stream().filter(s -> s != null && !s.isBlank()).distinct()
                    .collect(Collectors.toList());
            if (recipients.isEmpty()) {
                continue;
            }

            // 9. 将超期评审单添加到每个收件人的对应分桶中
            for (String recipient : recipients) {
                overdueByRecipient
                        .computeIfAbsent(recipient, k -> new TreeMap<>()) // 如果收件人不存在，创建TreeMap
                        .computeIfAbsent(overdueDays, k -> new ArrayList<>()) // 如果天数分桶不存在，创建ArrayList
                        .add(record); // 添加记录到对应分桶
            }
        }

        // 10. 如果没有需要发送的记录，直接返回
        if (overdueByRecipient.isEmpty()) {
            log.info("针对审阅和审查，没有超期的评审！");
            return;
        }

        // 11. 设置消息标题
        String title = "以下评审单已超期，请您尽快处理！";
        // 获取当前时间，用于更新最后发送时间
        LocalDateTime now = LocalDateTime.now();
        // 12. 遍历每个收件人的分组数据，发送消息
        for (Map.Entry<String, Map<Long, List<ReviewRecord>>> entry : overdueByRecipient.entrySet()) {
            String userId = entry.getKey(); // 评审人ID
            Map<Long, List<ReviewRecord>> overdueGroups = entry.getValue(); // 该评审人的超期分组
            // 跳过空分组
            if (overdueGroups.isEmpty()) {
                continue;
            }

            String markdown = buildOverdueMarkdown(overdueGroups, false);
            // 14. 发送钉钉消息
            DingMessageResponse resp = dingService.sendMessage(userId, title, markdown);
            if (resp != null && resp.getErrcode() == 0) {
                log.info("超期轮询钉钉消息已发送, taskId={},userId={}", resp.getTaskId(), userId);
                // 15. 更新每条记录的最后发送时间，用于后续去重
                overdueGroups.values().stream().flatMap(List::stream).distinct()
                        .forEach(r -> repository.updateLastSent(r.reviewId, "overdue_last_sent", now));
            }

        }
    }

    /**
     * 处理评审新建、取消、关闭
     * 同步sqlite，只保存OPEN评审
     * 逻辑：通过对比OPEN评审列表、CANCELED评审列表和数据库OPEN记录，推导出评审状态变化
     * 1. 在OPEN列表中 -> OPEN状态（新增或更新）
     * 2. 在CANCELED列表中 -> CANCELED状态（发送取消通知）
     * 3. 不在任何列表中但在数据库中有记录 -> CLOSED状态（发送关闭通知）
     */
    public void syncLifecycle() {
        log.info("开始同步评审开启/取消/关闭状态！");

        // 1. 获取全量数据
        List<ReviewItem.Review> openReviews = cbSwaggerService.fetchAllOpenReviews();
        List<ReviewItem.Review> canceledReviews = cbSwaggerService.fetchAllCanceledReviews();
        Map<Long, ReviewRecord> dbRecordMap = repository.findOpenRecords().stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        r -> r.reviewId,
                        r -> r,
                        (r1, r2) -> r1));

        log.info("获取数据完成：所有OPEN评审{}条，所有CANCELED评审{}条，数据库OPEN记录{}条",
                openReviews.size(), canceledReviews.size(), dbRecordMap.size());

        // 2. 建立快速查找集合
        Set<Long> openIds = openReviews.stream()
                .filter(Objects::nonNull)
                .map(r -> r.getId() != null ? r.getId().longValue() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Set<Long> canceledIds = canceledReviews.stream()
                .filter(Objects::nonNull)
                .map(r -> r.getId() != null ? r.getId().longValue() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 3. 初始化数据结构
        Map<String, Map<String, List<LifecycleNotify>>> lifecycleBuckets = new LinkedHashMap<>();
        List<Long> toDeleteIds = new ArrayList<>();

        // 4. 处理OPEN评审（新增或更新）
        for (ReviewItem.Review openReview : openReviews) {
            if (openReview == null || openReview.getId() == null) {
                continue;
            }

            long reviewId = openReview.getId().longValue();

            // 创建ReviewItem包装器
            ReviewItem item = new ReviewItem();
            item.setReview(openReview);

            ReviewRecord record = toRecord(item);

            // 检查是否已存在数据库记录
            ReviewRecord existing = dbRecordMap.get(reviewId);

            // 插入或更新数据库
            repository.upsert(record);
            // 更新读取的数据库缓存
            dbRecordMap.put(reviewId, record);
            // 如果是新增记录，则添加新建通知
            if (existing == null) {
                addLifecycleNotify(lifecycleBuckets, "NEW", buildRecipients(record, false),
                        new LifecycleNotify(reviewId, safe(record.reviewName)));
            }
        }

        // 5. 处理数据库记录，推导CANCELED和CLOSED状态
        for (ReviewRecord dbRecord : dbRecordMap.values()) {
            if (dbRecord == null) {
                continue;
            }

            Long reviewId = dbRecord.reviewId;

            if (canceledIds.contains(reviewId)) {
                // CANCELED状态：发送取消通知
                addLifecycleNotify(lifecycleBuckets, "CANCELED", buildRecipients(dbRecord, true),
                        new LifecycleNotify(reviewId, safe(dbRecord.reviewName)));
                toDeleteIds.add(reviewId);
                // log.debug("评审已取消：reviewId={}, name={}", reviewId, safe(dbRecord.reviewName));
            } else if (!openIds.contains(reviewId)) {
                // CLOSED状态：既不在OPEN列表中也不在CANCELED列表中
                addLifecycleNotify(lifecycleBuckets, "CLOSED", buildRecipients(dbRecord, true),
                        new LifecycleNotify(reviewId, safe(dbRecord.reviewName)));
                toDeleteIds.add(reviewId);
                // log.debug("评审已关闭：reviewId={}, name={}", reviewId, safe(dbRecord.reviewName));
            }
        }

        // 6. 发送通知
        sendLifecycleBuckets(lifecycleBuckets, "NEW", "有以下评审单需要您处理！", repository::updateNewNotified);
        sendLifecycleBuckets(lifecycleBuckets, "CANCELED", "以下评审单已取消！", repository::updateCancelNotified);
        sendLifecycleBuckets(lifecycleBuckets, "CLOSED", "以下评审单已关闭！", repository::updateCloseNotified);

        // 7. 批量删除已关闭/已取消的记录
        if (!toDeleteIds.isEmpty()) {
            repository.deleteByIds(toDeleteIds);
            log.info("已删除{}条已关闭/已取消的评审记录", toDeleteIds.size());
        }

        // 8. 记录统计信息
         int newCount = lifecycleBuckets.getOrDefault("NEW", Collections.emptyMap())
         .values().stream().mapToInt(List::size).sum();
         int canceledCount = lifecycleBuckets.getOrDefault("CANCELED",
         Collections.emptyMap())
         .values().stream().mapToInt(List::size).sum();
         int closedCount = lifecycleBuckets.getOrDefault("CLOSED",
         Collections.emptyMap())
         .values().stream().mapToInt(List::size).sum();

         log.info("生命周期同步完成：新增{}个，取消{}个，关闭{}个，总共处理{}条记录",
         newCount, canceledCount, closedCount, newCount + canceledCount +
         closedCount);
    }

    /**
     * 添加生命周期通知到对应的分组
     *
     * <p>
     * 将生命周期通知按事件类型和收件人分组，便于后续批量发送。
     * 同一个收件人的多条通知会被合并到同一个列表中。
     *
     * @param lifecycleBuckets 生命周期通知的分组映射
     * @param eventType        事件类型（NEW/CLOSED/CANCELED）
     * @param recipients       收件人列表
     * @param notify           评审单
     */
    private void addLifecycleNotify(Map<String, Map<String, List<LifecycleNotify>>> lifecycleBuckets,
            String eventType,
            List<String> recipients,
            LifecycleNotify notify) {
        if (recipients == null || recipients.isEmpty() || notify == null) {
            return;
        }
        Map<String, List<LifecycleNotify>> byRecipient = lifecycleBuckets.computeIfAbsent(eventType,
                k -> new LinkedHashMap<>());
        for (String recipient : recipients) {
            byRecipient.computeIfAbsent(recipient, k -> new ArrayList<>()).add(notify);
        }
    }

    /**
     * 构建评审记录的收件人列表
     *
     * @param record           评审记录
     * @param includeSubmitter 是否包含提交人
     * @return 过滤后的收件人列表（去重、非空）
     */
    private List<String> buildRecipients(ReviewRecord record, boolean includeSubmitter) {
        if (record == null) {
            return List.of();
        }
        List<String> recipients = new ArrayList<>();
        recipients.addAll(record.moderatorIds);
        recipients.addAll(record.reviewerIds);
        recipients.addAll(record.viewerIds);
        if (includeSubmitter && record.submitterId != null && !record.submitterId.isBlank()) {
            recipients.add(record.submitterId);
        }
        return recipients.stream().filter(s -> s != null && !s.isBlank()).distinct().collect(Collectors.toList());
    }

    /**
     * 发送生命周期通知的分组消息
     *
     * <p>
     * 处理按事件类型和收件人分组的生命周期通知，为每个收件人生成合并消息并发送。
     * 流程：
     * <ul>
     * <li>获取指定事件类型的分组数据</li>
     * <li>遍历每个收件人对应的通知列表</li>
     * <li>生成Markdown格式的消息内容</li>
     * <li>发送钉钉消息并更新通知状态</li>
     * <li>发送失败时不更新状态，留待下次重试</li>
     * </ul>
     *
     * @param lifecycleBuckets 生命周期通知的分组映射
     * @param eventType        事件类型（NEW/CLOSED/CANCELED）
     * @param title            消息标题
     * @param notifiedUpdater  通知状态更新函数
     */
    private void sendLifecycleBuckets(Map<String, Map<String, List<LifecycleNotify>>> lifecycleBuckets,
            String eventType,
            String title,
            java.util.function.BiConsumer<Long, Boolean> notifiedUpdater) {
        Map<String, List<LifecycleNotify>> byRecipient = lifecycleBuckets.get(eventType);
        if (byRecipient == null || byRecipient.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<LifecycleNotify>> entry : byRecipient.entrySet()) {
            // 评审人员
            String userId = entry.getKey();
            // 评审单列表
            List<LifecycleNotify> items = entry.getValue();

            if (items == null || items.isEmpty()) {
                continue;
            }
            String markdown;
            if ("CLOSED".equals(eventType)) {
                markdown = buildClosedReviewMarkdown(title, items);
            } else {
                markdown = buildLifecycleMarkdown(title, items);
            }
            try {
                // 检查发送结果
                DingMessageResponse resp = dingService.sendMessage(userId, title, markdown);

                if (resp != null && resp.getErrcode() == 0) { // 仅当成功时更新
                    for (LifecycleNotify item : items) {
                        notifiedUpdater.accept(item.reviewId, true);
                    }
                    log.info("评审生命周期通知已发送: taskId={}, userId={}, eventType={}, count={}",
                            resp.getTaskId(), userId, eventType, items.size());
                } 
            } catch (Exception e) {
                log.error("生命周期通知发送异常: userId={}, error={}", userId, e.getMessage());
            }
        }
    }

    /**
     * 将 CodeBeamer 的评审单对象转换成sqlite持久化记录，
     * 用于后续的通知去重、收件人汇总和状态追踪。
     */
    private ReviewRecord toRecord(ReviewItem item) {
        ReviewRecord r = new ReviewRecord();
        // 将远端字段压平到sqlite里，便于后续做幂等判断、收件人汇总和状态追踪。
        r.reviewId = item.getReview().getId().longValue();
        r.reviewName = item.getReview().getName();
        r.deadline = item.getReview().getDeadline();
        r.status = resolveStatus(item);
        r.submitterId = item.getReview().getSubmitter() == null ? "" : item.getReview().getSubmitter().getName();
        r.moderatorIds = ids(item.getReview().getModerators());
        r.reviewerIds = ids(item.getReview().getReviewers());
        r.viewerIds = ids(item.getReview().getViewers());
        r.createdAt = FMT.format(LocalDateTime.now());
        r.updatedAt = r.createdAt;
        return r;
    }

    /**
     * 将评审单状态统一映射为内部枚举值，避免后续逻辑直接依赖外部接口字段。
     */
    private String resolveStatus(ReviewItem item) {
        if (Boolean.TRUE.equals(item.getReview().getClosed()))
            return "CLOSED";
        if (Boolean.TRUE.equals(item.getReview().getCanceled()))
            return "CANCELED";
        return "OPEN";
    }

    /**
     * 计算当前日期距离截止日期已经过去多少天；
     * 返回负数表示截止日期解析失败或尚未到期。
     */
    private long calcOverdueDays(String deadline, LocalDate today) {
        LocalDate deadlineDate = parseDeadline(deadline);
        if (deadlineDate == null) {
            return -1;
        }
        // today-deadline
        return ChronoUnit.DAYS.between(deadlineDate, today);
    }

    private LocalDate parseDeadline(String deadline) {
        try {
            return LocalDate.parse(deadline.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断当天是否已经发送过同类提醒，用于抑制重复通知。
     */
    private boolean alreadySentToday(String sentAt, LocalDate today) {
        if (sentAt == null || sentAt.isBlank()) {
            return false;
        }
        try {
            return LocalDateTime.parse(sentAt, FMT).toLocalDate().isEqual(today);
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> ids(List<ReviewItem.Person> people) {
        if (people == null)
            return new ArrayList<>();
        // 这里只保留人员名称/ID 字段，避免把整个对象结构带入sqlite持久化。
        return people.stream().map(ReviewItem.Person::getName).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private int safeInt(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    /**
     * 由临期/超期天数决定收件人范围
     * 
     * @param record
     * @param overdueDays
     * @return
     */
    private List<String> resolveEightClockRecipients(ReviewRecord record, long overdueDays,
            boolean weeklyOverdueNotice) {
        List<String> recipients = new ArrayList<>();
        if (record == null) {
            return recipients;
        }
        // 临期只通知moderator、reviewer
        if (overdueDays <= 0 && Math.abs(overdueDays) < nearExpiredDefault) {
            recipients.addAll(record.moderatorIds);
            recipients.addAll(record.reviewerIds);
        }
        // 超期超过 weekly：仅周一通知科长+部长+总监+moderator+reviewer
        else if (weeklyOverdueNotice) {
            recipients.addAll(resolveManagerRecipients(record, "sectionManager", "departmentManager", "director"));
            recipients.addAll(record.moderatorIds);
            recipients.addAll(record.reviewerIds);
        }
        // 超期 <= overdue-minister-default,每天通知科长
        else if (overdueDays <= overdueMinisterDefault) {
            recipients.addAll(resolveManagerRecipients(record, "sectionManager"));
        }
        // 超期 <= overdue-director-default,每天通知科长+部长
        else if (overdueDays <= overdueDirectorDefault) {
            recipients.addAll(resolveManagerRecipients(record, "sectionManager", "departmentManager"));
        }
        // 超期 <= overdue-weekly,每天通知科长+部长+总监
        else {
            recipients.addAll(resolveManagerRecipients(record, "sectionManager", "departmentManager", "director"));
        }
        return recipients.stream().filter(s -> s != null && !s.isBlank()).distinct().collect(Collectors.toList());
    }

    /**
     *
     * @param record
     * @param roles
     *               sectionManager:科长
     *               departmentManager:部长
     *               director:总监
     * @return
     */
    private List<String> resolveManagerRecipients(ReviewRecord record, String... roles) {
        if (record == null) {
            return List.of();
        }
        // 先合并 moderator + reviewer，再去重，避免同一个人因为重复角色被重复查领导。
        Set<String> reviewUserIds = new LinkedHashSet<>();
        reviewUserIds.addAll(record.moderatorIds);
        reviewUserIds.addAll(record.reviewerIds);
        reviewUserIds = reviewUserIds.stream().filter(s -> s != null && !s.isBlank()).collect(Collectors.toSet());
        if (reviewUserIds.isEmpty()) {
            return List.of();
        }

        Set<String> requestedRoles = new LinkedHashSet<>();
        if (roles != null) {
            for (String role : roles) {
                if (role != null && !role.isBlank()) {
                    requestedRoles.add(role);
                }
            }
        }
        if (requestedRoles.isEmpty()) {
            return List.of();
        }

        // 每个 reviewer 只查一次组织架构，然后按需要的 role 集合提取并去重。
        Set<String> leaderIds = new LinkedHashSet<>();
        for (String userId : reviewUserIds) {
            OrganizationManagerResponse manager = reviewService.queryOrganizationManager(userId);
            if (manager == null) {
                continue;
            }
            addLeadersByRole(leaderIds, manager, requestedRoles);
        }
        return new ArrayList<>(leaderIds);
    }

    private void addLeadersByRole(Set<String> leaderIds, OrganizationManagerResponse manager, Set<String> roles) {
        for (String role : roles) {
            List<String> roleLeaders = switch (role) {
                case "sectionManager" -> manager.getSectionManager();
                case "departmentManager" -> manager.getDepartmentManager();
                case "director" -> manager.getDirector();
                default -> List.of();
            };
            if (roleLeaders == null || roleLeaders.isEmpty()) {
                continue;
            }
            for (String leaderId : roleLeaders) {
                if (leaderId != null && !leaderId.isBlank()) {
                    leaderIds.add(leaderId);
                }
            }
        }
    }

    /**
     * 发送8点任务的提醒消息
     *
     * <p>
     * 处理按收件人和逾期天数分组的评审记录，为每个收件人生成合并消息并发送。
     * 流程：
     * <ul>
     * <li>检查每个收件人的分组数据</li>
     * <li>提取该分组对应的最后发送时间标记</li>
     * <li>如果当天已发送过同类提醒，跳过该收件人</li>
     * <li>生成Markdown格式的消息内容</li>
     * <li>发送钉钉消息并更新最后发送时间</li>
     * <li>记录发送失败的错误日志</li>
     * </ul>
     *
     * @param buckets   按收件人和逾期天数分组的评审记录
     * @param title     消息标题
     * @param sentField 用于记录最后发送时间的字段名
     * @param today     当前日期，用于去重判断
     * @param now       当前时间，用于更新最后发送时间
     */
    private void sendEightClockBuckets(Map<String, Map<Long, List<ReviewRecord>>> buckets,
            String title,
            String sentField,
            LocalDate today,
            LocalDateTime now,
            boolean overdueMode,
            boolean redLabelMode) {
        if (buckets == null || buckets.isEmpty()) {
            log.info("每日定时任务没有需要发送的{}评审单！", overdueMode ? "超期" : "临期");
            return;
        }
        for (Map.Entry<String, Map<Long, List<ReviewRecord>>> entry : buckets.entrySet()) {
            String userId = entry.getKey();
            Map<Long, List<ReviewRecord>> grouped = entry.getValue();
            if (grouped == null || grouped.isEmpty()) {
                continue;
            }

            // 诊断日志：记录分组详情
            int totalRecords = grouped.values().stream().mapToInt(List::size).sum();
            int sentTodayCount = 0;
            for (List<ReviewRecord> records : grouped.values()) {
                for (ReviewRecord record : records) {
                    String sentTime = getSentTime(record, sentField);
                    if (sentTime != null && !sentTime.isBlank() && alreadySentToday(sentTime, today)) {
                        sentTodayCount++;
                    }
                }
            }
            log.info("分组详情：收件人={}, 总记录数={}, 今天已发送数={}, 未发送数={}",
                    userId, totalRecords, sentTodayCount, totalRecords - sentTodayCount);

            // 方案A：过滤出今天未发送的记录，重新分组
            Map<Long, List<ReviewRecord>> filteredGrouped = new TreeMap<>();
            for (Map.Entry<Long, List<ReviewRecord>> dayGroup : grouped.entrySet()) {
                Long days = dayGroup.getKey();
                List<ReviewRecord> originalRecords = dayGroup.getValue();

                List<ReviewRecord> filteredRecords = new ArrayList<>();
                for (ReviewRecord record : originalRecords) {
                    String sentTime = getSentTime(record, sentField);
                    if (sentTime == null || sentTime.isBlank() || !alreadySentToday(sentTime, today)) {
                        filteredRecords.add(record);
                    }
                }

                if (!filteredRecords.isEmpty()) {
                    filteredGrouped.put(days, filteredRecords);
                }
            }

            if (filteredGrouped.isEmpty()) {
                log.info("收件人{}的所有评审今天都已发送，跳过", userId);
                continue;
            }

            // 构建并发送消息
            String markdown = overdueMode ? buildOverdueMarkdown(filteredGrouped, redLabelMode)
                    : buildNearExpiredMarkdown(filteredGrouped, redLabelMode);
            try {
                DingMessageResponse resp = dingService.sendMessage(userId, title, markdown);
                if (resp != null && resp.getErrcode() == 0) {
                    List<Long> reviewIds = filteredGrouped.values().stream()
                            .flatMap(List::stream)
                            .map(r -> r.reviewId)
                            .distinct()
                            .collect(Collectors.toList());
                    log.info("每日{}定时任务钉钉消息已发送, taskId={}, userId={}, reviewIds={}", overdueMode ? "超期" : "临期", resp.getTaskId(),userId, reviewIds);

                    // 只更新过滤后记录的发送时间
                    for (List<ReviewRecord> group : filteredGrouped.values()) {
                        for (ReviewRecord r : group) {
                            repository.updateLastSent(r.reviewId, sentField, now);
                        }
                    }
                }

            } catch (Exception e) {
                log.error("每日定时任务-钉钉通知发送失败. userId={}, title={}, reviewCount={}", userId, title,
                        filteredGrouped.values().stream().mapToInt(List::size).sum(), e);
            }
        }
    }

    /**
     * 根据字段名获取记录的发送时间
     */
    private String getSentTime(ReviewRecord record, String sentField) {
        if (record == null) {
            return null;
        }
        if ("near_expired_last_sent".equals(sentField)) {
            return record.nearExpiredLastSent;
        }
        if ("overdue_manager_last_sent".equals(sentField)) {
            return record.overdueManagerLastSent;
        }
        return record.overdueLastSent;
    }

    /**
     * 生成超期提醒的 markdown 内容，按超期天数分组展示。
     */
    private String buildOverdueMarkdown(Map<Long, List<ReviewRecord>> overdueGroups, boolean redLabelMode) {
        StringBuilder sb = new StringBuilder("以下评审单已超期，请您尽快处理！\n");
        for (Map.Entry<Long, List<ReviewRecord>> entry : overdueGroups.entrySet()) {
            Long days = entry.getKey();
            String label = "超期 " + days + " 天";
            if (redLabelMode) {
                label = "<font color=\"red\">" + label + "</font>";
            }
            sb.append("\n### ").append(label).append("\n");
            for (ReviewRecord record : entry.getValue()) {
                sb.append("- ").append(buildReviewLine(record.reviewName, buildReviewUrl(record.reviewId)))
                        .append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 生成评审新建、关闭、取消的 markdown 内容。
     */
    private String buildLifecycleMarkdown(String title, List<LifecycleNotify> items) {
        StringBuilder sb = new StringBuilder(title).append("\n");
        for (LifecycleNotify item : items) {
            sb.append("- ").append(buildReviewLine(item.reviewName, buildReviewUrl(item.reviewId))).append("\n");
        }
        return sb.toString();
    }

    /**
     * 构建已关闭评审单的详细 markdown 内容，包含统计信息
     */
    private String buildClosedReviewMarkdown(String title, List<LifecycleNotify> items) {
        StringBuilder sb = new StringBuilder(title).append("\n");
        for (LifecycleNotify item : items) {
            long reviewId = item.reviewId;
            sb.append("- ").append(buildReviewLine(item.reviewName, buildReviewUrl(reviewId))).append("\n");

            try {
                ReviewStatisticsResponse stats = cbSwaggerService.getReviewStatistics(String.valueOf(reviewId));
                if (stats != null && stats.getReview() != null) {
                    if (stats.getReview().getProjectNames() != null && !stats.getReview().getProjectNames().isEmpty()) {
                        sb.append("  - ").append("项目：").append(String.join("、", stats.getReview().getProjectNames()))
                                .append("\n");
                    }
                    if (stats.getReview().getTrackerNames() != null && !stats.getReview().getTrackerNames().isEmpty()) {
                        sb.append("  - ").append("跟踪器：").append(String.join("、", stats.getReview().getTrackerNames()))
                                .append("\n");
                    }
                }

                if (stats != null && stats.getStatsPerUser() != null && !stats.getStatsPerUser().isEmpty()) {
                    sb.append("  - ").append("评审统计信息:").append("\n");
                    for (ReviewStatisticsResponse.StatsPerUser statPerUser : stats.getStatsPerUser()) {
                        String userName = statPerUser.getLoggedInUser() != null
                                ? statPerUser.getLoggedInUser().getUserName()
                                : null;
                        String dingName = userName != null ? dingService.getUserInfo(userName) : null;
                        String displayName = dingName != null ? dingName : (userName != null ? userName : "未知");

                        ReviewStatisticsResponse.ReviewStat reviewStat = statPerUser.getReviewStat();
                        if (reviewStat != null) {
                            sb.append("    - ").append(displayName)
                                    .append("：评审项").append(reviewStat.getTotal())
                                    .append("，已批准").append(reviewStat.getAccepted())
                                    .append("，已拒绝").append(reviewStat.getRejected())
                                    .append("，未审阅").append(reviewStat.getNotReviewed())
                                    .append("\n");
                        }
                    }
                } else {
                    sb.append("  - ").append("评审统计信息：暂无数据").append("\n");
                }
            } catch (Exception e) {
                log.error("获取评审统计信息失败，reviewId={}", item.reviewId, e);
                sb.append("  - ").append("统计信息获取失败").append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 生成临期提醒的 markdown 内容，按“还有 X 天截止/今天截止”分组展示。
     */
    private String buildNearExpiredMarkdown(Map<Long, List<ReviewRecord>> nearExpiredGroups, boolean redTodayMode) {
        StringBuilder sb = new StringBuilder("以下评审单即将截止，请您尽快处理！\n");
        for (Map.Entry<Long, List<ReviewRecord>> entry : nearExpiredGroups.entrySet()) {
            Long days = entry.getKey();
            String label = days == null || days == 0 ? "今天截止" : "还有" + days + "天截止";
            if (redTodayMode && (days == null || days == 0)) {
                label = "<font color=\"red\">" + label + "</font>";
            }
            sb.append("\n### ").append(label).append("\n");
            for (ReviewRecord record : entry.getValue()) {
                sb.append("- ").append(buildReviewLine(record.reviewName, buildReviewUrl(record.reviewId)))
                        .append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 统一生成评审单的 markdown 链接格式，避免各处拼接不一致。
     */
    private String buildReviewLine(String reviewName, String reviewUrl) {
        return "[" + safe(reviewName) + "](" + reviewUrl + ")";
    }

    /**
     * 拼出评审单详情页链接，供钉钉消息中的跳转使用。
     */
    private String buildReviewUrl(long reviewId) {
        // 统一通过配置拼接详情页地址，避免消息文案里出现硬编码链接。
        return reviewProperties.getLinkPrefix() + reviewId;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private static class LifecycleNotify {
        final long reviewId;
        final String reviewName;

        LifecycleNotify(long reviewId, String reviewName) {
            this.reviewId = reviewId;
            this.reviewName = reviewName;
        }
    }
}
