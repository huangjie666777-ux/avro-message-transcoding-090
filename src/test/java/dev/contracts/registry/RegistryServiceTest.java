package dev.contracts.registry;

import dev.contracts.registry.domain.*;
import dev.contracts.registry.error.ApiException;
import dev.contracts.registry.service.RegistryService;
import dev.contracts.registry.storage.SqliteSchemaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegistryServiceTest {

    private Path dbFile;
    private RegistryService service;

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("registry", ".db");
        Files.delete(dbFile);
        service = new RegistryService(new SqliteSchemaRepository(dbFile.toString()));
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(dbFile);
        Files.deleteIfExists(Path.of(dbFile + "-wal"));
        Files.deleteIfExists(Path.of(dbFile + "-shm"));
    }

    private PublishEntry entry(String subject, int expected, Compatibility mode, String schema, RefSpec... refs) {
        return new PublishEntry(subject, expected, mode, schema, List.of(refs));
    }

    private static String record(String namespace, String name, String fieldsJson) {
        return "{\"type\":\"record\",\"namespace\":\"" + namespace
                + "\",\"name\":\"" + name + "\",\"fields\":[" + fieldsJson + "]}";
    }

    @Test
    void publishesFirstVersionAndPersistsAcrossRestart() {
        String schema = record("ex", "User", "{\"name\":\"id\",\"type\":\"long\"}");
        var result = service.publish(List.of(entry("users", 0, Compatibility.BACKWARD, schema)));
        assertEquals(1, result.results().get(0).version());

        RegistryService restarted = new RegistryService(new SqliteSchemaRepository(dbFile.toString()));
        assertEquals(List.of("users"), restarted.listSubjects());
        assertEquals(1, restarted.listVersions("users").get(0).version());
        assertEquals("ex.User", restarted.getVersion("users", 1).rootFullName());
    }

    @Test
    void precheckDoesNotWriteAndFailedPublishDoesNotConsumeVersion() {
        String v1 = record("ex", "A", "{\"name\":\"x\",\"type\":\"string\"}");
        service.precheck(List.of(entry("a", 0, Compatibility.BACKWARD, v1)));
        assertTrue(service.listSubjects().isEmpty());

        service.publish(List.of(entry("a", 0, Compatibility.BACKWARD, v1)));

        String bad = record("ex", "A", "{\"name\":\"y\",\"type\":\"string\"}");
        ApiException ex = assertThrows(ApiException.class,
                () -> service.publish(List.of(entry("a", 1, Compatibility.BACKWARD, bad))));
        assertEquals(422, ex.status());
        assertEquals(1, service.listVersions("a").size());
    }

    @Test
    void rejectsInvalidSchemaBadDefaultAndLogicalType() {
        String invalid = "{\"type\":\"record\",\"name\":\"X\",\"fields\":[{}]}";
        assertBadRequest(() -> service.precheck(List.of(entry("x", 0, Compatibility.FULL, invalid))));

        String badDefault = "{\"type\":\"record\",\"name\":\"X\",\"fields\":"
                + "[{\"name\":\"f\",\"type\":[\"null\",\"int\"],\"default\":\"nope\"}]}";
        assertBadRequest(() -> service.precheck(List.of(entry("x", 0, Compatibility.FULL, badDefault))));

        String logical = "{\"type\":\"long\",\"logicalType\":\"timestamp-millis\"}";
        assertBadRequest(() -> service.precheck(List.of(entry("x", 0, Compatibility.FULL, logical))));
    }

    @Test
    void supportsComplexTypesInternalRecursionAndAliases() {
        String tree = """
                {"type":"record","name":"ex.Tree","fields":[
                  {"name":"value","type":{"type":"map","values":{"type":"array","items":"string"}}},
                  {"name":"children","type":{"type":"array","items":["null","ex.Tree"]},"default":[]}
                ]}
                """;
        var result = service.publish(List.of(entry("trees", 0, Compatibility.FULL, tree)));
        assertEquals(1, result.results().get(0).version());

        String renamed = """
                {"type":"record","name":"ex.Tree","aliases":["ex.OldTree"],"fields":[
                  {"name":"value","type":{"type":"map","values":{"type":"array","items":"string"}}},
                  {"name":"children","type":{"type":"array","items":["null","ex.Tree"]},"default":[]}
                ]}
                """;
        service.publish(List.of(entry("trees", 1, Compatibility.FULL, renamed)));
    }

    @Test
    void batchReferencesResolveAndStoreExactVersions() {
        String address = record("shared", "Address", "{\"name\":\"city\",\"type\":\"string\"}");
        String person = record("people", "Person",
                "{\"name\":\"home\",\"type\":\"shared.Address\"}");

        var result = service.publish(List.of(
                entry("addresses", 0, Compatibility.FULL, address),
                entry("people", 0, Compatibility.FULL, person,
                        new RefSpec("shared.Address", "addresses", null))
        ));
        assertEquals(1, result.results().get(0).version());
        assertEquals(1, result.results().get(1).version());
        RegisteredSchema people = service.getVersion("people", 1);
        assertEquals("addresses", people.references().get(0).subject());
        assertEquals(1, people.references().get(0).version());

        String addressV2 = record("shared", "Address",
                "{\"name\":\"city\",\"type\":\"string\"},"
                        + "{\"name\":\"zip\",\"type\":[\"null\",\"string\"],\"default\":null}");
        service.publish(List.of(entry("addresses", 1, Compatibility.FULL, addressV2)));

        String personV2 = "{\"type\":\"record\",\"namespace\":\"people\",\"name\":\"Person\",\"fields\":["
                + "{\"name\":\"home\",\"type\":\"shared.Address\"},"
                + "{\"name\":\"note\",\"type\":[\"null\",\"string\"],\"default\":null}]}";
        var r2 = service.publish(List.of(entry("people", 1, Compatibility.FULL, personV2,
                new RefSpec("shared.Address", "addresses", 1))));
        assertEquals(2, r2.results().get(0).version());
        assertEquals(1, service.getVersion("people", 2).references().get(0).version());
    }

    @Test
    void rejectsMissingReferenceNameConflictCrossSubjectCycleAndDuplicateSubject() {
        String address = record("shared", "Address", "{\"name\":\"city\",\"type\":\"string\"}");
        String person = record("people", "Person",
                "{\"name\":\"home\",\"type\":\"shared.Address\"}");

        assertBadRequest(() -> service.precheck(List.of(
                entry("people", 0, Compatibility.FULL, person,
                        new RefSpec("shared.Address", "addresses", null)))));

        service.publish(List.of(entry("addresses", 0, Compatibility.FULL, address)));
        assertBadRequest(() -> service.precheck(List.of(
                entry("people", 0, Compatibility.FULL, person,
                        new RefSpec("shared.Address", "addresses", 9)))));

        assertBadRequest(() -> service.precheck(List.of(
                entry("a", 0, Compatibility.FULL, address),
                entry("a", 0, Compatibility.FULL, address))));

        String aSchema = record("cyc", "A", "{\"name\":\"b\",\"type\":\"cyc.B\"}");
        String bSchema = record("cyc", "B",
                "{\"name\":\"a\",\"type\":[\"null\",\"cyc.A\"],\"default\":null}");
        assertBadRequest(() -> service.precheck(List.of(
                entry("a", 0, Compatibility.FULL, aSchema, new RefSpec("cyc.B", "b", null)),
                entry("b", 0, Compatibility.FULL, bSchema, new RefSpec("cyc.A", "a", null)))));

        assertBadRequest(() -> service.precheck(List.of(
                entry("x", 0, Compatibility.FULL, address),
                entry("y", 0, Compatibility.FULL,
                        record("shared", "Address", "{\"name\":\"z\",\"type\":\"int\"}")))));
    }

    @Test
    void backwardForwardAndFullChecksReportPathAndDirection() {
        String v1 = record("ex", "P",
                "{\"name\":\"id\",\"type\":\"long\"},"
                        + "{\"name\":\"name\",\"type\":\"string\"}");
        service.publish(List.of(entry("p", 0, Compatibility.BACKWARD, v1)));

        String addRequired = record("ex", "P",
                "{\"name\":\"id\",\"type\":\"long\"},"
                        + "{\"name\":\"name\",\"type\":\"string\"},"
                        + "{\"name\":\"age\",\"type\":\"int\"}");
        ApiException backward = assertThrows(ApiException.class,
                () -> service.precheck(List.of(entry("p", 1, Compatibility.BACKWARD, addRequired))));
        assertTrue(backward.getMessage().contains("BACKWARD"));
        assertTrue(backward.getMessage().contains("age"));

        String dropped = record("ex", "P", "{\"name\":\"id\",\"type\":\"long\"}");
        ApiException forward = assertThrows(ApiException.class,
                () -> service.precheck(List.of(entry("p", 1, Compatibility.FORWARD, dropped))));
        assertTrue(forward.getMessage().contains("FORWARD"));
        assertTrue(forward.getMessage().contains("name"));

        assertEquals(422, assertThrows(ApiException.class,
                () -> service.precheck(List.of(entry("p", 1, Compatibility.FULL, addRequired)))).status());

        String ok = record("ex", "P",
                "{\"name\":\"id\",\"type\":\"long\"},"
                        + "{\"name\":\"name\",\"type\":\"string\"},"
                        + "{\"name\":\"age\",\"type\":[\"null\",\"int\"],\"default\":null}");
        assertDoesNotThrow(() -> service.publish(List.of(entry("p", 1, Compatibility.BACKWARD, ok))));
    }

    @Test
    void versionConflictIsReportedClearly() {
        String v1 = record("ex", "U", "{\"name\":\"id\",\"type\":\"long\"}");
        service.publish(List.of(entry("u", 0, Compatibility.FULL, v1)));
        ApiException ex = assertThrows(ApiException.class,
                () -> service.publish(List.of(entry("u", 0, Compatibility.FULL, v1))));
        assertEquals(409, ex.status());
        assertEquals("VERSION_CONFLICT", ex.code());
    }

    private void assertBadRequest(ExecutableCall call) {
        ApiException ex = assertThrows(ApiException.class, call::run);
        assertEquals(400, ex.status(), ex.getMessage());
    }

    @FunctionalInterface
    private interface ExecutableCall {
        void run() throws Exception;
    }
}
