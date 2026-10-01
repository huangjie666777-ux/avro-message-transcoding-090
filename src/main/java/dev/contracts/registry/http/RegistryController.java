package dev.contracts.registry.http;

import dev.contracts.registry.domain.RegisteredSchema;
import dev.contracts.registry.error.ApiException;
import dev.contracts.registry.service.RegistryService;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.util.List;
import java.util.Map;

public class RegistryController {

    public static final long MAX_REQUEST_BYTES = 2L * 1024 * 1024;

    private final RegistryService service;

    public RegistryController(RegistryService service) {
        this.service = service;
    }

    public Javalin buildApp() {
        Javalin app = Javalin.create(cfg -> {
            cfg.http.maxRequestSize = MAX_REQUEST_BYTES;
            cfg.jsonMapper(HttpJson.jsonMapper());
        });

        app.exception(ApiException.class, (e, ctx) -> writeError(ctx, e.status(), e.code(), e.getMessage()));
        app.exception(io.javalin.http.HttpResponseException.class, (e, ctx) ->
                writeError(ctx, e.getStatus(), "HTTP_" + e.getStatus(), e.getMessage()));
        app.exception(Exception.class, (e, ctx) ->
                writeError(ctx, 500, "INTERNAL_ERROR", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));

        app.get("/health", ctx -> ctx.json(Map.of("status", "ok")));

        app.get("/subjects", ctx -> {
            List<String> subjects = service.listSubjects();
            ctx.json(Map.of("subjects", subjects));
        });

        app.get("/subjects/{subject}/versions", ctx -> {
            String subject = requiredSubject(ctx);
            List<RegisteredSchema> versions = service.listVersions(subject);
            ctx.json(Map.of("subject", subject,
                    "versions", versions.stream().map(RegisteredSchema::version).toList(),
                    "latest", versions.get(versions.size() - 1).version()));
        });

        app.get("/subjects/{subject}/versions/{version}", ctx -> {
            String subject = requiredSubject(ctx);
            int version = parseVersion(ctx.pathParam("version"));
            RegisteredSchema schema = service.getVersion(subject, version);
            ctx.json(versionView(schema));
        });

        app.post("/compatibility/precheck", ctx -> {
            Requests.BatchRequest request = decode(ctx);
            var result = service.precheck(Requests.toDomain(request));
            ctx.status(200).json(Map.of("status", "PASS", "wouldAssign", result.results()));
        });

        app.post("/compatibility/publish", ctx -> {
            Requests.BatchRequest request = decode(ctx);
            var result = service.publish(Requests.toDomain(request));
            ctx.status(201).json(Map.of("status", "CREATED", "assigned", result.results()));
        });

        return app;
    }

    private static Requests.BatchRequest decode(Context ctx) {
        try {
            return ctx.bodyStreamAsClass(Requests.BatchRequest.class);
        } catch (Exception e) {
            throw ApiException.badRequest("request body is not valid: " + rootMessage(e));
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable t = throwable;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static int parseVersion(String raw) {
        try {
            int value = Integer.parseInt(raw);
            if (value < 1) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            if ("latest".equalsIgnoreCase(raw)) {
                return -1;
            }
            throw ApiException.badRequest("version must be a positive integer or 'latest'");
        }
    }

    private static String requiredSubject(Context ctx) {
        String subject = ctx.pathParam("subject");
        if (subject == null || subject.isBlank()) {
            throw ApiException.badRequest("subject must be non-empty");
        }
        return subject;
    }

    private static Map<String, Object> versionView(RegisteredSchema schema) {
        Object schemaTree;
        try {
            schemaTree = HttpJson.mapper().readTree(schema.schemaJson());
        } catch (Exception e) {
            schemaTree = schema.schemaJson();
        }
        return Map.of(
                "subject", schema.subject(),
                "version", schema.version(),
                "compatibility", schema.compatibility(),
                "rootFullName", schema.rootFullName(),
                "references", schema.references().stream()
                        .map(r -> Map.of("name", r.name(), "subject", r.subject(), "version", r.version()))
                        .toList(),
                "schema", schemaTree
        );
    }

    private static void writeError(Context ctx, int status, String code, String message) {
        ctx.status(status).json(Map.of("error", code, "status", status, "message", message));
    }
}
