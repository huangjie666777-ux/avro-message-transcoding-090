package dev.contracts.registry.domain;

import java.util.List;

public record RegisteredSchema(
        String subject,
        int version,
        String compatibility,
        String schemaJson,
        String rootFullName,
        List<StoredRef> references
) {
}
