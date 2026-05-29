package org.example.workflow.cache;

import org.example.db.entity.OrgCache;
import org.example.db.mapper.OrgCacheMapper;
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

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * OrgCacheService单元测试
 *
 * 测试场景：
 * 1. 启动时初始化缓存 - 全量同步成功
 * 2. 获取科长 - 缓存命中
 * 3. 获取科长 - 缓存未命中实时查询
 * 4. 获取部长 - 缓存命中
 * 5. 获取部长 - 缓存未命中实时查询
 * 6. 用户无科长/部长
 * 7. 手动刷新缓存
 * 8. 定时刷新缓存
 *
 * @author system
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class OrgCacheServiceTest {

    @Mock
    private CBSwaggerService cbSwaggerService;

    @Mock
    private DingService dingService;

    @Mock(lenient = true)
    private OrgCacheMapper orgCacheMapper;

    @InjectMocks
    private OrgCacheService orgCacheService;

    /**
     * 创建测试用户
     */
    private ItemInfoResponse.MemberInfo createMember(String userid, String name) {
        ItemInfoResponse.MemberInfo member = new ItemInfoResponse.MemberInfo();
        member.setUserId(userid);
        member.setName(name);
        member.setDisplayName(name);
        return member;
    }

    /**
     * 场景1: 启动时初始化缓存 - 全量同步成功
     */
    @Test
    @DisplayName("启动时初始化缓存 - 全量同步成功")
    void testInitCache_Success() {
        List<ItemInfoResponse.MemberInfo> users = List.of(
                createMember("user1", "张三"),
                createMember("user2", "李四")
        );

        when(cbSwaggerService.getAllUsers()).thenReturn(users);
        when(dingService.queryOrganizationManager("user1")).thenReturn("manager1,director1");
        when(dingService.queryOrganizationManager("user2")).thenReturn("manager2,director2");
        when(orgCacheMapper.count()).thenReturn(2);

        orgCacheService.initCache();

        verify(orgCacheMapper).deleteAll();
        verify(orgCacheMapper, times(2)).insert(any(OrgCache.class));
        assertEquals(2, orgCacheService.getCacheSize());
    }

    /**
     * 场景2: 获取科长 - 缓存命中
     */
    @Test
    @DisplayName("获取科长 - 缓存命中")
    void testGetManager_CacheHit() {
        OrgCache cache = new OrgCache();
        cache.setUserid("user1");
        cache.setManagerUserid("manager1");

        when(orgCacheMapper.selectByUserid("user1")).thenReturn(cache);

        String manager = orgCacheService.getManager("user1");

        assertEquals("manager1", manager);
        verify(dingService, never()).queryOrganizationManager(anyString());
    }

    /**
     * 场景3: 获取科长 - 缓存未命中实时查询
     */
    @Test
    @DisplayName("获取科长 - 缓存未命中实时查询")
    void testGetManager_CacheMiss_RealtimeQuery() {
        when(orgCacheMapper.selectByUserid("user1")).thenReturn(null);
        when(dingService.queryOrganizationManager("user1")).thenReturn("manager1,director1");

        String manager = orgCacheService.getManager("user1");

        assertEquals("manager1", manager);
        verify(dingService).queryOrganizationManager("user1");
        verify(orgCacheMapper).insert(any(OrgCache.class));
    }

    /**
     * 场景4: 获取部长 - 缓存命中
     */
    @Test
    @DisplayName("获取部长 - 缓存命中")
    void testGetDirector_CacheHit() {
        OrgCache cache = new OrgCache();
        cache.setUserid("user1");
        cache.setDirectorUserid("director1");

        when(orgCacheMapper.selectByUserid("user1")).thenReturn(cache);

        String director = orgCacheService.getDirector("user1");

        assertEquals("director1", director);
        verify(dingService, never()).queryOrganizationManager(anyString());
    }

    /**
     * 场景5: 获取部长 - 缓存未命中实时查询
     */
    @Test
    @DisplayName("获取部长 - 缓存未命中实时查询")
    void testGetDirector_CacheMiss_RealtimeQuery() {
        when(orgCacheMapper.selectByUserid("user1")).thenReturn(null);
        when(dingService.queryOrganizationManager("user1")).thenReturn("manager1,director1");

        String director = orgCacheService.getDirector("user1");

        assertEquals("director1", director);
        verify(dingService).queryOrganizationManager("user1");
        verify(orgCacheMapper).insert(any(OrgCache.class));
    }

    /**
     * 场景6: 用户无科长/部长
     */
    @Test
    @DisplayName("用户无科长/部长")
    void testGetManager_NoManager() {
        OrgCache cache = new OrgCache();
        cache.setUserid("user1");
        cache.setManagerUserid(null);

        when(orgCacheMapper.selectByUserid("user1")).thenReturn(cache);

        String manager = orgCacheService.getManager("user1");

        assertNull(manager);
    }

    /**
     * 场景7: 空userid返回null
     */
    @Test
    @DisplayName("空userid返回null")
    void testGetManager_NullUserid() {
        String manager1 = orgCacheService.getManager(null);
        String manager2 = orgCacheService.getManager("");
        String director1 = orgCacheService.getDirector(null);
        String director2 = orgCacheService.getDirector("");

        assertNull(manager1);
        assertNull(manager2);
        assertNull(director1);
        assertNull(director2);
    }

    /**
     * 场景8: 手动刷新缓存
     */
    @Test
    @DisplayName("手动刷新缓存")
    void testManualRefresh() {
        List<ItemInfoResponse.MemberInfo> users = List.of(createMember("user1", "张三"));
        when(cbSwaggerService.getAllUsers()).thenReturn(users);
        when(dingService.queryOrganizationManager("user1")).thenReturn("manager1,director1");
        when(orgCacheMapper.count()).thenReturn(1);

        orgCacheService.manualRefresh();

        verify(orgCacheMapper).deleteAll();
        verify(orgCacheMapper).insert(any(OrgCache.class));
    }

    /**
     * 场景9: 定时刷新缓存
     */
    @Test
    @DisplayName("定时刷新缓存")
    void testScheduledRefresh() {
        List<ItemInfoResponse.MemberInfo> users = List.of(createMember("user1", "张三"));
        when(cbSwaggerService.getAllUsers()).thenReturn(users);
        when(dingService.queryOrganizationManager("user1")).thenReturn("manager1,director1");
        when(orgCacheMapper.count()).thenReturn(1);

        orgCacheService.scheduledRefresh();

        verify(orgCacheMapper).deleteAll();
        verify(orgCacheMapper).insert(any(OrgCache.class));
    }

    /**
     * 场景10: 获取缓存大小
     */
    @Test
    @DisplayName("获取缓存大小")
    void testGetCacheSize() {
        when(orgCacheMapper.count()).thenReturn(5);

        int size = orgCacheService.getCacheSize();

        assertEquals(5, size);
    }
}