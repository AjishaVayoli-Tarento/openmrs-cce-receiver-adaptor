package org.openphc.cce.receiver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Configuration for the OpenMRS user-facing notification (alert) integration.
 *
 * <p>When enabled, the adaptor pushes a fire-and-forget {@code POST /alert}
 * to OpenMRS after a successful referral order create. See
 * {@code docs/openmrs-notification-integration.md} for the wire contract.
 */
@Component
@ConfigurationProperties(prefix = "openmrs.notification")
public class NotificationProperties {

    /** Master switch. When false, the adaptor never POSTs /alert. */
    private boolean enabled = true;

    /** TTL (hours) before the alert auto-expires server-side. */
    private int expireHours = 48;

    /** Recipient resolution policy. */
    private final Recipients recipients = new Recipients();

    /** Idempotency / dedupe behaviour. */
    private final Dedupe dedupe = new Dedupe();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getExpireHours() { return expireHours; }
    public void setExpireHours(int expireHours) { this.expireHours = expireHours; }

    public Recipients getRecipients() { return recipients; }
    public Dedupe getDedupe() { return dedupe; }

    public static class Recipients {
        /** One of: role, static, role-or-static. */
        private String policy = "role-or-static";
        /** OpenMRS role name to fan out to (when policy uses role). */
        private String role = "Clinician";
        /** Hardcoded fallback user UUIDs (when policy uses static or role fallback). */
        private List<String> staticUuids = Collections.emptyList();
        /** TTL (seconds) for the resolved-recipients cache. */
        private int cacheTtlSeconds = 300;

        public String getPolicy() { return policy; }
        public void setPolicy(String policy) { this.policy = policy; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public List<String> getStaticUuids() { return staticUuids; }
        public void setStaticUuids(List<String> staticUuids) { this.staticUuids = staticUuids; }
        public int getCacheTtlSeconds() { return cacheTtlSeconds; }
        public void setCacheTtlSeconds(int cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }
    }

    public static class Dedupe {
        /** TTL (hours) for the (serviceRequestId -> alertUuid) memo. */
        private int ttlHours = 24;

        public int getTtlHours() { return ttlHours; }
        public void setTtlHours(int ttlHours) { this.ttlHours = ttlHours; }
    }
}
