/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.kafka.connect.data.Struct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.DebeziumException;
import io.debezium.data.Envelope.Operation;
import io.debezium.jdbc.JdbcConfiguration;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.pipeline.spi.Partition;
import io.debezium.relational.Column;
import io.debezium.relational.RelationalChangeRecordEmitter;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.relational.TableSchema;
import io.debezium.util.Clock;
import io.debezium.util.Strings;

/**
 * Base class to emit change data based on a single entry event.
 */
public abstract class BaseChangeRecordEmitter<T> extends RelationalChangeRecordEmitter {

    private static final Logger LOGGER = LoggerFactory.getLogger(BaseChangeRecordEmitter.class);

    private final OracleConnectorConfig connectorConfig;
    private final Object[] oldColumnValues;
    private final Object[] newColumnValues;
    private final OracleDatabaseSchema schema;
    protected final Table table;

    protected BaseChangeRecordEmitter(OracleConnectorConfig connectorConfig, Partition partition, OffsetContext offset,
                                      OracleDatabaseSchema schema, Table table, Clock clock, Object[] oldColumnValues,
                                      Object[] newColumnValues) {
        super(partition, offset, clock, connectorConfig);
        this.connectorConfig = connectorConfig;
        this.schema = schema;
        this.oldColumnValues = oldColumnValues;
        this.newColumnValues = newColumnValues;
        this.table = table;
    }

    @Override
    protected Object[] getOldColumnValues() {
        return oldColumnValues;
    }

    @Override
    protected Object[] getNewColumnValues() {
        return newColumnValues;
    }

    @Override
    protected void emitTruncateRecord(Receiver receiver, TableSchema tableSchema) throws InterruptedException {
        Struct envelope = tableSchema.getEnvelopeSchema().truncate(getOffset().getSourceInfo(), getClock().currentTimeAsInstant());
        receiver.changeRecord(getPartition(), tableSchema, Operation.TRUNCATE, null, envelope, getOffset(), null);
    }

    @Override
    protected void emitUpdateAsPrimaryKeyChangeRecord(Receiver receiver, TableSchema tableSchema, Struct oldKey,
                                                      Struct newKey, Struct oldValue, Struct newValue)
            throws InterruptedException {
        if (connectorConfig.isLobEnabled()) {
            final List<Column> reselectColumns = getReselectColumns(newValue);
            if (!reselectColumns.isEmpty()) {
                LOGGER.info("Table '{}' primary key changed from '{}' to '{}' via an UPDATE, re-selecting LOB columns {} out of bands.",
                        table.id(), oldKey, newKey, reselectColumns.stream().map(Column::name).collect(Collectors.toList()));

                final JdbcConfiguration jdbcConfig = connectorConfig.getJdbcConfig();
                try (OracleConnection connection = new OracleConnection(jdbcConfig, false)) {
                    final String query = getReselectQuery(reselectColumns, table, connection);
                    if (!Strings.isNullOrBlank(connectorConfig.getPdbName())) {
                        connection.setSessionToPdb(connectorConfig.getPdbName());
                    }
                    connection.prepareQuery(query,
                            ps -> prepareReselectQueryStatement(ps, table, newColumnValues),
                            rs -> updateNewValuesFromReselectQueryResults(rs, reselectColumns));

                    // newColumnValues have been updated via re-select, re-create the event's value
                    //
                    // NOTE: The conversion of the column data must occur within the current connection's context.
                    // This is because the converters for LOB may make specific callbacks to the underlying database
                    // and if the converter is called outside the scope of the current LOB object, the call will
                    // fail due to database connection being unavailable.
                    newValue = tableSchema.valueFromColumnData(newColumnValues);
                }
                catch (SQLException e) {
                    throw new DebeziumException("Failed to re-select table with LOB columns due to primary key update", e);
                }
            }
        }
        super.emitUpdateAsPrimaryKeyChangeRecord(receiver, tableSchema, oldKey, newKey, oldValue, newValue);
    }

