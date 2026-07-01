package org.openphc.cce.receiver.fhir;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.receiver.exception.FhirParsingException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FhirResourceParser} — parse/encode round-trips, Bundle type-checking, and
 * the parse-failure path. Backed by a real R4 {@link FhirContext} (HAPI parsing is deterministic).
 */
class FhirResourceParserTest {

    private FhirResourceParser parser;

    @BeforeEach
    void setUp() {
        parser = new FhirResourceParser(FhirContext.forR4());
    }

    @Test
    void parse_validPatient_returnsResource() {
        Resource resource = parser.parse("{\"resourceType\":\"Patient\",\"id\":\"p1\"}");

        assertInstanceOf(Patient.class, resource);
        assertEquals("p1", resource.getIdElement().getIdPart());
    }

    @Test
    void parse_invalidJson_throwsFhirParsingException() {
        assertThrows(FhirParsingException.class, () -> parser.parse("not fhir"));
    }

    @Test
    void parseBundle_validBundle_returnsBundle() {
        Bundle bundle = parser.parseBundle("{\"resourceType\":\"Bundle\",\"type\":\"transaction\"}");

        assertEquals(Bundle.BundleType.TRANSACTION, bundle.getType());
    }

    @Test
    void parseBundle_nonBundleResource_throwsWithType() {
        FhirParsingException ex = assertThrows(FhirParsingException.class,
                () -> parser.parseBundle("{\"resourceType\":\"Patient\",\"id\":\"p1\"}"));
        assertTrue(ex.getMessage().contains("Patient"));
    }

    @Test
    void encode_thenParse_roundTrips() {
        Patient patient = new Patient();
        patient.setId("p9");

        String json = parser.encode(patient);
        Resource parsed = parser.parse(json);

        assertInstanceOf(Patient.class, parsed);
        assertEquals("p9", parsed.getIdElement().getIdPart());
    }

    @Test
    void isBundle_detectsBundlePayloads() {
        assertTrue(parser.isBundle("{\"resourceType\":\"Bundle\"}"));
        assertFalse(parser.isBundle("{\"resourceType\":\"Patient\"}"));
        assertFalse(parser.isBundle(null));
    }
}
