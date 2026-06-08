package org.example.workflow.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.db.entity.OrgCache;
import org.example.db.mapper.OrgCacheMapper;
import org.example.model.dto.response.ItemInfoResponse;
import org.example.model.dto.response.OrganizationManagerResponse;
import org.example.service.CBSwaggerService;
import org.example.service.ReviewService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 组织架构缓存服务类
 *
 * 用于缓存员工与科长/部长的关系，减少钉钉API调用频率。
 * 实现启动时全量同步、定时刷新、缓存未命中实时查询补缓存的机制。
 *
 * @author system
 * @since 1.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OrgCacheService {

    private final CBSwaggerService cbSwaggerService;
    private final ReviewService reviewService;
    private final OrgCacheMapper orgCacheMapper;

    /**
     * 启动时初始化组织架构缓存
     *
     * 从Codebeamer获取所有用户，逐一调用钉钉API获取科长/部长关系。
     */
    @PostConstruct
    public void initCache() {
        log.info("开始初始化组织架构缓存...");
        syncOrgCache();
        log.info("组织架构缓存初始化完成, 缓存记录数: {}", orgCacheMapper.count());
    }

    /**
     * 定期刷新组织架构缓存
     *
     * 每小时执行一次，重新同步所有用户的科长/部长关系。
     */
    @Scheduled(fixedRate = 3600000, initialDelay = 3600000) // 启动后1小时才首次执行，避免与@PostConstruct重复
    public void scheduledRefresh() {
        log.info("开始定期刷新组织架构缓存...");
        syncOrgCache();
        log.info("组织架构缓存刷新完成, 缓存记录数: {}", orgCacheMapper.count());
    }

    /**
     * 同步组织架构缓存核心逻辑
     *
     * 获取Codebeamer所有用户并并发调用钉钉API获取科长/部长关系。
     * 使用线程池加速处理，避免串行执行耗时过长。
     */
    private void syncOrgCache() {
        try {
            // 获取Codebeamer所有用户
            List<ItemInfoResponse.MemberInfo> users = cbSwaggerService.getAllUsers();

            if (users == null || users.isEmpty()) {
                log.warn("Codebeamer用户列表为空，无法初始化组织架构缓存");
                return;
            }

            log.info("开始同步组织架构缓存, 用户总数={}", users.size());

            // === 步骤1: 测试网络连通性 ===
            String accessToken = reviewService.getAccessToken();
            if (accessToken == null || accessToken.isEmpty()) {
                log.error("组织架构接口网络不通，无法同步组织架构缓存，请检查钉钉接口配置或网络连接");
                return;
            }

            log.info("组织架构接口网络通畅，开始并发同步...");

            // 清空旧缓存
            orgCacheMapper.deleteAll();

            // === 步骤2: 并发同步所有用户 ===
            int threadPoolSize = 50;
            ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);

            // 使用线程安全的集合收集结果
            List<String> usersWithManager = new CopyOnWriteArrayList<>();  // 有科长或部长的用户
            List<String> usersWithoutManager = new CopyOnWriteArrayList<>();  // 无科长也无部长的用户
            List<String> failedUsers = new CopyOnWriteArrayList<>();  // 同步失败的用户

            // 提交所有任务
            List<Future<?>> futures = new ArrayList<>();
            for (ItemInfoResponse.MemberInfo user : users) {
                String userid = user.getUserId();
                String displayName = user.getDisplayName() != null ? user.getDisplayName() : user.getName();
                if (userid == null || userid.isEmpty()) {
                    continue;
                }

                Future<?> future = executor.submit(() -> {
                    try {
                        // 调用钉钉API获取科长/部长
                        OrganizationManagerResponse managerInfo = reviewService.queryOrganizationManager(userid);

                        OrgCache cache = new OrgCache();
                        cache.setUserid(userid);
                        cache.setLastSyncTime(LocalDateTime.now());

                        // 解析科长/部长信息
                        boolean hasManager = false;
                        boolean hasDirector = false;

                        if (managerInfo != null) {
                            // 科长：取第一个
                            if (managerInfo.getSectionManager() != null && !managerInfo.getSectionManager().isEmpty()) {
                                cache.setManagerUserid(managerInfo.getSectionManager().get(0));
                                hasManager = true;
                            }
                            // 部长：取第一个
                            if (managerInfo.getDepartmentManager() != null && !managerInfo.getDepartmentManager().isEmpty()) {
                                cache.setDirectorUserid(managerInfo.getDepartmentManager().get(0));
                                hasDirector = true;
                            }
                        }

                        orgCacheMapper.insert(cache);

                        // 分类统计
                        if (hasManager || hasDirector) {
                            usersWithManager.add(userid);
                        } else {
                            usersWithoutManager.add(displayName + "(" + userid + ")");
                        }

                    } catch (Exception e) {
                        failedUsers.add(displayName + "(" + userid + ")");
                        log.debug("同步用户失败: userid={}, error={}", userid, e.getMessage());
                    }
                });
                futures.add(future);
            }

            // 等待所有任务完成
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException | ExecutionException e) {
                    log.debug("任务执行异常: {}", e.getMessage());
                }
            }

            // 关闭线程池
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }

            // === 步骤3: 打印同步结果 ===
            log.info("组织架构同步完成: 有科长/部长的用户数={}, 无科长/部长的用户数={}, 同步失败的用户数={}",
                    usersWithManager.size(), usersWithoutManager.size(), failedUsers.size());

            if (!usersWithoutManager.isEmpty()) {
                log.info("无科长/部长的用户列表: {}", usersWithoutManager);
            }

            if (!failedUsers.isEmpty()) {
                log.warn("同步失败的用户列表: {}", failedUsers);
            }

        } catch (Exception e) {
            log.error("组织架构缓存同步失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取员工的科长userid
     *
     * 先查缓存，缓存未命中则实时查询钉钉API并补缓存。
     *
     * @param userid 员工钉钉 userid
     * @return 科长userid，不存在返回null
     */
    public String getManager(String userid) {
        if (userid == null || userid.isEmpty()) {
            return null;
        }

        // 查缓存
        OrgCache cache = orgCacheMapper.selectByUserid(userid);
        if (cache != null && cache.getManagerUserid() != null) {
            return cache.getManagerUserid();
        }

        // 缓存未命中，实时查询钉钉API
        log.debug("缓存未命中，实时查询科长: userid={}", userid);
        try {
            OrganizationManagerResponse managerInfo = reviewService.queryOrganizationManager(userid);
            if (managerInfo != null && managerInfo.getSectionManager() != null && !managerInfo.getSectionManager().isEmpty()) {
                String managerUserid = managerInfo.getSectionManager().get(0);

                // 补缓存
                if (cache == null) {
                    cache = new OrgCache();
                    cache.setUserid(userid);
                    cache.setLastSyncTime(LocalDateTime.now());
                }
                cache.setManagerUserid(managerUserid);
                orgCacheMapper.insert(cache);

                return managerUserid;
            }
        } catch (Exception e) {
            log.error("实时查询科长失败, userid={}, error={}", userid, e.getMessage());
        }

        return null;
    }

    /**
     * 获取员工的部长userid
     *
     * 先查缓存，缓存未命中则实时查询钉钉API并补缓存。
     *
     * @param userid 员工钉钉 userid
     * @return 部长userid，不存在返回null
     */
    public String getDirector(String userid) {
        if (userid == null || userid.isEmpty()) {
            return null;
        }

        // 查缓存
        OrgCache cache = orgCacheMapper.selectByUserid(userid);
        if (cache != null && cache.getDirectorUserid() != null) {
            return cache.getDirectorUserid();
        }

        // 缓存未命中，实时查询钉钉API
        log.debug("缓存未命中，实时查询部长: userid={}", userid);
        try {
            OrganizationManagerResponse managerInfo = reviewService.queryOrganizationManager(userid);
            if (managerInfo != null && managerInfo.getDepartmentManager() != null && !managerInfo.getDepartmentManager().isEmpty()) {
                String directorUserid = managerInfo.getDepartmentManager().get(0);

                // 补缓存
                if (cache == null) {
                    cache = new OrgCache();
                    cache.setUserid(userid);
                    cache.setLastSyncTime(LocalDateTime.now());
                }
                cache.setDirectorUserid(directorUserid);
                orgCacheMapper.insert(cache);

                return directorUserid;
            }
        } catch (Exception e) {
            log.error("实时查询部长失败, userid={}, error={}", userid, e.getMessage());
        }

        return null;
    }

    /**
     * 手动刷新组织架构缓存
     *
     * 提供手动触发刷新的接口，用于运维场景。
     */
    public void manualRefresh() {
        log.info("手动触发刷新组织架构缓存...");
        syncOrgCache();
    }

    /**
     * 获取当前缓存大小
     *
     * @return 缓存记录数
     */
    public int getCacheSize() {
        return orgCacheMapper.count();
    }
}