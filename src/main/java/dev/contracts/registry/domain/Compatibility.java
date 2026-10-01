package dev.contracts.registry.domain;

public enum Compatibility {
    BACKWARD("BACKWARD"),
    FORWARD("FORWARD"),
    FULL("FULL");

    private final String wire;

    Compatibility(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public boolean checkBackward() {
        return this == BACKWARD || this == FULL;
    }

    public boolean checkForward() {
        return this == FORWARD || this == FULL;
    }

    public static Compatibility fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException("compatibility must be one of BACKWARD, FORWARD, FULL");
        }
        return switch (value.trim().toUpperCase()) {
            case "BACKWARD" -> BACKWARD;
            case "FORWARD" -> FORWARD;
            case "FULL" -> FULL;
            default -> throw new IllegalArgumentException("compatibility must be one of BACKWARD, FORWARD, FULL");
        };
    }
}
