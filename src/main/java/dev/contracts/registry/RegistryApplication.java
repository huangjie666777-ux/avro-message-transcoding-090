package dev.contracts.registry;

import dev.contracts.registry.http.RegistryController;
import dev.contracts.registry.service.RegistryService;
import dev.contracts.registry.storage.SqliteSchemaRepository;
import io.javalin.Javalin;

public final class RegistryApplication {

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getProperty("port", System.getenv().getOrDefault("PORT", "8080")));
        String dbPath = System.getProperty("db", System.getenv().getOrDefault("REGISTRY_DB", "data/registry.db"));

        SqliteSchemaRepository repository = new SqliteSchemaRepository(dbPath);
        RegistryService service = new RegistryService(repository);
        Javalin app = new RegistryController(service).buildApp();
        app.start(port);
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
    }

    private RegistryApplication() {
    }
}
