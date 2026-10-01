package dev.contracts.registry.domain;

import org.apache.avro.Schema;

import java.util.List;
import java.util.Map;

/** Result of parsing a candidate batch. New versions are not assigned yet. */
public record ResolvedBatch(List<ResolvedSchema> schemas, Map<String, Schema> universe) {

    public record ResolvedSchema(
            PublishEntry entry,
            String rootFullName,
            Schema schema,
            List<StoredRef> resolvedRefs
    ) {
    }
}
