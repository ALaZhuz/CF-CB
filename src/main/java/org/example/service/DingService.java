package org.example.service;

/**
 * dingtalk api
 */
public interface DingService {
    /**
     * 获取accesstoken
     */
    String getAccessToken();

    /**
     * 发送钉钉消息
      * @param userIds 接收人id，逗号分隔
      * @param title 消息标题
      * @param markdown 消息内容，markdown格式
     */
    void sendMessage(String userIds, String title, String markdown);

    /**
     * 根据工号获取钉钉用户信息，目前只返回name
     * @param userId
     * @return
     */
    String getUserInfo(String userId);

    
}
