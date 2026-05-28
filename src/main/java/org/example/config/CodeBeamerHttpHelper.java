package org.example.config;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class CodeBeamerHttpHelper {

    private final CBProperties cbProperties;

    public CodeBeamerHttpHelper(CBProperties cbProperties) {
        this.cbProperties = cbProperties;
    }

    /**
     * 获取带 Basic Auth 的 HttpHeaders
     */
    public HttpHeaders getAuthHeaders() {
        String auth = cbProperties.getUsername() + ":" + cbProperties.getPassword();
        String encodedAuth = Base64.getEncoder()
                .encodeToString(auth.getBytes(StandardCharsets.UTF_8));

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + encodedAuth);
        headers.set("accept", "application/json");

        return headers;
    }

    /**
     * 获取带 Basic Auth 的 HttpEntity
     */
    public HttpEntity<Void> getAuthEntity() {
        return new HttpEntity<>(getAuthHeaders());
    }
}
