package dev.contracts.registry.service;

import dev.contracts.registry.compat.CompatibilityIssue;
import dev.contracts.registry.compat.SchemaCompatibilityService;
import dev.contracts.registry.domain.*;
import dev.contracts.registry.error.ApiException;
import dev.contracts.registry.schema.ReferenceResolver;
import dev.contracts.registry.storage.SqliteSchemaRepository;
import org.apache.avro.Schema;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

public class RegistryService {

    private final SqliteSchemaRepository repository;

    public RegistryService(SqliteSchemaRepository repository) {
        this.repository = repository;
    }

    /** Read-only validation: parses references and runs compatibility checks without writing. */
    public BatchResult precheck(List<PublishEntry> entries) {
        return run(entries, false);
    }

    /** Atomic publish: all versions are assigned in one transaction or nothing is written. */
    public BatchResult publish(List<PublishEntry> entries) {
        return run(entries, true);
    }

    private BatchResult run(List<PublishEntry> entries, boolean commit) {
        validateBatchShape(entries);
        if (commit) {
            return repository.inImmediateTransaction(c -> doRun(c, entries, true));
        }
        // Precheck needs a consistent read snapshot; a short read-only transaction is enough.
        try (Connection c = repository.openConnection()) {
            try {
                c.setAutoCommit(false);
                BatchResult result = doRun(c, entries, false);
                c.rollback();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("precheck failed: " + e.getMessage(), e);
        }
    }

    private void validateBatchShape(List<PublishEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw ApiException.badRequest("batch must contain at least one entry");
        }
        Set<String> seen = new HashSet<>();
        for (PublishEntry entry : entries) {
            if (entry == null) {
                throw ApiException.badRequest("batch entries must not be null");
            }
            if (entry.subject() == null || entry.subject().isBlank()) {
                throw ApiException.badRequest("subject name must be non-empty");
            }
            if (!seen.add(entry.subject())) {
                throw ApiException.badRequest("subject '" + entry.subject()
                        + "' may appear at most once per batch");
            }
            if (entry.expectedLatest() < 0) {
                throw ApiException.badRequest("[" + entry.subject()
                        + "] expectedLatest must be >= 0 (0 for a new subject)");
            }
            if (entry.compatibility() == null) {
                throw ApiException.badRequest("[" + entry.subject()
                        + "] compatibility must be BACKWARD, FORWARD or FULL");
            }
        }
    }

