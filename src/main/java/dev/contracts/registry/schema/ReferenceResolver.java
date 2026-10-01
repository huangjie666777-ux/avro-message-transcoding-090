package dev.contracts.registry.schema;

import dev.contracts.registry.domain.*;
import dev.contracts.registry.error.ApiException;
import org.apache.avro.Schema;

import java.util.*;

/**
 * Builds the name universe for a publish batch and parses every entry.
 *
 * <p>Reference resolution order:
 * <ol>
 *   <li>Validate per-entry declaration shape (unique names, no unknown subjects).</li>
 *   <li>Compute cross-subject dependency edges (including the stored deps of fixed
 *       versions) and reject cross-topic cycles; internal recursion stays legal.</li>
 *   <li>Topologically parse: fixed-version references contribute their exact stored
 *       closure, batch references contribute the peer schema as it is parsed.</li>
 * </ol>
 */
public final class ReferenceResolver {

    public interface SubjectLookup {
        /** Returns the registered schema or null if the subject/version does not exist. */
        RegisteredSchema load(String subject, int version);

        /** Returns the current latest version of a subject, 0 if unknown. */
        int latestVersion(String subject);
    }

    private final SubjectLookup lookup;

    public ReferenceResolver(SubjectLookup lookup) {
        this.lookup = lookup;
    }

    public ResolvedBatch resolve(List<PublishEntry> entries) {
        Map<String, PublishEntry> bySubject = new LinkedHashMap<>();
        for (PublishEntry entry : entries) {
            if (bySubject.put(entry.subject(), entry) != null) {
                throw ApiException.badRequest("subject '" + entry.subject() + "' appears more than once in the batch");
            }
        }

        validateDeclarations(entries, bySubject);
        detectCrossSubjectCycles(entries, bySubject);

        Map<String, Schema> universe = new LinkedHashMap<>();
        Map<String, String> owner = new HashMap<>();

        // Load fixed-version closures first. They are pinned exact snapshots.
        for (PublishEntry entry : entries) {
            for (RefSpec ref : entry.references()) {
                if (!ref.isBatchRef()) {
            loadFixedClosure(entry.subject(), ref, universe, owner, new HashSet<>());
                }
            }
        }

        // Parse batch subjects in dependency order.
        Map<String, ResolvedBatch.ResolvedSchema> parsed = new LinkedHashMap<>();
        Set<String> parsing = new HashSet<>();
        for (PublishEntry entry : entries) {
            parseEntry(entry, bySubject, universe, owner, parsed, parsing);
        }

        List<ResolvedBatch.ResolvedSchema> ordered = entries.stream()
                .map(e -> parsed.get(e.subject()))
                .toList();
        return new ResolvedBatch(ordered, universe);
    }

    private void validateDeclarations(List<PublishEntry> entries, Map<String, PublishEntry> bySubject) {
        Set<String> selfRefs = new HashSet<>();
        for (PublishEntry entry : entries) {
            Set<String> declaredNames = new HashSet<>();
            for (RefSpec ref : entry.references()) {
                if (!declaredNames.add(ref.name())) {
                    throw ApiException.invalidSchema(entry.subject(),
                            "duplicate reference for name '" + ref.name() + "'");
                }
                if (ref.subject().equals(entry.subject())) {
                    selfRefs.add(entry.subject());
                }
                if (!ref.isBatchRef()) {
                    int latest = lookup.latestVersion(ref.subject());
                    if (latest == 0) {
                        throw ApiException.badRequest("[" + entry.subject() + "] reference subject '"
                                + ref.subject() + "' does not exist");
                    }
                    if (ref.version() < 1 || ref.version() > latest) {
                        throw ApiException.badRequest("[" + entry.subject() + "] reference '" + ref.subject()
                                + "' version " + ref.version() + " does not exist (latest: " + latest + ")");
                    }
                } else if (!bySubject.containsKey(ref.subject())) {
                    throw ApiException.badRequest("[" + entry.subject() + "] batch reference '"
                            + ref.subject() + "' is not part of this batch");
                }
            }
        }
        for (String self : selfRefs) {
            throw ApiException.badRequest("[" + self + "] a subject may not reference itself as another version; "
                    + "internal recursion inside one schema is supported directly");
        }
    }

