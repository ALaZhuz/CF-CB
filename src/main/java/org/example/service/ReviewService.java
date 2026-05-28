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
}
