/*
 * @Author: 张阳阳 1401459021@qq.com
 * @Date: 2026-05-12 16:44:10
 * @LastEditors: 张阳阳 1401459021@qq.com
 * @LastEditTime: 2026-05-28 11:20:30
 * @FilePath: \cf-cb\src\main\java\org\example\service\impl\DingServiceImpl.java
 * @Description: 这是默认设置,请设置`customMade`, 打开koroFileHeader查看配置 进行设置: https://github.com/OBKoro1/koro1FileHeader/wiki/%E9%85%8D%E7%BD%AE
 */
/*
 * @Author: 张阳阳 1401459021@qq.com
 * @Date: 2026-05-12 16:44:10
 * @LastEditors: 张阳阳 1401459021@qq.com
 * @LastEditTime: 2026-05-26 21:08:45
 * @FilePath: \cf-cb\src\main\java\org\example\service\impl\DingServiceImpl.java
 * @Description: 这是默认设置,请设置`customMade`, 打开koroFileHeader查看配置 进行设置: https://github.com/OBKoro1/koro1FileHeader/wiki/%E9%85%8D%E7%BD%AE
 */
package org.example.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.config.DingProperties;
import org.example.service.DingService;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DingServiceImpl implements DingService {
    private final RestTemplate restTemplate;
    private final DingProperties dingProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getAccessToken() {
        Map<String, Object> req = new HashMap<>();
        req.put("appKey", dingProperties.getAppKey());
        req.put("appSecret", dingProperties.getAppSecret());
        Map<?, ?> resp = postForObject(dingProperties.getAccessTokenUrl(), req, Map.class);
        return resp == null || resp.get("accessToken") == null ? "" : resp.get("accessToken").toString();
    }

    @Override
    public void sendMessage(String userIds, String title, String markdown) {
        String accessToken = getAccessToken();
        Map<String, Object> actionCard = new HashMap<>();
        actionCard.put("title", title);
        actionCard.put("text", markdown);
        Map<String, Object> msg = new HashMap<>();
        msg.put("msgtype", "markdown");
        msg.put("markdown", actionCard);
        Map<String, Object> req = new HashMap<>();
        req.put("agent_id", Long.valueOf(dingProperties.getAgentId()));
        req.put("userid_list", userIds);
        req.put("to_all_user", false);
        req.put("msg", msg);
        postForObject(dingProperties.getMessageUrl() + "?access_token=" + accessToken, req, Map.class);
    }

    @Override
    public String getUserInfo(String userId) {
        String accessToken = getAccessToken();
        String url = dingProperties.getUserInfoUrl() + "?access_token=" + accessToken;
        Map<String, Object> req = new HashMap<>();
        req.put("userid", userId);
        req.put("language", "zh_CN");
        Map<?, ?> resp = postForObject(url, req, Map.class);
        Integer errcode = (Integer) resp.get("errcode");
        if (errcode == null || errcode != 0) {
            return null;
        }
        Map<?, ?> result = (Map<?, ?>) resp.get("result");
        if (result == null) {
            return null;
        }
        String name = (String) result.get("name");
        return name;
    }


    private <T> T postForObject(String url, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.POST, entity, type);
        return response.getBody();
    }

    
}
