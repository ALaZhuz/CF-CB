package org.example.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.config.DingProperties;
import org.example.config.ReviewProperties;
import org.example.model.cb.ReviewItem;
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

    public void runEightOClockTasks() {
        List<ReviewRecord> records = repository.findOpenRecords();
        if (records == null || records.isEmpty()) {
            return;
        }

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        // 临期记录的分组映射：评审人 -> (临期天数 -> 评审记录列表)
        Map<String, Map<Long, List<ReviewRecord>>> nearExpiredByRecipient = new LinkedHashMap<>();
        // 超期记录的分组映射：评审人 -> (超期天数 -> 评审记录列表)
        Map<String, Map<Long, List<ReviewRecord>>> overdueByRecipient = new LinkedHashMap<>();

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
            boolean weeklyOverdueNotice = isOverdue && overdueDays > overdueWeekly && today.getDayOfWeek() == java.time.DayOfWeek.MONDAY;

            List<String> recipients = resolveEightClockRecipients(record, overdueDays, weeklyOverdueNotice);
            if (recipients.isEmpty()) {
                continue;
            }

            // 9. 根据记录状态选择目标分组（临期或超期）
            // 临期消息和超期消息分开统计，避免不同语义混在一起。
            Map<String, Map<Long, List<ReviewRecord>>> targetBuckets = isNearExpired ? nearExpiredByRecipient : overdueByRecipient;
            // 使用逾期天数的绝对值作为分桶键（对于临期记录，表示还有多少天截止）
            long bucketDays = Math.abs(overdueDays);
            for (String recipient : recipients) {
                targetBuckets
                        .computeIfAbsent(recipient, k -> new TreeMap<>())
                        .computeIfAbsent(bucketDays, k -> new ArrayList<>())
                        .add(record);
            }
        }

        sendEightClockBuckets(nearExpiredByRecipient, "以下评审单即将截止，请您尽快处理！", "near_expired_last_sent", today, now, false, false);
        // 春风组织架构接口不可用
