package me.amiralles.applications.entity;

import java.util.stream.Stream;

public enum ApplicationStatus {

    STARTED,
    PREPROCESSED,
    PROCESSED,
    COMPLETED,
    CANCELED,
    ERROR;

    public static ApplicationStatus fromString(String string) {
        return Stream.of(ApplicationStatus.values())
                .filter(t -> t.name().equalsIgnoreCase(string))
                .findAny().orElse(null);
    }
}
