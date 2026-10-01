package dev.contracts.registry.compat;

import dev.contracts.registry.domain.Compatibility;
import dev.contracts.registry.domain.RegisteredSchema;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs Avro's reader/writer resolution rules against every historical version.
 *
 * <p>BACKWARD: new schema as reader, historical as writer (new reader reads old data).
 * FORWARD: historical reader, new writer (old reader reads new data).
 * FULL: both directions.
 *
 * <p>Schemas are parsed in their exact stored reference closure so upgraded
 * dependencies never change historical meaning.
 */
public final class SchemaCompatibilityService {

    public interface ClosureLoader {
        /**
         * Parses a registered schema together with the exact pinned closures of
         * all its stored references.
         */
        Schema loadWithClosure(RegisteredSchema registered);
    }

    private final ClosureLoader closureLoader;

    public SchemaCompatibilityService(ClosureLoader closureLoader) {
        this.closureLoader = closureLoader;
    }

    public List<CompatibilityIssue> check(
            String subject,
            Compatibility mode,
            Schema candidate,
            Map<Integer, RegisteredSchema> history
    ) {
        List<CompatibilityIssue> issues = new ArrayList<>();
        for (RegisteredSchema historical : history.values()) {
            Schema oldSchema = closureLoader.loadWithClosure(historical);
            if (mode.checkBackward()) {
                issues.addAll(runOne(subject, historical.version(), "BACKWARD",
                        candidate, oldSchema));
            }
            if (mode.checkForward()) {
                issues.addAll(runOne(subject, historical.version(), "FORWARD",
                        oldSchema, candidate));
            }
        }
        return issues;
    }

    private List<CompatibilityIssue> runOne(String subject, int version, String direction,
                                            Schema reader, Schema writer) {
        SchemaCompatibility.SchemaPairCompatibility result =
                SchemaCompatibility.checkReaderWriterCompatibility(reader, writer);
        if (result.getType() == SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE) {
            return List.of();
        }
        List<CompatibilityIssue> issues = new ArrayList<>();
        for (SchemaCompatibility.Incompatibility incompatibility
                : result.getResult().getIncompatibilities()) {
            String path = incompatibility.getLocation();
            if (path == null || path.isBlank() || path.equals("/")) {
                path = "<root>";
            }
            String reason = incompatibility.getType() + ": " + incompatibility.getMessage();
            issues.add(new CompatibilityIssue(subject, version, direction, path, reason));
        }
        if (issues.isEmpty()) {
            issues.add(new CompatibilityIssue(subject, version, direction, "<root>", result.getDescription()));
        }
        return issues;
    }
}
