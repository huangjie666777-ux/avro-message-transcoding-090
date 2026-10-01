package dev.contracts.registry.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.contracts.registry.error.ApiException;
import org.apache.avro.AvroTypeException;
import org.apache.avro.Schema;
import org.apache.avro.SchemaParseException;

import java.util.Iterator;
import java.util.Map;

/**
 * Parses a single Avro schema that uses only declared names. Validates syntax,
 * default values and rejects logicalType.
 */
public final class SchemaValidator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SchemaValidator() {
    }

    public static Schema parse(String subject, String schemaJson, Map<String, Schema> knownTypes) {
        if (schemaJson == null || schemaJson.isBlank()) {
            throw ApiException.invalidSchema(subject, "schema must be a non-empty JSON document");
        }
        rejectLogicalType(subject, schemaJson);
        try {
            Schema.Parser parser = new Schema.Parser();
            parser.setValidateDefaults(true);
            if (knownTypes != null && !knownTypes.isEmpty()) {
                parser.addTypes(knownTypes);
            }
            return parser.parse(schemaJson);
        } catch (SchemaParseException | AvroTypeException e) {
            throw ApiException.invalidSchema(subject, rootMessage(e));
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? e.getClass().getSimpleName() : message;
    }

    private static void rejectLogicalType(String subject, String schemaJson) {
        JsonNode tree;
        try {
            tree = JSON.readTree(schemaJson);
        } catch (Exception e) {
            throw ApiException.invalidSchema(subject, "schema is not valid JSON: " + e.getMessage());
        }
        if (tree == null || tree.isNull()) {
            throw ApiException.invalidSchema(subject, "schema must be a non-empty JSON document");
        }
        walk(subject, tree);
    }

    private static void walk(String subject, JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            if (node.has("logicalType")) {
                throw ApiException.invalidSchema(subject, "logicalType is not supported");
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                walk(subject, fields.next().getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                walk(subject, child);
            }
        }
    }
}
