package org.example.workflow.config;

import org.example.db.entity.ConfigMeta;
import org.example.db.mapper.ConfigMetaMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ConfigMetaService单元测试
 *
 * 测试场景：
 * 1. 检查初始化状态 - 已初始化
 * 2. 检查初始化状态 - 未初始化
 * 3. 标记初始化完成
 * 4. 重置初始化状态
 * 5. 更新YAML时间和加载时间
 * 6. 仅更新加载时间
 *
 * @author system
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class ConfigMetaServiceTest {

    @Mock
    private ConfigMetaMapper configMetaMapper;

    @InjectMocks
    private ConfigMetaService configMetaService;

    /**
     * 场景1: 检查初始化状态 - 已初始化
     */
    @Test
    @DisplayName("检查初始化状态 - 已初始化")
    void testCheckInitialized_True() {
        when(configMetaMapper.isInitialized()).thenReturn(true);

        boolean result = configMetaService.checkInitialized();

        assertTrue(result);
        verify(configMetaMapper).isInitialized();
    }

    /**
     * 场景2: 检查初始化状态 - 未初始化
     */
    @Test
    @DisplayName("检查初始化状态 - 未初始化")
    void testCheckInitialized_False() {
        when(configMetaMapper.isInitialized()).thenReturn(false);

        boolean result = configMetaService.checkInitialized();

        assertFalse(result);
        verify(configMetaMapper).isInitialized();
    }

    /**
     * 场景3: 标记初始化完成
     */
    @Test
    @DisplayName("标记初始化完成")
    void testMarkInitialized() {
        doNothing().when(configMetaMapper).updateInitialized(true);

        configMetaService.markInitialized();

        verify(configMetaMapper).updateInitialized(true);
    }

    /**
     * 场景4: 重置初始化状态
     */
    @Test
    @DisplayName("重置初始化状态")
    void testResetInitialized() {
        doNothing().when(configMetaMapper).updateInitialized(false);

        configMetaService.resetInitialized();

        verify(configMetaMapper).updateInitialized(false);
    }

    /**
     * 场景5: 更新YAML时间和加载时间
     */
    @Test
    @DisplayName("更新YAML时间和加载时间")
    void testUpdateYamlLoadedTime() {
        LocalDateTime yamlTime = LocalDateTime.of(2026, 5, 28, 10, 0);
        LocalDateTime loadedTime = LocalDateTime.of(2026, 5, 28, 10, 1);

        doNothing().when(configMetaMapper).updateYamlTime(yamlTime, loadedTime);

        configMetaService.updateYamlLoadedTime(yamlTime, loadedTime);

        verify(configMetaMapper).updateYamlTime(yamlTime, loadedTime);
    }

    /**
     * 场景6: 仅更新加载时间
     */
    @Test
    @DisplayName("仅更新加载时间")
    void testUpdateLastLoadedTime() {
        LocalDateTime loadedTime = LocalDateTime.of(2026, 5, 28, 10, 1);

        doNothing().when(configMetaMapper).updateLastLoadedTime(loadedTime);

        configMetaService.updateLastLoadedTime(loadedTime);

        verify(configMetaMapper).updateLastLoadedTime(loadedTime);
    }

    /**
     * 场景7: 查询配置元数据
     */
    @Test
    @DisplayName("查询配置元数据")
    void testSelect() {
        ConfigMeta configMeta = new ConfigMeta();
        configMeta.setId(1L);
        configMeta.setInitialized(true);
        configMeta.setYamlModifiedTime(LocalDateTime.of(2026, 5, 28, 9, 0));
        configMeta.setLastLoadedTime(LocalDateTime.of(2026, 5, 28, 10, 0));

        when(configMetaMapper.select()).thenReturn(configMeta);

        ConfigMeta result = configMetaMapper.select();

        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertTrue(result.getInitialized());
    }
}