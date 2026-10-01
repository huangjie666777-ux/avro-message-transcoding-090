package dev.contracts.registry.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.contracts.registry.domain.Compatibility;
import dev.contracts.registry.domain.PublishEntry;
import dev.contracts.registry.domain.RefSpec;
import dev.contracts.registry.error.ApiException;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = false)
public record Requests(BatchRequest precheck, BatchRequest publish) {

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record BatchRequest(List<EntryRequest> entries) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EntryRequest(
            String subject,
            Integer expectedLatest,
            String compatibility,
            Object schema,
            List<RefRequest> references
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RefRequest(String name, String subject, Integer version) {
    }

    public static List<PublishEntry> toDomain(BatchRequest request) {
        if (request == null || request.entries() == null || request.entries().isEmpty()) {
            throw ApiException.badRequest("request body must contain a non-empty 'entries' array");
        }
        List<PublishEntry> entries = new ArrayList<>();
        for (EntryRequest entry : request.entries()) {
            if (entry == null) {
                throw ApiException.badRequest("entries must not contain null");
            }
            if (entry.subject() == null || entry.subject().isBlank()) {
                throw ApiException.badRequest("entry subject must be non-empty");
            }
            int expectedLatest = entry.expectedLatest() == null ? 0 : entry.expectedLatest();
            Compatibility compatibility;
            try {
                compatibility = Compatibility.fromWire(entry.compatibility());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("[" + entry.subject() + "] " + e.getMessage());
            }
            String schemaJson;
            try {
                schemaJson = HttpJson.mapper().writeValueAsString(entry.schema());
            } catch (Exception e) {
                throw ApiException.invalidSchema(entry.subject(), "schema is not representable JSON");
            }
            if ("null".equals(schemaJson)) {
                throw ApiException.badRequest("[" + entry.subject() + "] schema is required");
            }
            List<RefSpec> refs = new ArrayList<>();
            if (entry.references() != null) {
                for (RefRequest ref : entry.references()) {
                    if (ref == null || ref.name() == null || ref.subject() == null) {
                        throw ApiException.badRequest("[" + entry.subject()
                                + "] each reference requires name and subject");
                    }
                    refs.add(new RefSpec(ref.name(), ref.subject(), ref.version()));
                }
            }
            entries.add(new PublishEntry(entry.subject().trim(), expectedLatest, compatibility, schemaJson, refs));
        }
        return entries;
    }
}
