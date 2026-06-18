package org.example.service;

import org.example.model.dto.response.DingMessageResponse;
import org.example.model.dto.response.DingRobotMessageResponse;

/**
 * 钉钉服务接口
 *
 * 提供钉钉消息发送、用户校验等功能。
 * 支持企业钉钉和个人钉钉两种通知模式。
 *
 * @author system
 * @since 1.0
 */
public interface DingService {

    /**
     * 获取企业钉钉AccessToken
     *
     * @return AccessToken字符串，失败返回空字符串
     */
    String getAccessToken();

    /**
     * 发送ActionCard消息（企业钉钉）
     *
     * @param userIds 接收用户ID列表，逗号分隔
     * @param title 消息标题
     * @param markdown Markdown内容
     * @param singleTitle 按钮标题
     * @param singleUrl 按钮跳转链接
     */
    void sendMessage(String userIds, String title, String markdown, String singleTitle, String singleUrl);
    /**
     * 发送钉钉消息
      * @param userIds 接收人id，逗号分隔
      * @param title 消息标题
      * @param markdown 消息内容，markdown格式
     */
    DingMessageResponse sendMessage(String userIds, String title, String markdown);



    /**
     * 根据工号获取钉钉用户信息，目前只返回name
     * @param userId
     * @return
     */
    String getUserInfo(String userId);

    /**
     * 发送纯文本消息
     *
     * 根据配置的模式选择企业钉钉或个人钉钉Webhook发送。
     *
     * @param userIds 接收用户ID列表，逗号分隔（企业钉钉模式使用）
     * @param content 文本内容
     */
    void sendTextMessage(String userIds, String content);

    /**
     * 查询组织管理者
     *
     * @param employeeId 员工ID
     * @return 管理者ID列表，逗号分隔
     */
    String queryOrganizationManager(String employeeId);

    /**
     * 校验用户userid在钉钉中是否存在
     *
     * 用于beforeEvent校验，确保通知目标有效。
     *
     * @param userid 用户钉钉userid
     * @return true表示用户存在
     */
    boolean checkUserExists(String userid);

    /**
     * 发送机器人消息
     *
     * 使用钉钉机器人API发送markdown消息给指定用户。
     * HTTP状态码200视为成功，无效用户和限流用户作为警告记录。
     *
     * @param userId 接收用户ID（单个用户）
     * @param title 消息标题
     * @param markdown Markdown内容
     * @return 响应对象，包含无效用户列表、限流用户列表等信息；异常时返回null
     */
    DingRobotMessageResponse sendRobotMessage(String userId, String title, String markdown);
}
