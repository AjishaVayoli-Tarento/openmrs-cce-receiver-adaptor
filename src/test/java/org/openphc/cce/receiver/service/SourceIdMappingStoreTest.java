package org.openphc.cce.receiver.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SourceIdMappingStore}, the in-memory map from inbound references
 * (fullUrl or {@code ResourceType/id}) to the provisioned OpenMRS UUID.
 */
class SourceIdMappingStoreTest {

    private SourceIdMappingStore store;

    @BeforeEach
    void setUp() {
        store = new SourceIdMappingStore();
    }

    @Test
    void newStore_isEmpty() {
        assertTrue(store.isEmpty());
        assertNull(store.getOpenMrsUuid("anything"));
    }

    @Test
    void resolvesByFullUrl() {
        store.put("urn:uuid:abc", "Patient", "123", "openmrs-uuid-1");

        assertEquals("openmrs-uuid-1", store.getOpenMrsUuid("urn:uuid:abc"));
        assertFalse(store.isEmpty());
    }

    @Test
    void resolvesByResourceTypeAndId() {
        store.put("urn:uuid:abc", "Patient", "123", "openmrs-uuid-1");

        assertEquals("openmrs-uuid-1", store.getOpenMrsUuid("Patient/123"));
    }

    @Test
    void getOpenMrsUuid_nullReference_returnsNull() {
        store.put("urn:uuid:abc", "Patient", "123", "openmrs-uuid-1");

        assertNull(store.getOpenMrsUuid(null));
    }

    @Test
    void put_withNullFullUrl_onlyIndexesByTypeAndId() {
        store.put(null, "Encounter", "e1", "openmrs-uuid-2");

        assertEquals("openmrs-uuid-2", store.getOpenMrsUuid("Encounter/e1"));
        assertFalse(store.isEmpty());
    }

    @Test
    void put_withNullTypeOrId_onlyIndexesByFullUrl() {
        store.put("urn:uuid:xyz", null, null, "openmrs-uuid-3");

        assertEquals("openmrs-uuid-3", store.getOpenMrsUuid("urn:uuid:xyz"));
        assertNull(store.getOpenMrsUuid("null/null"));
    }

    @Test
    void unknownReference_returnsNull() {
        store.put("urn:uuid:abc", "Patient", "123", "openmrs-uuid-1");

        assertNull(store.getOpenMrsUuid("Observation/999"));
    }
}
