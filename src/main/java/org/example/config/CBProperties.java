package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "codebeamer")
@Component
public class CBProperties {
    private String baseUrl;
    private String username;
    private String password;
}
