package org.example.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "codebeamer")
public class CBProperties {
    private String baseUrlPrefix;
    private String username;
    private String password;

    public String getBaseUrl() {
        return baseUrlPrefix + "/cb/api";
    }
}