    private BatchResult doRun(Connection c, List<PublishEntry> entries, boolean commit) throws SQLException {
        // 1. Re-check expected versions inside the transaction so concurrent
        //    publishers get a clear VERSION_CONFLICT.
        Map<String, Integer> assignedVersions = new LinkedHashMap<>();
        for (PublishEntry entry : entries) {
            int current = repository.latestVersion(c, entry.subject());
            if (current != entry.expectedLatest()) {
                throw ApiException.versionConflict("subject '" + entry.subject()
                        + "' expected latest version " + entry.expectedLatest()
                        + " but server has " + current);
            }
            assignedVersions.put(entry.subject(), current + 1);
        }

        // 2. Resolve references with a connection-bound lookup.
        ReferenceResolver resolver = new ReferenceResolver(new ReferenceResolver.SubjectLookup() {
            @Override
            public RegisteredSchema load(String subject, int version) {
                try {
                    return repository.load(c, subject, version);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public int latestVersion(String subject) {
                try {
                    return repository.latestVersion(c, subject);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        ResolvedBatch resolved = resolver.resolve(entries);

        // 3. Compatibility against ALL historical versions of each subject.
        SchemaCompatibilityService compatibilityService = new SchemaCompatibilityService(
                registered -> {
                    try {
                        return loadWithClosure(c, registered, new HashMap<>());
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                });
        List<CompatibilityIssue> issues = new ArrayList<>();
        for (ResolvedBatch.ResolvedSchema schema : resolved.schemas()) {
            Map<Integer, RegisteredSchema> history = new LinkedHashMap<>();
            for (RegisteredSchema old : repository.allHistory(c, schema.entry().subject())) {
                history.put(old.version(), old);
            }
            issues.addAll(compatibilityService.check(
                    schema.entry().subject(),
                    schema.entry().compatibility(),
                    schema.schema(),
                    history));
        }
        if (!issues.isEmpty()) {
            throw ApiException.incompatible(formatIssues(issues));
        }

        // 4. Assign versions and insert everything. Batch refs become the now-known
        //    assigned versions of their peers.
        List<BatchResult.AssignedVersion> results = new ArrayList<>();
        for (ResolvedBatch.ResolvedSchema schema : resolved.schemas()) {
            String subject = schema.entry().subject();
            int version = assignedVersions.get(subject);
            List<StoredRef> refs = new ArrayList<>();
            for (StoredRef ref : schema.resolvedRefs()) {
                int refVersion = ref.version() == -1
                        ? assignedVersions.get(ref.subject())
                        : ref.version();
                refs.add(new StoredRef(ref.name(), ref.subject(), refVersion));
            }
            if (commit) {
                repository.insert(c, subject, version,
                        schema.entry().compatibility().wire(),
                        schema.entry().schemaJson(), schema.rootFullName(), refs);
            }
            results.add(new BatchResult.AssignedVersion(subject, version));
        }
        return new BatchResult(results);
    }

    private Schema loadWithClosure(Connection c, RegisteredSchema registered,
                                  Map<String, Schema> universe) throws SQLException {
        for (StoredRef ref : registered.references()) {
            RegisteredSchema dep = repository.load(c, ref.subject(), ref.version());
            if (dep == null) {
                throw new IllegalStateException("stored reference missing: " + ref.subject() + ":" + ref.version());
            }
            loadWithClosure(c, dep, universe);
        }
        Schema schema = dev.contracts.registry.schema.SchemaValidator.parse(
                registered.subject(), registered.schemaJson(), universe);
        collectNames(schema, universe);
        return schema;
    }

    private void collectNames(Schema schema, Map<String, Schema> universe) {
        switch (schema.getType()) {
            case RECORD, ENUM, FIXED -> {
                if (universe.putIfAbsent(schema.getFullName(), schema) == null
                        && schema.getType() == Schema.Type.RECORD) {
                    for (Schema.Field f : schema.getFields()) {
                        collectNames(f.schema(), universe);
                    }
                }
            }
            case ARRAY -> collectNames(schema.getElementType(), universe);
            case MAP -> collectNames(schema.getValueType(), universe);
            case UNION -> {
                for (Schema b : schema.getTypes()) {
                    collectNames(b, universe);
                }
            }
            default -> {
            }
        }
    }

    private String formatIssues(List<CompatibilityIssue> issues) {
        StringBuilder sb = new StringBuilder("schema compatibility check failed (").append(issues.size()).append("):\n");
        for (CompatibilityIssue issue : issues) {
            sb.append(" - ").append(issue.describe()).append('\n');
        }
        return sb.toString().trim();
    }

    public List<String> listSubjects() {
        try (Connection c = repository.openConnection()) {
            return repository.listSubjects(c);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<RegisteredSchema> listVersions(String subject) {
        try (Connection c = repository.openConnection()) {
            List<RegisteredSchema> versions = repository.listVersions(c, subject);
            if (versions.isEmpty()) {
                throw new ApiException(404, "SUBJECT_NOT_FOUND", "subject '" + subject + "' not found");
            }
            return versions;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public RegisteredSchema getVersion(String subject, int version) {
        if (version < 1) {
            throw ApiException.badRequest("version must be >= 1");
        }
        try (Connection c = repository.openConnection()) {
            RegisteredSchema schema = repository.load(c, subject, version);
            if (schema == null) {
                throw new ApiException(404, "VERSION_NOT_FOUND",
                        "subject '" + subject + "' version " + version + " not found");
            }
            return schema;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
