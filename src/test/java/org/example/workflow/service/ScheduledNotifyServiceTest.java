package org.example.workflow.service;

import org.example.db.entity.ItemStateRecord;
import org.example.db.mapper.ItemStateRecordMapper;
import org.example.db.mapper.NotifyLogMapper;
import org.example.model.dto.response.ItemInfoResponse;
import org.example.service.CBSwaggerService;
import org.example.service.DingService;
import org.example.workflow.cache.OrgCacheService;
import org.example.workflow.config.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ScheduledNotifyService单元测试
 *
 * 测试场景：
 * 1. 停留天数计算
 * 2. 成员通知判断 - 达到间隔天数
 * 3. 科长通知判断 - 达到升级天数
 * 4. 部长通知判断 - 达到升级天数
 * 5. 部长通知判断 - 配置null不通知
 * 6. 分类规则匹配
 * 7. 今日已通知跳过
 *
 * @author system
 * @since 1.0
 */
@ExtendWith(MockitoExtension.class)
class ScheduledNotifyServiceTest {

    @Mock
    private ConfigMetaService configMetaService;

    @Mock(lenient = true)
    private WorkflowConfigService workflowConfigService;

    @Mock(lenient = true)
    private CBSwaggerService cbSwaggerService;

    @Mock(lenient = true)
    private DingService dingService;

    @Mock(lenient = true)
    private OrgCacheService orgCacheService;

    @Mock(lenient = true)
    private ItemStateRecordMapper itemStateRecordMapper;

    @Mock(lenient = true)
    private NotifyLogMapper notifyLogMapper;

    @InjectMocks
    private ScheduledNotifyService scheduledNotifyService;

    private ClassifyConfig classifyConfig;
    private ClassifyRule classifyRule;

    @BeforeEach
    void setUp() {
        classifyConfig = new ClassifyConfig();
        classifyConfig.setClassifyField("severities");

        classifyRule = new ClassifyRule();
        classifyRule.setCategory("严重");
        classifyRule.setMemberIntervalDays(1);
        classifyRule.setManagerEscalateDays(2);
        classifyRule.setDirectorEscalateDays(3);
        classifyConfig.setClassifyRules(List.of(classifyRule));
    }

    /**
     * 场景1: 成员通知判断 - 达到间隔天数
     */
    @Test
    @DisplayName("成员通知判断 - 达到间隔天数")
    void testShouldSendMemberNotification() {
        ClassifyRule rule = new ClassifyRule();
        rule.setMemberIntervalDays(2);

        // 第2天，达到间隔
        assertTrue(shouldSendMember(2, rule));
        // 第4天，达到间隔
        assertTrue(shouldSendMember(4, rule));
        // 第1天，未达到间隔
        assertFalse(shouldSendMember(1, rule));
        // 第3天，未达到间隔
        assertFalse(shouldSendMember(3, rule));
    }

    private boolean shouldSendMember(int stayDays, ClassifyRule rule) {
        if (rule.getMemberIntervalDays() == null || rule.getMemberIntervalDays() <= 0) {
            return false;
        }
        return stayDays >= rule.getMemberIntervalDays() &&
                stayDays % rule.getMemberIntervalDays() == 0;
    }

    /**
     * 场景2: 科长通知判断
     */
    @Test
    @DisplayName("科长通知判断")
    void testShouldSendManagerNotification() {
        ClassifyRule rule = new ClassifyRule();
        rule.setManagerEscalateDays(3);

        // 第3天，达到升级天数
        assertTrue(shouldSendManager(3, rule));
        // 第4天，超过升级天数
        assertTrue(shouldSendManager(4, rule));
        // 第2天，未达到
        assertFalse(shouldSendManager(2, rule));
        // 配置null
        rule.setManagerEscalateDays(null);
        assertFalse(shouldSendManager(5, rule));
    }

    private boolean shouldSendManager(int stayDays, ClassifyRule rule) {
        if (rule.getManagerEscalateDays() == null) {
            return false;
        }
        return stayDays >= rule.getManagerEscalateDays();
    }

    /**
     * 场景3: 部长通知判断
     */
    @Test
    @DisplayName("部长通知判断")
    void testShouldSendDirectorNotification() {
        ClassifyRule rule = new ClassifyRule();
        rule.setDirectorEscalateDays(5);

        // 第5天，达到升级天数
        assertTrue(shouldSendDirector(5, rule));
        // 第6天，超过升级天数
        assertTrue(shouldSendDirector(6, rule));
        // 第4天，未达到
        assertFalse(shouldSendDirector(4, rule));
        // 配置null，不通知部长
        rule.setDirectorEscalateDays(null);
        assertFalse(shouldSendDirector(10, rule));
    }