    /**
     * Returns a list of columns that should be reselected.
     *
     * Currently, this method is only concerned about LOB-based columns and so if a table does not have any
     * LOB columns or if the LOB column's value is not the unavailable value placeholder configured in the
     * connector configuration, this method may return no columns indicating that a reselection is not
     * required for the change event.
     *
     * @param newValue the currently constructed new value payload for the change event, should not be null
     * @return list of columns that should be reselected, which can be empty
     */
    private List<Column> getReselectColumns(Struct newValue) {
        List<Column> lobColumns = schema.getLobColumnsForTable(table.id());
        if (lobColumns.isEmpty()) {
            return Collections.emptyList();
        }
        else {
            return lobColumns
                    .stream()
                    .filter(column -> newValue.schema().field(column.name()) != null)
                    .filter(column -> {
                        final Object value = newValue.get(column.name());
                        return schema.isColumnUnavailableValuePlaceholder(column, value);
                    })
                    .collect(Collectors.toList());
        }
    }

    /**
     * Creates the reselect query, a query that explicitly only selects the LOB-based columns from the
     * underlying relational table based on the event's current primary key value set.
     *
     * @param reselectColumns the columns that should be reselected, should never be null or empty
     * @param table the relational table model
     * @param connection the database connection
     * @return the query string for the reselect query
     */
    private String getReselectQuery(List<Column> reselectColumns, Table table, OracleConnection connection) {
        final TableId id = new TableId(null, table.id().schema(), table.id().table());
        final StringBuilder query = new StringBuilder("SELECT ")
                .append(reselectColumns.stream().map(c -> connection.quoteIdentifier(c.name())).collect(Collectors.joining(", ")))
                .append(" FROM ")
                .append(id.toDoubleQuotedString())
                .append(" WHERE ");

        for (int i = 0; i < table.primaryKeyColumnNames().size(); ++i) {
            if (i > 0) {
                query.append(" AND ");
            }
            query.append(connection.quoteIdentifier(table.primaryKeyColumnNames().get(i))).append("=?");
        }

        return query.toString();
    }

    /**
     * Prepares the reselect query, binding the primary key column values
     *
     * @param ps the prepared statement
     * @param table the relational model table
     * @param rawValues the adapter provided original values
     * @throws SQLException if a database error occurred
     */
    private void prepareReselectQueryStatement(PreparedStatement ps, Table table, Object[] rawValues) throws SQLException {
        final List<String> primaryKeyColumnNames = table.primaryKeyColumnNames();
        for (int i = 0; i < primaryKeyColumnNames.size(); i++) {
            final Column column = table.columnWithName(primaryKeyColumnNames.get(i));
            ps.setObject(i + 1, convertReselectPrimaryKeyColumn(ps.getConnection(), column, rawValues[column.position() - 1]));
        }
    }

    /**
     * Applies the reselect query results to the new column values object array.
     *
     * @param rs the reselect query result set
     * @param reselectColumns the columns that were re-selected
     * @throws SQLException if a database error occurred
     */
    private void updateNewValuesFromReselectQueryResults(ResultSet rs, List<Column> reselectColumns) throws SQLException {
        if (rs.next()) {
            for (int i = 0; i < reselectColumns.size(); ++i) {
                final Column column = reselectColumns.get(i);
                newColumnValues[column.position() - 1] = rs.getObject(i + 1);
            }
        }
    }

    /**
     * Converts the reselect query's primary key column value, if applicable.
     *
     * @param connection the underlying jdbc connection, should not be {@code null}
     * @param column the column, should not be {@code null}
     * @param value the value to be converted, may be {@code null}
     * @return the converted value to be directly bound to the reselect query
     */
    protected Object convertReselectPrimaryKeyColumn(Connection connection, Column column, Object value) {
        return value;
    }

    protected Object convertValueViaQuery(Connection connection, String value) {
        try (Statement statement = connection.createStatement()) {
            try (ResultSet rs = statement.executeQuery(String.format("SELECT %s FROM DUAL", value))) {
                if (!rs.next()) {
                    throw new DebeziumException("Expected query to return a value but did not.");
                }
                return rs.getObject(1);
            }
        }
        catch (SQLException e) {
            throw new DebeziumException(String.format("Failed to execute reselect query for value '%s'.", value), e);
        }
    }
}
