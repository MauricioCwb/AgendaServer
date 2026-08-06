package br.com.mauricio.agendaserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurableGeocoderTest {
    @Test void mockProviderIsDeterministicAndPrecise() {
        Geocoder.Result first = ConfigurableGeocoder.mock("RUA TESTE, 100, SOROCABA, SP");
        Geocoder.Result second = ConfigurableGeocoder.mock("RUA TESTE, 100, SOROCABA, SP");
        assertTrue(first.success());
        assertEquals(first.latitude(), second.latitude());
        assertEquals(first.longitude(), second.longitude());
        assertEquals("ADDRESS", first.precision());
        assertEquals("mock", first.provider());
    }

    @Test void blocksPublicNominatimForBulkProcessing() {
        assertTrue(ConfigurableGeocoder.isPublicNominatim("https://nominatim.openstreetmap.org/search"));
        assertFalse(ConfigurableGeocoder.isPublicNominatim("https://geocoder.example.internal/search"));
    }
}
