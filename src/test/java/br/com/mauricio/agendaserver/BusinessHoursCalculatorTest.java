package br.com.mauricio.agendaserver;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BusinessHoursCalculatorTest {
    private static final LocalTime START = LocalTime.of(9, 0);
    private static final LocalTime END = LocalTime.of(18, 0);

    @Test
    void addsTwoHoursInsideSameBusinessDay() {
        assertEquals(LocalDateTime.of(2026, 8, 11, 13, 30),
                BusinessHoursCalculator.addBusinessHours(LocalDateTime.of(2026, 8, 11, 11, 30), 2, START, END));
    }

    @Test
    void carriesRemainingTimeToNextBusinessDay() {
        assertEquals(LocalDateTime.of(2026, 8, 12, 10, 30),
                BusinessHoursCalculator.addBusinessHours(LocalDateTime.of(2026, 8, 11, 17, 30), 2, START, END));
    }

    @Test
    void skipsWeekend() {
        assertEquals(LocalDateTime.of(2026, 8, 17, 10, 0),
                BusinessHoursCalculator.addBusinessHours(LocalDateTime.of(2026, 8, 14, 17, 0), 2, START, END));
    }
}
