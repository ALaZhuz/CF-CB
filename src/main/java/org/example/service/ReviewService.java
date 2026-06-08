package org.example.service;

import org.example.model.dto.response.OrganizationManagerResponse;

public interface ReviewService {

    /**
     * 获取春风组织架构：科长、部长、总监。
     *
     * @param employeeId 工号
     * @return 领导信息
     */
    OrganizationManagerResponse queryOrganizationManager(String employeeId);

    /**
     * 获取AccessToken
     *
     * 用于检测网络连通性。
     *
     * @return AccessToken，获取失败返回空字符串
     */
    String getAccessToken();
}
