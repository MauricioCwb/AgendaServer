package br.com.mauricio.agendaserver;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

final class BusinessHoursCalculator {
    private BusinessHoursCalculator() { }

    static LocalDateTime addBusinessHours(LocalDateTime start, int hours, LocalTime businessStart, LocalTime businessEnd) {
        if (start == null) throw new IllegalArgumentException("Data inicial obrigatória.");
        if (hours < 0) throw new IllegalArgumentException("Horas úteis não podem ser negativas.");
        if (businessStart == null || businessEnd == null || !businessEnd.isAfter(businessStart)) {
            throw new IllegalArgumentException("Janela de horário útil inválida.");
        }

        LocalDateTime cursor = normalizeToBusinessWindow(start, businessStart, businessEnd);
        int minutesRemaining = Math.multiplyExact(hours, 60);
        while (minutesRemaining > 0) {
            LocalDateTime endOfDay = LocalDateTime.of(cursor.toLocalDate(), businessEnd);
            long available = java.time.Duration.between(cursor, endOfDay).toMinutes();
            if (available >= minutesRemaining) return cursor.plusMinutes(minutesRemaining);
            minutesRemaining -= (int) Math.max(0, available);
            cursor = nextBusinessDay(cursor.toLocalDate().plusDays(1), businessStart);
        }
        return cursor;
    }

    static LocalDateTime normalizeToBusinessWindow(LocalDateTime value, LocalTime businessStart, LocalTime businessEnd) {
        LocalDate date = value.toLocalDate();
        if (!isBusinessDay(date)) return nextBusinessDay(date.plusDays(1), businessStart);
        LocalTime time = value.toLocalTime();
        if (time.isBefore(businessStart)) return LocalDateTime.of(date, businessStart);
        if (!time.isBefore(businessEnd)) return nextBusinessDay(date.plusDays(1), businessStart);
        return value;
    }

    private static LocalDateTime nextBusinessDay(LocalDate date, LocalTime businessStart) {
        LocalDate candidate = date;
        while (!isBusinessDay(candidate)) candidate = candidate.plusDays(1);
        return LocalDateTime.of(candidate, businessStart);
    }

    private static boolean isBusinessDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }
}
