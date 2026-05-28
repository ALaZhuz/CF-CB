/*
 * @Author: 张阳阳 1401459021@qq.com
 * @Date: 2026-05-25 17:42:19
 * @LastEditors: 张阳阳 1401459021@qq.com
 * @LastEditTime: 2026-05-28 16:11:45
 * @FilePath: \cf-cb\src\main\java\org\example\config\DingProperties.java
 * @Description: 这是默认设置,请设置`customMade`, 打开koroFileHeader查看配置 进行设置: https://github.com/OBKoro1/koro1FileHeader/wiki/%E9%85%8D%E7%BD%AE
 */
package org.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ding")
public class DingProperties {
    private String baseUrlPrefix;
    private String messageBaseUrlPrefix;
    private String clientBaseUrlPrefix;
    private String corpId;
    private String appKey;
    private String appSecret;
    private String agentId;

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
