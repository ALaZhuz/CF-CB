package org.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 钉钉配置属性绑定类
 *
 * 用于读取 application.yml 中的钉钉相关配置。
 * 支持企业钉钉和个人钉钉两种通知模式。
 *
 * @author system
 * @since 1.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "ding")
public class DingProperties {

    /** 企业ID */
    private String corpId;

    /** 应用Key */
    private String appKey;

    /** 应用Secret */
    private String appSecret;

    /** AgentId */
    private String agentId;

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

    /** 通知模式：enterprise（企业钉钉）或 personal（个人钉钉Webhook） */
    private String mode = "enterprise";

    /** 个人钉钉Webhook URL（当mode为personal时使用） */
    private String personalWebhookUrl;

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
}
