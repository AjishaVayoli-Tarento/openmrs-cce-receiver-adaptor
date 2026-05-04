package org.openphc.cce.receiver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;

@Component
public class OAuth2TokenProvider {

    private static final Logger log = LoggerFactory.getLogger(OAuth2TokenProvider.class);

    private final OpenMrsProperties properties;
    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.MIN;

    public OAuth2TokenProvider(OpenMrsProperties properties) {
        this.properties = properties;
    }

    public synchronized String getAccessToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry)) {
            return cachedToken;
        }

        var auth = properties.auth();
        if (auth == null || auth.oauth2() == null || auth.oauth2().tokenUrl() == null) {
            throw new IllegalStateException("OAuth2 configuration not provided");
        }

        var oauth2 = auth.oauth2();
        String body = "grant_type=client_credentials"
                + "&client_id=" + oauth2.clientId()
                + "&client_secret=" + oauth2.clientSecret();
        if (oauth2.scope() != null && !oauth2.scope().isBlank()) {
            body += "&scope=" + oauth2.scope();
        }

        log.debug("Requesting OAuth2 token from {}", oauth2.tokenUrl());

        String response = RestClient.create()
                .post()
                .uri(oauth2.tokenUrl())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(body)
                .retrieve()
                .body(String.class);

        // Parse access_token and expires_in from JSON response
        cachedToken = extractJsonField(response, "access_token");
        String expiresIn = extractJsonField(response, "expires_in");
        long expiry = expiresIn != null ? Long.parseLong(expiresIn) : 300;
        tokenExpiry = Instant.now().plusSeconds(expiry - 30); // refresh 30s before expiry

        log.debug("OAuth2 token acquired, expires in {}s", expiry);
        return cachedToken;
    }

    private String extractJsonField(String json, String field) {
        if (json == null) return null;
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + key.length());
        if (colonIdx < 0) return null;
        int start = colonIdx + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) start++;
        if (start >= json.length()) return null;
        // Check if it was a quoted string or a number
        if (json.charAt(start - 1) == '"') {
            int end = json.indexOf('"', start);
            return end > start ? json.substring(start, end) : null;
        } else {
            // number
            start--; // back to first digit
            while (start < json.length() && json.charAt(start) == ' ') start++;
            int end = start;
            while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
            return json.substring(start, end);
        }
    }
}
