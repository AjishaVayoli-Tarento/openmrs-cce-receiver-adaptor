package org.openphc.cce.receiver.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

import jakarta.annotation.PostConstruct;

@Component
@EnableScheduling
public class OpenMrsConfigDiscovery {

    private static final Logger log = LoggerFactory.getLogger(OpenMrsConfigDiscovery.class);

    private final RestClient restClient;
    private final DiscoveredConfig discoveredConfig;
    private final OpenMrsProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenMrsConfigDiscovery(@Qualifier("openmrsRestClient") RestClient restClient,
                                  DiscoveredConfig discoveredConfig,
                                  OpenMrsProperties properties) {
        this.restClient = restClient;
        this.discoveredConfig = discoveredConfig;
        this.properties = properties;
    }

    @PostConstruct
    public void discover() {
        log.info("Starting OpenMRS configuration discovery...");
        try {
            discoverLocation();
            discoverIdentifierTypes();
            discoverIdgenSource();
            discoverVisitType();
            discoverEncounterTypes();
            discoverPersonAttributeTypes();
            discoverAllergySeverityConcepts();
            log.info("OpenMRS configuration discovery complete");
        } catch (Exception e) {
            log.error("Configuration discovery failed — adaptor may not function correctly: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${openmrs.capability-refresh-ms:21600000}")
    public void refresh() {
        log.info("Refreshing OpenMRS discovered configuration...");
        discover();
    }

    private void discoverLocation() {
        if (properties.identifier() != null && properties.identifier().locationUuid() != null
                && !properties.identifier().locationUuid().isBlank()) {
            discoveredConfig.setLocationUuid(properties.identifier().locationUuid());
            log.info("Using configured location UUID: {}", discoveredConfig.getLocationUuid());
            return;
        }

        try {
            String response = restClient.get()
                    .uri("/location?v=default&limit=1")
                    .retrieve()
                    .body(String.class);

            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray() && !results.isEmpty()) {
                discoveredConfig.setLocationUuid(results.get(0).path("uuid").asText());
                log.info("Discovered location: {}", discoveredConfig.getLocationUuid());
            }
        } catch (Exception e) {
            log.warn("Failed to discover location: {}", e.getMessage());
        }
    }

    private void discoverIdentifierTypes() {
        try {
            String response = restClient.get()
                    .uri("/patientidentifiertype?v=default")
                    .retrieve()
                    .body(String.class);

            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray()) {
                for (JsonNode type : results) {
                    String name = type.path("display").asText(type.path("name").asText(""));
                    String uuid = type.path("uuid").asText();
                    discoveredConfig.addSourceIdentifierType(name, uuid);

                    // Check if this is the configured primary identifier type
                    if (properties.identifier() != null && name.equals(properties.identifier().typeName())) {
                        discoveredConfig.setIdentifierTypeName(name);
                        discoveredConfig.setIdentifierTypeUuid(uuid);
                    }
                }
                log.info("Discovered {} identifier types", results.size());
            }
        } catch (Exception e) {
            log.warn("Failed to discover identifier types: {}", e.getMessage());
        }
    }

