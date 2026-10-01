package dev.contracts.registry.storage;

import dev.contracts.registry.domain.RegisteredSchema;
import dev.contracts.registry.schema.ReferenceResolver;
import org.apache.avro.Schema;

/** Read access used by reference resolution and compatibility analysis. */
public interface SchemaClosureSource {

    RegisteredSchema loadStored(String subject, int version);

    Schema loadSchemaWithClosure(RegisteredSchema registered);
}
