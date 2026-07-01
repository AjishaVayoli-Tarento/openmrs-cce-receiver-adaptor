package org.openphc.cce.receiver.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.receiver.exception.FhirParsingException;
import org.openphc.cce.receiver.model.ResourceEntry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BundleSplitter} — splitting a FHIR transaction Bundle into per-resource
 * entries, wrapping standalone resources, and the malformed-payload failure path.
 */
class BundleSplitterTest {

    private BundleSplitter splitter;

    @BeforeEach
    void setUp() {
        splitter = new BundleSplitter();
    }

    @Test
    void split_bundle_returnsOneEntryPerResourceWithMethodAndFullUrl() {
        String bundle = """
                {
                  "resourceType": "Bundle",
                  "type": "transaction",
                  "entry": [
                    {
                      "fullUrl": "urn:uuid:patient-1",
                      "resource": {"resourceType": "Patient", "id": "p1"},
                      "request": {"method": "PUT"}
                    },
                    {
                      "fullUrl": "urn:uuid:enc-1",
                      "resource": {"resourceType": "Encounter", "id": "e1"}
                    }
                  ]
                }
                """;

        List<ResourceEntry> entries = splitter.split(bundle);

        assertEquals(2, entries.size());
        assertEquals("Patient", entries.get(0).resourceType());
        assertEquals("PUT", entries.get(0).method());
        assertEquals("urn:uuid:patient-1", entries.get(0).fullUrl());
        // Missing request.method defaults to POST.
        assertEquals("Encounter", entries.get(1).resourceType());
        assertEquals("POST", entries.get(1).method());
    }

    @Test
    void split_bundle_skipsEntriesMissingResource() {
        String bundle = """
                {
                  "resourceType": "Bundle",
                  "entry": [
                    {"fullUrl": "urn:uuid:1"},
                    {"resource": {"resourceType": "Patient", "id": "p1"}}
                  ]
                }
                """;

        List<ResourceEntry> entries = splitter.split(bundle);

        assertEquals(1, entries.size());
        assertEquals("Patient", entries.get(0).resourceType());
    }

    @Test
    void split_bundle_withNoEntries_returnsEmptyList() {
        List<ResourceEntry> entries = splitter.split("{\"resourceType\":\"Bundle\"}");

        assertTrue(entries.isEmpty());
    }

    @Test
    void split_standaloneResource_wrapsAsSingleEntry() {
        String payload = "{\"resourceType\":\"ServiceRequest\",\"id\":\"sr1\"}";

        List<ResourceEntry> entries = splitter.split(payload);

        assertEquals(1, entries.size());
        assertEquals("ServiceRequest", entries.get(0).resourceType());
        assertEquals("POST", entries.get(0).method());
        assertNull(entries.get(0).fullUrl());
        assertEquals(payload, entries.get(0).resourceJson());
    }

    @Test
    void split_invalidJson_throwsFhirParsingException() {
        assertThrows(FhirParsingException.class, () -> splitter.split("not-json {{{"));
    }
}