    private void discoverIdgenSource() {
        if (properties.identifier() != null && properties.identifier().idgenSourceUuid() != null) {
            discoveredConfig.setIdgenSourceUuid(properties.identifier().idgenSourceUuid());
            log.info("Using configured idgen source UUID: {}", discoveredConfig.getIdgenSourceUuid());
            return;
        }

        try {
            String response = restClient.get()
                    .uri("/idgen/identifiersource?v=default")
                    .retrieve()
                    .body(String.class);

            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray() && !results.isEmpty()) {
                discoveredConfig.setIdgenSourceUuid(results.get(0).path("uuid").asText());
                log.info("Discovered idgen source: {}", discoveredConfig.getIdgenSourceUuid());
            }
        } catch (Exception e) {
            log.warn("Failed to discover idgen source: {}", e.getMessage());
        }
    }

    private void discoverVisitType() {
        try {
            String response = restClient.get()
                    .uri("/visittype?v=default")
                    .retrieve()
                    .body(String.class);

            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray()) {
                // Prefer "Facility Visit"
                for (JsonNode vt : results) {
                    String name = vt.path("display").asText(vt.path("name").asText(""));
                    if ("Facility Visit".equalsIgnoreCase(name)) {
                        discoveredConfig.setVisitTypeUuid(vt.path("uuid").asText());
                        log.info("Discovered visit type 'Facility Visit': {}", discoveredConfig.getVisitTypeUuid());
                        return;
                    }
                }
                // Fallback to first
                if (!results.isEmpty()) {
                    discoveredConfig.setVisitTypeUuid(results.get(0).path("uuid").asText());
                    log.info("Using first visit type: {}", discoveredConfig.getVisitTypeUuid());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to discover visit types: {}", e.getMessage());
        }
    }

    private void discoverEncounterTypes() {
        try {
            String response = restClient.get()
                    .uri("/encountertype?v=default")
                    .retrieve()
                    .body(String.class);

            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray()) {
                for (JsonNode et : results) {
                    String name = et.path("display").asText(et.path("name").asText(""));
                    String uuid = et.path("uuid").asText();
                    discoveredConfig.getEncounterTypeCache().put(name, uuid);
                }
                log.info("Discovered {} encounter types", results.size());
            }
        } catch (Exception e) {
            log.warn("Failed to discover encounter types: {}", e.getMessage());
        }
    }

    private void discoverPersonAttributeTypes() {
        try {
            String response = restClient.get()
                    .uri("/personattributetype?v=default")
                    .retrieve()
                    .body(String.class);

            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray()) {
                for (JsonNode at : results) {
                    String name = at.path("display").asText(at.path("name").asText(""));
                    String uuid = at.path("uuid").asText();
                    if (name.toLowerCase().contains("phone")) {
                        discoveredConfig.setPhoneNumberAttributeTypeUuid(uuid);
                    } else if (name.toLowerCase().contains("email")) {
                        discoveredConfig.setEmailAttributeTypeUuid(uuid);
                    }
                }
                log.info("Discovered person attribute types (phone={}, email={})",
                        discoveredConfig.getPhoneNumberAttributeTypeUuid(),
                        discoveredConfig.getEmailAttributeTypeUuid());
            }
        } catch (Exception e) {
            log.warn("Failed to discover person attribute types: {}", e.getMessage());
        }
    }

    private void discoverAllergySeverityConcepts() {
        discoveredConfig.setSevereSeverityConceptUuid(searchConceptUuid("Severe"));
        discoveredConfig.setModerateSeverityConceptUuid(searchConceptUuid("Moderate"));
        discoveredConfig.setMildSeverityConceptUuid(searchConceptUuid("Mild"));
        log.info("Discovered allergy severity concepts (severe={}, moderate={}, mild={})",
                discoveredConfig.getSevereSeverityConceptUuid(),
                discoveredConfig.getModerateSeverityConceptUuid(),
                discoveredConfig.getMildSeverityConceptUuid());
    }

    private String searchConceptUuid(String name) {
        try {
            String response = restClient.get()
                    .uri("/concept?q={name}&v=default", name)
                    .retrieve()
                    .body(String.class);

            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray() && !results.isEmpty()) {
                for (JsonNode r : results) {
                    if (r.path("display").asText("").equalsIgnoreCase(name)) {
                        return r.path("uuid").asText(null);
                    }
                }
                return results.get(0).path("uuid").asText(null);
            }
        } catch (Exception e) {
            log.warn("Failed to search concept '{}': {}", name, e.getMessage());
        }
        return null;
    }

    /**
     * Ensures an identifier type exists in OpenMRS.
     * Checks in-memory cache → searches OpenMRS REST API → auto-creates if not found.
     */
    public String ensureIdentifierTypeExists(String typeName) {
        // Check cache first
        Map<String, String> typeUuids = discoveredConfig.getIdentifierTypeUuids();
        if (typeUuids.containsKey(typeName)) return typeUuids.get(typeName);

        try {
            // Search OpenMRS
            String response = restClient.get()
                    .uri("/patientidentifiertype?v=default&q={name}", typeName)
                    .retrieve()
                    .body(String.class);

            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray() && !results.isEmpty()) {
                for (JsonNode t : results) {
                    if (typeName.equalsIgnoreCase(t.path("display").asText(t.path("name").asText("")))) {
                        String uuid = t.path("uuid").asText();
                        discoveredConfig.addSourceIdentifierType(typeName, uuid);
                        log.info("Found existing identifier type '{}': {}", typeName, uuid);
                        return uuid;
                    }
                }
            }

            // Auto-create if not found
            log.info("Auto-creating identifier type '{}' in OpenMRS", typeName);
            String body = objectMapper.writeValueAsString(Map.of(
                    "name", typeName,
                    "description", "Auto-created by CCE Receiver Adaptor",
                    "required", false,
                    "uniquenessBehavior", "NON_UNIQUE"
            ));

            response = restClient.post()
                    .uri("/patientidentifiertype")
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            String uuid = objectMapper.readTree(response).path("uuid").asText(null);
            if (uuid != null) {
                discoveredConfig.addSourceIdentifierType(typeName, uuid);
                log.info("Created identifier type '{}': {}", typeName, uuid);
            }
            return uuid;
        } catch (Exception e) {
            log.error("Failed to ensure identifier type '{}': {}", typeName, e.getMessage());
            return null;
        }
    }
}
