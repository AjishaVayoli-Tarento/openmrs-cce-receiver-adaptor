package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.receiver.config.DiscoveredConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class VisitManager {

    private static final Logger log = LoggerFactory.getLogger(VisitManager.class);
    private static final DateTimeFormatter OPENMRS_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    private final RestClient restClient;
    private final DiscoveredConfig discoveredConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VisitManager(@Qualifier("openmrsRestClient") RestClient restClient,
                        DiscoveredConfig discoveredConfig) {
        this.restClient = restClient;
        this.discoveredConfig = discoveredConfig;
    }

    public ObjectNode ensureVisit(ObjectNode encounterNode, Map<String, String> perRequestVisitCache) {
        // Skip if visit/partOf already set
        if (!encounterNode.path("partOf").isMissingNode() ||
                encounterNode.has("visit")) {
            return encounterNode;
        }

        // Skip if no type[] (visit-type encounter)
        if (encounterNode.path("type").isMissingNode() || !encounterNode.path("type").isArray()) {
            return encounterNode;
        }

        String patientUuid = extractPatientUuid(encounterNode);
        if (patientUuid == null) {
            log.warn("Cannot link Visit — no patient UUID found in Encounter");
            return encounterNode;
        }

        // Check per-request cache
        String visitUuid = perRequestVisitCache.get(patientUuid);
        if (visitUuid == null) {
            visitUuid = findOrCreateVisit(patientUuid, encounterNode);
            if (visitUuid != null) {
                perRequestVisitCache.put(patientUuid, visitUuid);
            }
        }

        if (visitUuid != null) {
            encounterNode.put("visit", visitUuid);
            log.debug("Linked Encounter to Visit: {}", visitUuid);
        }

        return encounterNode;
    }

    public String ensureEncounterForOrder(ObjectNode orderNode, Map<String, String> perRequestVisitCache) {
        // Check if encounter already set
        JsonNode encounterRef = orderNode.path("encounter");
        if (!encounterRef.isMissingNode()) {
            String ref = encounterRef.path("reference").asText("");
            if (!ref.isBlank()) return null; // already has encounter
        }

        String patientUuid = extractPatientUuid(orderNode);
        if (patientUuid == null) {
            log.warn("Cannot create encounter for order — no patient UUID");
            return null;
        }

        // Find or create visit
        String visitUuid = perRequestVisitCache.get(patientUuid);
        if (visitUuid == null) {
            visitUuid = findOrCreateVisit(patientUuid, orderNode);
            if (visitUuid != null) {
                perRequestVisitCache.put(patientUuid, visitUuid);
            }
        }

        // Always create a NEW encounter (never reuse)
        return createEncounter(patientUuid, visitUuid);
    }

    private String findOrCreateVisit(String patientUuid, ObjectNode resourceNode) {
        // Search for active visit
        try {
            String response = restClient.get()
                    .uri("/visit?patient={uuid}&includeInactive=false&v=default", patientUuid)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("results");
            if (results.isArray() && !results.isEmpty()) {
                String uuid = results.get(0).path("uuid").asText(null);
                log.debug("Found active visit for patient {}: {}", patientUuid, uuid);
                return uuid;
            }
        } catch (Exception e) {
            log.warn("Failed to search visits for patient {}: {}", patientUuid, e.getMessage());
        }

        // Create new visit
        return createVisit(patientUuid, resourceNode);
    }

    private String createVisit(String patientUuid, ObjectNode resourceNode) {
        try {
            String startDatetime = extractStartDatetime(resourceNode);

            ObjectNode visitPayload = objectMapper.createObjectNode();
            visitPayload.put("patient", patientUuid);
            visitPayload.put("visitType", discoveredConfig.getVisitTypeUuid());
            visitPayload.put("startDatetime", startDatetime);

            if (discoveredConfig.getLocationUuid() != null) {
                visitPayload.put("location", discoveredConfig.getLocationUuid());
            }

            String response = restClient.post()
                    .uri("/visit")
                    .body(visitPayload.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            String uuid = root.path("uuid").asText(null);
            log.info("Created new Visit for patient {}: {}", patientUuid, uuid);
            return uuid;
        } catch (Exception e) {
            log.error("Failed to create Visit for patient {}: {}", patientUuid, e.getMessage());
            return null;
        }
    }

    private String createEncounter(String patientUuid, String visitUuid) {
        try {
            String now = ZonedDateTime.now().format(OPENMRS_DATE_FORMAT);

            // Get encounter type UUID for "Consultation"
            String encounterTypeUuid = discoveredConfig.getEncounterTypeCache().get("Consultation");

            ObjectNode encounterPayload = objectMapper.createObjectNode();
            encounterPayload.put("patient", patientUuid);
            encounterPayload.put("encounterDatetime", now);

            if (encounterTypeUuid != null) {
                encounterPayload.put("encounterType", encounterTypeUuid);
            }
            if (visitUuid != null) {
                encounterPayload.put("visit", visitUuid);
            }
            if (discoveredConfig.getLocationUuid() != null) {
                encounterPayload.put("location", discoveredConfig.getLocationUuid());
            }

            String response = restClient.post()
                    .uri("/encounter")
                    .body(encounterPayload.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            String uuid = root.path("uuid").asText(null);
            log.info("Created encounter for standalone order: {}", uuid);
            return uuid;
        } catch (Exception e) {
            log.error("Failed to create encounter for order: {}", e.getMessage());
            return null;
        }
    }

    private String extractPatientUuid(ObjectNode node) {
        // Try subject.reference
        String ref = node.path("subject").path("reference").asText("");
        if (ref.isBlank()) {
            ref = node.path("patient").path("reference").asText("");
        }
        if (ref.isBlank()) return null;

        // Extract UUID from ResourceType/UUID
        String[] parts = ref.split("/");
        return parts[parts.length - 1];
    }

    private String extractStartDatetime(ObjectNode node) {
        // Try period.start from encounter
        String start = node.path("period").path("start").asText("");
        if (!start.isBlank()) return start;

        // Fallback to current time
        return ZonedDateTime.now().format(OPENMRS_DATE_FORMAT);
    }
}
