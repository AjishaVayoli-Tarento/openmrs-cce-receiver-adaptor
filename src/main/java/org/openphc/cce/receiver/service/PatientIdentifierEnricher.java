package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.receiver.config.DiscoveredConfig;
import org.openphc.cce.receiver.config.OpenMrsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class PatientIdentifierEnricher {

    private static final Logger log = LoggerFactory.getLogger(PatientIdentifierEnricher.class);
    private static final String LUHN_MOD30_CHARS = "0123456789ACDEFGHJKLMNPRTUVWXY";

    private final RestClient restClient;
    private final DiscoveredConfig discoveredConfig;
    private final OpenMrsProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PatientIdentifierEnricher(@Qualifier("openmrsRestClient") RestClient restClient,
                                     DiscoveredConfig discoveredConfig,
                                     OpenMrsProperties properties) {
        this.restClient = restClient;
        this.discoveredConfig = discoveredConfig;
        this.properties = properties;
    }

    public ObjectNode enrich(ObjectNode patientNode) {
        ArrayNode identifiers = getOrCreateIdentifiers(patientNode);

        // Generate OpenMRS ID using idgen if configured
        if (discoveredConfig.getIdgenSourceUuid() != null) {
            String generatedId = generateIdentifierFromIdgen();
            if (generatedId != null) {
                addIdentifier(identifiers, generatedId,
                        discoveredConfig.getIdentifierTypeUuid(),
                        discoveredConfig.getIdentifierTypeName(),
                        true);
            }
        }

        // Promote source identifiers with proper type mapping
        promoteSourceIdentifiers(identifiers);

        return patientNode;
    }

    private ArrayNode getOrCreateIdentifiers(ObjectNode patientNode) {
        if (patientNode.has("identifier") && patientNode.get("identifier").isArray()) {
            return (ArrayNode) patientNode.get("identifier");
        }
        return patientNode.putArray("identifier");
    }

    private String generateIdentifierFromIdgen() {
        try {
            String body = "{\"generateIdentifiers\": true, \"sourceUuid\": \"" +
                    discoveredConfig.getIdgenSourceUuid() + "\", \"numberToGenerate\": 1}";

            String response = restClient.post()
                    .uri("/idgen/identifiersource/{uuid}/generate",
                            discoveredConfig.getIdgenSourceUuid())
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode identifiers = root.path("identifiers");
            if (identifiers.isArray() && !identifiers.isEmpty()) {
                return identifiers.get(0).asText(null);
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to generate identifier from idgen: {}", e.getMessage());
            // Fallback: generate Luhn Mod 30 locally
            return generateLuhnMod30Id();
        }
    }

    private String generateLuhnMod30Id() {
        String base = String.valueOf(System.currentTimeMillis() % 100000000L);
        char checkChar = computeLuhnMod30Check(base);
        return base + checkChar;
    }

    private char computeLuhnMod30Check(String input) {
        int factor = 2;
        int sum = 0;
        int n = LUHN_MOD30_CHARS.length();

        for (int i = input.length() - 1; i >= 0; i--) {
            int codePoint = LUHN_MOD30_CHARS.indexOf(Character.toUpperCase(input.charAt(i)));
            if (codePoint < 0) codePoint = input.charAt(i) - '0';

            int addend = factor * codePoint;
            factor = (factor == 2) ? 1 : 2;
            addend = (addend / n) + (addend % n);
            sum += addend;
        }
        int remainder = sum % n;
        int checkCodePoint = (n - remainder) % n;
        return LUHN_MOD30_CHARS.charAt(checkCodePoint);
    }

    private void promoteSourceIdentifiers(ArrayNode identifiers) {
        for (JsonNode id : identifiers) {
            if (!id.isObject()) continue;
            ObjectNode idNode = (ObjectNode) id;

            // Check if this identifier has a type with text that maps to a known type
            JsonNode typeNode = idNode.path("type");
            if (typeNode.isMissingNode()) continue;

            String typeText = typeNode.path("text").asText("");
            if (typeText.isEmpty()) {
                // Try coding display
                JsonNode coding = typeNode.path("coding");
                if (coding.isArray() && !coding.isEmpty()) {
                    typeText = coding.get(0).path("display").asText("");
                }
            }

            if (!typeText.isEmpty()) {
                // Look up the identifier type UUID from discovered config
                String typeUuid = discoveredConfig.getIdentifierTypeUuids().get(typeText);
                if (typeUuid == null) {
                    // Try to create or find the identifier type
                    typeUuid = findOrCreateIdentifierType(typeText);
                    if (typeUuid != null) {
                        discoveredConfig.addSourceIdentifierType(typeText, typeUuid);
                    }
                }
            }
        }
    }

    private String findOrCreateIdentifierType(String typeName) {
        try {
            // Search for existing
            String response = restClient.get()
                    .uri("/patientidentifiertype?q={name}&v=default", typeName)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("results");
            if (results.isArray() && !results.isEmpty()) {
                return results.get(0).path("uuid").asText(null);
            }

            // Create new
            String body = objectMapper.writeValueAsString(Map.of(
                    "name", typeName,
                    "description", "Auto-created by CCE Receiver for source identifier type: " + typeName,
                    "required", false,
                    "uniquenessBehavior", "UNIQUE"
            ));

            response = restClient.post()
                    .uri("/patientidentifiertype")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            root = objectMapper.readTree(response);
            return root.path("uuid").asText(null);
        } catch (Exception e) {
            log.warn("Failed to find or create identifier type '{}': {}", typeName, e.getMessage());
            return null;
        }
    }

    private void addIdentifier(ArrayNode identifiers, String value, String typeUuid, String typeName, boolean preferred) {
        ObjectNode id = objectMapper.createObjectNode();
        id.put("identifier", value);
        id.put("preferred", preferred);

        if (typeUuid != null) {
            ObjectNode type = id.putObject("identifierType");
            type.put("uuid", typeUuid);
        }

        if (properties.identifier() != null && properties.identifier().locationUuid() != null) {
            ObjectNode location = id.putObject("location");
            location.put("uuid", properties.identifier().locationUuid());
        }

        identifiers.add(id);
    }
}
