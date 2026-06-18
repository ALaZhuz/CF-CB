package org.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

/**
 * 钉钉配置属性绑定类
 *
 * 用于读取 application.yml 中的钉钉相关配置。
 * 仅支持企业钉钉模式。
 *
 * @author system
 * @since 1.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "dingtalk")
public class DingProperties {

    /** 企业ID */
    private String corpId;

    /** 应用Key */
    private String appKey;

    /** 应用Secret */
    private String appSecret;

    /** AgentId */
    private String agentId;

    /** 机器人Code */
    private String robotCode;

    /** 获取AccessToken的URL */
    private String accessTokenUrl;

    /** 发送消息的URL */
    private String messageUrl;

    /** 客户端授权URL */
    private String clientAuthorizeUrl;

    /** 客户端AccessToken URL */
    private String clientAccessTokenUrl;

    /** 组织Webhook URL */
    private String organizationWebhookUrl;

    /** 用户查询URL（用于校验userid是否存在） */
    private String userQueryUrl = "https://oapi.dingtalk.com/topapi/v2/user/get";

    private String baseUrlPrefix;
    private String messageBaseUrlPrefix;
    private String clientBaseUrlPrefix;


    public String getAccessTokenUrl() {
        return baseUrlPrefix + "/v1.0/oauth2/accessToken";
    }

    public String getMessageUrl() {
        return messageBaseUrlPrefix + "/topapi/message/corpconversation/asyncsend_v2";
    }

    /**
     * 1. cfmoto-获取code信息
     * @return
     */
    public String getClientAuthorizeCodeUrl() {
        return clientBaseUrlPrefix + "/oauth2/authorize";
    }

    /**
     * 2. cfmoto-获取accessToken
     * @return
     */
    public String getClientAccessTokenUrl() {
        return clientBaseUrlPrefix + "/oauth2/access_token";
    }

    /**
     * 3. 获取组织架构
     * @return
     */
    public String getOrganizationWebhookUrl() {
        return clientBaseUrlPrefix + "/api/open-esb/server/webhook/trigger/";
    }

    public String getUserInfoUrl() {
        return messageBaseUrlPrefix + "/topapi/v2/user/get";
    }

    // ===== 新增：时间参数配置（对应application.yml第49-62行） =====
    private Integer nearExpiredDefault = 5;
    private Integer overdueMinisterDefault = 3;
    private Integer overdueDirectorDefault = 5;
    private Integer overdueWeekly = 8;
    private Integer overdueWeeklyDay = 7;
    private String notifyCron = "0 0 8 * * ?";
    private Integer fixedDelay = 1800000;

    // ===== 新增：显示配置（对应application.yml第65-68行） =====
    private Integer isModerator = 1;
    private Integer isDealine = 1;

    // ===== 新增：消息模板配置（对应application.yml第71-81行） =====
    private Map<String, String> adviceMd = new HashMap<>();

    // ===== 新增：角色通知开关（对应application.yml第86-92行） =====
    private Map<String, Integer> isAdviceRole = new HashMap<>();

    @PostConstruct
    public void initDefaultValues() {
        // 初始化默认消息模板
        if (adviceMd.isEmpty()) {
            adviceMd.put("new", "您有新的评审单待处理，请及时登录系统查看!");
            adviceMd.put("canceled", "您的评审单已取消!");
            adviceMd.put("closed", "您的评审单已关闭!");
            adviceMd.put("overdue", "您参与的评审单还未被主持人关闭，请尽快完成评审，如果已经完成，请忽略这条信息，谢谢！");
            adviceMd.put("near_expired", "您参与的评审单还未被主持人关闭，请尽快在最后期限前完成评审，如果已经完成，请忽略这条信息，谢谢！");
        }
        // 初始化默认角色开关
        if (isAdviceRole.isEmpty()) {
            isAdviceRole.put("moderator", 1);
            isAdviceRole.put("reviewer", 1);
            isAdviceRole.put("viewer", 1);
            isAdviceRole.put("sectionManager", 1);
            isAdviceRole.put("departmentManager", 1);
            isAdviceRole.put("director", 1);
        }
    }

    // 便捷方法
    public String getAdviceMdTemplate(String key) {
        return adviceMd.getOrDefault(key, "");
    }

    public boolean shouldNotifyRole(String role) {
        return isAdviceRole.getOrDefault(role, 0) == 1;
    }

    // 新增：获取显示配置的方法
    public Integer getIsModerator() {
        return isModerator;
    }

    public Integer getIsDealine() {
        return isDealine;
    }
}
