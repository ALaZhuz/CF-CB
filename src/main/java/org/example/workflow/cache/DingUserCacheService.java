package org.example.workflow.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.model.dto.response.ItemInfoResponse;
import org.example.service.CBSwaggerService;
import org.example.service.DingService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 钉钉用户缓存服务类
 *
 * 用于缓存钉钉userid的有效性，支持beforeEvent校验时快速判断用户是否存在。
 * 实现启动时全量验证、定期刷新、缓存未命中实时查询的机制。
 *
 * @author system
 * @since 1.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DingUserCacheService {

    private final CBSwaggerService cbSwaggerService;
    private final DingService dingService;

    /**
     * 有效userid缓存集合
     *
     * 使用ConcurrentHashMap保证并发安全。
     * Key为userid字符串，Value为true表示有效。
     */
    private final Set<String> validUserIds = ConcurrentHashMap.newKeySet();

    /**
     * 启动时初始化userid缓存
     *
     * 从Codebeamer获取所有用户，逐一验证钉钉存在性。
     */
    @PostConstruct
    public void initCache() {
        log.info("开始初始化钉钉userid缓存...");
        refreshCache();
        log.info("钉钉userid缓存初始化完成, 有效用户数: {}", validUserIds.size());
    }

    /**
     * 定期刷新userid缓存
     *
     * 每小时执行一次，重新验证所有用户的钉钉存在性。
     */
    @Scheduled(fixedRate = 3600000) // 1小时 = 3600000毫秒
    public void scheduledRefresh() {
        log.info("开始定期刷新钉钉userid缓存...");
        refreshCache();
        log.info("钉钉userid缓存刷新完成, 有效用户数: {}", validUserIds.size());
    }

    /**
     * 刷新缓存核心逻辑
     *
     * 获取Codebeamer所有用户并验证钉钉存在性。
     * 验证完成后统一输出日志：不存在的用户WARN日志，存在的用户INFO日志。
     */
    private void refreshCache() {
        try {
            // 获取Codebeamer所有用户
            List<ItemInfoResponse.MemberInfo> users = cbSwaggerService.getAllUsers();

            if (users == null || users.isEmpty()) {
                log.warn("Codebeamer用户列表为空，无法初始化userid缓存");
                return;
            }

            // 清空旧缓存
            validUserIds.clear();

            // 收集验证结果
            List<String> invalidUserIds = new ArrayList<>();
            List<String> validUserInfoList = new ArrayList<>();

            // 逐一验证用户
            for (ItemInfoResponse.MemberInfo user : users) {
                String userid = user.getUserId();
                if (userid == null || userid.isEmpty()) {
                    continue;
                }

                // 验证用户在钉钉中是否存在
                boolean exists = dingService.checkUserExists(userid);
                if (exists) {
                    validUserIds.add(userid);
                    // 格式：codebeamer userid, display name, dingTalk userid
                    String displayName = user.getDisplayName() != null ? user.getDisplayName() : user.getName();
                    validUserInfoList.add(String.format("codebeamerUserId=%s, displayName=%s, dingTalkUserId=%s",
                            userid, displayName, userid));
                } else {
                    invalidUserIds.add(userid);
                }
            }

            // 统一输出日志
            if (!invalidUserIds.isEmpty()) {
                log.warn("钉钉用户不存在的codebeamer userid列表: {}", invalidUserIds);
            }
            if (!validUserInfoList.isEmpty()) {
                log.info("钉钉用户存在的用户列表: {}", validUserInfoList);
            }
        } catch (Exception e) {
            log.error("刷新userid缓存失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 判断userid是否有效
     *
     * 先检查缓存，缓存未命中则实时查询钉钉API。
     *
     * @param userid 用户钉钉userid
     * @return true表示用户存在且有效
     */
    public boolean isValidUserId(String userid) {
        if (userid == null || userid.isEmpty()) {
            return false;
        }

        // 缓存命中
        if (validUserIds.contains(userid)) {
            return true;
        }

        // 缓存未命中，实时查询钉钉API
        log.debug("缓存未命中，实时查询钉钉API: userid={}", userid);
        boolean exists = dingService.checkUserExists(userid);

        if (exists) {
            // 查询成功，加入缓存
            validUserIds.add(userid);
            log.debug("实时查询成功，已加入缓存: userid={}", userid);
        }

        return exists;
    }

    /**
     * 批量校验userid有效性
     *
     * 用于beforeEvent校验，检查所有通知成员的userid。
     *
     * @param userids userid列表
     * @return 无效的userid列表，全部有效返回空集合
     */
    public Set<String> findInvalidUserIds(List<String> userids) {
        Set<String> invalidIds = new HashSet<>();

        if (userids == null || userids.isEmpty()) {
            return invalidIds;
        }

        for (String userid : userids) {
            if (!isValidUserId(userid)) {
                invalidIds.add(userid);
            }
        }

        return invalidIds;
    }

    /**
     * 获取当前缓存大小
     *
     * @return 缓存中的有效userid数量
     */
    public int getCacheSize() {
        return validUserIds.size();
    }

    /**
     * 手动刷新缓存
     *
     * 提供手动触发刷新的接口，用于运维场景。
     */
    public void manualRefresh() {
        log.info("手动触发刷新钉钉userid缓存...");
        refreshCache();
    }
}