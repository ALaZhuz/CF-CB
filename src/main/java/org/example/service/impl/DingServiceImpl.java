package org.example.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.config.DingProperties;
import org.example.model.dto.response.DingRobotMessageResponse;
import org.example.service.DingService;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 钉钉服务实现类
 *
 * 实现钉钉消息发送、用户校验等功能。
 * 支持企业钉钉和个人钉钉两种通知模式。
 *
 * @author system
 * @since 1.0
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DingServiceImpl implements DingService {

    private final RestTemplate restTemplate;
    private final DingProperties dingProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取企业钉钉AccessToken
     *
     * @return AccessToken字符串，失败返回空字符串
     */

    public String getAccessToken() {
        Map<String, Object> req = new HashMap<>();
        req.put("appKey", dingProperties.getAppKey());
        req.put("appSecret", dingProperties.getAppSecret());
        Map<?, ?> resp = postForObject(dingProperties.getAccessTokenUrl(), req, Map.class);
        return resp == null || resp.get("accessToken") == null ? "" : resp.get("accessToken").toString();
    }

    /**
     * 查询组织管理者
     *
     * @param userId 员工ID
     * @return 管理者ID列表，逗号分隔
     */
    @Override
    public String getUserInfo(String userId) {
        String accessToken = getAccessToken();
        String url = dingProperties.getUserInfoUrl() + "?access_token=" + accessToken;
        Map<String, Object> req = new HashMap<>();
        req.put("userid", userId);
        req.put("language", "zh_CN");
        Map<?, ?> resp = postForObject(url, req, Map.class);
        if (resp == null) {
            return null;
        }

        // 安全转换 errcode
        Integer errcode = null;
        Object errcodeObj = resp.get("errcode");
        if (errcodeObj instanceof Number) {
            errcode = ((Number) errcodeObj).intValue();
        } else if (errcodeObj != null) {
            try {
                errcode = Integer.parseInt(errcodeObj.toString());
            } catch (NumberFormatException e) {
                // 转换失败，视为错误
                errcode = -1;
            }
        }

        if (errcode == null || errcode != 0) {
            return null;
        }

        Map<?, ?> result = (Map<?, ?>) resp.get("result");
        if (result == null) {
            return null;
        }

        Object nameObj = result.get("name");
        return nameObj != null ? nameObj.toString() : null;
    }

    /**
     * 校验用户userid在钉钉中是否存在
     *
     * 调用钉钉用户查询API确认userid有效性。
     * 不在此方法中打印日志，由调用方统一处理日志输出。
     *
     * @param userid 用户钉钉userid
     * @return true表示用户存在
     */
    @Override
    public boolean checkUserExists(String userid) {
        if (userid == null || userid.isEmpty()) {
            return false;
        }

        try {
            String accessToken = getAccessToken();
            String url = dingProperties.getUserQueryUrl() + "?access_token=" + accessToken;

            Map<String, Object> req = new HashMap<>();
            req.put("userid", userid);

            Map<?, ?> resp = postForObject(url, req, Map.class);

            if (resp == null) {
                return false;
            }

            // 检查返回结果中的errcode，0表示成功
            Object errcode = resp.get("errcode");
            if (errcode != null && ((Number) errcode).intValue() == 0) {
                return true;
            }

            return false;
        } catch (Exception e) {
            log.error("钉钉用户校验异常, userid={}, error={}", userid, e.getMessage());
            return false;
        }
    }

    /**
     * 合并多个管理者ID
     *
     * @param nodes JSON节点数组
     * @return 合并后的ID字符串，逗号分隔
     */
    private String joinIds(JsonNode... nodes) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode node : nodes) {
            String value = node.isMissingNode() || node.isNull() ? "" : node.asText("");
            if (!value.isBlank()) {
                if (sb.length() > 0) sb.append(',');
                sb.append(value);
            }
        }
        return sb.toString();
    }

    /**
     * POST请求并返回对象
     *
     * @param url 请求URL
     * @param body 请求体
     * @param type 返回类型
     * @return 响应对象
     */
    private <T> T postForObject(String url, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        ResponseEntity<T> response = restTemplate.exchange(url, HttpMethod.POST, entity, type);
        return response.getBody();
    }

    /**
     * POST请求并返回字符串
     *
     * @param url 请求URL
     * @param body 请求体
     * @return 响应字符串
     */
    private String postForString(String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        return response.getBody();
    }

    /**
     * 发送机器人消息
     *
     * 使用钉钉机器人API发送markdown消息给指定用户。
     * HTTP状态码200视为成功，无效用户和限流用户作为警告记录。
     *
     * @param userId 接收用户ID（单个用户）
     * @param title 消息标题
     * @param markdown Markdown内容
     * @return 响应对象；HTTP非200或异常时返回null
     */
    @Override
    public DingRobotMessageResponse sendRobotMessage(String userId, String title, String markdown, String msgKey) {
        try {
            String accessToken = getAccessToken();
            String url = dingProperties.getBaseUrlPrefix() + "/v1.0/robot/oToMessages/batchSend";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-acs-dingtalk-access-token", accessToken);

            // 栄据消息类型构建不同的 msgParam
            Map<String, Object> msgParam = new HashMap<>();
            if ("sampleText".equals(msgKey)) {
                // sampleText: 只需要 content
                msgParam.put("content", markdown);
            } else if ("sampleMarkdown".equals(msgKey)) {
                // sampleMarkdown: 需要 title 和 text
                msgParam.put("title", title);
                msgParam.put("text", markdown);
            } else {
                // 默认使用 markdown 格式
                msgParam.put("title", title);
                msgParam.put("text", markdown);
            }

            Map<String, Object> req = new HashMap<>();
            req.put("robotCode", dingProperties.getRobotCode());
            req.put("userIds", Collections.singletonList(userId));
            req.put("msgKey", msgKey);
            req.put("msgParam", objectMapper.writeValueAsString(msgParam));

            HttpEntity<Object> entity = new HttpEntity<>(req, headers);
            ResponseEntity<DingRobotMessageResponse> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, DingRobotMessageResponse.class);

            // 明确判断 HTTP 状态码是否为 200
            if (response.getStatusCode() == HttpStatus.OK) {
                DingRobotMessageResponse result = response.getBody();

                if (result != null) {
                    // 打印无效用户列表和限流用户列表
//                    if (result.getInvalidStaffIdList() != null && !result.getInvalidStaffIdList().isEmpty()) {
//                        log.warn("ALM机器人消息发送-无效用户: userId={}, invalidStaffIdList={}",
//                            userId, result.getInvalidStaffIdList());
//                    }
//                    if (result.getFlowControlledStaffIdList() != null && !result.getFlowControlledStaffIdList().isEmpty()) {
//                        log.warn("ALM机器人消息发送-被限流用户: userId={}, flowControlledStaffIdList={}",
//                            userId, result.getFlowControlledStaffIdList());
//                    }
//
//                    log.info("机器人消息发送成功: userId={}, processQueryKey={}",
//                        userId, result.getProcessQueryKey());

                    return result;
                }
            } else {
                log.error("机器人消息发送失败: userId={}, httpStatus={}",
                    userId, response.getStatusCodeValue());
                return null;
            }

        } catch (Exception e) {
            log.error("机器人消息发送异常: userId={}, error={}", userId, e.getMessage());
            return null;
        }

        return null;
    }
}
