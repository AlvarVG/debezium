/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.document.Document;
import io.debezium.relational.RelationalSnapshotChangeEventSource.RelationalSnapshotContext;
import io.debezium.relational.TableId;

/**
 * Abstract implementation of the {@link StreamingAdapter} for which all streaming adapters are derived.
 *
 * @author Chris Cranford
 */
public abstract class AbstractStreamingAdapter<T extends AbstractOracleStreamingChangeEventSourceMetrics> implements StreamingAdapter<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractStreamingAdapter.class);

    protected final OracleConnectorConfig connectorConfig;

    public AbstractStreamingAdapter(OracleConnectorConfig connectorConfig) {
        this.connectorConfig = connectorConfig;
    }

    protected Scn resolveScn(Document document) {
        final String scn = document.getString(SourceInfo.SCN_KEY);
        if (scn == null) {
            Long scnValue = document.getLong(SourceInfo.SCN_KEY);
            return Scn.valueOf(scnValue == null ? 0 : scnValue);
        }
        return Scn.valueOf(scn);
    }

    /**
     * Checks whether the two specified system change numbers have the same timestamp.
     *
     * @param scn1 first scn number, may be {@code null}
     * @param scn2 second scn number, may be {@code null}
     * @param connection the database connection, must not be {@code null}
     * @return true if the two system change numbers have the same timestamp; false otherwise
     * @throws SQLException if a database error occurred
     */
    protected boolean areSameTimestamp(Scn scn1, Scn scn2, OracleConnection connection) throws SQLException {
        if (scn1 == null) {
            return false;
        }
        if (scn2 == null) {
            return false;
        }

        final String query = "SELECT 1 FROM DUAL WHERE SCN_TO_TIMESTAMP(" + scn1 + ")=SCN_TO_TIMESTAMP(" + scn2 + ")";
        try (Statement s = connection.connection().createStatement(); ResultSet rs = s.executeQuery(query)) {
            return rs.next();
        }
    }

    /**
     * Returns the SCN of the latest DDL change to the captured tables.
     * The result will be empty if there is no table to capture as per the configuration.
     *
     * @param ctx the snapshot contest, must not be {@code null}
     * @param connection the database connection, must not be {@code null}
     * @return the latest table DDL system change number, never {@code null} but may be empty.
     * @throws SQLException if a database error occurred
     */
    protected Optional<Scn> getLatestTableDdlScn(RelationalSnapshotContext<OraclePartition, OracleOffsetContext> ctx, OracleConnection connection)
            throws SQLException {
        if (ctx.capturedTables.isEmpty()) {
            return Optional.empty();
        }

        final String lastDdlScnQuery = "SELECT TIMESTAMP_TO_SCN(MAX(last_ddl_time))" +
                " FROM all_objects" +
                " WHERE" +
                ctx.capturedTables.stream()
                        .map(t -> " (owner=? and object_name=?)")
                        .collect(Collectors.joining(" OR"));

        try (PreparedStatement statement = connection.connection().prepareStatement(lastDdlScnQuery)) {
            int index = 1;
            for (TableId tableId : ctx.capturedTables) {
                statement.setString(index, tableId.schema());
                statement.setString(index + 1, tableId.table());
                index += 2;
            }

            try (ResultSet rs = statement.executeQuery()) {

                if (!rs.next()) {
                    throw new IllegalStateException("Couldn't get latest table DDL SCN");
                }

                // Guard against LAST_DDL_TIME with value of 0.
                // This case should be treated as if we were unable to determine a value for LAST_DDL_TIME.
                // This forces later calculations to be based upon the current SCN.
                String latestDdlTime = rs.getString(1);
                if ("0".equals(latestDdlTime)) {
                    return Optional.empty();
                }

                return Optional.of(Scn.valueOf(latestDdlTime));
            }
        }
        catch (SQLException e) {
            if (e.getErrorCode() >= 8180 && e.getErrorCode() <= 8189) {
                // These error codes are all related to failures with flashback area where
                // flashback may not be enabled, the SCN or its timestamp has aged out, etc
                LOGGER.info("No latest table SCN could be resolved, defaulting to current SCN");
                return Optional.empty();
            }
            throw e;
        }
    }
}
