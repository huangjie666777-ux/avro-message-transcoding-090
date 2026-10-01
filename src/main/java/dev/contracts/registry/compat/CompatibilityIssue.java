package dev.contracts.registry.compat;

public record CompatibilityIssue(String subject, int historicalVersion, String direction, String path, String reason) {

    public String describe() {
        return "subject='" + subject + "' historicalVersion=" + historicalVersion
                + " direction=" + direction + " path=" + path + " reason=" + reason;
    }
}
