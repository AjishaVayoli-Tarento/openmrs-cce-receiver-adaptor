package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openphc.cce.receiver.exception.FhirParsingException;
import org.openphc.cce.receiver.model.ResourceEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class BundleSplitter {

    private static final Logger log = LoggerFactory.getLogger(BundleSplitter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<ResourceEntry> split(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String resourceType = root.path("resourceType").asText("");

            if ("Bundle".equals(resourceType)) {
                return splitBundle(root);
            } else {
                return List.of(wrapStandalone(root, payload));
            }
        } catch (JsonProcessingException e) {
            throw new FhirParsingException("Failed to parse payload as JSON: " + e.getMessage(), e);
        }
    }

    private List<ResourceEntry> splitBundle(JsonNode bundle) {
        List<ResourceEntry> entries = new ArrayList<>();
        JsonNode entryArray = bundle.path("entry");

        if (!entryArray.isArray() || entryArray.isEmpty()) {
            log.warn("Bundle has no entries");
            return entries;
        }

        for (JsonNode entry : entryArray) {
            JsonNode resource = entry.path("resource");
            if (resource.isMissingNode()) {
                log.warn("Bundle entry missing 'resource' field, skipping");
                continue;
            }

            String type = resource.path("resourceType").asText("");
            String fullUrl = entry.path("fullUrl").asText(null);
            String method = entry.path("request").path("method").asText("POST");
            String resourceJson = resource.toString();

            entries.add(new ResourceEntry(type, resourceJson, method, fullUrl));
        }

        log.info("Split Bundle into {} resource entries", entries.size());
        return entries;
    }

    private ResourceEntry wrapStandalone(JsonNode root, String originalJson) {
        String type = root.path("resourceType").asText("");
        return new ResourceEntry(type, originalJson, "POST", null);
    }
}
