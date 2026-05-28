package org.example.workflow.cache;

import org.example.model.dto.response.ItemInfoResponse;
import org.example.service.CBSwaggerService;
import org.example.service.DingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * DingUserCacheService单元测试
 *
 * 测试场景：
 * 1. 启动时初始化缓存 - 全量验证成功
 * 2. 启动时初始化缓存 - 部分用户不存在
 * 3. 定期刷新缓存
 * 4. 缓存命中 - 直接返回true
 * 5. 缓存未命中 - 实时查询成功
 * 6. 缓存未命中 - 实时查询失败
 * 7. 批量校验userid
 * 8. 获取缓存大小
 * 9. 手动刷新缓存
 *
 * @author system
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class DingUserCacheServiceTest {

    @Mock
    private CBSwaggerService cbSwaggerService;

    @Mock
    private DingService dingService;

    @InjectMocks
    private DingUserCacheService dingUserCacheService;

    /**
     * 测试数据初始化
     */
    private ItemInfoResponse.MemberInfo createMember(String userid, String name) {
        ItemInfoResponse.MemberInfo member = new ItemInfoResponse.MemberInfo();
        member.setUserId(userid);
        member.setName(name);
        member.setDisplayName(name);
        return member;
    }

    /**
     * 场景1: 启动时初始化缓存 - 全量验证成功
     */
    @Test
    @DisplayName("启动时初始化缓存 - 全量验证成功")
    void testInitCache_AllUsersValid() {
        List<ItemInfoResponse.MemberInfo> users = Arrays.asList(
                createMember("user123", "张三"),
                createMember("user456", "李四"),
                createMember("user789", "王五")
        );

        when(cbSwaggerService.getAllUsers()).thenReturn(users);
        when(dingService.checkUserExists("user123")).thenReturn(true);
        when(dingService.checkUserExists("user456")).thenReturn(true);
        when(dingService.checkUserExists("user789")).thenReturn(true);

        dingUserCacheService.initCache();

        assertEquals(3, dingUserCacheService.getCacheSize());
        assertTrue(dingUserCacheService.isValidUserId("user123"));
        assertTrue(dingUserCacheService.isValidUserId("user456"));
        assertTrue(dingUserCacheService.isValidUserId("user789"));
    }

    /**
     * 场景2: 启动时初始化缓存 - 部分用户不存在
     */
    @Test
    @DisplayName("启动时初始化缓存 - 部分用户不存在")
    void testInitCache_SomeUsersInvalid() {
        List<ItemInfoResponse.MemberInfo> users = Arrays.asList(
                createMember("user123", "张三"),
                createMember("user456", "李四"),
                createMember("user000", "无效用户")
        );

        when(cbSwaggerService.getAllUsers()).thenReturn(users);
        when(dingService.checkUserExists("user123")).thenReturn(true);
        when(dingService.checkUserExists("user456")).thenReturn(true);
        when(dingService.checkUserExists("user000")).thenReturn(false);

        dingUserCacheService.initCache();

        assertEquals(2, dingUserCacheService.getCacheSize());
        assertTrue(dingUserCacheService.isValidUserId("user123"));
        assertTrue(dingUserCacheService.isValidUserId("user456"));
        assertFalse(dingUserCacheService.isValidUserId("user000"));
    }

    /**
     * 场景3: 启动时初始化缓存 - 用户列表为空
     */
    @Test
    @DisplayName("启动时初始化缓存 - 用户列表为空")
    void testInitCache_EmptyUserList() {
        when(cbSwaggerService.getAllUsers()).thenReturn(Collections.emptyList());

        dingUserCacheService.initCache();

        assertEquals(0, dingUserCacheService.getCacheSize());
    }

    /**
     * 场景4: 缓存命中 - 直接返回true
     */
    @Test
    @DisplayName("缓存命中 - 直接返回true")
    void testIsValidUserId_CacheHit() {
        // 先初始化缓存
        List<ItemInfoResponse.MemberInfo> users = Collections.singletonList(createMember("user123", "张三"));
        when(cbSwaggerService.getAllUsers()).thenReturn(users);
        when(dingService.checkUserExists("user123")).thenReturn(true);
        dingUserCacheService.initCache();

        // 再次查询，缓存命中，不调用钉钉API
        boolean result = dingUserCacheService.isValidUserId("user123");

        assertTrue(result);
        // 验证钉钉API只被调用一次（初始化时）
        verify(dingService, times(1)).checkUserExists("user123");
    }

    /**
     * 场景5: 缓存未命中 - 实时查询成功
     */
    @Test
    @DisplayName("缓存未命中 - 实时查询成功")
    void testIsValidUserId_CacheMiss_QuerySuccess() {
        // 初始化空缓存
        when(cbSwaggerService.getAllUsers()).thenReturn(Collections.emptyList());
        dingUserCacheService.initCache();

        // 新用户实时查询
        when(dingService.checkUserExists("newUser")).thenReturn(true);

        boolean result = dingUserCacheService.isValidUserId("newUser");

        assertTrue(result);
        // 验证实时查询被调用
        verify(dingService).checkUserExists("newUser");
        // 验证新用户被加入缓存
        assertTrue(dingUserCacheService.isValidUserId("newUser"));
    }

    /**
     * 场景6: 缓存未命中 - 实时查询失败
     */
    @Test
    @DisplayName("缓存未命中 - 实时查询失败")
    void testIsValidUserId_CacheMiss_QueryFail() {
        // 初始化空缓存
        when(cbSwaggerService.getAllUsers()).thenReturn(Collections.emptyList());
        dingUserCacheService.initCache();

        // 无效用户实时查询失败
        when(dingService.checkUserExists("invalidUser")).thenReturn(false);

        boolean result = dingUserCacheService.isValidUserId("invalidUser");

        assertFalse(result);
        // 验证不会被加入缓存
        // 再次查询仍需实时查询
        when(dingService.checkUserExists("invalidUser")).thenReturn(false);
        assertFalse(dingUserCacheService.isValidUserId("invalidUser"));
        verify(dingService, times(2)).checkUserExists("invalidUser");
    }

    /**
     * 场景7: 空userid - 返回false
     */
    @Test
    @DisplayName("空userid - 返回false")
    void testIsValidUserId_NullOrEmpty() {
        assertFalse(dingUserCacheService.isValidUserId(null));
        assertFalse(dingUserCacheService.isValidUserId(""));
        assertFalse(dingUserCacheService.isValidUserId("   "));
    }

    /**
     * 场景8: 批量校验userid - 全部有效
     */
    @Test
    @DisplayName("批量校验userid - 全部有效")
    void testFindInvalidUserIds_AllValid() {
        List<ItemInfoResponse.MemberInfo> users = Arrays.asList(
                createMember("user123", "张三"),
                createMember("user456", "李四")
        );
        when(cbSwaggerService.getAllUsers()).thenReturn(users);
        when(dingService.checkUserExists(anyString())).thenReturn(true);
        dingUserCacheService.initCache();

        Set<String> invalidIds = dingUserCacheService.findInvalidUserIds(Arrays.asList("user123", "user456"));

        assertTrue(invalidIds.isEmpty());
    }

    /**
     * 场景9: 批量校验userid - 部分无效
     */
    @Test
    @DisplayName("批量校验userid - 部分无效")
    void testFindInvalidUserIds_SomeInvalid() {
        List<ItemInfoResponse.MemberInfo> users = Collections.singletonList(createMember("user123", "张三"));
        when(cbSwaggerService.getAllUsers()).thenReturn(users);
        when(dingService.checkUserExists("user123")).thenReturn(true);
        dingUserCacheService.initCache();

        // user456不在缓存中，实时查询返回false
        when(dingService.checkUserExists("user456")).thenReturn(false);

        Set<String> invalidIds = dingUserCacheService.findInvalidUserIds(Arrays.asList("user123", "user456"));

        assertEquals(1, invalidIds.size());
        assertTrue(invalidIds.contains("user456"));
    }

    /**
     * 场景10: 批量校验userid - 空列表
     */
    @Test
    @DisplayName("批量校验userid - 空列表")
    void testFindInvalidUserIds_EmptyList() {
        Set<String> invalidIds = dingUserCacheService.findInvalidUserIds(Collections.emptyList());
        assertTrue(invalidIds.isEmpty());

        Set<String> invalidIdsNull = dingUserCacheService.findInvalidUserIds(null);
        assertTrue(invalidIdsNull.isEmpty());
    }

    /**
     * 场景11: 定期刷新缓存
     */
    @Test
    @DisplayName("定期刷新缓存")
    void testScheduledRefresh() {
        // 第一次初始化
        List<ItemInfoResponse.MemberInfo> users1 = Collections.singletonList(createMember("user123", "张三"));
        when(cbSwaggerService.getAllUsers()).thenReturn(users1);
        when(dingService.checkUserExists("user123")).thenReturn(true);
        dingUserCacheService.initCache();

        assertEquals(1, dingUserCacheService.getCacheSize());

        // 模拟定期刷新 - 用户列表变化
        List<ItemInfoResponse.MemberInfo> users2 = Arrays.asList(
                createMember("user123", "张三"),
                createMember("user456", "李四")
        );
        when(cbSwaggerService.getAllUsers()).thenReturn(users2);
        when(dingService.checkUserExists("user456")).thenReturn(true);

        dingUserCacheService.scheduledRefresh();

        assertEquals(2, dingUserCacheService.getCacheSize());
        assertTrue(dingUserCacheService.isValidUserId("user456"));
    }

    /**
     * 场景12: 手动刷新缓存
     */
    @Test
    @DisplayName("手动刷新缓存")
    void testManualRefresh() {
        List<ItemInfoResponse.MemberInfo> users = Collections.singletonList(createMember("user123", "张三"));
        when(cbSwaggerService.getAllUsers()).thenReturn(users);
        when(dingService.checkUserExists("user123")).thenReturn(true);

        dingUserCacheService.manualRefresh();

        assertEquals(1, dingUserCacheService.getCacheSize());
        assertTrue(dingUserCacheService.isValidUserId("user123"));
    }

    /**
     * 场景13: 初始化异常 - 不抛出错误
     */
    @Test
    @DisplayName("初始化异常 - 不抛出错误")
    void testInitCache_Exception() {
        when(cbSwaggerService.getAllUsers()).thenThrow(new RuntimeException("API调用失败"));

        dingUserCacheService.initCache();

        // 异常不抛出，缓存为空
        assertEquals(0, dingUserCacheService.getCacheSize());
    }
}