package dev.contracts.registry.storage;

import dev.contracts.registry.domain.RegisteredSchema;
import dev.contracts.registry.domain.StoredRef;
import org.apache.avro.Schema;

import java.sql.*;
import java.util.*;

/**
 * SQLite-backed schema store. All writes happen in a single IMMEDIATE transaction;
 * version numbers are allocated only after every check succeeds, so a failed
 * publish never leaves a half batch or a consumed version number.
 */
public class SqliteSchemaRepository implements SchemaClosureSource, dev.contracts.registry.schema.ReferenceResolver.SubjectLookup {

    private final String url;

    public SqliteSchemaRepository(String dbPath) {
        this.url = "jdbc:sqlite:" + dbPath;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("sqlite-jdbc driver missing", e);
        }
        init();
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA busy_timeout=10000");
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    /** Opens a read connection for short-lived queries. Caller must close it. */
    public Connection openConnection() {
        try {
            return open();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open database connection", e);
        }
    }

    private void init() {
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schema_versions (
                        subject       TEXT NOT NULL,
                        version       INTEGER NOT NULL,
                        compatibility TEXT NOT NULL,
                        schema_json   TEXT NOT NULL,
                        root_fullname TEXT NOT NULL,
                        created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
                        PRIMARY KEY (subject, version)
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS schema_refs (
                        subject     TEXT NOT NULL,
                        version     INTEGER NOT NULL,
                        ref_name    TEXT NOT NULL,
                        ref_subject TEXT NOT NULL,
                        ref_version INTEGER NOT NULL,
                        PRIMARY KEY (subject, version, ref_name),
                        FOREIGN KEY (subject, version) REFERENCES schema_versions(subject, version)
                    )
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot initialize registry database", e);
        }
    }

    public <T> T inImmediateTransaction(TransactionCallback<T> callback) {
        try (Connection connection = open()) {
            // IMMEDIATE takes the write lock up-front: a second concurrent publisher
            // blocks and then sees the winner's rows during its re-check.
            try (Statement st = connection.createStatement()) {
                st.execute("BEGIN IMMEDIATE");
            }
            boolean committed = false;
            try {
                T result = callback.execute(connection);
                try (Statement commit = connection.createStatement()) {
                    commit.execute("COMMIT");
                }
                committed = true;
                return result;
            } finally {
                if (!committed) {
                    try (Statement rollback = connection.createStatement()) {
                        rollback.execute("ROLLBACK");
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("database transaction failed: " + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    public interface TransactionCallback<T> {
        T execute(Connection connection) throws SQLException;
    }

    public int latestVersion(Connection c, String subject) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(MAX(version), 0) FROM schema_versions WHERE subject = ?")) {
            ps.setString(1, subject);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public List<String> listSubjects(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT subject, MAX(version) v FROM schema_versions GROUP BY subject ORDER BY subject")) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        }
    }

    public List<RegisteredSchema> listVersions(Connection c, String subject) throws SQLException {
        List<RegisteredSchema> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT subject, version, compatibility, schema_json, root_fullname "
                        + "FROM schema_versions WHERE subject = ? ORDER BY version")) {
            ps.setString(1, subject);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readRow(c, rs));
                }
            }
        }
        return out;
    }

    public RegisteredSchema load(Connection c, String subject, int version) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT subject, version, compatibility, schema_json, root_fullname "
                        + "FROM schema_versions WHERE subject = ? AND version = ?")) {
            ps.setString(1, subject);
            ps.setInt(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRow(c, rs) : null;
            }
        }
    }

    public List<RegisteredSchema> allHistory(Connection c, String subject) throws SQLException {
        return listVersions(c, subject);
    }

    private RegisteredSchema readRow(Connection c, ResultSet rs) throws SQLException {
        String subject = rs.getString("subject");
        int version = rs.getInt("version");
        String compatibility = rs.getString("compatibility");
        String schemaJson = rs.getString("schema_json");
        String rootFullName = rs.getString("root_fullname");
        List<StoredRef> refs = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ref_name, ref_subject, ref_version FROM schema_refs "
                        + "WHERE subject = ? AND version = ? ORDER BY ref_name")) {
            ps.setString(1, subject);
            ps.setInt(2, version);
            try (ResultSet refRs = ps.executeQuery()) {
                while (refRs.next()) {
                    refs.add(new StoredRef(refRs.getString(1), refRs.getString(2), refRs.getInt(3)));
                }
            }
        }
        return new RegisteredSchema(subject, version, compatibility, schemaJson, rootFullName, refs);
    }

    public void insert(Connection c, String subject, int version, String compatibility,
                       String schemaJson, String rootFullName, List<StoredRef> refs) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO schema_versions(subject, version, compatibility, schema_json, root_fullname) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, subject);
            ps.setInt(2, version);
            ps.setString(3, compatibility);
            ps.setString(4, schemaJson);
            ps.setString(5, rootFullName);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO schema_refs(subject, version, ref_name, ref_subject, ref_version) "
                        + "VALUES (?, ?, ?, ?, ?)")) {
            for (StoredRef ref : refs) {
                ps.setString(1, subject);
                ps.setInt(2, version);
                ps.setString(3, ref.name());
                ps.setString(4, ref.subject());
                ps.setInt(5, ref.version());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    @Override
    public RegisteredSchema loadStored(String subject, int version) {
        try (Connection c = open()) {
            return load(c, subject, version);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public int latestVersion(String subject) {
        try (Connection c = open()) {
            return latestVersion(c, subject);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public RegisteredSchema load(String subject, int version) {
        return loadStored(subject, version);
    }

    /**
     * Parses a stored schema with the exact pinned closures of all its references.
     * Dependencies are loaded recursively; cross-subject closures are acyclic by
     * construction at publish time.
     */
    @Override
    public Schema loadSchemaWithClosure(RegisteredSchema registered) {
        Map<String, Schema> universe = new LinkedHashMap<>();
        loadClosureInto(registered, universe, new HashSet<>());
        return dev.contracts.registry.schema.SchemaValidator.parse(
                registered.subject(), registered.schemaJson(), universe);
    }

    private void loadClosureInto(RegisteredSchema registered, Map<String, Schema> universe,
                                 Set<String> loading) {
        String key = registered.subject() + ":" + registered.version();
        if (loading.contains(key)) {
            return;
        }
        loading.add(key);
        for (StoredRef ref : registered.references()) {
            RegisteredSchema dep = loadStored(ref.subject(), ref.version());
            if (dep == null) {
                throw new IllegalStateException("missing stored reference " + ref.subject() + ":" + ref.version());
            }
            loadClosureInto(dep, universe, loading);
        }
        Schema schema = dev.contracts.registry.schema.SchemaValidator.parse(
                registered.subject(), registered.schemaJson(), universe);
        for (Schema named : collect(schema, new ArrayList<>(), new HashSet<>())) {
            universe.putIfAbsent(named.getFullName(), named);
        }
    }

    private List<Schema> collect(Schema schema, List<Schema> out, Set<Schema> seen) {
        switch (schema.getType()) {
            case RECORD, ENUM, FIXED -> {
                if (seen.add(schema)) {
                    out.add(schema);
                    if (schema.getType() == Schema.Type.RECORD) {
                        for (Schema.Field f : schema.getFields()) {
                            collect(f.schema(), out, seen);
                        }
                    }
                }
            }
            case ARRAY -> collect(schema.getElementType(), out, seen);
            case MAP -> collect(schema.getValueType(), out, seen);
            case UNION -> {
                for (Schema b : schema.getTypes()) {
                    collect(b, out, seen);
                }
            }
            default -> {
            }
        }
        return out;
    }
}
