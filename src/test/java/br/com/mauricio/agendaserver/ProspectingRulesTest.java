package br.com.mauricio.agendaserver;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProspectingRulesTest {
    @Test void clampsPerTaskLimitToSafeBounds() {
        assertEquals(1, ProspectingRules.perTaskLimit(0));
        assertEquals(100, ProspectingRules.perTaskLimit(100));
        assertEquals(500, ProspectingRules.perTaskLimit(999));
    }

    @Test void respectsConfiguredPoolAndDeduplicates() {
        List<String> values = new ArrayList<>();
        for (int index = 0; index < 120; index++) values.add("email" + index);
        values.add("email1");
        List<String> selected = ProspectingRules.distinctLimited(values, value -> value, 100);
        assertEquals(100, selected.size());
        assertEquals(100, selected.stream().distinct().count());
    }

    @Test void respectsDailyAllowance() {
        assertEquals(5, ProspectingRules.dailyAllowance(0, 5));
        assertEquals(2, ProspectingRules.dailyAllowance(3, 5));
        assertEquals(0, ProspectingRules.dailyAllowance(5, 5));
    }

    @Test void respectsCooldown() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 3, 12, 0);
        assertTrue(ProspectingRules.withinCooldown(now.minusDays(10), 90, now));
        assertFalse(ProspectingRules.withinCooldown(now.minusDays(91), 90, now));
        assertFalse(ProspectingRules.withinCooldown(null, 90, now));
    }

    @Test void validatesStrongAndExpiringTokens() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 3, 12, 0);
        String token = "a".repeat(64);
        assertTrue(ProspectingRules.tokenUsable(token, now.plusHours(72), now));
        assertFalse(ProspectingRules.tokenUsable(token, now.minusSeconds(1), now));
        assertFalse(ProspectingRules.tokenUsable("fraco", now.plusHours(1), now));
    }

    @Test void stopsWhenTaskCancelledOrFilled() {
        assertTrue(ProspectingRules.taskAcceptsInvites("ACTIVE", 1, 2));
        assertFalse(ProspectingRules.taskAcceptsInvites("ACTIVE", 2, 2));
        assertFalse(ProspectingRules.taskAcceptsInvites("CANCELLED", 0, 2));
    }
    @Test void distinguishesPrimaryAndSecondaryCnaeRules() {
        assertTrue(ProspectingRules.cnaeMatches(true, true, false));
        assertFalse(ProspectingRules.cnaeMatches(false, true, false));
        assertTrue(ProspectingRules.cnaeMatches(false, false, true));
    }

    @Test void detectsRepeatedEmailAtConfiguredThreshold() {
        assertFalse(ProspectingRules.repeatedEmail(19, 20));
        assertTrue(ProspectingRules.repeatedEmail(20, 20));
    }

    @Test void onlyReusesValidAddressLevelGeocodes() {
        assertTrue(ProspectingRules.reusableGeocodeCache("VALID", -23.5, -47.4, 0.90, 0.75, "ADDRESS"));
        assertFalse(ProspectingRules.reusableGeocodeCache("INVALID", -23.5, -47.4, 0.90, 0.75, "ADDRESS"));
        assertFalse(ProspectingRules.reusableGeocodeCache("VALID", -23.5, -47.4, 0.90, 0.75, "CITY"));
    }

    @Test void recoversPersistentJobsIdempotently() {
        assertEquals("PENDING", ProspectingRules.recoverJobState("FILTERING", 0, 0, 0));
        assertEquals("READY", ProspectingRules.recoverJobState("SENDING", 2, 0, 0));
        assertEquals("PARTIAL", ProspectingRules.recoverJobState("SENDING", 0, 2, 1));
        assertEquals("SENT", ProspectingRules.recoverJobState("SENDING", 0, 2, 0));
    }

    @Test void invitationRegistrationRequiresSameNormalizedEmail() {
        assertTrue(ProspectingRules.registrationEmailMatches("Contato@Empresa.com", " contato@empresa.com "));
        assertFalse(ProspectingRules.registrationEmailMatches("a@empresa.com", "b@empresa.com"));
    }

    @Test void resumesImportAtSafeFileIndex() {
        assertEquals(0, ProspectingRules.resumeFileIndex(-1, 10));
        assertEquals(3, ProspectingRules.resumeFileIndex(3, 10));
        assertEquals(10, ProspectingRules.resumeFileIndex(15, 10));
    }

}
