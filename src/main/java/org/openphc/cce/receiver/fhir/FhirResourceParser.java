package org.openphc.cce.receiver.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.openphc.cce.receiver.exception.FhirParsingException;
import org.springframework.stereotype.Component;

@Component
public class FhirResourceParser {

    private final FhirContext fhirContext;

    public FhirResourceParser(FhirContext fhirContext) {
        this.fhirContext = fhirContext;
    }

    public Resource parse(String json) {
        try {
            IParser parser = fhirContext.newJsonParser();
            return (Resource) parser.parseResource(json);
        } catch (Exception e) {
            throw new FhirParsingException("Failed to parse FHIR resource: " + e.getMessage(), e);
        }
    }

    public Bundle parseBundle(String json) {
        try {
            IParser parser = fhirContext.newJsonParser();
            Resource resource = (Resource) parser.parseResource(json);
            if (resource instanceof Bundle bundle) {
                return bundle;
            }
            throw new FhirParsingException("Expected a Bundle but got: " + resource.fhirType());
        } catch (FhirParsingException e) {
            throw e;
        } catch (Exception e) {
            throw new FhirParsingException("Failed to parse FHIR Bundle: " + e.getMessage(), e);
        }
    }

    public String encode(Resource resource) {
        IParser parser = fhirContext.newJsonParser();
        parser.setPrettyPrint(false);
        return parser.encodeResourceToString(resource);
    }

    public boolean isBundle(String json) {
        return json != null && json.contains("\"resourceType\"") && json.contains("\"Bundle\"");
    }
}
