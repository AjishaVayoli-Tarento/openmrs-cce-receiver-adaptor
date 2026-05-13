package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.receiver.config.NotificationProperties;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class OpenMrsNotificationClientTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private WireMockServer wm;
    private OpenMrsNotificationClient client;
    private NotificationProperties props;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();

        props = new NotificationProperties();
        // Tests assume defaults except where overridden.
        RestClient rest = RestClient.builder().baseUrl(wm.baseUrl()).build();
        client = new OpenMrsNotificationClient(rest, props);
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private OpenMrsNotificationClient.NotificationPayload payload(String body) {
        return new OpenMrsNotificationClient.NotificationPayload(
                "REFERRAL_ARRIVED", "INFO", "New referral received", body, "/openmrs/spa/x");
    }

    @Test
    void posts_alert_with_recipients_and_openmrs_date_format() throws Exception {
        wm.stubFor(post("/alert")
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"uuid\":\"abc-123\"}")));

        client.notify(payload("Patient X has been referred"),
                List.of("user-uuid-1", "user-uuid-2"),
                "ServiceRequest:sr-1");

        var requests = wm.findAll(postRequestedFor(urlEqualTo("/alert")));
        assertEquals(1, requests.size(), "exactly one /alert POST");

        JsonNode body = OM.readTree(requests.get(0).getBodyAsString());
        assertEquals(false, body.path("alertRead").asBoolean());
        assertEquals(false, body.path("satisfiedByAny").asBoolean());

        // OpenMRS expects yyyy-MM-dd'T'HH:mm:ss.SSSZ — numeric offset, NOT trailing 'Z'.
        String date = body.path("dateToExpire").asText();
        assertTrue(date.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}[+-]\\d{4}"),
                "dateToExpire must use OpenMRS Long-ISO8601 format, was: " + date);

        // Recipients are wrapped as [{recipient: uuid}, ...]
        JsonNode recips = body.path("recipients");
        assertEquals(2, recips.size());
        assertEquals("user-uuid-1", recips.get(0).path("recipient").asText());
        assertEquals("user-uuid-2", recips.get(1).path("recipient").asText());

        // text is the stringified NotificationPayload JSON
        JsonNode text = OM.readTree(body.path("text").asText());
        assertEquals(1, text.path("v").asInt());
        assertEquals("REFERRAL_ARRIVED", text.path("type").asText());
        assertEquals("INFO", text.path("severity").asText());
        assertEquals("New referral received", text.path("title").asText());
        assertEquals("Patient X has been referred", text.path("body").asText());
        assertEquals("/openmrs/spa/x", text.path("link").asText());
    }

    @Test
    void skips_post_when_disabled() {
        props.setEnabled(false);

        client.notify(payload("body"), List.of("uuid"), "key");

        wm.verify(0, postRequestedFor(urlEqualTo("/alert")));
    }

    @Test
    void skips_post_when_no_recipients() {
        client.notify(payload("body"), List.of(), "key");
        wm.verify(0, postRequestedFor(urlEqualTo("/alert")));

        client.notify(payload("body"), null, "key");
        wm.verify(0, postRequestedFor(urlEqualTo("/alert")));
    }

    @Test
    void dedupes_same_key_within_ttl() {
        wm.stubFor(post("/alert")
                .willReturn(aResponse().withStatus(201).withBody("{\"uuid\":\"u1\"}")));

        client.notify(payload("b"), List.of("u"), "ServiceRequest:dup-1");
        client.notify(payload("b"), List.of("u"), "ServiceRequest:dup-1");
        client.notify(payload("b"), List.of("u"), "ServiceRequest:dup-1");

        wm.verify(1, postRequestedFor(urlEqualTo("/alert")));
    }

    @Test
    void distinct_dedupe_keys_both_fire() {
        wm.stubFor(post("/alert")
                .willReturn(aResponse().withStatus(201).withBody("{}")));

        client.notify(payload("b"), List.of("u"), "ServiceRequest:a");
        client.notify(payload("b"), List.of("u"), "ServiceRequest:b");

        wm.verify(2, postRequestedFor(urlEqualTo("/alert")));
    }

    @Test
    void blank_dedupe_key_does_not_dedupe() {
        wm.stubFor(post("/alert")
                .willReturn(aResponse().withStatus(201).withBody("{}")));

        client.notify(payload("b"), List.of("u"), null);
        client.notify(payload("b"), List.of("u"), "");
        client.notify(payload("b"), List.of("u"), "   ");

        wm.verify(3, postRequestedFor(urlEqualTo("/alert")));
    }

    @Test
    void truncates_body_to_512_chars_with_ellipsis() throws Exception {
        wm.stubFor(post("/alert")
                .willReturn(aResponse().withStatus(201).withBody("{}")));

        String bigBody = "x".repeat(2000);
        client.notify(payload(bigBody), List.of("u"), "key-trunc");

        var req = wm.findAll(postRequestedFor(urlEqualTo("/alert"))).get(0);
        String text = OM.readTree(req.getBodyAsString()).path("text").asText();

        assertTrue(text.length() <= 512, "text must fit DB column, was " + text.length());
        String trimmedBody = OM.readTree(text).path("body").asText();
        assertTrue(trimmedBody.length() < 2000, "body must be truncated, was " + trimmedBody.length());
        // Title + envelope must survive untouched.
        assertEquals("New referral received", OM.readTree(text).path("title").asText());
    }

    @Test
    void swallows_server_error_so_referral_flow_is_not_broken() {
        wm.stubFor(post("/alert").willReturn(serverError()));

        try {
            client.notify(payload("b"), List.of("u"), "key-err");
        } catch (Exception e) {
            fail("notify must never throw: " + e);
        }

        // And subsequent calls still attempt (no false dedupe on failure).
        wm.stubFor(post("/alert")
                .willReturn(aResponse().withStatus(201).withBody("{}")));
        client.notify(payload("b"), List.of("u"), "key-err-2");
        wm.verify(2, postRequestedFor(urlEqualTo("/alert")));
    }

    @Test
    void uses_configured_expire_hours() throws Exception {
        props.setExpireHours(1);
        wm.stubFor(post("/alert")
                .willReturn(aResponse().withStatus(201).withBody("{}")));

        long before = System.currentTimeMillis();
        client.notify(payload("b"), List.of("u"), "k");
        long after = System.currentTimeMillis();

        var req = wm.findAll(postRequestedFor(urlEqualTo("/alert"))).get(0);
        String date = OM.readTree(req.getBodyAsString()).path("dateToExpire").asText();
        // Parse back via the same pattern; expect ~now+1h within test elapsed window.
        java.time.OffsetDateTime parsed = java.time.OffsetDateTime.parse(
                date, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ"));
        long expireMillis = parsed.toInstant().toEpochMilli();
        long minExpected = before + 3600_000L - 1000;
        long maxExpected = after + 3600_000L + 1000;
        assertTrue(expireMillis >= minExpected && expireMillis <= maxExpected,
                "expireMillis " + expireMillis + " not within [" + minExpected + "," + maxExpected + "]");
    }
}
