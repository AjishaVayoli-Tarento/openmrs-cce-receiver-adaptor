package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.receiver.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fire-and-forget client that pushes a single notification (OpenMRS alert) to
 * the {@code /alert} REST endpoint. Failures are logged and swallowed — a
 * missed notification must NEVER fail the referral order create.
 *
 * <p>See {@code docs/openmrs-notification-integration.md} for the contract.
 */
@Service
public class OpenMrsNotificationClient {

    private static final Logger log = LoggerFactory.getLogger(OpenMrsNotificationClient.class);

    /** Hard server-side DB column limit on {@code notification_alert.text}. */
    private static final int TEXT_COLUMN_LIMIT = 512;

    /** OpenMRS REST AlertResource requires this exact ISO8601 "Long" form (millis + numeric offset). */
    private static final DateTimeFormatter OPENMRS_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT);

    private final RestClient restClient;
    private final NotificationProperties props;
    private final ObjectMapper om = new ObjectMapper();

    /** (sourceKey -> emittedAtMillis) memo for short-window dedupe across retries. */
    private final ConcurrentHashMap<String, Long> dedupe = new ConcurrentHashMap<>();

    public OpenMrsNotificationClient(@Qualifier("openmrsRestClient") RestClient restClient,
                                     NotificationProperties props) {
        this.restClient = restClient;
        this.props = props;
    }

    /**
     * Pushes an alert. Caller-supplied {@code dedupeKey} (e.g. ServiceRequest id)
     * suppresses duplicate fires within the configured TTL window.
     */
    public void notify(NotificationPayload payload,
                       List<String> recipientUserUuids,
                       String dedupeKey) {
        if (!props.isEnabled()) {
            log.debug("Notifications disabled — skipping (title='{}')", payload.title());
            return;
        }
        if (recipientUserUuids == null || recipientUserUuids.isEmpty()) {
            log.info("Skipping notify — no recipients resolved (title='{}')", payload.title());
            return;
        }
        if (dedupeKey != null && !dedupeKey.isBlank() && isDuplicate(dedupeKey)) {
            log.debug("Skipping notify — duplicate within TTL (key='{}', title='{}')",
                    dedupeKey, payload.title());
            return;
        }

        try {
            String text = buildText(payload);

            ObjectNode body = om.createObjectNode();
            body.put("text", text);
            body.put("satisfiedByAny", false);
            body.put("alertRead", false);
            body.put("dateToExpire",
                    OPENMRS_DATE_FMT.withZone(ZoneOffset.UTC)
                            .format(Instant.now().plus(props.getExpireHours(), ChronoUnit.HOURS)));
            var recipients = body.putArray("recipients");
            recipientUserUuids.forEach(uuid -> recipients.addObject().put("recipient", uuid));

            String response = restClient.post()
                    .uri("/alert")
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);

            String alertUuid = null;
            try {
                JsonNode root = om.readTree(response == null ? "{}" : response);
                alertUuid = root.path("uuid").asText(null);
            } catch (Exception ignored) {
                // best-effort — uuid is informational only
            }

            if (dedupeKey != null && !dedupeKey.isBlank()) {
                dedupe.put(dedupeKey, System.currentTimeMillis());
            }
            log.info("Pushed alert title='{}' to {} recipient(s) uuid={}",
                    payload.title(), recipientUserUuids.size(), alertUuid);
        } catch (Exception e) {
            log.warn("Failed to push alert title='{}': {}", payload.title(), e.toString());
        }
    }

    private boolean isDuplicate(String key) {
        long ttlMs = Math.max(1, props.getDedupe().getTtlHours()) * 3600_000L;
        long now = System.currentTimeMillis();
        // Best-effort cleanup of stale entries.
        dedupe.entrySet().removeIf(e -> (now - e.getValue()) > ttlMs);
        Long last = dedupe.get(key);
        return last != null && (now - last) < ttlMs;
    }

    private String buildText(NotificationPayload p) {
        ObjectNode n = om.createObjectNode();
        n.put("v", 1);
        if (p.type() != null)     n.put("type", p.type());
        if (p.severity() != null) n.put("severity", p.severity());
        n.put("title", p.title());
        if (p.body() != null)     n.put("body", p.body());
        if (p.link() != null)     n.put("link", p.link());

        String s = n.toString();
        if (s.length() > TEXT_COLUMN_LIMIT) {
            int over = s.length() - TEXT_COLUMN_LIMIT;
            String original = p.body() == null ? "" : p.body();
            // Truncate body only — preserve routing & semantics. Reserve 1 char for the ellipsis.
            int keep = Math.max(0, original.length() - over - 1);
            String trimmed = original.substring(0, keep);
            n.put("body", trimmed.isEmpty() ? "" : trimmed + "…");
            s = n.toString();
            if (s.length() > TEXT_COLUMN_LIMIT) {
                // Title/link alone overflow the column — caller bug.
                throw new IllegalStateException(
                        "Notification payload exceeds " + TEXT_COLUMN_LIMIT + " chars after body truncation: " + s.length());
            }
        }
        return s;
    }

    public record NotificationPayload(
            String type,
            String severity,   // INFO | WARNING | CRITICAL
            String title,
            String body,
            String link
    ) {}
}
