package dev.contracts.registry;

import dev.contracts.registry.http.RegistryController;
import dev.contracts.registry.service.RegistryService;
import dev.contracts.registry.storage.SqliteSchemaRepository;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class HttpApiTest {

    private Path dbFile;
    private Javalin app;
    private int port;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void setUp() throws Exception {
        dbFile = Files.createTempFile("http-registry", ".db");
        Files.delete(dbFile);
        var repository = new SqliteSchemaRepository(dbFile.toString());
        app = new RegistryController(new RegistryService(repository)).buildApp();
        app.start(0);
        port = app.port();
    }

    @AfterEach
    void tearDown() throws Exception {
        app.stop();
        Files.deleteIfExists(dbFile);
        Files.deleteIfExists(Path.of(dbFile + "-wal"));
        Files.deleteIfExists(Path.of(dbFile + "-shm"));
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String publishBody(String subject, int expected, String mode, String schema) {
        return "{\"entries\":[{\"subject\":\"" + subject + "\",\"expectedLatest\":" + expected
                + ",\"compatibility\":\"" + mode + "\",\"schema\":" + schema + "}]}";
    }

    @Test
    void fullHttpFlow() throws Exception {
        assertEquals(200, get("/health").statusCode());

        String schema = "{\"type\":\"record\",\"name\":\"ex.User\",\"fields\":["
                + "{\"name\":\"id\",\"type\":\"long\"}]}";

        HttpResponse<String> created = post("/compatibility/publish",
                publishBody("users", 0, "BACKWARD", schema));
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.body().contains("\"version\":1"));

        assertEquals(200, get("/subjects").statusCode());
        assertTrue(get("/subjects").body().contains("users"));
        assertEquals(200, get("/subjects/users/versions").statusCode());

        HttpResponse<String> fetched = get("/subjects/users/versions/1");
        assertEquals(200, fetched.statusCode());
        assertTrue(fetched.body().contains("ex.User"));
        assertEquals(404, get("/subjects/users/versions/9").statusCode());

        // incompatible -> 422
        String bad = "{\"type\":\"record\",\"name\":\"ex.User\",\"fields\":["
                + "{\"name\":\"id\",\"type\":\"string\"}]}";
        HttpResponse<String> incompatible = post("/compatibility/publish",
                publishBody("users", 1, "BACKWARD", bad));
        assertEquals(422, incompatible.statusCode());
        assertTrue(incompatible.body().contains("INCOMPATIBLE_SCHEMA"));
        assertTrue(incompatible.body().contains("BACKWARD"));

        // stale expected version -> 409
        HttpResponse<String> conflict = post("/compatibility/publish",
                publishBody("users", 0, "BACKWARD", schema));
        assertEquals(409, conflict.statusCode());
        assertTrue(conflict.body().contains("VERSION_CONFLICT"));

        // malformed JSON -> 400
        HttpResponse<String> malformed = post("/compatibility/precheck", "{not json");
        assertEquals(400, malformed.statusCode());

        // precheck must not write
        HttpResponse<String> precheck = post("/compatibility/precheck",
                publishBody("other", 0, "FULL", schema.replace("ex.User", "ex.Other")));
        assertEquals(200, precheck.statusCode());
        assertEquals(404, get("/subjects/other/versions").statusCode());
    }

    @Test
    void rejectsOversizedRequests() throws Exception {
        char[] padding = new char[(int) RegistryController.MAX_REQUEST_BYTES + 1024];
        java.util.Arrays.fill(padding, 'a');
        HttpResponse<String> response = post("/compatibility/precheck",
                "{\"entries\":[],\"x\":\"" + new String(padding) + "\"}");
        assertTrue(response.statusCode() == 413 || response.statusCode() == 400,
                "expected rejection, got " + response.statusCode());
    }
}