//        sendEightClockBuckets(overdueByRecipient, "以下评审单已超期，请您尽快处理！", "overdue_last_sent", today, now, true, today.getDayOfWeek() == java.time.DayOfWeek.MONDAY);

    }

    public void runThirtyMinuteTasks() {
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
            recipients = recipients.stream().filter(s -> s != null && !s.isBlank()).distinct().collect(Collectors.toList());
            if (recipients.isEmpty()) {
                continue;
            }

            // 9. 将超期评审单添加到每个收件人的对应分桶中
            for (String recipient : recipients) {
                overdueByRecipient
                        .computeIfAbsent(recipient, k -> new TreeMap<>())      // 如果收件人不存在，创建TreeMap
                        .computeIfAbsent(overdueDays, k -> new ArrayList<>())  // 如果天数分桶不存在，创建ArrayList
                        .add(record);  // 添加记录到对应分桶
            }
        }

        // 10. 如果没有需要发送的记录，直接返回
        if (overdueByRecipient.isEmpty()) {
            return;
        }

        // 11. 设置消息标题
        String title = "以下评审单已超期，请您尽快处理！";
        // 获取当前时间，用于更新最后发送时间
        LocalDateTime now = LocalDateTime.now();
        // 12. 遍历每个收件人的分组数据，发送消息
        for (Map.Entry<String, Map<Long, List<ReviewRecord>>> entry : overdueByRecipient.entrySet()) {
            String userId = entry.getKey();  // 评审人ID
            Map<Long, List<ReviewRecord>> overdueGroups = entry.getValue();  // 该评审人的超期分组
            // 跳过空分组
            if (overdueGroups.isEmpty()) {
                continue;
            }

           String markdown = buildOverdueMarkdown(overdueGroups, false);
            // 14. 发送钉钉消息
            dingService.sendMessage(userId, title, markdown);

            // 15. 更新每条记录的最后发送时间，用于后续去重
            overdueGroups.values().stream().flatMap(List::stream).distinct()
                    .forEach(r -> repository.updateLastSent(r.reviewId, "overdue_last_sent", now));
        }
    }

    /**
     * 处理评审新建、取消、关闭
     * 同步sqlite，只保存OPEN评审
     */
    public void syncLifecycle() {
        // 1. 获取所有评审项
        List<ReviewItem> reviews = cbSwaggerService.fetchAllReviews();
        // 2. 评审状态分组映射：状态 -> (收件人 -> 通知列表)
        Map<String, Map<String, List<LifecycleNotify>>> lifecycleBuckets = new LinkedHashMap<>();
        // 3. sqlite OPEN评审映射：评审ID -> 评审信息
        Map<Long, ReviewRecord> openRecordMap = repository.findOpenRecords().stream()
                .filter(Objects::nonNull)  // 过滤空记录
                .collect(Collectors.toMap(r -> r.reviewId, r -> r, (a, b) -> a, HashMap::new));

        // 4. 初始化需要删除的ID列表，后续批量删除
        List<Long> closeIds = new ArrayList<>();   
        List<Long> cancelIds = new ArrayList<>();  

        for (ReviewItem item : reviews) {
            // 跳过无效项：项为空、评审为空或评审ID为空
            if (item == null || item.getReview() == null || item.getReview().getId() == null) {
                continue;
            }

            // 6. 获取评审ID并转换为sqlite格式
            long reviewId = item.getReview().getId().longValue();
            ReviewRecord record = toRecord(item);  
            // 检查sqlite是否已存在该评审的OPEN记录
            ReviewRecord existing = openRecordMap.get(reviewId);

            // 7. 处理OPEN状态的评审
            if ("OPEN".equalsIgnoreCase(record.status)) {
                // 插入或更新sqlite
                repository.upsert(record);
                // 更新sqlite映射
                openRecordMap.put(reviewId, record);
                // 如果是新增记录（sqlite不存在），则添加新建通知
                if (existing == null) {
                    addLifecycleNotify(lifecycleBuckets, "NEW", buildRecipients(record, false), new LifecycleNotify(reviewId, safe(record.reviewName)));
                }
                continue;  // 继续处理下一个评审项
            }

            // 8. 如果sqlite不存在对应的非OPEN记录，跳过（说明已经处理过或从未纳入sqlite缓存）
            if (existing == null) {
                continue;
            }

            // 9. 处理CLOSED状态的评审
            if ("CLOSED".equalsIgnoreCase(record.status)) {
                // 添加关闭通知（收件人包含提交人）
                addLifecycleNotify(lifecycleBuckets, "CLOSED", buildRecipients(existing, true), new LifecycleNotify(reviewId, safe(existing.reviewName)));
                closeIds.add(reviewId);  // 添加到关闭ID列表
            }
            // 10. 处理CANCELED状态的评审
            else if ("CANCELED".equalsIgnoreCase(record.status)) {
                // 添加取消通知（收件人包含提交人）
                addLifecycleNotify(lifecycleBuckets, "CANCELED", buildRecipients(existing, true), new LifecycleNotify(reviewId, safe(existing.reviewName)));
                cancelIds.add(reviewId);  // 添加到取消ID列表
            }
        }

        // 11. 发送不同类型生命周期通知
        // 发送新建通知
        sendLifecycleBuckets(lifecycleBuckets, "NEW", "有以下评审单需要您处理！", repository::updateNewNotified);
        // 发送关闭通知
        sendLifecycleBuckets(lifecycleBuckets, "CLOSED", "以下评审单已关闭！", repository::updateCloseNotified);
        // 发送取消通知
        sendLifecycleBuckets(lifecycleBuckets, "CANCELED", "以下评审单已取消！", repository::updateCancelNotified);

        // 12. 从sqlite数据库删除已关闭和已取消的记录
        repository.deleteByIds(closeIds);
        repository.deleteByIds(cancelIds);
    }

    /**
     * 添加生命周期通知到对应的分组
     *
     * <p>将生命周期通知按事件类型和收件人分组，便于后续批量发送。
     * 同一个收件人的多条通知会被合并到同一个列表中。
     *
     * @param lifecycleBuckets 生命周期通知的分组映射
     * @param eventType 事件类型（NEW/CLOSED/CANCELED）
     * @param recipients 收件人列表
     * @param notify 评审单
     */
    private void addLifecycleNotify(Map<String, Map<String, List<LifecycleNotify>>> lifecycleBuckets,
                                    String eventType,
                                    List<String> recipients,
                                    LifecycleNotify notify) {
        if (recipients == null || recipients.isEmpty() || notify == null) {
            return;
        }
        Map<String, List<LifecycleNotify>> byRecipient = lifecycleBuckets.computeIfAbsent(eventType, k -> new LinkedHashMap<>());
        for (String recipient : recipients) {
            byRecipient.computeIfAbsent(recipient, k -> new ArrayList<>()).add(notify);
        }
    }

    /**
     * 构建评审记录的收件人列表
     *
     * <p>从评审记录中提取相关人员ID，包括：
     * <ul>
     *   <li>负责人（moderatorIds）</li>
     *   <li>评审人（reviewerIds）</li>
     *   <li>观察者（viewerIds）</li>
     *   <li>如果需要包含提交人，则额外添加提交人ID</li>
     * </ul>
     *
     * @param record 评审记录
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
     * <p>处理按事件类型和收件人分组的生命周期通知，为每个收件人生成合并消息并发送。
     * 流程：
     * <ul>
     *   <li>获取指定事件类型的分组数据</li>
     *   <li>遍历每个收件人对应的通知列表</li>
     *   <li>生成Markdown格式的消息内容</li>
     *   <li>发送钉钉消息并更新通知状态</li>
     *   <li>发送失败时不更新状态，留待下次重试</li>
     * </ul>
     *
     * @param lifecycleBuckets 生命周期通知的分组映射
     * @param eventType 事件类型（NEW/CLOSED/CANCELED）
     * @param title 消息标题
     * @param notifiedUpdater 通知状态更新函数
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
                dingService.sendMessage(userId, title, markdown);
                for (LifecycleNotify item : items) {
                    notifiedUpdater.accept(item.reviewId, true);
                }
            } catch (Exception ignored) {
                // 发送失败不更新发送状态，留待下次重试
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
        if (Boolean.TRUE.equals(item.getReview().getClosed())) return "CLOSED";
        if (Boolean.TRUE.equals(item.getReview().getCanceled())) return "CANCELED";
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
        //today-deadline
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
        if (people == null) return new ArrayList<>();
        // 这里只保留人员名称/ID 字段，避免把整个对象结构带入sqlite持久化。
        return people.stream().map(ReviewItem.Person::getName).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private int safeInt(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    /**
     * 由临期/超期天数决定收件人范围
     * @param record
     * @param overdueDays
     * @return
     */
    private List<String> resolveEightClockRecipients(ReviewRecord record, long overdueDays, boolean weeklyOverdueNotice) {
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
     * sectionManager:科长
     * departmentManager:部长
     * director:总监
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
     * <p>处理按收件人和逾期天数分组的评审记录，为每个收件人生成合并消息并发送。
     * 流程：
     * <ul>
     *   <li>检查每个收件人的分组数据</li>
     *   <li>提取该分组对应的最后发送时间标记</li>
     *   <li>如果当天已发送过同类提醒，跳过该收件人</li>
     *   <li>生成Markdown格式的消息内容</li>
     *   <li>发送钉钉消息并更新最后发送时间</li>
     *   <li>记录发送失败的错误日志</li>
     * </ul>
     *
     * @param buckets 按收件人和逾期天数分组的评审记录
     * @param title 消息标题
     * @param sentField 用于记录最后发送时间的字段名
     * @param today 当前日期，用于去重判断
     * @param now 当前时间，用于更新最后发送时间
     */
    private void sendEightClockBuckets(Map<String, Map<Long, List<ReviewRecord>>> buckets,
                                       String title,
                                       String sentField,
                                       LocalDate today,
                                       LocalDateTime now,
                                       boolean overdueMode,
                                       boolean redLabelMode) {
        if (buckets == null || buckets.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Map<Long, List<ReviewRecord>>> entry : buckets.entrySet()) {
            String userId = entry.getKey();
            Map<Long, List<ReviewRecord>> grouped = entry.getValue();
            if (grouped == null || grouped.isEmpty()) {
                continue;
            }

            String sentMarker = extractSentMarker(grouped, sentField);
            if (alreadySentToday(sentMarker, today)) {
                continue;
            }

            String markdown = overdueMode ? buildOverdueMarkdown(grouped, redLabelMode) : buildNearExpiredMarkdown(grouped, redLabelMode);
            try {
                dingService.sendMessage(userId, title, markdown);
                for (List<ReviewRecord> group : grouped.values()) {
                    for (ReviewRecord r : group) {
                        repository.updateLastSent(r.reviewId, sentField, now);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to send 8am notification. userId={}, title={}, reviewCount={}", userId, title, grouped.values().stream().mapToInt(List::size).sum(), e);
            }
        }
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
                sb.append("- ").append(buildReviewLine(record.reviewName, buildReviewUrl(record.reviewId))).append("\n");
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
                        sb.append("  - ").append("项目：").append(String.join("、", stats.getReview().getProjectNames())).append("\n");
                    }
                    if (stats.getReview().getTrackerNames() != null && !stats.getReview().getTrackerNames().isEmpty()) {
                        sb.append("  - ").append("跟踪器：").append(String.join("、", stats.getReview().getTrackerNames())).append("\n");
                    }
                }

                if (stats != null && stats.getStatsPerUser() != null && !stats.getStatsPerUser().isEmpty()) {
                    sb.append("  - ").append("评审统计信息:").append("\n");
                    for (ReviewStatisticsResponse.StatsPerUser statPerUser : stats.getStatsPerUser()) {
                        String userName = statPerUser.getLoggedInUser() != null ? statPerUser.getLoggedInUser().getUserName() : null;
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
                    sb.append("\n评审统计信息：暂无数据\n");
                }
            } catch (Exception e) {
                log.error("获取评审统计信息失败，reviewId={}", item.reviewId, e);
                sb.append("\n（统计信息获取失败）\n");
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
                sb.append("- ").append(buildReviewLine(record.reviewName, buildReviewUrl(record.reviewId))).append("\n");
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

    /**
     * 提取分组数据的最后发送时间标记
     *
     * <p>从按逾期天数分组的评审记录中提取第一条记录的指定发送时间字段值。
     * 用于判断该分组是否已经在当天发送过提醒。
     *
     * @param grouped 按逾期天数分组的评审记录
     * @param sentField 发送时间字段名
     * @return 最后发送时间字符串，如果分组为空或字段不存在则返回null
     */
    private String extractSentMarker(Map<Long, List<ReviewRecord>> grouped, String sentField) {
        if (grouped == null || grouped.isEmpty()) {
            return null;
        }
        for (List<ReviewRecord> records : grouped.values()) {
            if (records == null || records.isEmpty()) {
                continue;
            }
            ReviewRecord first = records.get(0);
            if (first == null) {
                continue;
            }
            if ("near_expired_last_sent".equals(sentField)) {
                return first.nearExpiredLastSent;
            }
            if ("overdue_manager_last_sent".equals(sentField)) {
                return first.overdueManagerLastSent;
            }
            return first.overdueLastSent;
        }
        return null;
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
