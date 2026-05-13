package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openphc.cce.receiver.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the set of OpenMRS user UUIDs that should receive a notification
 * for a referral. Caches results in-memory with a short TTL — the {@code /user}
 * endpoint is not free.
 *
 * <p>Policies (configured via {@code openmrs.notification.recipients.policy}):
 * <ul>
 *   <li>{@code role} — fan out to every user with the configured role</li>
 *   <li>{@code static} — use the configured {@code static-uuids} list verbatim</li>
 *   <li>{@code role-or-static} (default) — role lookup, falling back to static
 *       if zero users are returned (or if the call fails)</li>
 * </ul>
 */
@Component
public class RecipientResolver {

    private static final Logger log = LoggerFactory.getLogger(RecipientResolver.class);
    private final ObjectMapper om = new ObjectMapper();

    private final RestClient restClient;
    private final NotificationProperties props;
    private final ConcurrentHashMap<String, CachedResult> cache = new ConcurrentHashMap<>();

    public RecipientResolver(@Qualifier("openmrsRestClient") RestClient restClient,
                             NotificationProperties props) {
        this.restClient = restClient;
        this.props = props;
    }

    /**
     * Resolves the recipient user UUIDs for the given referral. The {@code resourceNode}
     * is currently unused; it is accepted so future policies (location-aware, etc.)
     * can route per-referral without changing callers.
     */
    public List<String> resolveForReferral(JsonNode resourceNode) {
        String policy = safe(props.getRecipients().getPolicy()).toLowerCase();
        return switch (policy) {
            case "static" -> staticList();
            case "role" -> byRole();
            default -> roleOrStatic();
        };
    }

    private List<String> roleOrStatic() {
        List<String> byRole = byRole();
        if (!byRole.isEmpty()) return byRole;
        List<String> fallback = staticList();
        if (!fallback.isEmpty()) {
            log.debug("Role lookup empty — falling back to static recipients ({})", fallback.size());
        }
        return fallback;
    }

    private List<String> staticList() {
        List<String> list = props.getRecipients().getStaticUuids();
        return list == null ? Collections.emptyList() : list;
    }

    private List<String> byRole() {
        String role = props.getRecipients().getRole();
        if (role == null || role.isBlank()) return Collections.emptyList();

        String cacheKey = "role:" + role;
        CachedResult hit = cache.get(cacheKey);
        long ttlMs = Math.max(1, props.getRecipients().getCacheTtlSeconds()) * 1000L;
        if (hit != null && (System.currentTimeMillis() - hit.timestamp) < ttlMs) {
            return hit.value;
        }

        try {
            String uri = UriComponentsBuilder.fromPath("/user")
                    .queryParam("role", role)
                    .queryParam("v", "custom:(uuid)")
                    .build()
                    .toUriString();

            String response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(String.class);

            List<String> uuids = new ArrayList<>();
            JsonNode results = om.readTree(response == null ? "{}" : response).path("results");
            if (results.isArray()) {
                for (JsonNode r : results) {
                    String uuid = r.path("uuid").asText(null);
                    if (uuid != null && !uuid.isBlank()) uuids.add(uuid);
                }
            }
            log.debug("Resolved {} recipient(s) for role='{}'", uuids.size(), role);
            cache.put(cacheKey, new CachedResult(uuids, System.currentTimeMillis()));
            return uuids;
        } catch (Exception e) {
            log.warn("Failed to resolve recipients by role '{}': {}", role, e.toString());
            return Collections.emptyList();
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private record CachedResult(List<String> value, long timestamp) {}
}
