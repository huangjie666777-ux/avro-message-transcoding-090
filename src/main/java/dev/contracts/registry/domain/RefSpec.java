package dev.contracts.registry.domain;

import java.util.Objects;

/** A declared cross-subject reference: {@code name} (Avro full name) -> subject (version, or batch peer). */
public record RefSpec(String name, String subject, Integer version) {

    public RefSpec {
        Objects.requireNonNull(name, "reference name");
        Objects.requireNonNull(subject, "reference subject");
        name = name.trim();
        subject = subject.trim();
        if (name.isEmpty() || subject.isEmpty()) {
            throw new IllegalArgumentException("reference name and subject must be non-empty");
        }
    }

    public boolean isBatchRef() {
        return version == null;
    }
}