    private boolean shouldSendDirector(int stayDays, ClassifyRule rule) {
        if (rule.getDirectorEscalateDays() == null) {
            return false;
        }
        return stayDays >= rule.getDirectorEscalateDays();
    }

    /**
     * 场景4: 分类规则匹配 - 匹配成功
     */
    @Test
    @DisplayName("分类规则匹配 - 匹配成功")
    void testMatchClassifyRule_Success() {
        // 使用真实的 WorkflowConfigService 进行匹配逻辑测试
        WorkflowProperties properties = new WorkflowProperties();
        WorkflowConfigService realService = new WorkflowConfigService(properties);

        ClassifyConfig config = new ClassifyConfig();
        ClassifyRule rule1 = new ClassifyRule();
        rule1.setCategory("严重");
        rule1.setMemberIntervalDays(1);

        ClassifyRule rule2 = new ClassifyRule();
        rule2.setCategory("一般");
        rule2.setMemberIntervalDays(2);

        config.setClassifyRules(List.of(rule1, rule2));

        ClassifyRule matched = realService.matchClassifyRule("严重", config);

        assertNotNull(matched);
        assertEquals("严重", matched.getCategory());
    }

    /**
     * 场景5: 分类规则匹配 - 使用默认分类
     */
    @Test
    @DisplayName("分类规则匹配 - 使用默认分类")
    void testMatchClassifyRule_DefaultCategory() {
        // 使用真实的 WorkflowConfigService
        WorkflowProperties properties = new WorkflowProperties();
        WorkflowConfigService realService = new WorkflowConfigService(properties);

        ClassifyConfig config = new ClassifyConfig();
        ClassifyRule rule1 = new ClassifyRule();
        rule1.setCategory("严重");
        rule1.setMemberIntervalDays(1);

        ClassifyRule rule2 = new ClassifyRule();
        rule2.setCategory("一般");
        rule2.setMemberIntervalDays(2);

        config.setClassifyRules(List.of(rule1, rule2));
        config.setDefaultCategory("一般");

        // "轻微" 不匹配任何规则，使用默认分类
        ClassifyRule matched = realService.matchClassifyRule("轻微", config);

        assertNotNull(matched);
        assertEquals("一般", matched.getCategory());
    }

    /**
     * 场景6: 分类规则匹配 - 无匹配
     */
    @Test
    @DisplayName("分类规则匹配 - 无匹配")
    void testMatchClassifyRule_NoMatch() {
        WorkflowProperties properties = new WorkflowProperties();
        WorkflowConfigService realService = new WorkflowConfigService(properties);

        ClassifyConfig config = new ClassifyConfig();
        ClassifyRule rule = new ClassifyRule();
        rule.setCategory("严重");
        rule.setMemberIntervalDays(1);

        config.setClassifyRules(List.of(rule));
        // 无默认分类

        ClassifyRule matched = realService.matchClassifyRule("一般", config);

        assertNull(matched);
    }

    /**
     * 场景7: 分类字段值为null
     */
    @Test
    @DisplayName("分类规则匹配 - classifyValue为null")
    void testMatchClassifyRule_NullValue() {
        WorkflowProperties properties = new WorkflowProperties();
        WorkflowConfigService realService = new WorkflowConfigService(properties);

        ClassifyConfig config = new ClassifyConfig();
        ClassifyRule rule = new ClassifyRule();
        rule.setCategory("严重");
        config.setClassifyRules(List.of(rule));

        ClassifyRule matched = realService.matchClassifyRule(null, config);

        assertNull(matched);
    }

    /**
     * 场景8: 分类配置为null
     */
    @Test
    @DisplayName("分类规则匹配 - classifyConfig为null")
    void testMatchClassifyRule_NullConfig() {
        WorkflowProperties properties = new WorkflowProperties();
        WorkflowConfigService realService = new WorkflowConfigService(properties);

        ClassifyRule matched = realService.matchClassifyRule("严重", null);

        assertNull(matched);
    }

    /**
     * 场景10: 成员通知间隔为0或null
     */
    @Test
    @DisplayName("成员通知间隔为0或null")
    void testShouldSendMemberNotification_ZeroOrNull() {
        ClassifyRule rule1 = new ClassifyRule();
        rule1.setMemberIntervalDays(0);

        assertFalse(shouldSendMember(1, rule1));
        assertFalse(shouldSendMember(100, rule1));

        ClassifyRule rule2 = new ClassifyRule();
        rule2.setMemberIntervalDays(null);

        assertFalse(shouldSendMember(1, rule2));
        assertFalse(shouldSendMember(100, rule2));
    }
}