package org.example.service;

public interface DingService {
    String getAccessToken();

    void sendMessage(String userIds, String title, String markdown, String singleTitle, String singleUrl);

    String queryOrganizationManager(String employeeId);
}
