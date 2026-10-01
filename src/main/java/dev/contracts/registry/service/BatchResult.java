package dev.contracts.registry.service;

import java.util.List;

public record BatchResult(List<AssignedVersion> results) {

    public record AssignedVersion(String subject, int version) {
    }
}