    /**
     * Cycles across subjects are illegal. Edges are: entry -> fixed subject (whose
     * own stored deps are followed) and entry -> batch peer.
     */
    private void detectCrossSubjectCycles(List<PublishEntry> entries, Map<String, PublishEntry> bySubject) {
        Map<String, List<String>> graph = new HashMap<>();
        for (PublishEntry entry : entries) {
            List<String> edges = new ArrayList<>();
            for (RefSpec ref : entry.references()) {
                edges.add(ref.subject());
                if (!ref.isBatchRef()) {
                    RegisteredSchema registered = lookup.load(ref.subject(), ref.version());
                    if (registered != null) {
                        for (StoredRef transitive : registered.references()) {
                            edges.add(transitive.subject());
                        }
                    }
                }
            }
            graph.put(entry.subject(), edges);
        }
        Set<String> visited = new HashSet<>();
        Set<String> stack = new HashSet<>();
        List<String> path = new ArrayList<>();
        for (PublishEntry entry : entries) {
            dfsCycle(entry.subject(), graph, visited, stack, path, bySubject);
        }
    }

    private void dfsCycle(String subject, Map<String, List<String>> graph, Set<String> visited,
                          Set<String> stack, List<String> path, Map<String, PublishEntry> batch) {
        if (stack.contains(subject)) {
            int start = path.indexOf(subject);
            List<String> cycle = new ArrayList<>(path.subList(start, path.size()));
            cycle.add(subject);
            throw ApiException.badRequest("cross-subject reference cycle detected: " + String.join(" -> ", cycle)
                    + "; internal recursion inside one schema is legal");
        }
        if (visited.contains(subject)) {
            return;
        }
        visited.add(subject);
        stack.add(subject);
        path.add(subject);
        for (String next : graph.getOrDefault(subject, List.of())) {
            // Only follow edges into the batch further; stored subjects have already
            // been published acyclic (their stored deps are added as edges too).
            if (batch.containsKey(next) || stack.contains(next)) {
                dfsCycle(next, graph, visited, stack, path, batch);
            }
        }
        stack.remove(subject);
        path.remove(path.size() - 1);
    }

    private void loadFixedClosure(String ownerSubject, RefSpec ref, Map<String, Schema> universe,
                                  Map<String, String> owner, Set<SchemaVersion> loading) {
        RegisteredSchema registered = lookup.load(ref.subject(), ref.version());
        if (registered == null) {
            throw ApiException.badRequest("[" + ownerSubject + "] reference '" + ref.subject()
                    + "' version " + ref.version() + " does not exist");
        }
        SchemaVersion key = new SchemaVersion(ref.subject(), ref.version());
        if (loading.contains(key)) {
            return; // same pinned snapshot revisited
        }
        loading.add(key);
        for (StoredRef transitive : registered.references()) {
            loadFixedClosure(ownerSubject,
                    new RefSpec(transitive.name(), transitive.subject(), transitive.version()),
                    universe, owner, loading);
        }
        Schema parsed = SchemaValidator.parse(registered.subject(), registered.schemaJson(), universe);
        if (!parsed.getFullName().equals(ref.name())) {
            throw ApiException.badRequest("[" + ownerSubject + "] reference name '" + ref.name()
                    + "' does not match root name '" + parsed.getFullName() + "' of " + ref.subject()
                    + " version " + ref.version());
        }
        addToUniverse(ownerSubject, parsed, ref.subject(), universe, owner);
    }

