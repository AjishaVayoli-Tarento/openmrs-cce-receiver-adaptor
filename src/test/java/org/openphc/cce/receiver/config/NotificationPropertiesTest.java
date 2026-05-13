package org.openphc.cce.receiver.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationPropertiesTest {

    @Test
    void defaults_match_documented_contract() {
        NotificationProperties p = new NotificationProperties();

        assertTrue(p.isEnabled(), "notifications enabled by default");
        assertEquals(48, p.getExpireHours());

        NotificationProperties.Recipients r = p.getRecipients();
        assertNotNull(r);
        assertEquals("role-or-static", r.getPolicy());
        assertEquals("Clinician", r.getRole());
        assertEquals(List.of(), r.getStaticUuids());
        assertEquals(300, r.getCacheTtlSeconds());

        assertEquals(24, p.getDedupe().getTtlHours());
    }

    @Test
    void setters_round_trip() {
        NotificationProperties p = new NotificationProperties();
        p.setEnabled(false);
        p.setExpireHours(12);
        p.getRecipients().setPolicy("static");
        p.getRecipients().setRole("Doctor");
        p.getRecipients().setStaticUuids(List.of("uuid-1", "uuid-2"));
        p.getRecipients().setCacheTtlSeconds(60);
        p.getDedupe().setTtlHours(1);

        assertEquals(false, p.isEnabled());
        assertEquals(12, p.getExpireHours());
        assertEquals("static", p.getRecipients().getPolicy());
        assertEquals("Doctor", p.getRecipients().getRole());
        assertEquals(List.of("uuid-1", "uuid-2"), p.getRecipients().getStaticUuids());
        assertEquals(60, p.getRecipients().getCacheTtlSeconds());
        assertEquals(1, p.getDedupe().getTtlHours());
    }
}
