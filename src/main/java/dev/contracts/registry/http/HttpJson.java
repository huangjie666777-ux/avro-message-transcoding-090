package dev.contracts.registry.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.javalin.json.JavalinJackson;

public final class HttpJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private HttpJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static JavalinJackson jsonMapper() {
        return new JavalinJackson(MAPPER, false);
    }
}
