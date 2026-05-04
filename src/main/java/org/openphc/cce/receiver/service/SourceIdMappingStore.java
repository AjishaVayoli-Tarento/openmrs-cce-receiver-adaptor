package org.openphc.cce.receiver.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class SourceIdMappingStore {

    private final Map<String, String> fullUrlToOpenMrsUuid = new ConcurrentHashMap<>();
    private final Map<String, String> resourceTypeAndIdToUuid = new ConcurrentHashMap<>();

    public void put(String fullUrl, String resourceType, String resourceId, String openMrsUuid) {
        if (fullUrl != null) {
            fullUrlToOpenMrsUuid.put(fullUrl, openMrsUuid);
        }
        if (resourceType != null && resourceId != null) {
            resourceTypeAndIdToUuid.put(resourceType + "/" + resourceId, openMrsUuid);
        }
    }

    public String getOpenMrsUuid(String reference) {
        if (reference == null) return null;

        // Check fullUrl map first
        String uuid = fullUrlToOpenMrsUuid.get(reference);
        if (uuid != null) return uuid;

        // Check resource type + id map
        return resourceTypeAndIdToUuid.get(reference);
    }

    public boolean isEmpty() {
        return fullUrlToOpenMrsUuid.isEmpty() && resourceTypeAndIdToUuid.isEmpty();
    }
}
