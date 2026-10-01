package dev.contracts.registry.domain;

import java.util.List;

public record PublishEntry(
        String subject,
        int expectedLatest,
        Compatibility compatibility,
        String schemaJson,
        List<RefSpec> references
) {
}
