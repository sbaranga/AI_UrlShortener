package com.example.urlshortener.config;

import com.example.urlshortener.util.Base62Encoder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** Prefix for the short URL returned to clients, without a trailing slash. */
    @NotBlank
    private String baseUrl = "http://localhost:8080";

    @Min(4)
    @Max(Base62Encoder.MAX_LENGTH)
    private int codeLength = 7;

    @Min(1)
    @Max(20)
    private int maxCodeAttempts = 5;

    private List<String> allowedOrigins = List.of("http://localhost:4200");

    @Valid
    private final Auth auth = new Auth();

    @Valid
    private final RateLimit rateLimit = new RateLimit();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        // Tolerate a trailing slash in configuration so short URLs never come out with "//".
        this.baseUrl = baseUrl == null ? null : baseUrl.replaceAll("/+$", "");
    }

    public int getCodeLength() {
        return codeLength;
    }

    public void setCodeLength(int codeLength) {
        this.codeLength = codeLength;
    }

    public int getMaxCodeAttempts() {
        return maxCodeAttempts;
    }

    public void setMaxCodeAttempts(int maxCodeAttempts) {
        this.maxCodeAttempts = maxCodeAttempts;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public Auth getAuth() {
        return auth;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public static class Auth {

        @NotBlank
        private String username = "admin";

        @NotBlank
        private String password = "change-me";

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class RateLimit {

        private boolean enabled = true;

        @Min(1)
        private int capacity = 30;

        @Min(1)
        private int refillPerMinute = 30;

        @Min(1)
        private int maxTrackedClients = 10_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public int getRefillPerMinute() {
            return refillPerMinute;
        }

        public void setRefillPerMinute(int refillPerMinute) {
            this.refillPerMinute = refillPerMinute;
        }

        public int getMaxTrackedClients() {
            return maxTrackedClients;
        }

        public void setMaxTrackedClients(int maxTrackedClients) {
            this.maxTrackedClients = maxTrackedClients;
        }
    }
}
