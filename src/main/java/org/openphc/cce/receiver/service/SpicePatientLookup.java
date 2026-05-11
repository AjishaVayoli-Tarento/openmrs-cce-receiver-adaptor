package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openphc.cce.receiver.config.SpiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Iterator;

/**
 * Looks up demographics from the upstream SPICE FHIR server by identifier
 * (the national-id used as the inbound {@code subject.reference}). Returned
 * demographics are used to seed the placeholder OpenMRS patient instead of
 * generic "Unknown" values.
 *
 * <p>Authentication: uses the static bearer token configured via
 * {@code spice.fhir.token}. The token is assumed to be long-lived (dev/prod
 * deployments rotate it out-of-band).
 */
@Component
@EnableConfigurationProperties(SpiceProperties.class)
public class SpicePatientLookup {

    private static final Logger log = LoggerFactory.getLogger(SpicePatientLookup.class);

    private final SpiceProperties properties;
    private final RestClient fhirClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SpicePatientLookup(SpiceProperties properties) {
        this.properties = properties;
        this.fhirClient = buildFhirClient(properties);
    }

    private static RestClient buildFhirClient(SpiceProperties properties) {
        if (!properties.enabled() || properties.baseUrl() == null || properties.baseUrl().isBlank()) {
            return null;
        }
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(properties.timeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .defaultHeader("Accept", "application/fhir+json")
                .defaultHeader("client", properties.client())
                .build();
    }

    public boolean isEnabled() {
        return fhirClient != null;
    }

    /**
     * Look up demographics from the SPICE FHIR server by identifier (typically
     * the national-id used as the inbound subject reference). Searches the
     * resource type configured by {@code spice.fhir.lookup-resource} (default
     * {@code RelatedPerson} — SPICE stores national-id on RelatedPerson, not
     * Patient). Returns null when disabled, lookup fails, or nothing matches.
     */
    public Demographics fetchByIdentifier(String identifierValue) {
        if (fhirClient == null || identifierValue == null || identifierValue.isBlank()) return null;
        try {
            String body = fetchResourceBundle(identifierValue);
            if (body == null) return null;
            JsonNode bundle = objectMapper.readTree(body);
            JsonNode resource = pickResource(bundle, identifierValue);
            if (resource == null) {
                log.info("SPICE returned no {} for identifier '{}'", properties.lookupResource(), identifierValue);
                return null;
            }
            return toDemographics(resource);
        } catch (Exception e) {
            log.warn("Failed to fetch SPICE {} for identifier '{}': {}",
                    properties.lookupResource(), identifierValue, e.getMessage());
            return null;
        }
    }

    private String fetchResourceBundle(String identifierValue) {
        return doFetch(identifierValue);
    }

    private String doFetch(String identifierValue) {
        // Use typed identifier search (system|value) when an identifier system
        // is configured, so we don't accidentally match patient-id, village-id,
        // etc. that happen to share a numeric value.
        String identifierParam = (properties.identifierSystem() != null && !properties.identifierSystem().isBlank())
                ? properties.identifierSystem() + "|" + identifierValue
                : identifierValue;
        String token = properties.token();
        return fhirClient.get()
                .uri(uri -> uri.path("/" + properties.lookupResource())
                        .queryParam("identifier", identifierParam)
                        .build())
                .headers(h -> {
                    if (token != null && !token.isBlank()) h.setBearerAuth(token);
                })
                .retrieve()
                .body(String.class);
    }

    private JsonNode pickResource(JsonNode bundle, String identifierValue) {
        if (bundle == null || bundle.isMissingNode() || bundle.isNull()) return null;
        String wanted = properties.lookupResource();
        if (wanted.equals(bundle.path("resourceType").asText(""))) return bundle;
        JsonNode entries = bundle.path("entry");
        if (entries.isArray() && !entries.isEmpty()) {
            for (Iterator<JsonNode> it = entries.elements(); it.hasNext(); ) {
                JsonNode resource = it.next().path("resource");
                if (wanted.equals(resource.path("resourceType").asText(""))) return resource;
            }
        }
        log.debug("No {} resource in SPICE bundle for identifier '{}'", wanted, identifierValue);
        return null;
    }

    private Demographics toDemographics(JsonNode patient) {
        String given = null;
        String family = null;
        JsonNode names = patient.path("name");
        if (names.isArray() && !names.isEmpty()) {
            JsonNode name = names.get(0);
            JsonNode givenArr = name.path("given");
            if (givenArr.isArray() && !givenArr.isEmpty()) {
                given = givenArr.get(0).asText(null);
            }
            family = name.path("family").asText(null);
            String text = name.path("text").asText(null);
            // SPICE often only populates name.text — split it into given/family
            // so the placeholder OpenMRS patient gets a sensible first name.
            if (text != null && !text.isBlank()) {
                if (given == null || given.isBlank()) {
                    int sp = text.indexOf(' ');
                    given = sp > 0 ? text.substring(0, sp) : text;
                }
                if (family == null || family.isBlank()) {
                    int sp = text.indexOf(' ');
                    family = sp > 0 ? text.substring(sp + 1).trim() : text;
                }
            }
        }
        String gender = patient.path("gender").asText(null);
        String birthDate = patient.path("birthDate").asText(null);
        return new Demographics(given, family, gender, birthDate);
    }

    public record Demographics(String givenName, String familyName, String gender, String birthDate) {}
}