    private ResolvedBatch.ResolvedSchema parseEntry(PublishEntry entry, Map<String, PublishEntry> bySubject,
                                                    Map<String, Schema> universe, Map<String, String> owner,
                                                    Map<String, ResolvedBatch.ResolvedSchema> parsed,
                                                    Set<String> parsing) {
        ResolvedBatch.ResolvedSchema existing = parsed.get(entry.subject());
        if (existing != null) {
            return existing;
        }
        if (!parsing.add(entry.subject())) {
            throw ApiException.badRequest("cross-subject reference cycle detected at '" + entry.subject()
                    + "'; internal recursion inside one schema is legal");
        }
        List<StoredRef> storedRefs = new ArrayList<>();
        for (RefSpec ref : entry.references()) {
            Schema referenced;
            if (ref.isBatchRef()) {
                PublishEntry peer = bySubject.get(ref.subject());
                ResolvedBatch.ResolvedSchema peerSchema =
                        parseEntry(peer, bySubject, universe, owner, parsed, parsing);
                referenced = peerSchema.schema();
                storedRefs.add(new StoredRef(ref.name(), ref.subject(),
                        // version assigned at publish time; placeholder repaired there
                        -1));
            } else {
                referenced = universe.get(ref.name());
                storedRefs.add(new StoredRef(ref.name(), ref.subject(), ref.version()));
            }
            if (referenced == null || !referenced.getFullName().equals(ref.name())) {
                throw ApiException.badRequest("[" + entry.subject() + "] reference name '" + ref.name()
                        + "' does not match root name of " + ref.subject());
            }
        }
        Schema schema = SchemaValidator.parse(entry.subject(), entry.schemaJson(), universe);
        addToUniverse(entry.subject(), schema, entry.subject(), universe, owner);
        parsing.remove(entry.subject());
        ResolvedBatch.ResolvedSchema result =
                new ResolvedBatch.ResolvedSchema(entry, schema.getFullName(), schema, storedRefs);
        parsed.put(entry.subject(), result);
        return result;
    }

    private void addToUniverse(String ownerSubject, Schema schema, String schemaOwner,
                               Map<String, Schema> universe, Map<String, String> owner) {
        List<Schema> named = new ArrayList<>();
        collectNamed(schema, named, new HashSet<>(), universe);
        for (Schema namedSchema : named) {
            String fullName = namedSchema.getFullName();
            String existingOwner = owner.get(fullName);
            if (existingOwner != null && !existingOwner.equals(schemaOwner)) {
                throw ApiException.badRequest("name conflict: '" + fullName + "' is defined by both '"
                        + existingOwner + "' and '" + schemaOwner + "' (referenced by '" + ownerSubject + "')");
            }
            universe.putIfAbsent(fullName, namedSchema);
            owner.putIfAbsent(fullName, schemaOwner);
        }
    }

    private void collectNamed(Schema schema, List<Schema> out, Set<Schema> seen,
                              Map<String, Schema> universe) {
        if (schema == null) {
            return;
        }
        // Types already present in the universe are owned by another subject (a
        // declared reference); do not re-attribute them to the current owner.
        if ((schema.getType() == Schema.Type.RECORD
                || schema.getType() == Schema.Type.ENUM
                || schema.getType() == Schema.Type.FIXED)
                && universe.containsKey(schema.getFullName())) {
            return;
        }
        switch (schema.getType()) {
            case RECORD, ENUM, FIXED -> {
                if (!seen.add(schema)) {
                    return;
                }
                out.add(schema);
                if (schema.getType() == Schema.Type.RECORD) {
                    for (Schema.Field field : schema.getFields()) {
                        collectNamed(field.schema(), out, seen, universe);
                    }
                }
            }
            case ARRAY -> collectNamed(schema.getElementType(), out, seen, universe);
            case MAP -> collectNamed(schema.getValueType(), out, seen, universe);
            case UNION -> {
                for (Schema branch : schema.getTypes()) {
                    collectNamed(branch, out, seen, universe);
                }
            }
            default -> {
            }
        }
    }

    private record SchemaVersion(String subject, int version) {
    }
}
