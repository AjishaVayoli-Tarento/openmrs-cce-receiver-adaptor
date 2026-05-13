package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.receiver.config.NotificationProperties;
import org.springframework.web.client.RestClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipientResolverTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private WireMockServer wm;
    private NotificationProperties props;
    private RecipientResolver resolver;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();

        props = new NotificationProperties();
        props.getRecipients().setRole("Clinician");

        RestClient rest = RestClient.builder().baseUrl(wm.baseUrl()).build();
        resolver = new RecipientResolver(rest, props);
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private void stubUserResults(String... uuids) {
        StringBuilder sb = new StringBuilder("{\"results\":[");
        for (int i = 0; i < uuids.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"uuid\":\"").append(uuids[i]).append("\"}");
        }
        sb.append("]}");
        wm.stubFor(get(urlPathEqualTo("/user"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(sb.toString())));
    }

    @Test
    void static_policy_returns_configured_uuids_without_calling_user_endpoint() {
        props.getRecipients().setPolicy("static");
        props.getRecipients().setStaticUuids(List.of("u-1", "u-2"));

        List<String> r = resolver.resolveForReferral(OM.createObjectNode());

        assertEquals(List.of("u-1", "u-2"), r);
        wm.verify(0, getRequestedFor(urlPathEqualTo("/user")));
    }

    @Test
    void role_policy_calls_user_endpoint_with_role_and_custom_v() {
        props.getRecipients().setPolicy("role");
        stubUserResults("a-uuid", "b-uuid");

        List<String> r = resolver.resolveForReferral(OM.createObjectNode());

        assertEquals(List.of("a-uuid", "b-uuid"), r);
        var req = wm.findAll(getRequestedFor(urlPathEqualTo("/user"))).get(0);
        String url = req.getUrl();
        assertTrue(url.contains("role=Clinician"), "must pass configured role: " + url);
        assertTrue(url.contains("v=custom"), "must request the custom uuid view: " + url);
    }

    @Test
    void role_policy_returns_empty_on_server_error_does_not_throw() {
        props.getRecipients().setPolicy("role");
        wm.stubFor(get(urlPathEqualTo("/user")).willReturn(serverError()));

        List<String> r = resolver.resolveForReferral(OM.createObjectNode());

        assertTrue(r.isEmpty());
    }

    @Test
    void role_or_static_falls_back_to_static_when_role_returns_empty() {
        props.getRecipients().setPolicy("role-or-static");
        props.getRecipients().setStaticUuids(List.of("fallback-uuid"));
        stubUserResults(); // empty results array

        List<String> r = resolver.resolveForReferral(OM.createObjectNode());

        assertEquals(List.of("fallback-uuid"), r);
    }

    @Test
    void role_or_static_prefers_role_when_present() {
        props.getRecipients().setPolicy("role-or-static");
        props.getRecipients().setStaticUuids(List.of("fallback"));
        stubUserResults("real-user");

        List<String> r = resolver.resolveForReferral(OM.createObjectNode());

        assertEquals(List.of("real-user"), r);
    }

    @Test
    void unknown_policy_defaults_to_role_or_static_behaviour() {
        props.getRecipients().setPolicy("nonsense");
        props.getRecipients().setStaticUuids(List.of("fallback"));
        stubUserResults(); // empty

        List<String> r = resolver.resolveForReferral(OM.createObjectNode());

        assertEquals(List.of("fallback"), r);
    }

    @Test
    void caches_role_lookup_within_ttl() {
        props.getRecipients().setPolicy("role");
        props.getRecipients().setCacheTtlSeconds(300);
        stubUserResults("u-1");

        resolver.resolveForReferral(OM.createObjectNode());
        resolver.resolveForReferral(OM.createObjectNode());
        resolver.resolveForReferral(OM.createObjectNode());

        wm.verify(1, getRequestedFor(urlPathEqualTo("/user")));
    }

    @Test
    void blank_role_short_circuits_to_empty() {
        props.getRecipients().setPolicy("role");
        props.getRecipients().setRole("");

        List<String> r = resolver.resolveForReferral(OM.createObjectNode());

        assertTrue(r.isEmpty());
        wm.verify(0, getRequestedFor(urlPathEqualTo("/user")));
    }
}
