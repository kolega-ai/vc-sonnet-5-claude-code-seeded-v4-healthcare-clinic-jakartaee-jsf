package com.clinic.service;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** One bookable slot on a provider's calendar. */
public record AvailabilitySlot(Long providerId, String providerName,
                               LocalDateTime start, LocalDateTime end)
        implements Serializable {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM");

    public String getLabel() {
        return start.format(DAY) + " · " + start.format(TIME) + "–" + end.format(TIME);
    }

    public String getTimeLabel() {
        return start.format(TIME);
    }

    public String getDayLabel() {
        return start.format(DAY);
    }

    public String getIsoStart() {
        return start.toString();
    }
}
